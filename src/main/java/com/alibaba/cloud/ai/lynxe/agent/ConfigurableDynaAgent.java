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
package com.alibaba.cloud.ai.lynxe.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.ToolCallback;

import com.alibaba.cloud.ai.lynxe.config.LynxeProperties;
import com.alibaba.cloud.ai.lynxe.event.LynxeEventPublisher;
import com.alibaba.cloud.ai.lynxe.llm.ConversationMemoryLimitService;
import com.alibaba.cloud.ai.lynxe.llm.LlmService;
import com.alibaba.cloud.ai.lynxe.llm.StreamingResponseHandler;
import com.alibaba.cloud.ai.lynxe.planning.PlanningFactory.ToolCallBackContext;
import com.alibaba.cloud.ai.lynxe.recorder.service.PlanExecutionRecorder;
import com.alibaba.cloud.ai.lynxe.runtime.entity.vo.ExecutionStep;
import com.alibaba.cloud.ai.lynxe.runtime.service.AgentInterruptionHelper;
import com.alibaba.cloud.ai.lynxe.runtime.service.ParallelToolExecutionService;
import com.alibaba.cloud.ai.lynxe.runtime.service.PlanIdDispatcher;
import com.alibaba.cloud.ai.lynxe.runtime.service.ServiceGroupIndexService;
import com.alibaba.cloud.ai.lynxe.runtime.service.UserInputService;
import com.alibaba.cloud.ai.lynxe.tool.TerminableTool;
import com.alibaba.cloud.ai.lynxe.tool.TerminateTool;
import com.alibaba.cloud.ai.lynxe.workspace.conversation.service.MemoryService;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * ConfigurableDynaAgent - A flexible agent that allows passing tool lists dynamically
 * This agent can be configured with different tool sets at runtime and extends
 * DynamicAgent to inherit all the core functionality while adding configurable tool
 * management.
 */
public class ConfigurableDynaAgent extends DynamicAgent {

	private static final Logger log = LoggerFactory.getLogger(ConfigurableDynaAgent.class);

	private ServiceGroupIndexService serviceGroupIndexService;

	/**
	 * Constructor for ConfigurableDynaAgent with configurable parameters
	 * @param llmService LLM service
	 * @param planExecutionRecorder Plan execution recorder
	 * @param lynxeProperties Lynxe properties
	 * @param name Agent name (configurable)
	 * @param description Agent description (configurable)
	 * @param nextStepPrompt Next step prompt (configurable)
	 * @param availableToolKeys List of available tool keys (can be null/empty)
	 * @param toolCallingManager Tool calling manager
	 * @param initialAgentSetting Initial agent settings
	 * @param userInputService User input service
	 * @param promptService Prompt service
	 * @param model Dynamic model entity
	 * @param streamingResponseHandler Streaming response handler
	 * @param step Execution step
	 * @param planIdDispatcher Plan ID dispatcher
	 */
	public ConfigurableDynaAgent(LlmService llmService, PlanExecutionRecorder planExecutionRecorder,
			LynxeProperties lynxeProperties, String name, String description, String nextStepPrompt,
			List<String> availableToolKeys, ToolCallingManager toolCallingManager,
			Map<String, Object> initialAgentSetting, UserInputService userInputService, String modelName,
			StreamingResponseHandler streamingResponseHandler, ExecutionStep step, PlanIdDispatcher planIdDispatcher,
			LynxeEventPublisher lynxeEventPublisher, AgentInterruptionHelper agentInterruptionHelper,
			ObjectMapper objectMapper, ParallelToolExecutionService parallelToolExecutionService,
			MemoryService memoryService, ConversationMemoryLimitService conversationMemoryLimitService,
			ServiceGroupIndexService serviceGroupIndexService) {
		super(llmService, planExecutionRecorder, lynxeProperties, name, description, nextStepPrompt, availableToolKeys,
				toolCallingManager, initialAgentSetting, userInputService, modelName, streamingResponseHandler, step,
				planIdDispatcher, lynxeEventPublisher, agentInterruptionHelper, objectMapper,
				parallelToolExecutionService, memoryService, conversationMemoryLimitService, serviceGroupIndexService);
		this.serviceGroupIndexService = serviceGroupIndexService;
	}

