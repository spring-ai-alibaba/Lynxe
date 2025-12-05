/*
 * Copyright 2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.alibaba.cloud.ai.lynxe.tool.mapreduce;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;

import com.alibaba.cloud.ai.lynxe.planning.PlanningFactory.ToolCallBackContext;
import com.alibaba.cloud.ai.lynxe.runtime.executor.LevelBasedExecutorPool;
import com.alibaba.cloud.ai.lynxe.runtime.service.PlanIdDispatcher;
import com.alibaba.cloud.ai.lynxe.runtime.service.ServiceGroupIndexService;
import com.alibaba.cloud.ai.lynxe.tool.AbstractBaseTool;
import com.alibaba.cloud.ai.lynxe.tool.AsyncToolCallBiFunctionDef;
import com.alibaba.cloud.ai.lynxe.tool.code.ToolExecuteResult;
import com.alibaba.cloud.ai.lynxe.tool.i18n.ToolI18nService;
import com.alibaba.cloud.ai.lynxe.tool.mapreduce.ParallelExecutionTool.RegisterBatchInput;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Parallel execution manager that follows DefaultToolCallingManager execution pattern
 *
 * This class provides functionality to: 1. Batch register executable functions 2. Execute
 * all registered functions in parallel using a 'start' function 3. Track function
 * execution status and get pending functions
 *
 * Uses asynchronous non-blocking execution by default to prevent thread pool starvation.
 */
