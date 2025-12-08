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
import org.springframework.stereotype.Service;

import com.alibaba.cloud.ai.lynxe.planning.PlanningFactory.ToolCallBackContext;
import com.alibaba.cloud.ai.lynxe.runtime.executor.LevelBasedExecutorPool;
import com.alibaba.cloud.ai.lynxe.runtime.service.PlanIdDispatcher;
import com.alibaba.cloud.ai.lynxe.runtime.service.ServiceGroupIndexService;
import com.alibaba.cloud.ai.lynxe.tool.AsyncToolCallBiFunctionDef;
import com.alibaba.cloud.ai.lynxe.tool.ToolCallBiFunctionDef;
import com.alibaba.cloud.ai.lynxe.tool.code.ToolExecuteResult;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Common service for parallel execution of tools. Handles the execution logic shared
 * between ParallelExecutionTool and FileBasedParallelExecutionTool.
 */
@Service
public class ParallelExecutionService {

	private static final Logger logger = LoggerFactory.getLogger(ParallelExecutionService.class);

	private final ObjectMapper objectMapper;

	private final PlanIdDispatcher planIdDispatcher;

	private final LevelBasedExecutorPool levelBasedExecutorPool;

	private final ServiceGroupIndexService serviceGroupIndexService;

	public ParallelExecutionService(ObjectMapper objectMapper, PlanIdDispatcher planIdDispatcher,
			LevelBasedExecutorPool levelBasedExecutorPool, ServiceGroupIndexService serviceGroupIndexService) {
		this.objectMapper = objectMapper;
		this.planIdDispatcher = planIdDispatcher;
		this.levelBasedExecutorPool = levelBasedExecutorPool;
		this.serviceGroupIndexService = serviceGroupIndexService;
	}

	/**
	 * Look up tool context using qualified key conversion This method handles the
	 * conversion from raw tool name to qualified key format (serviceGroup_toolName) based
	 * on serviceGroup, and provides fallback to original toolName if conversion fails.
	 * @param toolName The raw tool name to look up
	 * @param toolCallbackMap Map of tool callbacks
	 * @return ToolCallBackContext if found, null otherwise
	 */
	public ToolCallBackContext lookupToolContext(String toolName, Map<String, ToolCallBackContext> toolCallbackMap) {
		// Convert tool name to qualified key format (serviceGroup_toolName) if needed
		// This handles the case where tools are registered with qualified keys based on
		// serviceGroup
		String lookupKey = toolName;
		if (serviceGroupIndexService != null) {
			try {
				String convertedKey = serviceGroupIndexService.constructFrontendToolKey(toolName);
				if (convertedKey != null && !convertedKey.equals(toolName)) {
					lookupKey = convertedKey;
					logger.debug("Converted tool key from '{}' to '{}' for lookup", toolName, lookupKey);
				}
			}
			catch (Exception e) {
				logger.debug("Failed to convert tool key '{}' in lookupToolContext: {}", toolName, e.getMessage());
			}
		}

		// Try lookup with converted key first, then fallback to original toolName
		ToolCallBackContext toolContext = toolCallbackMap.get(lookupKey);
		if (toolContext == null && !lookupKey.equals(toolName)) {
			// Fallback to original toolName if converted key lookup failed
			toolContext = toolCallbackMap.get(toolName);
			if (toolContext != null) {
				logger.debug("Found tool using original name '{}' after converted key '{}' failed", toolName,
						lookupKey);
			}
		}

		return toolContext;
	}