	/**
	 * Override getToolCallList to handle null/empty availableToolKeys If
	 * availableToolKeys is null or empty, return all available tools from
	 * toolCallbackProvider Also ensures TerminateTool is always included
	 * @return List of tool callbacks
	 */
	@Override
	public List<ToolCallback> getToolCallList() {
		List<ToolCallback> toolCallbacks = new ArrayList<>();
		Map<String, ToolCallBackContext> toolCallBackContext = toolCallbackProvider.getToolCallBackContext();

		// Add all available tool keys that are not already in availableToolKeys
		if (availableToolKeys == null || availableToolKeys.isEmpty()) {
			// If availableToolKeys is null or empty, add all available tools
			availableToolKeys.addAll(toolCallBackContext.keySet());
			log.info("No specific tools configured, added all available tools: {}", availableToolKeys);
		}

		// Check if any TerminableTool is already included
		boolean hasTerminableTool = false;
		for (String toolKey : availableToolKeys) {
			// Convert serviceGroup.toolName format to serviceGroup_toolName format if
			// needed
			String lookupKey = convertServiceGroupToolNameToQualifiedKey(toolKey);
			if (lookupKey == null) {
				lookupKey = toolKey; // Use original key if conversion failed or not
										// needed
			}

			// Try to find the tool with the given key (supports both qualified and
			// unqualified keys)
			if (toolCallBackContext.containsKey(lookupKey)) {
				ToolCallBackContext toolCallback = toolCallBackContext.get(lookupKey);
				if (toolCallback != null && toolCallback.getFunctionInstance() instanceof TerminableTool) {
					hasTerminableTool = true;
					break;
				}
			}
			else {
				// Backward compatibility: try to find by unqualified tool name
				ToolCallBackContext foundCallback = findToolByUnqualifiedName(toolCallBackContext, lookupKey);
				if (foundCallback != null && foundCallback.getFunctionInstance() instanceof TerminableTool) {
					hasTerminableTool = true;
					break;
				}
			}
		}

		// Add TerminateTool if no TerminableTool is present
		if (!hasTerminableTool) {
			// Try to find TerminateTool by unqualified name first
			// The qualified key format is now serviceGroup_toolName, so we search for it
			ToolCallBackContext terminateToolContext = findToolByUnqualifiedName(toolCallBackContext,
					TerminateTool.name);
			if (terminateToolContext != null) {
				// Find the qualified key for this tool
				for (Map.Entry<String, ToolCallBackContext> entry : toolCallBackContext.entrySet()) {
					if (entry.getValue() == terminateToolContext) {
						availableToolKeys.add(entry.getKey());
						log.debug("Added TerminateTool with qualified key: {}", entry.getKey());
						break;
					}
				}
			}
			else if (toolCallBackContext.containsKey(TerminateTool.name)) {
				availableToolKeys.add(TerminateTool.name);
				log.debug("Added TerminateTool with unqualified key: {}", TerminateTool.name);
			}
			else {
				log.warn("TerminateTool not found in toolCallBackContext");
			}
		}
		else {
			log.debug("Found existing TerminableTool in tool list for agent {}", getName());
		}

		// Build the tool callbacks list
		for (String toolKey : availableToolKeys) {
			ToolCallBackContext toolCallback = null;

			// Convert serviceGroup.toolName format to serviceGroup_toolName format if
			// needed
			String lookupKey = convertServiceGroupToolNameToQualifiedKey(toolKey);
			if (lookupKey == null) {
				lookupKey = toolKey; // Use original key if conversion failed or not
										// needed
			}

			// First try exact match (supports new qualified keys)
			if (toolCallBackContext.containsKey(lookupKey)) {
				toolCallback = toolCallBackContext.get(lookupKey);
			}
			else {
				// Backward compatibility: try to find by unqualified tool name
				toolCallback = findToolByUnqualifiedName(toolCallBackContext, lookupKey);
				if (toolCallback != null) {
					log.info("Found tool '{}' using backward compatibility lookup", lookupKey);
				}
			}

			if (toolCallback != null) {
				toolCallbacks.add(toolCallback.getToolCallback());
			}
			else {
				log.warn("Tool callback for '{}' not found in the map.", toolKey);
			}
		}

		log.info("Agent {} configured with {} tools: {}", getName(), toolCallbacks.size(), availableToolKeys);
		return toolCallbacks;
	}

	/**
	 * Convert serviceGroup.toolName format to serviceGroup_toolName format This method
	 * delegates to ServiceGroupIndexService for the conversion logic
	 * @param toolKey The tool key in serviceGroup.toolName format or other formats
	 * @return The converted key in serviceGroup_toolName format, or null if conversion is
	 * not needed
	 */
	private String convertServiceGroupToolNameToQualifiedKey(String toolKey) {
		if (toolKey == null || toolKey.isEmpty() || serviceGroupIndexService == null) {
			return null;
		}

		try {
			String convertedKey = serviceGroupIndexService.constructFrontendToolKey(toolKey);
			// Return null if key was not converted (already in correct format)
			if (convertedKey != null && !convertedKey.equals(toolKey)) {
				return convertedKey;
			}
		}
		catch (Exception e) {
			log.debug("Failed to convert tool key '{}' using ServiceGroupIndexService: {}", toolKey, e.getMessage());
		}

		// Return null if conversion is not needed (key is already in correct format or
		// conversion failed)
		return null;
	}