public class ParallelExecutionTool extends AbstractBaseTool<RegisterBatchInput>
		implements AsyncToolCallBiFunctionDef<RegisterBatchInput> {

	private static final Logger logger = LoggerFactory.getLogger(ParallelExecutionTool.class);

	private final ObjectMapper objectMapper;

	private final Map<String, ToolCallBackContext> toolCallbackMap;

	private final PlanIdDispatcher planIdDispatcher;

	// levelBasedExecutorPool is now handled by ParallelExecutionService
	@SuppressWarnings("unused")
	private final LevelBasedExecutorPool levelBasedExecutorPool;

	private final ParallelExecutionService parallelExecutionService;

	/**
	 * Registry entry for a function
	 */
	static class FunctionRegistry {

		private final String id;

		private final String toolName;

		private final Map<String, Object> input;

		private ToolExecuteResult result;

		public FunctionRegistry(String id, String toolName, Map<String, Object> input) {
			this.id = id;
			this.toolName = toolName;
			this.input = input;
		}

		public String getId() {
			return id;
		}

		public String getToolName() {
			return toolName;
		}

		public Map<String, Object> getInput() {
			return input;
		}

		public ToolExecuteResult getResult() {
			return result;
		}

		public void setResult(ToolExecuteResult result) {
			this.result = result;
		}

	}

	/**
	 * Input class for batch function registration
	 */
	static class RegisterBatchInput {

		private String action;

		@com.fasterxml.jackson.annotation.JsonProperty("tool_name")
		private String toolName;

		private List<Map<String, Object>> functions;

		public RegisterBatchInput() {
		}

		public String getAction() {
			return action;
		}

		public void setAction(String action) {
			this.action = action;
		}

		public String getToolName() {
			return toolName;
		}

		public void setToolName(String toolName) {
			this.toolName = toolName;
		}

		public List<Map<String, Object>> getFunctions() {
			return functions;
		}

		public void setFunctions(List<Map<String, Object>> functions) {
			this.functions = functions;
		}

	}

	private final ToolI18nService toolI18nService;

	// Store all function registries in a list (allows duplicates)
	private final List<FunctionRegistry> functionRegistries = new ArrayList<>();

	public ParallelExecutionTool(ObjectMapper objectMapper, Map<String, ToolCallBackContext> toolCallbackMap,
			PlanIdDispatcher planIdDispatcher, LevelBasedExecutorPool levelBasedExecutorPool,
			ToolI18nService toolI18nService, ServiceGroupIndexService serviceGroupIndexService,
			ParallelExecutionService parallelExecutionService) {
		this.objectMapper = objectMapper;
		this.toolCallbackMap = toolCallbackMap;
		this.planIdDispatcher = planIdDispatcher;
		this.levelBasedExecutorPool = levelBasedExecutorPool;
		this.toolI18nService = toolI18nService;
		this.parallelExecutionService = parallelExecutionService;
	}

	/**
	 * Set the tool callback map (used to look up actual tool implementations)
	 */
	public void setToolCallbackMap(Map<String, ToolCallBackContext> toolCallbackMap) {
		this.toolCallbackMap.putAll(toolCallbackMap);
	}

	@Override
	public String getServiceGroup() {
		return "parallel-execution";
	}

	@Override
	public String getName() {
		return "parallel_execution_tool";
	}

	@Override
	public String getDescription() {
		return toolI18nService.getDescription("parallel-execution-tool");
	}

	@Override
	public String getParameters() {
		return toolI18nService.getParameters("parallel-execution-tool");
	}

	@Override
	public Class<RegisterBatchInput> getInputType() {
		return RegisterBatchInput.class;
	}

	/**
	 * Synchronous version - delegates to async version for proper execution This method
	 * overrides AbstractBaseTool.apply() to ensure we use applyAsync() instead of run().
	 * @param input Tool input parameters
	 * @param toolContext Tool execution context
	 * @return Tool execution result (blocks until async operation completes)
	 */
	@Override
	public ToolExecuteResult apply(RegisterBatchInput input, ToolContext toolContext) {
		return applyAsync(input, toolContext).join();
	}

	/**
	 * Asynchronous version - returns CompletableFuture for non-blocking execution This is
	 * the primary implementation that prevents thread pool starvation.
	 * @param input Tool input parameters
	 * @param toolContext Tool execution context
	 * @return CompletableFuture that completes with the tool execution result
	 */
	@Override
	public CompletableFuture<ToolExecuteResult> applyAsync(RegisterBatchInput input, ToolContext toolContext) {
		try {
			String action = input.getAction();
			if (action == null) {
				return CompletableFuture.completedFuture(new ToolExecuteResult("Action is required"));
			}

			logger.debug("Executing action: {} with context: {} (async mode)", action, toolContext);

			switch (action) {
				case "registerBatch":
					// registerBatch is synchronous, wrap in completed future
					return CompletableFuture.completedFuture(registerFunctionsBatch(input));
				case "start":
					// This is the key async operation!
					return startExecutionAsync(toolContext);
				case "getPending":
					return CompletableFuture.completedFuture(getPendingFunctions());
				case "clearPending":
					return CompletableFuture.completedFuture(clearPendingFunctions());
				default:
					return CompletableFuture.completedFuture(new ToolExecuteResult("Unknown action: " + action));
			}
		}
		catch (Exception e) {
			logger.error("Error in ParallelExecutionManager (async): {}", e.getMessage(), e);
			return CompletableFuture.completedFuture(new ToolExecuteResult("Error: " + e.getMessage()));
		}
	}

	@Override
	public ToolExecuteResult run(RegisterBatchInput input) {
		throw new UnsupportedOperationException(
				"ParallelExecutionManager must be called using apply() method with ToolContext, not run()");
	}

	/**
	 * Register multiple functions in batch
	 */
	@SuppressWarnings("unchecked")
	private ToolExecuteResult registerFunctionsBatch(RegisterBatchInput input) {
		try {
			String toolName = input.getToolName();
			if (toolName == null || toolName.trim().isEmpty()) {
				return new ToolExecuteResult("Error: tool_name parameter is required for registerBatch action");
			}

			List<Map<String, Object>> functionParamsList = input.getFunctions();
			if (functionParamsList == null || functionParamsList.isEmpty()) {
				return new ToolExecuteResult("No functions provided");
			}

			// Ensure all items are properly formatted as Map<String, Object>
			List<Map<String, Object>> validatedParamsList = new ArrayList<>();
			for (Object item : functionParamsList) {
				if (item instanceof Map) {
					// Each item is already a Map of input parameters
					validatedParamsList.add((Map<String, Object>) item);
				}
				else {
					// Try to convert using ObjectMapper
					Map<String, Object> params = objectMapper.convertValue(item, Map.class);
					validatedParamsList.add(params);
				}
			}

			if (validatedParamsList.isEmpty()) {
				return new ToolExecuteResult("No valid functions provided");
			}

			List<Map<String, Object>> registeredFunctions = new ArrayList<>();
			for (Map<String, Object> functionParams : validatedParamsList) {
				// Ensure functionParams is not null
				Map<String, Object> params = (functionParams != null) ? functionParams : new HashMap<>();

				String funcId = planIdDispatcher.generateParallelExecutionId();
				FunctionRegistry function = new FunctionRegistry(funcId, toolName, params);
				functionRegistries.add(function);

				registeredFunctions
					.add(Map.of("id", funcId, "input", params, "toolName", toolName, "status", "REGISTERED"));
			}

			Map<String, Object> result = new HashMap<>();
			result.put("message", "Successfully registered " + registeredFunctions.size() + " functions");
			result.put("functions", registeredFunctions);
			try {
				return new ToolExecuteResult(objectMapper.writeValueAsString(result));
			}
			catch (JsonProcessingException e) {
				logger.error("Error serializing result: {}", e.getMessage(), e);
				return new ToolExecuteResult("Successfully registered " + registeredFunctions.size() + " functions");
			}
		}
		catch (Exception e) {
			logger.error("Error registering functions batch: {}", e.getMessage(), e);
			return new ToolExecuteResult("Error registering functions: " + e.getMessage());
		}
	}

	/**
	 * Execute registered functions in parallel using ParallelExecutionService
	 * Asynchronous version - does NOT block on .join() Returns CompletableFuture instead
	 * of blocking for results. This prevents thread pool starvation in nested parallel
	 * execution scenarios.
	 * @param parentToolContext Tool execution context
	 * @return CompletableFuture that completes with the execution result
	 */
	private CompletableFuture<ToolExecuteResult> startExecutionAsync(ToolContext parentToolContext) {
		try {
			if (functionRegistries.isEmpty()) {
				return CompletableFuture.completedFuture(new ToolExecuteResult("No functions registered"));
			}

			// Collect pending functions and create execution requests
			List<FunctionRegistry> pendingFunctions = new ArrayList<>();
			List<ParallelExecutionService.ParallelExecutionRequest> executions = new ArrayList<>();

			for (FunctionRegistry function : functionRegistries) {
				// A function is pending if it has no result yet
				if (function.getResult() != null) {
					continue; // Skip already executed functions
				}

				// Check if tool exists before adding to execution list
				String toolName = function.getToolName();
				ToolCallBackContext toolContext = parallelExecutionService.lookupToolContext(toolName, toolCallbackMap);

				if (toolContext == null) {
					logger.warn("Tool not found in callback map: {} (async)", toolName);
					function.setResult(new ToolExecuteResult("Tool not found: " + toolName));
					continue;
				}

				// Add to pending list and create execution request
				pendingFunctions.add(function);
				executions.add(new ParallelExecutionService.ParallelExecutionRequest(toolName, function.getInput()));
			}

			if (executions.isEmpty()) {
				return CompletableFuture.completedFuture(new ToolExecuteResult("No pending functions to execute"));
			}

			// Use ParallelExecutionService to execute all tools in parallel
			return parallelExecutionService.executeToolsInParallel(executions, toolCallbackMap, parentToolContext)
				.thenApply(results -> {
					// Map results back to FunctionRegistry objects
					for (int i = 0; i < pendingFunctions.size() && i < results.size(); i++) {
						FunctionRegistry function = pendingFunctions.get(i);
						Map<String, Object> result = results.get(i);

						String status = (String) result.get("status");
						if ("SUCCESS".equals(status)) {
							Object outputObj = result.get("output");
							String output = outputObj != null ? outputObj.toString() : "No output";
							// Remove excessive escaping from JSON strings
							if (output != null) {
								output = output.replace("\\\"", "\"").replace("\\\\", "\\");
							}
							function.setResult(new ToolExecuteResult(output));
						}
						else {
							Object errorObj = result.get("error");
							String error = errorObj != null ? errorObj.toString() : "Unknown error";
							function.setResult(new ToolExecuteResult("Error: " + error));
						}
					}

					// Build result with function IDs
					List<Map<String, Object>> resultList = new ArrayList<>();
					for (FunctionRegistry function : functionRegistries) {
						if (function.getResult() != null) {
							Map<String, Object> item = new HashMap<>();
							item.put("id", function.getId());
							item.put("status", "COMPLETED");
							String output = null;
							try {
								output = function.getResult().getOutput();
								// Remove excessive escaping from JSON strings
								if (output != null) {
									output = output.replace("\\\"", "\"").replace("\\\\", "\\");
								}
							}
							catch (Exception ignore) {
							}
							if (output == null) {
								output = "No output";
							}
							item.put("output", output);
							resultList.add(item);
						}
					}

					Map<String, Object> finalResult = new HashMap<>();
					finalResult.put("results", resultList);
					finalResult.put("message", "Successfully executed " + executions.size() + " functions");
					try {
						return new ToolExecuteResult(objectMapper.writeValueAsString(finalResult));
					}
					catch (JsonProcessingException e) {
						logger.error("Error serializing result (async): {}", e.getMessage(), e);
						return new ToolExecuteResult("Successfully executed " + executions.size() + " functions");
					}
				})
				.exceptionally(ex -> {
					// Handle any exceptions that occur during execution
					logger.error("Error in async execution: {}", ex.getMessage(), ex);
					return new ToolExecuteResult("Error starting execution: " + ex.getMessage());
				});
		}
		catch (Exception e) {
			logger.error("Error starting async execution: {}", e.getMessage(), e);
			return CompletableFuture
				.completedFuture(new ToolExecuteResult("Error starting execution: " + e.getMessage()));
		}
	}

	/**
	 * Get all functions that are registered but not yet started
	 */
	private ToolExecuteResult getPendingFunctions() {
		try {
			List<Map<String, Object>> pendingFunctions = new ArrayList<>();
			for (FunctionRegistry function : functionRegistries) {
				// A function is pending if it has no result yet
				if (function.getResult() == null) {
					Map<String, Object> pendingFunc = new HashMap<>();
					pendingFunc.put("input", function.getInput());
					pendingFunc.put("toolName", function.getToolName());
					pendingFunc.put("status", "PENDING");
					pendingFunctions.add(pendingFunc);
				}
			}

			try {
				// Return the array directly as JSON string
				return new ToolExecuteResult(objectMapper.writeValueAsString(pendingFunctions));
			}
			catch (JsonProcessingException e) {
				logger.error("Error serializing result: {}", e.getMessage(), e);
				return new ToolExecuteResult("Found " + pendingFunctions.size() + " pending functions");
			}
		}
		catch (Exception e) {
			logger.error("Error getting pending functions: {}", e.getMessage(), e);
			return new ToolExecuteResult("Error getting pending functions: " + e.getMessage());
		}
	}

	/**
	 * Clear all pending functions (functions with no result yet)
	 */
	private ToolExecuteResult clearPendingFunctions() {
		try {
			int clearedCount = 0;
			for (FunctionRegistry function : functionRegistries) {
				// A function is pending if it has no result yet
				if (function.getResult() == null) {
					function.setResult(new ToolExecuteResult("Cleared"));
					clearedCount++;
				}
			}

			Map<String, Object> result = new HashMap<>();
			result.put("message", "Cleared " + clearedCount + " pending functions");
			result.put("count", clearedCount);
			try {
				return new ToolExecuteResult(objectMapper.writeValueAsString(result));
			}
			catch (JsonProcessingException e) {
				logger.error("Error serializing result: {}", e.getMessage(), e);
				return new ToolExecuteResult("Cleared " + clearedCount + " pending functions");
			}
		}
		catch (Exception e) {
			logger.error("Error clearing pending functions: {}", e.getMessage(), e);
			return new ToolExecuteResult("Error clearing pending functions: " + e.getMessage());
		}
	}

	@Override
	public void cleanup(String planId) {
		functionRegistries.clear();
		logger.debug("Cleaned up function registries");
	}

	@Override
	public String getCurrentToolStateString() {
		if (functionRegistries.isEmpty()) {
			return "No functions registered";
		}

		List<Map<String, Object>> plannedFunctions = new ArrayList<>();

		for (FunctionRegistry function : functionRegistries) {
			// Only include functions that are pending (not yet executed)
			if (function.getResult() == null) {
				Map<String, Object> funcInfo = new HashMap<>();
				funcInfo.put("input", function.getInput());
				funcInfo.put("toolName", function.getToolName());
				funcInfo.put("status", "PENDING");
				plannedFunctions.add(funcInfo);
			}
		}

		try {
			return objectMapper.writeValueAsString(plannedFunctions);
		}
		catch (Exception e) {
			return "[]";
		}
	}

	@Override
	public boolean isSelectable() {
		return true;
	}

}