	/**
	 * Execute a single tool with given parameters
	 * @param toolName Name of the tool to execute
	 * @param params Parameters for the tool
	 * @param toolCallbackMap Map of tool callbacks
	 * @param toolContext Parent tool context (for propagating toolCallId and planDepth)
	 * @param index Index for result tracking (can be null)
	 * @return CompletableFuture that completes with execution result
	 */
	public CompletableFuture<Map<String, Object>> executeTool(String toolName, Map<String, Object> params,
			Map<String, ToolCallBackContext> toolCallbackMap, ToolContext toolContext, Integer index) {
		// Use common lookup method
		ToolCallBackContext toolContextBackend = lookupToolContext(toolName, toolCallbackMap);

		if (toolContextBackend == null) {
			Map<String, Object> errorResult = new HashMap<>();
			if (index != null) {
				errorResult.put("index", index);
			}
			errorResult.put("status", "ERROR");
			errorResult.put("error", "Tool not found: " + toolName);
			return CompletableFuture.completedFuture(errorResult);
		}

		ToolCallBiFunctionDef<?> functionInstance = toolContextBackend.getFunctionInstance();

		// Get tool's expected input type and required parameters from schema
		Class<?> inputType = functionInstance.getInputType();
		List<String> requiredParamNames = getRequiredParameterNames(functionInstance);

		// Fill missing required parameters with empty string
		Map<String, Object> filledParams = fillMissingParameters(params, requiredParamNames);

		// Extract planDepth from context if available
		// Note: We always generate a new toolCallId for each tool execution to ensure
		// proper sub-plan association. Each tool call needs its own unique toolCallId
		// so that sub-plans can be correctly linked to their parent tool calls.
		Integer propagatedPlanDepth = null;
		try {
			if (toolContext != null && toolContext.getContext() != null) {
				Object d = toolContext.getContext().get("planDepth");
				if (d instanceof Number) {
					propagatedPlanDepth = ((Number) d).intValue();
				}
				else if (d instanceof String) {
					propagatedPlanDepth = Integer.parseInt((String) d);
				}
			}
		}
		catch (Exception ignore) {
			// ignore extraction errors
		}

		// Generate a unique tool call ID for each tool execution
		// This is critical for sub-plan creation: each tool call needs its own toolCallId
		// so that sub-plans can be properly associated with their parent tool calls
		String toolCallId = planIdDispatcher.generateToolCallId();

		// Determine depth level
		final int depthLevel = (propagatedPlanDepth != null) ? propagatedPlanDepth : 0;

		// Check if tool supports async execution
		boolean isAsyncTool = functionInstance instanceof AsyncToolCallBiFunctionDef;

		// Convert Map to expected input type
		Object convertedInput;
		try {
			if (inputType == Map.class || Map.class.isAssignableFrom(inputType)) {
				convertedInput = filledParams;
			}
			else {
				convertedInput = objectMapper.convertValue(filledParams, inputType);
			}
		}
		catch (Exception e) {
			logger.error("Error converting input for tool {}: {}", toolName, e.getMessage(), e);
			Map<String, Object> errorResult = new HashMap<>();
			if (index != null) {
				errorResult.put("index", index);
			}
			errorResult.put("status", "ERROR");
			errorResult.put("error", "Error converting input: " + e.getMessage());
			return CompletableFuture.completedFuture(errorResult);
		}

		// Create ToolContext for this execution
		ToolContext executionContext = new ToolContext(propagatedPlanDepth == null ? Map.of("toolcallId", toolCallId)
				: Map.of("toolcallId", toolCallId, "planDepth", propagatedPlanDepth));

		// Execute the tool
		if (levelBasedExecutorPool != null) {
			if (isAsyncTool) {
				// Async tool with level-based executor
				@SuppressWarnings("unchecked")
				AsyncToolCallBiFunctionDef<Object> asyncTool = (AsyncToolCallBiFunctionDef<Object>) functionInstance;
				return asyncTool.applyAsync(convertedInput, executionContext).thenApply(result -> {
					Map<String, Object> resultMap = new HashMap<>();
					if (index != null) {
						resultMap.put("index", index);
					}
					resultMap.put("status", "SUCCESS");
					resultMap.put("output", result.getOutput());
					return resultMap;
				}).exceptionally(e -> {
					logger.error("Error executing tool {}: {}", toolName, e.getMessage(), e);
					Map<String, Object> errorResult = new HashMap<>();
					if (index != null) {
						errorResult.put("index", index);
					}
					errorResult.put("status", "ERROR");
					errorResult.put("error", e.getMessage());
					return errorResult;
				});
			}
			else {
				// Sync tool with level-based executor
				return levelBasedExecutorPool.submitTask(depthLevel, () -> {
					try {
						@SuppressWarnings("unchecked")
						ToolExecuteResult result = ((ToolCallBiFunctionDef<Object>) functionInstance)
							.apply(convertedInput, executionContext);
						Map<String, Object> resultMap = new HashMap<>();
						if (index != null) {
							resultMap.put("index", index);
						}
						resultMap.put("status", "SUCCESS");
						resultMap.put("output", result.getOutput());
						return resultMap;
					}
					catch (Exception e) {
						logger.error("Error executing tool {}: {}", toolName, e.getMessage(), e);
						Map<String, Object> errorResult = new HashMap<>();
						if (index != null) {
							errorResult.put("index", index);
						}
						errorResult.put("status", "ERROR");
						errorResult.put("error", e.getMessage());
						return errorResult;
					}
				});
			}
		}
		else {
			// Fallback to default executor
			if (isAsyncTool) {
				@SuppressWarnings("unchecked")
				AsyncToolCallBiFunctionDef<Object> asyncTool = (AsyncToolCallBiFunctionDef<Object>) functionInstance;
				return asyncTool.applyAsync(convertedInput, executionContext).thenApply(result -> {
					Map<String, Object> resultMap = new HashMap<>();
					if (index != null) {
						resultMap.put("index", index);
					}
					resultMap.put("status", "SUCCESS");
					resultMap.put("output", result.getOutput());
					return resultMap;
				}).exceptionally(e -> {
					logger.error("Error executing tool {}: {}", toolName, e.getMessage(), e);
					Map<String, Object> errorResult = new HashMap<>();
					if (index != null) {
						errorResult.put("index", index);
					}
					errorResult.put("status", "ERROR");
					errorResult.put("error", e.getMessage());
					return errorResult;
				});
			}
			else {
				return CompletableFuture.supplyAsync(() -> {
					try {
						@SuppressWarnings("unchecked")
						ToolExecuteResult result = ((ToolCallBiFunctionDef<Object>) functionInstance)
							.apply(convertedInput, executionContext);
						Map<String, Object> resultMap = new HashMap<>();
						if (index != null) {
							resultMap.put("index", index);
						}
						resultMap.put("status", "SUCCESS");
						resultMap.put("output", result.getOutput());
						return resultMap;
					}
					catch (Exception e) {
						logger.error("Error executing tool {}: {}", toolName, e.getMessage(), e);
						Map<String, Object> errorResult = new HashMap<>();
						if (index != null) {
							errorResult.put("index", index);
						}
						errorResult.put("status", "ERROR");
						errorResult.put("error", e.getMessage());
						return errorResult;
					}
				});
			}
		}
	}

