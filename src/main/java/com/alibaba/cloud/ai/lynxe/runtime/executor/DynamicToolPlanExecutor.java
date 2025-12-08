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
package com.alibaba.cloud.ai.lynxe.runtime.executor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.model.tool.ToolCallingManager;

import com.alibaba.cloud.ai.lynxe.agent.BaseAgent;
import com.alibaba.cloud.ai.lynxe.agent.ConfigurableDynaAgent;
import com.alibaba.cloud.ai.lynxe.agent.ToolCallbackProvider;
import com.alibaba.cloud.ai.lynxe.agent.entity.DynamicAgentEntity;
import com.alibaba.cloud.ai.lynxe.config.LynxeProperties;
import com.alibaba.cloud.ai.lynxe.event.LynxeEventPublisher;
import com.alibaba.cloud.ai.lynxe.llm.ConversationMemoryLimitService;
import com.alibaba.cloud.ai.lynxe.llm.LlmService;
import com.alibaba.cloud.ai.lynxe.llm.StreamingResponseHandler;
import com.alibaba.cloud.ai.lynxe.model.repository.DynamicModelRepository;
import com.alibaba.cloud.ai.lynxe.planning.PlanningFactory;
import com.alibaba.cloud.ai.lynxe.planning.PlanningFactory.ToolCallBackContext;
import com.alibaba.cloud.ai.lynxe.recorder.service.PlanExecutionRecorder;
import com.alibaba.cloud.ai.lynxe.runtime.entity.vo.ExecutionContext;
import com.alibaba.cloud.ai.lynxe.runtime.entity.vo.ExecutionStep;
import com.alibaba.cloud.ai.lynxe.runtime.service.AgentInterruptionHelper;
import com.alibaba.cloud.ai.lynxe.runtime.service.FileUploadService;
import com.alibaba.cloud.ai.lynxe.runtime.service.ParallelToolExecutionService;
import com.alibaba.cloud.ai.lynxe.runtime.service.PlanIdDispatcher;
import com.alibaba.cloud.ai.lynxe.runtime.service.ServiceGroupIndexService;
import com.alibaba.cloud.ai.lynxe.runtime.service.UserInputService;
import com.alibaba.cloud.ai.lynxe.workspace.conversation.service.MemoryService;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Dynamic Agent Plan Executor - Specialized executor for DynamicAgentExecutionPlan with
 * user-selected tools support
 */
public class DynamicToolPlanExecutor extends AbstractPlanExecutor {

	private static final Logger log = LoggerFactory.getLogger(DynamicToolPlanExecutor.class);

	/**
	 * Constructor for DynamicAgentPlanExecutor
	 * @param agents List of dynamic agent entities
	 * @param recorder Plan execution recorder
	 * @param agentService Agent service
	 * @param llmService LLM service
	 * @param lynxeProperties Lynxe properties
	 * @param levelBasedExecutorPool Level-based executor pool for depth-based execution
	 * @param dynamicModelRepository Dynamic model repository
	 */
	private final PlanningFactory planningFactory;

	private final ToolCallingManager toolCallingManager;

	private final UserInputService userInputService;

	private final StreamingResponseHandler streamingResponseHandler;

	private final PlanIdDispatcher planIdDispatcher;

	private final LynxeEventPublisher lynxeEventPublisher;

	private final ObjectMapper objectMapper;

	private final ParallelToolExecutionService parallelToolExecutionService;

	private final MemoryService memoryService;

	private final ConversationMemoryLimitService conversationMemoryLimitService;

	private final ServiceGroupIndexService serviceGroupIndexService;

	public DynamicToolPlanExecutor(List<DynamicAgentEntity> agents, PlanExecutionRecorder recorder,
			LlmService llmService, LynxeProperties lynxeProperties, LevelBasedExecutorPool levelBasedExecutorPool,
			DynamicModelRepository dynamicModelRepository, FileUploadService fileUploadService,
			AgentInterruptionHelper agentInterruptionHelper, PlanningFactory planningFactory,
			ToolCallingManager toolCallingManager, UserInputService userInputService,
			StreamingResponseHandler streamingResponseHandler, PlanIdDispatcher planIdDispatcher,
			LynxeEventPublisher lynxeEventPublisher, ObjectMapper objectMapper,
			ParallelToolExecutionService parallelToolExecutionService, MemoryService memoryService,
			ConversationMemoryLimitService conversationMemoryLimitService,
			ServiceGroupIndexService serviceGroupIndexService) {
		super(agents, recorder, llmService, lynxeProperties, levelBasedExecutorPool, fileUploadService,
				agentInterruptionHelper);
		this.planningFactory = planningFactory;
		this.toolCallingManager = toolCallingManager;
		this.userInputService = userInputService;
		this.streamingResponseHandler = streamingResponseHandler;
		this.planIdDispatcher = planIdDispatcher;
		this.lynxeEventPublisher = lynxeEventPublisher;
		this.objectMapper = objectMapper;
		this.parallelToolExecutionService = parallelToolExecutionService;
		this.memoryService = memoryService;
		this.conversationMemoryLimitService = conversationMemoryLimitService;
		this.serviceGroupIndexService = serviceGroupIndexService;
	}

