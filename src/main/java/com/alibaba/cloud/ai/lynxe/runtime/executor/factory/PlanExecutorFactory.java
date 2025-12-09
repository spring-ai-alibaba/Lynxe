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
package com.alibaba.cloud.ai.lynxe.runtime.executor.factory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.stereotype.Component;

import com.alibaba.cloud.ai.lynxe.config.LynxeProperties;
import com.alibaba.cloud.ai.lynxe.event.LynxeEventPublisher;
import com.alibaba.cloud.ai.lynxe.llm.ConversationMemoryLimitService;
import com.alibaba.cloud.ai.lynxe.llm.LlmService;
import com.alibaba.cloud.ai.lynxe.llm.StreamingResponseHandler;
import com.alibaba.cloud.ai.lynxe.model.repository.DynamicModelRepository;
import com.alibaba.cloud.ai.lynxe.planning.PlanningFactory;
import com.alibaba.cloud.ai.lynxe.recorder.service.PlanExecutionRecorder;
import com.alibaba.cloud.ai.lynxe.runtime.entity.vo.PlanInterface;
import com.alibaba.cloud.ai.lynxe.runtime.executor.DynamicToolPlanExecutor;
import com.alibaba.cloud.ai.lynxe.runtime.executor.LevelBasedExecutorPool;
import com.alibaba.cloud.ai.lynxe.runtime.executor.PlanExecutorInterface;
import com.alibaba.cloud.ai.lynxe.runtime.service.AgentInterruptionHelper;
import com.alibaba.cloud.ai.lynxe.runtime.service.FileUploadService;
import com.alibaba.cloud.ai.lynxe.runtime.service.PlanIdDispatcher;
import com.alibaba.cloud.ai.lynxe.runtime.service.ServiceGroupIndexService;
import com.alibaba.cloud.ai.lynxe.runtime.service.UserInputService;
import com.alibaba.cloud.ai.lynxe.tool.mapreduce.ParallelExecutionService;
import com.alibaba.cloud.ai.lynxe.workspace.conversation.service.MemoryService;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Plan Executor Factory - Creates appropriate executor based on plan type Factory class
 * that selects the appropriate PlanExecutor implementation based on the planType from
 * PlanInterface
 */
@Component
public class PlanExecutorFactory implements IPlanExecutorFactory {

	private static final Logger log = LoggerFactory.getLogger(PlanExecutorFactory.class);

	private final LlmService llmService;

	private final PlanExecutionRecorder recorder;

	private final LynxeProperties lynxeProperties;

	private final ObjectMapper objectMapper;

	private final LevelBasedExecutorPool levelBasedExecutorPool;

	private final DynamicModelRepository dynamicModelRepository;

	private final FileUploadService fileUploadService;

	private final AgentInterruptionHelper agentInterruptionHelper;

	private final PlanningFactory planningFactory;

	private final ToolCallingManager toolCallingManager;

	private final UserInputService userInputService;

	private final StreamingResponseHandler streamingResponseHandler;

	private final PlanIdDispatcher planIdDispatcher;

	private final LynxeEventPublisher lynxeEventPublisher;

	private final ParallelExecutionService parallelExecutionService;

	private final MemoryService memoryService;

	private final ConversationMemoryLimitService conversationMemoryLimitService;

	private final ServiceGroupIndexService serviceGroupIndexService;

	public PlanExecutorFactory(LlmService llmService, PlanExecutionRecorder recorder, LynxeProperties lynxeProperties,
			ObjectMapper objectMapper, LevelBasedExecutorPool levelBasedExecutorPool,
			DynamicModelRepository dynamicModelRepository, FileUploadService fileUploadService,
			AgentInterruptionHelper agentInterruptionHelper, PlanningFactory planningFactory,
			ToolCallingManager toolCallingManager, UserInputService userInputService,
			StreamingResponseHandler streamingResponseHandler, PlanIdDispatcher planIdDispatcher,
			LynxeEventPublisher lynxeEventPublisher, ParallelExecutionService parallelExecutionService,
			MemoryService memoryService, ConversationMemoryLimitService conversationMemoryLimitService,
			ServiceGroupIndexService serviceGroupIndexService) {
		this.llmService = llmService;
		this.recorder = recorder;
		this.lynxeProperties = lynxeProperties;
		this.objectMapper = objectMapper;
		this.levelBasedExecutorPool = levelBasedExecutorPool;
		this.dynamicModelRepository = dynamicModelRepository;
		this.fileUploadService = fileUploadService;
		this.agentInterruptionHelper = agentInterruptionHelper;
		this.planningFactory = planningFactory;
		this.toolCallingManager = toolCallingManager;
		this.userInputService = userInputService;
		this.streamingResponseHandler = streamingResponseHandler;
		this.planIdDispatcher = planIdDispatcher;
		this.lynxeEventPublisher = lynxeEventPublisher;
		this.parallelExecutionService = parallelExecutionService;
		this.memoryService = memoryService;
		this.conversationMemoryLimitService = conversationMemoryLimitService;
		this.serviceGroupIndexService = serviceGroupIndexService;
	}

	/**
	 * Create a dynamic agent plan executor for DynamicToolsAgent execution
	 * @return DynamicAgentPlanExecutor instance for dynamic agent plans
	 */
	private PlanExecutorInterface createDynamicToolExecutor() {
		log.debug("Creating dynamic agent plan executor");
		return new DynamicToolPlanExecutor(null, recorder, llmService, lynxeProperties, levelBasedExecutorPool,
				dynamicModelRepository, fileUploadService, agentInterruptionHelper, planningFactory, toolCallingManager,
				userInputService, streamingResponseHandler, planIdDispatcher, lynxeEventPublisher, objectMapper,
				parallelExecutionService, memoryService, conversationMemoryLimitService, serviceGroupIndexService);
	}

	/**
	 * Get supported plan types
	 * @return Array of supported plan type strings
	 */
	public String[] getSupportedPlanTypes() {
		return new String[] { "dynamic_agent" };
	}

	/**
	 * Check if a plan type is supported
	 * @param planType The plan type to check
	 * @return true if the plan type is supported, false otherwise
	 */
	public boolean isPlanTypeSupported(String planType) {
		if (planType == null) {
			return false;
		}
		String normalizedType = planType.toLowerCase();
		return "simple".equals(normalizedType) || "direct".equals(normalizedType)
				|| "dynamic_agent".equals(normalizedType);
	}

	/**
	 * Create the appropriate executor based on plan type
	 * @param plan The execution plan containing type information
	 * @return The appropriate PlanExecutorInterface implementation
	 * @throws IllegalArgumentException if plan type is not supported
	 */
	public PlanExecutorInterface createExecutor(PlanInterface plan) {
		if (plan == null) {
			throw new IllegalArgumentException("Plan cannot be null");
		}

		String planType = plan.getPlanType();
		if (planType == null || planType.trim().isEmpty()) {
			throw new IllegalArgumentException("Plan type is null or empty");
		}

		log.info("Creating executor for plan type: {} (planId: {})", planType, plan.getCurrentPlanId());

		return switch (planType.toLowerCase()) {
			case "dynamic_agent" -> createDynamicToolExecutor();
			default -> {
				log.warn("Unknown plan type: {}, defaulting to dynamic agent executor", planType);
				yield createDynamicToolExecutor();
			}
		};
	}

}