	/**
	 * Execute multiple tools in parallel
	 * @param executions List of execution requests (toolName and params)
	 * @param toolCallbackMap Map of tool callbacks
	 * @param toolContext Parent tool context
	 * @return CompletableFuture that completes with all results
	 */
	public CompletableFuture<List<Map<String, Object>>> executeToolsInParallel(
			List<ParallelExecutionRequest> executions, Map<String, ToolCallBackContext> toolCallbackMap,
			ToolContext toolContext) {
		List<CompletableFuture<Map<String, Object>>> futures = new ArrayList<>();

		for (int i = 0; i < executions.size(); i++) {
			ParallelExecutionRequest request = executions.get(i);
			futures.add(executeTool(request.getToolName(), request.getParams(), toolCallbackMap, toolContext, i));
		}

		return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).thenApply(v -> {
			List<Map<String, Object>> results = new ArrayList<>();
			for (CompletableFuture<Map<String, Object>> future : futures) {
				try {
					results.add(future.join());
				}
				catch (Exception e) {
					logger.error("Error getting result from future: {}", e.getMessage(), e);
					Map<String, Object> errorResult = new HashMap<>();
					errorResult.put("status", "ERROR");
					errorResult.put("error", e.getMessage());
					results.add(errorResult);
				}
			}
			// Sort results by index if present
			results.sort((a, b) -> {
				Integer indexA = (Integer) a.get("index");
				Integer indexB = (Integer) b.get("index");
				if (indexA == null && indexB == null) {
					return 0;
				}
				if (indexA == null) {
					return 1;
				}
				if (indexB == null) {
					return -1;
				}
				return Integer.compare(indexA, indexB);
			});
			return results;
		});
	}

	/**
	 * Get required parameter names from tool's parameter schema
	 */
	@SuppressWarnings("unchecked")
	private List<String> getRequiredParameterNames(ToolCallBiFunctionDef<?> tool) {
		try {
			String parametersSchema = tool.getParameters();
			if (parametersSchema == null || parametersSchema.trim().isEmpty()) {
				return new ArrayList<>();
			}

			// Parse JSON schema
			Map<String, Object> schema = objectMapper.readValue(parametersSchema, Map.class);

			// Handle oneOf schemas (like in ParallelExecutionTool)
			if (schema.containsKey("oneOf")) {
				// For oneOf, we'll check all variants and collect required fields
				List<String> allRequired = new ArrayList<>();
				List<Map<String, Object>> oneOfSchemas = (List<Map<String, Object>>) schema.get("oneOf");
				for (Map<String, Object> variant : oneOfSchemas) {
					Object requiredObj = variant.get("required");
					if (requiredObj instanceof List) {
						allRequired.addAll((List<String>) requiredObj);
					}
				}
				return allRequired;
			}

			// Get required fields from schema
			Object requiredObj = schema.get("required");
			if (requiredObj instanceof List) {
				return new ArrayList<>((List<String>) requiredObj);
			}

			return new ArrayList<>();
		}
		catch (Exception e) {
			logger.debug("Could not parse required parameters from schema: {}", e.getMessage());
			return new ArrayList<>();
		}
	}

	/**
	 * Fill missing required parameters with empty string
	 */
	private Map<String, Object> fillMissingParameters(Map<String, Object> params, List<String> requiredParamNames) {
		Map<String, Object> filledParams = new HashMap<>(params);

		// Fill missing required parameters with empty string
		if (requiredParamNames != null && !requiredParamNames.isEmpty()) {
			for (String paramName : requiredParamNames) {
				if (!filledParams.containsKey(paramName)) {
					filledParams.put(paramName, "");
				}
			}
		}

		return filledParams;
	}

	/**
	 * Request for parallel execution
	 */
	public static class ParallelExecutionRequest {

		private String toolName;

		private Map<String, Object> params;

		public ParallelExecutionRequest() {
		}

		public ParallelExecutionRequest(String toolName, Map<String, Object> params) {
			this.toolName = toolName;
			this.params = params;
		}

		public String getToolName() {
			return toolName;
		}

		public void setToolName(String toolName) {
			this.toolName = toolName;
		}

		public Map<String, Object> getParams() {
			return params;
		}

		public void setParams(Map<String, Object> params) {
			this.params = params;
		}

	}

}