	protected String getStepFromStepReq(String stepRequirement) {
		String stepType = super.getStepFromStepReq(stepRequirement);
		if ("DEFAULT_AGENT".equals(stepType)) {
			return "ConfigurableDynaAgent";
		}
		return stepType;
	}

	/**
	 * Get the executor for the step.
	 */
	protected BaseAgent getExecutorForStep(ExecutionContext context, ExecutionStep step) {

		String stepType = getStepFromStepReq(step.getStepRequirement());
		int stepIndex = step.getStepIndex();
		String expectedReturnInfo = step.getTerminateColumns();

		String planStatus = context.getPlan().getPlanExecutionStateStringFormat(true);
		String stepText = step.getStepRequirement();

		Map<String, Object> initSettings = new HashMap<>();
		initSettings.put(PLAN_STATUS_KEY, planStatus);
		initSettings.put(CURRENT_STEP_INDEX_KEY, String.valueOf(stepIndex));
		initSettings.put(STEP_TEXT_KEY, stepText);
		initSettings.put(EXTRA_PARAMS_KEY, context.getPlan().getExecutionParams());
		if ("ConfigurableDynaAgent".equals(stepType)) {
			String modelName = step.getModelName();
			List<String> selectedToolKeys = step.getSelectedToolKeys();

			// Convert selectedToolKeys from serviceGroup.toolName to
			// serviceGroup_toolName format
			List<String> convertedToolKeys = convertSelectedToolKeys(selectedToolKeys);

			BaseAgent executor = createConfigurableDynaAgent(context.getPlan().getCurrentPlanId(),
					context.getPlan().getRootPlanId(), initSettings, expectedReturnInfo, step, modelName,
					convertedToolKeys, context.getPlanDepth(), context.getConversationId());
			return executor;
		}
		else {
			throw new IllegalArgumentException("No executor found for step type: " + stepType);
		}
	}

	private BaseAgent createConfigurableDynaAgent(String planId, String rootPlanId,
			Map<String, Object> initialAgentSetting, String expectedReturnInfo, ExecutionStep step, String modelName,
			List<String> selectedToolKeys, int planDepth, String conversationId) {

		String name = "ConfigurableDynaAgent";
		String description = "A configurable dynamic agent";
		String nextStepPrompt = "Based on the current environment information and prompt to make a next step decision";

		ConfigurableDynaAgent agent = new ConfigurableDynaAgent(llmService, getRecorder(), lynxeProperties, name,
				description, nextStepPrompt, selectedToolKeys, toolCallingManager, initialAgentSetting,
				userInputService, modelName, streamingResponseHandler, step, planIdDispatcher, lynxeEventPublisher,
				agentInterruptionHelper, objectMapper, parallelToolExecutionService, memoryService,
				conversationMemoryLimitService, serviceGroupIndexService);

		agent.setCurrentPlanId(planId);
		agent.setRootPlanId(rootPlanId);
		agent.setPlanDepth(planDepth);
		if (conversationId != null && !conversationId.trim().isEmpty()) {
			agent.setConversationId(conversationId);
		}

		Map<String, ToolCallBackContext> toolCallbackMap = planningFactory.toolCallbackMap(planId, rootPlanId,
				expectedReturnInfo);
		agent.setToolCallbackProvider(new ToolCallbackProvider() {
			@Override
			public Map<String, ToolCallBackContext> getToolCallBackContext() {
				return toolCallbackMap;
			}
		});
		return agent;
	}

	/**
	 * Convert selectedToolKeys from serviceGroup.toolName format to serviceGroup_toolName
	 * format
	 * @param selectedToolKeys List of tool keys in serviceGroup.toolName format (from
	 * frontend)
	 * @return List of tool keys in serviceGroup_toolName format (for backend lookup)
	 */
	private List<String> convertSelectedToolKeys(List<String> selectedToolKeys) {
		if (selectedToolKeys == null || selectedToolKeys.isEmpty()) {
			return selectedToolKeys;
		}

		List<String> convertedKeys = new ArrayList<>();
		for (String toolKey : selectedToolKeys) {
			if (toolKey == null || toolKey.isEmpty()) {
				convertedKeys.add(toolKey);
				continue;
			}

			// Convert serviceGroup.toolName to serviceGroup_toolName using
			// ServiceGroupIndexService
			String convertedKey = serviceGroupIndexService.constructFrontendToolKey(toolKey);
			convertedKeys.add(convertedKey);

			if (!convertedKey.equals(toolKey)) {
				log.debug("Converted tool key from '{}' to '{}'", toolKey, convertedKey);
			}
		}

		return convertedKeys;
	}

}