	/**
	 * Find a tool by unqualified name (backward compatibility helper) Searches for tools
	 * where the qualified key is "toolName" or "serviceGroup_toolName" Uses
	 * ServiceGroupIndexService to construct qualified keys when serviceGroup is available
	 * @param toolCallBackContext Map of all available tools
	 * @param unqualifiedName The tool name without serviceGroup prefix
	 * @return The matching ToolCallBackContext or null if not found
	 */
	private ToolCallBackContext findToolByUnqualifiedName(Map<String, ToolCallBackContext> toolCallBackContext,
			String unqualifiedName) {
		// First try exact match (for tools that don't have serviceGroup prefix)
		if (toolCallBackContext.containsKey(unqualifiedName)) {
			return toolCallBackContext.get(unqualifiedName);
		}

		// Try to find by serviceGroup and tool name using ServiceGroupIndexService
		// This is more efficient than iterating through all tools
		if (serviceGroupIndexService != null) {
			try {
				ToolCallBackContext found = findToolByServiceGroupAndName(toolCallBackContext, unqualifiedName);
				if (found != null) {
					return found;
				}
			}
			catch (Exception e) {
				// Log and continue with fallback search if service-based lookup fails
				log.debug("ServiceGroupIndexService lookup failed for tool '{}', falling back to manual search: {}",
						unqualifiedName, e.getMessage());
			}
		}

		// Fallback: Then try to find by matching the tool name part after the underscore
		// Format: serviceGroup_toolName or just toolName
		for (Map.Entry<String, ToolCallBackContext> entry : toolCallBackContext.entrySet()) {
			String qualifiedKey = entry.getKey();

			// Check if the qualified key matches the unqualified name exactly
			if (qualifiedKey.equals(unqualifiedName)) {
				return entry.getValue();
			}

			// Check if the qualified key is in format "serviceGroup_toolName"
			int underscoreIndex = qualifiedKey.lastIndexOf('_');
			if (underscoreIndex > 0 && underscoreIndex < qualifiedKey.length() - 1) {
				String toolNamePart = qualifiedKey.substring(underscoreIndex + 1);
				if (toolNamePart.equals(unqualifiedName)) {
					log.debug("Backward compatibility: Matched unqualified tool '{}' to qualified key '{}'",
							unqualifiedName, qualifiedKey);
					return entry.getValue();
				}
			}
		}
		return null;
	}

	/**
	 * Find a tool by serviceGroup and tool name using ServiceGroupIndexService Constructs
	 * qualified keys using the service to match tools efficiently
	 * @param toolCallBackContext Map of all available tools
	 * @param toolName The tool name to search for
	 * @return The matching ToolCallBackContext or null if not found
	 */
	private ToolCallBackContext findToolByServiceGroupAndName(Map<String, ToolCallBackContext> toolCallBackContext,
			String toolName) {
		try {
			// Iterate through tools to find one with matching name and construct
			// qualified key
			for (Map.Entry<String, ToolCallBackContext> entry : toolCallBackContext.entrySet()) {
				ToolCallBackContext context = entry.getValue();
				if (context != null && context.getFunctionInstance() != null) {
					// Get tool name and serviceGroup from the tool instance
					String actualToolName = context.getFunctionInstance().getName();
					String serviceGroup = context.getFunctionInstance().getServiceGroup();

					// Check if tool name matches
					if (toolName.equals(actualToolName)) {
						// If serviceGroup exists, construct qualified key in
						// serviceGroup_toolName format
						if (serviceGroup != null && !serviceGroup.isEmpty()) {
							String expectedQualifiedKey = serviceGroup + "_" + actualToolName;
							// Check if the constructed key matches the entry key
							if (entry.getKey().equals(expectedQualifiedKey)) {
								log.debug("Found tool '{}' with serviceGroup '{}' using ServiceGroupIndexService",
										toolName, serviceGroup);
								return context;
							}
						}
						else {
							// Tool has no serviceGroup, check if key matches tool name
							// (backward compatibility)
							// This allows tools without serviceGroups to still be found
							if (entry.getKey().equals(toolName)) {
								log.debug("Found tool '{}' without serviceGroup", toolName);
								return context;
							}
						}
					}
				}
			}
		}
		catch (Exception e) {
			// Log and return null to allow fallback search in findToolByUnqualifiedName
			log.debug("Error in findToolByServiceGroupAndName for tool '{}': {}", toolName, e.getMessage());
		}
		// Return null to allow fallback search in findToolByUnqualifiedName
		return null;
	}

}
