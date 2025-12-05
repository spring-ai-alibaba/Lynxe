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
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.chat.prompt.SystemPromptTemplate;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;

import com.alibaba.cloud.ai.lynxe.config.LynxeProperties;
import com.alibaba.cloud.ai.lynxe.event.LynxeEventPublisher;
import com.alibaba.cloud.ai.lynxe.event.PlanExceptionClearedEvent;
import com.alibaba.cloud.ai.lynxe.llm.ConversationMemoryLimitService;
import com.alibaba.cloud.ai.lynxe.llm.LlmService;
import com.alibaba.cloud.ai.lynxe.llm.StreamingResponseHandler;
import com.alibaba.cloud.ai.lynxe.planning.PlanningFactory.ToolCallBackContext;
import com.alibaba.cloud.ai.lynxe.recorder.service.PlanExecutionRecorder;
import com.alibaba.cloud.ai.lynxe.recorder.service.PlanExecutionRecorder.ActToolParam;
import com.alibaba.cloud.ai.lynxe.recorder.service.PlanExecutionRecorder.ThinkActRecordParams;
import com.alibaba.cloud.ai.lynxe.runtime.entity.vo.ExecutionStep;
import com.alibaba.cloud.ai.lynxe.runtime.executor.AbstractPlanExecutor;
import com.alibaba.cloud.ai.lynxe.runtime.service.AgentInterruptionHelper;
import com.alibaba.cloud.ai.lynxe.runtime.service.ParallelToolExecutionService;
import com.alibaba.cloud.ai.lynxe.runtime.service.PlanIdDispatcher;
import com.alibaba.cloud.ai.lynxe.runtime.service.ServiceGroupIndexService;
import com.alibaba.cloud.ai.lynxe.runtime.service.TaskInterruptionCheckerService;
import com.alibaba.cloud.ai.lynxe.runtime.service.UserInputService;
import com.alibaba.cloud.ai.lynxe.tool.ErrorReportTool;
import com.alibaba.cloud.ai.lynxe.tool.FormInputTool;
import com.alibaba.cloud.ai.lynxe.tool.SystemErrorReportTool;
import com.alibaba.cloud.ai.lynxe.tool.TerminableTool;
import com.alibaba.cloud.ai.lynxe.tool.TerminateTool;
import com.alibaba.cloud.ai.lynxe.tool.ToolCallBiFunctionDef;
import com.alibaba.cloud.ai.lynxe.tool.code.ToolExecuteResult;
import com.alibaba.cloud.ai.lynxe.workspace.conversation.service.MemoryService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.common.util.StringUtils;
import reactor.core.publisher.Flux;

public class DynamicAgent extends ReActAgent {

	private static final String CURRENT_STEP_ENV_DATA_KEY = "current_step_env_data";

	private static final Logger log = LoggerFactory.getLogger(DynamicAgent.class);

	private final ObjectMapper objectMapper;

	private final String agentName;

	private final String agentDescription;

	private final String nextStepPrompt;

	protected ToolCallbackProvider toolCallbackProvider;

	protected final List<String> availableToolKeys;

	private ChatResponse response;

	private StreamingResponseHandler.StreamingResult streamResult;

	private Prompt userPrompt;

	private List<ActToolParam> actToolInfoList = new ArrayList<>();

	private final ToolCallingManager toolCallingManager;

	private final UserInputService userInputService;

	private final String modelName;

	private final StreamingResponseHandler streamingResponseHandler;

	private LynxeEventPublisher lynxeEventPublisher;

	private AgentInterruptionHelper agentInterruptionHelper;

	private ParallelToolExecutionService parallelToolExecutionService;

	private MemoryService memoryService;

	private ConversationMemoryLimitService conversationMemoryLimitService;

	private ServiceGroupIndexService serviceGroupIndexService;

	/**
	 * List to record all exceptions from LLM calls during retry attempts
	 */
	private final List<Exception> llmCallExceptions = new ArrayList<>();

	/**
	 * Latest exception from LLM calls, used when max retries are reached
	 */
	private Exception latestLlmException = null;

	/**
	 * Track the last N tool call results to detect loops
	 */
	private static final int REPEATED_RESULT_THRESHOLD = 3;

	private final List<String> recentToolResults = new ArrayList<>();

	/**
	 * Flag to track if user request has been saved to conversation memory This prevents
	 * duplicate saves during retry attempts
	 */
	private boolean userRequestSavedToConversationMemory = false;

	public void clearUp(String planId) {
		Map<String, ToolCallBackContext> toolCallBackContext = toolCallbackProvider.getToolCallBackContext();
		for (ToolCallBackContext toolCallBack : toolCallBackContext.values()) {
			try {
				toolCallBack.getFunctionInstance().cleanup(planId);
			}
			catch (Exception e) {
				log.error("Error cleaning up tool callback context: {}", e.getMessage(), e);
			}
		}
		// Also remove any pending form input tool for this root plan ID
		if (userInputService != null) {
			String rootPlanId = getRootPlanId();
			if (rootPlanId != null) {
				userInputService.removeFormInputTool(rootPlanId);
			}
		}
	}

	public DynamicAgent(LlmService llmService, PlanExecutionRecorder planExecutionRecorder,
			LynxeProperties lynxeProperties, String name, String description, String nextStepPrompt,
			List<String> availableToolKeys, ToolCallingManager toolCallingManager,
			Map<String, Object> initialAgentSetting, UserInputService userInputService, String modelName,
			StreamingResponseHandler streamingResponseHandler, ExecutionStep step, PlanIdDispatcher planIdDispatcher,
			LynxeEventPublisher lynxeEventPublisher, AgentInterruptionHelper agentInterruptionHelper,
			ObjectMapper objectMapper, ParallelToolExecutionService parallelToolExecutionService,
			MemoryService memoryService, ConversationMemoryLimitService conversationMemoryLimitService,
			ServiceGroupIndexService serviceGroupIndexService) {
		super(llmService, planExecutionRecorder, lynxeProperties, initialAgentSetting, step, planIdDispatcher);
		this.objectMapper = objectMapper;
		super.objectMapper = objectMapper; // Set parent's objectMapper as well
		this.agentName = name;
		this.agentDescription = description;
		this.nextStepPrompt = nextStepPrompt;
		if (availableToolKeys == null) {
			this.availableToolKeys = new ArrayList<>();
		}
		else {
			this.availableToolKeys = availableToolKeys;
		}
		this.toolCallingManager = toolCallingManager;
		this.userInputService = userInputService;
		this.modelName = modelName;
		this.streamingResponseHandler = streamingResponseHandler;
		this.lynxeEventPublisher = lynxeEventPublisher;
		this.agentInterruptionHelper = agentInterruptionHelper;
		this.parallelToolExecutionService = parallelToolExecutionService;
		this.memoryService = memoryService;
		this.conversationMemoryLimitService = conversationMemoryLimitService;
		this.serviceGroupIndexService = serviceGroupIndexService;
	}

	@Override
	protected boolean think() {
		// Check for interruption before starting thinking process
		if (agentInterruptionHelper != null && !agentInterruptionHelper.checkInterruptionAndContinue(getRootPlanId())) {
			log.info("Agent {} thinking process interrupted for rootPlanId: {}", getName(), getRootPlanId());
			// Throw exception to signal interruption instead of returning false
			throw new TaskInterruptionCheckerService.TaskInterruptedException(
					"Agent thinking interrupted for rootPlanId: " + getRootPlanId());
		}

		collectAndSetEnvDataForTools();

		try {
			boolean result = executeWithRetry(3);
			// If retries exhausted and we have exceptions, the result will be false
			// and latestLlmException will be set
			return result;
		}
		catch (TaskInterruptionCheckerService.TaskInterruptedException e) {
			log.info("Agent {} thinking process interrupted: {}", getName(), e.getMessage());
			throw e; // Re-throw the interruption exception
		}
		catch (Exception e) {
			log.error(String.format("🚨 Oops! The %s's thinking process hit a snag: %s", getName(), e.getMessage()), e);
			log.info("Exception occurred", e);
			// Record this exception as well
			latestLlmException = e;
			llmCallExceptions.add(e);
			return false;
		}
	}

	private boolean executeWithRetry(int maxRetries) throws Exception {
		int attempt = 0;
		Exception lastException = null;
		// Track early termination count to prevent infinite loops
		int earlyTerminationCount = 0;
		final int EARLY_TERMINATION_THRESHOLD = 3; // Fail after 3 early terminations
		// Clear exception list at the start of retry cycle
		llmCallExceptions.clear();
		latestLlmException = null;

		while (attempt < maxRetries) {
			attempt++;

			// Check for interruption before each retry attempt
			if (agentInterruptionHelper != null
					&& !agentInterruptionHelper.checkInterruptionAndContinue(getRootPlanId())) {
				log.info("Agent {} retry process interrupted at attempt {}/{} for rootPlanId: {}", getName(), attempt,
						maxRetries, getRootPlanId());
				throw new TaskInterruptionCheckerService.TaskInterruptedException(
						"Agent thinking interrupted at attempt " + attempt);
			}

			try {
				log.info("Attempt {}/{}: Executing agent thinking process", attempt, maxRetries);

				Message systemMessage = getThinkMessage();
				// Use current env as user message
				Message currentStepEnvMessage = currentStepEnvMessage();

				// If early termination occurred in previous attempt, add explicit tool
				// call requirement
				if (earlyTerminationCount > 0) {
					String toolCallRequirement = String
						.format("\n\n⚠️ IMPORTANT: You must call at least one tool to proceed. "
								+ "Previous attempt returned only text without tool calls (early termination detected %d time(s)). "
								+ "Do not provide explanations or reasoning - call a tool immediately.",
								earlyTerminationCount);
					// Append requirement to current step env message
					String enhancedEnvText = currentStepEnvMessage.getText() + toolCallRequirement;
					// Create new UserMessage with enhanced text, preserving metadata
					UserMessage enhancedMessage = new UserMessage(enhancedEnvText);
					if (currentStepEnvMessage.getMetadata() != null) {
						enhancedMessage.getMetadata().putAll(currentStepEnvMessage.getMetadata());
					}
					currentStepEnvMessage = enhancedMessage;
					log.info("Added explicit tool call requirement to retry message (early termination count: {})",
							earlyTerminationCount);
				}
				// Record think message
				List<Message> thinkMessages = Arrays.asList(systemMessage, currentStepEnvMessage);
				String thinkInput = thinkMessages.toString();

				// log.debug("Messages prepared for the prompt: {}", thinkMessages);
				// Build current prompt. System message is the first message
				List<Message> messages = new ArrayList<>();
				// Add history message from agent memory
				ChatMemory chatMemory = llmService.getAgentMemory(lynxeProperties.getMaxMemory());
				List<Message> historyMem = chatMemory.get(getCurrentPlanId());
				// List<Message> subAgentMem = chatMemory.get(getCurrentPlanId());

				// Add conversation history from MemoryService if conversationId is
				// available and conversation memory is enabled
				if (lynxeProperties.getEnableConversationMemory() && memoryService != null
						&& getConversationId() != null && !getConversationId().trim().isEmpty()) {
					try {
						ChatMemory conversationMemory = llmService
							.getConversationMemoryWithLimit(lynxeProperties.getMaxMemory(), getConversationId());
						List<Message> conversationHistory = conversationMemory.get(getConversationId());
						if (conversationHistory != null && !conversationHistory.isEmpty()) {
							log.debug("Adding {} conversation history messages for conversationId: {}",
									conversationHistory.size(), getConversationId());
							// Insert conversation history before current step env message
							// to maintain chronological order
							messages.addAll(conversationHistory);
						}
					}
					catch (Exception e) {
						log.warn(
								"Failed to retrieve conversation history for conversationId: {}. Continuing without it.",
								getConversationId(), e);
					}
				}
				else if (!lynxeProperties.getEnableConversationMemory()) {
					log.debug("Conversation memory is disabled, skipping conversation history retrieval");
				}
				messages.addAll(Collections.singletonList(systemMessage));
				messages.addAll(historyMem);
				messages.add(currentStepEnvMessage);

				// Save user request (stepText) to conversation memory after building
				// messages
				// This prevents duplicate messages in the conversation history
				saveUserRequestToConversationMemory();

				String toolcallId = planIdDispatcher.generateToolCallId();
				// Call the LLM
				Map<String, Object> toolContextMap = new HashMap<>();
				toolContextMap.put("toolcallId", toolcallId);
				toolContextMap.put("planDepth", getPlanDepth());
				ToolCallingChatOptions chatOptions = ToolCallingChatOptions.builder()
					.internalToolExecutionEnabled(false)
					.toolContext(toolContextMap)
					// can't support by toocall options :
					// .parallelToolCalls(lynxeProperties.getParallelToolCalls())
					.build();
				userPrompt = new Prompt(messages, chatOptions);
				List<ToolCallback> callbacks = getToolCallList();
				ChatClient chatClient;
				if (modelName == null || modelName.isEmpty()) {
					chatClient = llmService.getDefaultDynamicAgentChatClient();
				}
				else {
					chatClient = llmService.getDynamicAgentChatClient(modelName);
				}
				// Calculate input character count from all messages before calling LLM
				int inputCharCount = messages.stream().mapToInt(message -> {
					String text = message.getText();
					if (text == null || text.trim().isEmpty()) {
						return 0;
					}
					return text.length();
				}).sum();
				log.info("User prompt character count: {}", inputCharCount);

				// Use streaming response handler for better user experience and content
				// merging
				Flux<ChatResponse> responseFlux = chatClient.prompt(userPrompt)
					.toolCallbacks(callbacks)
					.stream()
					.chatResponse();
				boolean isDebugModel = lynxeProperties.getDebugDetail() != null && lynxeProperties.getDebugDetail();
				// Enable early termination for agent thinking (should have tool calls)
				streamResult = streamingResponseHandler.processStreamingResponse(responseFlux,
						"Agent " + getName() + " thinking", getCurrentPlanId(), isDebugModel, true, inputCharCount);

				response = streamResult.getLastResponse();

				// Use merged content from streaming handler
				List<ToolCall> toolCalls = streamResult.getEffectiveToolCalls();
				String responseByLLm = streamResult.getEffectiveText();

				// Get input and output character counts from StreamingResult
				int finalInputCharCount = streamResult.getInputCharCount();
				int finalOutputCharCount = streamResult.getOutputCharCount();
				log.info("Input character count: {}, Output character count: {}", finalInputCharCount,
						finalOutputCharCount);

				// Check for early termination
				boolean isEarlyTerminated = streamResult.isEarlyTerminated();
				if (isEarlyTerminated) {
					earlyTerminationCount++;
					log.warn(
							"Early termination detected (attempt {}): thinking-only response with no tool calls. Count: {}/{}",
							attempt, earlyTerminationCount, EARLY_TERMINATION_THRESHOLD);

					// If early termination threshold reached, fail gracefully
					if (earlyTerminationCount >= EARLY_TERMINATION_THRESHOLD) {
						log.error(
								"Early termination threshold ({}) reached. LLM repeatedly returned thinking-only responses without tool calls. Failing gracefully.",
								EARLY_TERMINATION_THRESHOLD);
						// Store a special exception to indicate early termination failure
						latestLlmException = new Exception(
								"Early termination threshold reached: LLM returned thinking-only responses without tool calls "
										+ earlyTerminationCount + " times. The model must call tools to proceed.");
						return false; // Return false to trigger failure handling in
										// step()
					}
				}

				log.info(String.format("✨ %s's thoughts: %s", getName(), responseByLLm));
				log.info(String.format("🛠️ %s selected %d tools to use", getName(), toolCalls.size()));

				if (!toolCalls.isEmpty()) {
					// Reset early termination count on successful tool call
					earlyTerminationCount = 0;
					log.info(String.format("🧰 Tools being prepared: %s",
							toolCalls.stream().map(ToolCall::name).collect(Collectors.toList())));

					String stepId = super.step.getStepId();
					String thinkActId = planIdDispatcher.generateThinkActId();

					actToolInfoList = new ArrayList<>();
					for (ToolCall toolCall : toolCalls) {
						ActToolParam actToolInfo = new ActToolParam(toolCall.name(), toolCall.arguments(), toolcallId);
						actToolInfoList.add(actToolInfo);
					}

					ThinkActRecordParams paramsN = new ThinkActRecordParams(thinkActId, stepId, thinkInput,
							responseByLLm, null, finalInputCharCount, finalOutputCharCount, actToolInfoList);
					planExecutionRecorder.recordThinkingAndAction(step, paramsN);

					// Clear exception cache if this was a retry attempt
					if (attempt > 1 && lynxeEventPublisher != null) {
						log.info("Retry successful for planId: {}, clearing exception cache", getCurrentPlanId());
						lynxeEventPublisher.publish(new PlanExceptionClearedEvent(getCurrentPlanId()));
					}

					return true;
				}

				// No tool calls - check if this is due to early termination
				if (isEarlyTerminated) {
					log.warn(
							"Attempt {}: Early termination - no tools selected (thinking-only response). Retrying with explicit tool call requirement...",
							attempt);
				}
				else {
					log.warn("Attempt {}: No tools selected. Retrying...", attempt);
				}

			}
			catch (Exception e) {
				lastException = e;
				latestLlmException = e;
				// Record exception to the list (record all exceptions, even non-retryable
				// ones)
				llmCallExceptions.add(e);
				log.warn("Attempt {} failed: {}", attempt, e.getMessage());
				log.debug("Exception details for attempt {}: {}", attempt, e.getMessage(), e);

				// Check if this is a network-related error that should be retried
				if (isRetryableException(e)) {
					if (attempt < maxRetries) {
						long waitTime = calculateBackoffDelay(attempt);
						log.info("Retrying in {}ms due to retryable error: {}", waitTime, e.getMessage());
						try {
							Thread.sleep(waitTime);
						}
						catch (InterruptedException ie) {
							Thread.currentThread().interrupt();
							throw new Exception("Retry interrupted", ie);
						}
						continue;
					}
				}
				else {
					// Non-retryable error - still record it, but throw immediately
					log.error("Non-retryable error encountered at attempt {}/{}: {}", attempt, maxRetries,
							e.getMessage());
					throw e;
				}
			}
		}

		// All retries exhausted
		if (lastException != null) {
			log.error("All {} retry attempts failed. Total exceptions recorded: {}. Latest exception: {}", maxRetries,
					llmCallExceptions.size(), latestLlmException != null ? latestLlmException.getMessage() : "N/A");
			// Store the latest exception for use in step() method
			// Don't throw exception here, let think() return false and step() handle it
			return false;
		}
		return false;
	}

	/**
	 * Check if the exception is retryable (network issues, timeouts, etc.)
	 */
	private boolean isRetryableException(Exception e) {
		String message = e.getMessage();
		if (message == null)
			return false;

		// Check for network-related errors
		return message.contains("Failed to resolve") || message.contains("timeout") || message.contains("connection")
				|| message.contains("DNS") || message.contains("WebClientRequestException")
				|| message.contains("DnsNameResolverTimeoutException");
	}

	/**
	 * Calculate exponential backoff delay
	 */
	private long calculateBackoffDelay(int attempt) {
		// Exponential backoff: 2^attempt * 2000ms, max 60 seconds
		long delay = Math.min(2000L * (1L << (attempt - 1)), 60000L);
		return delay;
	}

	@Override
	public AgentExecResult step() {
		try {
			boolean shouldAct = think();
			if (!shouldAct) {
				// Check if we have a latest exception from LLM calls (max retries
				// reached)
				if (latestLlmException != null) {
					// Check if failure was due to early termination threshold
					if (latestLlmException.getMessage() != null
							&& latestLlmException.getMessage().contains("Early termination threshold reached")) {
						log.error(
								"Agent {} failed due to early termination threshold. LLM repeatedly returned thinking-only responses without tool calls.",
								getName());
						// Return FAILED state to stop infinite retry loop
						return new AgentExecResult(
								"Agent failed: LLM repeatedly returned thinking-only responses without tool calls. "
										+ "Please ensure the model is configured to call tools. "
										+ latestLlmException.getMessage(),
								AgentState.FAILED);
					}

					log.error(
							"Agent {} thinking failed after all retries. Simulating full flow with SystemErrorReportTool",
							getName());
					return handleLlmTimeoutWithSystemErrorReport();
				}
				// No tools selected after all retries - require LLM to output tool calls
				log.warn("Agent {} did not select any tools after all retries. Requiring tool call.", getName());
				return new AgentExecResult(
						"No tools were selected. You must select and call at least one tool to proceed. Please retry with tool calls.",
						AgentState.IN_PROGRESS);
			}
			return act();
		}
		catch (TaskInterruptionCheckerService.TaskInterruptedException e) {
			// Agent was interrupted, return INTERRUPTED state to stop execution
			return new AgentExecResult("Agent execution interrupted: " + e.getMessage(), AgentState.INTERRUPTED);
		}
		catch (Exception e) {
			log.error("Unexpected exception in step()", e);
			return handleExceptionWithSystemErrorReport(e, new ArrayList<>());
		}
	}

	/**
	 * Get the list of all exceptions recorded during LLM calls
	 * @return List of exceptions (may be empty if no exceptions occurred)
	 */
	public List<Exception> getLlmCallExceptions() {
		return new ArrayList<>(llmCallExceptions); // Return a copy to prevent external
													// modification
	}

	/**
	 * Get the latest exception from LLM calls
	 * @return Latest exception, or null if no exceptions occurred
	 */
	public Exception getLatestLlmException() {
		return latestLlmException;
	}

	/**
	 * Build error message from the latest exception
	 * @return Formatted error message with exception details
	 */
	private String buildErrorMessageFromLatestException() {
		if (latestLlmException == null) {
			return "Unknown error occurred during LLM call";
		}

		StringBuilder errorMessage = new StringBuilder();
		errorMessage.append("LLM call failed after all retry attempts. ");

		// Add exception type and message
		String exceptionType = latestLlmException.getClass().getSimpleName();
		String exceptionMessage = latestLlmException.getMessage();

		errorMessage.append("Latest error: [").append(exceptionType).append("] ").append(exceptionMessage);

		// Add exception count information
		if (!llmCallExceptions.isEmpty()) {
			errorMessage.append(" (Total attempts: ").append(llmCallExceptions.size()).append(")");
		}

		// Add detailed error information for WebClientResponseException
		if (latestLlmException instanceof org.springframework.web.reactive.function.client.WebClientResponseException webClientException) {
			String responseBody = webClientException.getResponseBodyAsString();
			if (responseBody != null && !responseBody.isEmpty()) {
				errorMessage.append(". API Response: ").append(responseBody);
			}
		}

		return errorMessage.toString();
	}

	@Override
	protected AgentExecResult act() {
		// Check for interruption before starting action process
		if (agentInterruptionHelper != null && !agentInterruptionHelper.checkInterruptionAndContinue(getRootPlanId())) {
			log.info("Agent {} action process interrupted for rootPlanId: {}", getName(), getRootPlanId());
			return new AgentExecResult("Action interrupted by user", AgentState.INTERRUPTED);
		}

		try {
			List<ToolCall> toolCalls = streamResult.getEffectiveToolCalls();

			// Route to appropriate handler based on tool count
			if (toolCalls == null || toolCalls.isEmpty()) {
				return new AgentExecResult("tool call is empty , please retry", AgentState.IN_PROGRESS);
			}
			else if (toolCalls.size() == 1) {
				// Single tool execution - core logic
				return processSingleTool(toolCalls.get(0));
			}
			else {
				// Multiple tools execution
				return processMultipleTools(toolCalls);
			}
		}
		catch (Exception e) {
			log.error("Error executing tools: {}", e.getMessage(), e);

			StringBuilder errorMessage = new StringBuilder("Error executing tools: ");
			errorMessage.append(e.getMessage());

			String firstToolcall = actToolInfoList != null && !actToolInfoList.isEmpty()
					&& actToolInfoList.get(0).getParameters() != null
							? actToolInfoList.get(0).getParameters().toString() : "unknown";
			errorMessage.append("  . llm return param :  ").append(firstToolcall);

			// Clean up form input tool using root plan ID on error
			String rootPlanId = getRootPlanId();
			if (rootPlanId != null) {
				userInputService.removeFormInputTool(rootPlanId);
			}
			return new AgentExecResult(e.getMessage(), AgentState.COMPLETED);
		}
	}

	/**
	 * Process a single tool execution This is the core logic for tool execution
	 * @param toolCall The tool call to execute
	 * @return AgentExecResult containing the execution result
	 */
	private AgentExecResult processSingleTool(ToolCall toolCall) {
		ToolExecutionResult toolExecutionResult = null;
		try {
			// Check for interruption before tool execution
			if (agentInterruptionHelper != null
					&& !agentInterruptionHelper.checkInterruptionAndContinue(getRootPlanId())) {
				log.info("Agent {} tool execution interrupted for rootPlanId: {}", getName(), getRootPlanId());
				return new AgentExecResult("Tool execution interrupted by user", AgentState.INTERRUPTED);
			}

			// Execute tool call
			toolExecutionResult = toolCallingManager.executeToolCalls(userPrompt, response);
			processMemory(toolExecutionResult);

			// Get tool response message
			ToolResponseMessage toolResponseMessage = (ToolResponseMessage) toolExecutionResult.conversationHistory()
				.get(toolExecutionResult.conversationHistory().size() - 1);

			if (toolResponseMessage.getResponses().isEmpty()) {
				return new AgentExecResult("Tool response is empty", AgentState.IN_PROGRESS);
			}

			// Process single tool response
			ToolResponseMessage.ToolResponse toolCallResponse = toolResponseMessage.getResponses().get(0);
			String toolName = toolCall.name();
			ActToolParam param = actToolInfoList.get(0);

			// Check if tool callback context exists
			ToolCallBackContext toolCallBackContext = getToolCallBackContext(toolName);
			if (toolCallBackContext == null) {
				String errorMessage = String.format("Tool callback context not found for tool: %s", toolName);
				log.error(errorMessage);
				// Process tool result even if callback context is missing
				String result = processToolResult(toolCallResponse.responseData());
				param.setResult(result);
				// Return error result but continue execution
				return new AgentExecResult(errorMessage + ". Tool response: " + result, AgentState.IN_PROGRESS);
			}

			ToolCallBiFunctionDef<?> toolInstance = toolCallBackContext.getFunctionInstance();

			String result;
			boolean shouldTerminate = false;

			// Handle different tool types
			if (toolInstance instanceof FormInputTool) {
				AgentExecResult formResult = handleFormInputTool((FormInputTool) toolInstance, param);
				result = formResult.getResult();
				param.setResult(result);
			}
			else if (toolInstance instanceof TerminableTool) {
				TerminableTool terminableTool = (TerminableTool) toolInstance;
				result = processToolResult(toolCallResponse.responseData());
				param.setResult(result);

				// Handle TerminateTool specifically - set state to COMPLETED
				if (toolInstance instanceof TerminateTool) {
					log.info("TerminateTool called for planId: {}", getCurrentPlanId());
					shouldTerminate = true;
				}
				// Handle ErrorReportTool specifically to extract errorMessage
				else if (toolInstance instanceof ErrorReportTool) {
					String errorMessage = extractAndSetErrorMessage(result, "ErrorReportTool");
					recordErrorToolThinkingAndAction(param, "Error occurred during execution",
							"ErrorReportTool called to report error", errorMessage);
				}

				if (terminableTool.canTerminate()) {
					log.info("TerminableTool can terminate for planId: {}", getCurrentPlanId());
					String rootPlanId = getRootPlanId();
					if (rootPlanId != null) {
						userInputService.removeFormInputTool(rootPlanId);
					}
					shouldTerminate = true;
				}
				else {
					log.info("TerminableTool cannot terminate yet for planId: {}", getCurrentPlanId());
				}
			}
			// Handle SystemErrorReportTool specifically to extract errorMessage
			else if (toolInstance instanceof SystemErrorReportTool) {
				result = processToolResult(toolCallResponse.responseData());
				param.setResult(result);
				String errorMessage = extractAndSetErrorMessage(result, "SystemErrorReportTool");
				recordErrorToolThinkingAndAction(param, "System error occurred during execution",
						"SystemErrorReportTool called to report system error", errorMessage);
			}
			else {
				// Regular tool
				result = processToolResult(toolCallResponse.responseData());
				param.setResult(result);
				log.info("Tool {} executed successfully for planId: {}", toolName, getCurrentPlanId());
			}

			// Execute shared post-tool flow
			executePostToolFlow(toolInstance, toolCallResponse, result, List.of(param));

			// Check for repeated results and force compress if detected
			checkAndHandleRepeatedResult(result);

			// Return result with appropriate state
			// Note: Final result will be saved to conversation memory in
			// handleCompletedExecution()
			return new AgentExecResult(result, shouldTerminate ? AgentState.COMPLETED : AgentState.IN_PROGRESS);
		}
		catch (Exception e) {
			log.error("Error executing single tool: {}", e.getMessage(), e);
			processMemory(toolExecutionResult); // Process memory even on error
			// For other errors, wrap exception with SystemErrorReportTool
			List<AgentExecResult> emptyResults = new ArrayList<>();
			return handleExceptionWithSystemErrorReport(e, emptyResults);
		}
	}

	/**
	 * Process multiple tools execution using parallel execution service Multiple tools
	 * execution does not support TerminableTool and FormInputTool. If these tools are
	 * present, return error message asking LLM to retry without them.
	 * @param toolCalls List of tool calls to execute
	 * @return AgentExecResult containing the execution results
	 */
	private AgentExecResult processMultipleTools(List<ToolCall> toolCalls) {
		// Check for interruption before starting
		if (agentInterruptionHelper != null && !agentInterruptionHelper.checkInterruptionAndContinue(getRootPlanId())) {
			log.info("Agent {} tool execution interrupted before starting for rootPlanId: {}", getName(),
					getRootPlanId());
			return new AgentExecResult("Tool execution interrupted by user", AgentState.INTERRUPTED);
		}

		try {
			// Check for TerminableTool and FormInputTool in multiple tools
			List<String> restrictedToolNames = new ArrayList<>();
			for (ToolCall toolCall : toolCalls) {
				String toolName = toolCall.name();
				ToolCallBackContext context = getToolCallBackContext(toolName);
				if (context != null) {
					ToolCallBiFunctionDef<?> toolInstance = context.getFunctionInstance();
					if (toolInstance instanceof TerminableTool || toolInstance instanceof FormInputTool) {
						restrictedToolNames.add(toolName);
					}
				}
			}

			// If restricted tools found, return error asking LLM to retry without them
			if (!restrictedToolNames.isEmpty()) {
				String errorMessage = String.format(
						"Multiple tools execution does not support TerminableTool and FormInputTool. "
								+ "Found restricted tools: %s. Please retry by calling tools separately, "
								+ "excluding TerminableTool and FormInputTool from multiple tool calls.",
						String.join(", ", restrictedToolNames));
				log.warn("Multiple tools execution rejected: {}", errorMessage);
				return new AgentExecResult(errorMessage, AgentState.IN_PROGRESS);
			}

			// Execute all tools in parallel
			if (parallelToolExecutionService == null) {
				log.error("ParallelToolExecutionService is not available");
				return new AgentExecResult("Parallel execution service is not available", AgentState.COMPLETED);
			}

			Map<String, ToolCallBackContext> toolCallbackMap = toolCallbackProvider.getToolCallBackContext();
			Map<String, Object> toolContextMap = new HashMap<>();
			toolContextMap.put("toolcallId", planIdDispatcher.generateToolCallId());
			toolContextMap.put("planDepth", getPlanDepth());
			ToolContext parentToolContext = new ToolContext(toolContextMap);

			List<ParallelToolExecutionService.ToolExecutionResult> parallelResults = parallelToolExecutionService
				.executeToolsInParallel(toolCalls, toolCallbackMap, planIdDispatcher, parentToolContext);
			log.info("Executed {} tools in parallel", parallelResults.size());

			// Process results and update actToolInfoList
			List<String> resultList = new ArrayList<>();
			for (int i = 0; i < toolCalls.size() && i < actToolInfoList.size(); i++) {
				ToolCall toolCall = toolCalls.get(i);
				String toolName = toolCall.name();
				ActToolParam param = actToolInfoList.get(i);

				// Find corresponding result
				String processedResult = null;
				for (ParallelToolExecutionService.ToolExecutionResult result : parallelResults) {
					if (result.getToolName().equals(toolName)) {
						if (result.isSuccess()) {
							processedResult = processToolResult(result.getResult().getOutput());
						}
						else {
							processedResult = "Error: " + result.getResult().getOutput();
						}
						break;
					}
				}

				if (processedResult == null) {
					processedResult = "Tool execution result not found";
					log.warn("Result not found for tool: {}", toolName);
				}

				param.setResult(processedResult);
				resultList.add(processedResult);
				log.info("Tool {} executed successfully for planId: {}", toolName, getCurrentPlanId());
			}

			// Record the results
			recordActionResult(actToolInfoList);

			// Update memory using ToolCallingManager (for compatibility)
			try {
				ToolExecutionResult toolExecutionResult = toolCallingManager.executeToolCalls(userPrompt, response);
				processMemory(toolExecutionResult);
			}
			catch (Exception e) {
				log.warn("Error processing memory after parallel execution: {}", e.getMessage());
			}

			// Return result
			return new AgentExecResult(resultList.toString(), AgentState.IN_PROGRESS);
		}
		catch (Exception e) {
			log.error("Error executing multiple tools: {}", e.getMessage(), e);
			return new AgentExecResult("Error executing tools: " + e.getMessage(), AgentState.IN_PROGRESS);
		}
	}

	/**
	 * Handle FormInputTool specific logic with exclusive storage
	 */
	private AgentExecResult handleFormInputTool(FormInputTool formInputTool, ActToolParam param) {
		// Ensure the form input tool has the correct plan IDs set
		formInputTool.setCurrentPlanId(getCurrentPlanId());
		formInputTool.setRootPlanId(getRootPlanId());

		// Check if the tool is waiting for user input
		if (formInputTool.getInputState() == FormInputTool.InputState.AWAITING_USER_INPUT) {
			String rootPlanId = getRootPlanId();
			String currentPlanId = getCurrentPlanId();
			log.info("FormInputTool is awaiting user input for rootPlanId: {} (currentPlanId: {})", rootPlanId,
					currentPlanId);

			// Use exclusive storage method - this will handle waiting and queuing
			// automatically
			boolean stored = userInputService.storeFormInputToolExclusive(rootPlanId, formInputTool, currentPlanId);
			if (!stored) {
				log.error("Failed to store form for sub-plan {} due to lock timeout or interruption", currentPlanId);
				param.setResult("Failed to store form due to system timeout");
				return new AgentExecResult("Failed to store form due to system timeout", AgentState.COMPLETED);
			}

			// Wait for user input or timeout
			waitForUserInputOrTimeout(formInputTool);

			// After waiting, check the state again
			if (formInputTool.getInputState() == FormInputTool.InputState.INPUT_RECEIVED) {
				log.info("User input received for rootPlanId: {} from sub-plan {}", rootPlanId, currentPlanId);

				UserMessage userMessage = UserMessage.builder()
					.text("User input received for form: " + formInputTool.getCurrentToolStateString())
					.build();
				processUserInputToMemory(userMessage);

				// Update the result in actToolInfoList
				param.setResult(formInputTool.getCurrentToolStateString());
				return new AgentExecResult(param.getResult(), AgentState.IN_PROGRESS);

			}
			else if (formInputTool.getInputState() == FormInputTool.InputState.INPUT_TIMEOUT) {
				log.warn("Input timeout occurred for FormInputTool for rootPlanId: {} from sub-plan {}", rootPlanId,
						currentPlanId);

				UserMessage userMessage = UserMessage.builder().text("Input timeout occurred for form: ").build();
				processUserInputToMemory(userMessage);
				userInputService.removeFormInputTool(rootPlanId);
				param.setResult("Input timeout occurred");

				return new AgentExecResult("Input timeout occurred.", AgentState.IN_PROGRESS);
			}
			else {
				throw new RuntimeException("FormInputTool is not in the correct state");
			}
		}
		else {
			throw new RuntimeException("FormInputTool is not in the correct state");
		}
	}

	/**
	 * Recursively convert HashMap to LinkedHashMap to preserve insertion order
	 * @param obj The object that may contain HashMaps
	 * @return Object with all HashMaps converted to LinkedHashMaps
	 */
	private Object convertToLinkedHashMap(Object obj) {
		if (obj == null) {
			return null;
		}

		if (obj instanceof Map<?, ?> map) {
			// Convert HashMap to LinkedHashMap
			Map<String, Object> linkedMap = new LinkedHashMap<>();
			for (Map.Entry<?, ?> entry : map.entrySet()) {
				if (entry.getKey() instanceof String key) {
					linkedMap.put(key, convertToLinkedHashMap(entry.getValue()));
				}
			}
			return linkedMap;
		}
		else if (obj instanceof List<?> list) {
			// Recursively process list items
			List<Object> linkedList = new ArrayList<>();
			for (Object item : list) {
				linkedList.add(convertToLinkedHashMap(item));
			}
			return linkedList;
		}

		return obj;
	}

	/**
	 * Process tool result to remove escaped JSON if it's a valid JSON string. This fixes
	 * the issue where DefaultToolCallingManager returns escaped JSON strings.
	 * @param result The raw tool result string
	 * @return Processed result with unescaped JSON if applicable
	 */
	private String processToolResult(String result) {
		if (result == null || result.trim().isEmpty()) {
			return result;
		}

		// Try to parse and re-serialize if it's a valid JSON string
		// This removes escaping that might have been added by DefaultToolCallingManager
		try {
			// First, try to parse as JSON object using LinkedHashMap to preserve order
			// Try as Map first, if it fails, fall back to Object.class
			Object jsonObject;
			try {
				jsonObject = objectMapper.readValue(result, new TypeReference<LinkedHashMap<String, Object>>() {
				});
			}
			catch (Exception e) {
				// If parsing as Map fails, try as generic Object
				jsonObject = objectMapper.readValue(result, Object.class);
			}

			// Check if it's a Map with "output" field (from DefaultToolCallingManager
			// format)
			if (jsonObject instanceof Map<?, ?> map) {
				Object outputValue = map.get("output");
				if (outputValue instanceof String outputString) {
					// The output field contains an escaped JSON string, parse it
					try {
						// Use TypeReference with LinkedHashMap to preserve property order
						Object innerJsonObject = objectMapper.readValue(outputString,
								new TypeReference<LinkedHashMap<String, Object>>() {
								});
						// Recursively convert any nested HashMaps to LinkedHashMaps
						innerJsonObject = convertToLinkedHashMap(innerJsonObject);
						// Create a new map with the parsed inner JSON object, preserving
						// the "output" field
						// Use LinkedHashMap to preserve insertion order
						Map<String, Object> resultMap = new LinkedHashMap<>();
						// Copy all entries from the original map (preserve order if it's
						// already LinkedHashMap)
						for (Map.Entry<?, ?> entry : map.entrySet()) {
							if (entry.getKey() instanceof String key) {
								resultMap.put(key, entry.getValue());
							}
						}
						resultMap.put("output", innerJsonObject);
						// Return the unescaped JSON string with output field preserved
						return objectMapper.writeValueAsString(resultMap);
					}
					catch (Exception innerException) {
						// If inner parsing fails, return the original map as-is
						return objectMapper.writeValueAsString(jsonObject);
					}
				}
				else {
					// It's a Map but no "output" field or output is not a string,
					// Convert to LinkedHashMap to preserve order, then re-serialize
					Object convertedObject = convertToLinkedHashMap(jsonObject);
					return objectMapper.writeValueAsString(convertedObject);
				}
			}
			// If the parsed object is a String, it means the input was a JSON string
			// (e.g., "\"{\\\"message\\\":[...]}\""), so we need to parse it again
			else if (jsonObject instanceof String jsonString) {
				// Try to parse the inner JSON string
				try {
					// Use TypeReference with LinkedHashMap to preserve property order
					Object innerJsonObject = objectMapper.readValue(jsonString,
							new TypeReference<LinkedHashMap<String, Object>>() {
							});
					// Recursively convert any nested HashMaps to LinkedHashMaps
					innerJsonObject = convertToLinkedHashMap(innerJsonObject);
					// Re-serialize the inner JSON object
					return objectMapper.writeValueAsString(innerJsonObject);
				}
				catch (Exception innerException) {
					// If inner parsing fails, return the parsed string as-is
					return jsonString;
				}
			}
			else {
				// It's already a JSON object, convert to LinkedHashMap to preserve order,
				// then re-serialize
				Object convertedObject = convertToLinkedHashMap(jsonObject);
				return objectMapper.writeValueAsString(convertedObject);
			}
		}
		catch (Exception e) {
			// If it's not valid JSON, return as-is
			return result;
		}
	}

	/**
	 * Record action result with simplified parameters
	 */
	private void recordActionResult(List<ActToolParam> actToolInfoList) {
		planExecutionRecorder.recordActionResult(actToolInfoList);
	}

	/**
	 * Execute shared post-tool flow - record action result This method is called after
	 * tool execution to perform common post-processing
	 * @param toolInstance The tool instance that was executed
	 * @param toolCallResponse The tool call response
	 * @param result The processed result string
	 * @param actToolParams The action tool parameters
	 */
	private void executePostToolFlow(ToolCallBiFunctionDef<?> toolInstance,
			ToolResponseMessage.ToolResponse toolCallResponse, String result, List<ActToolParam> actToolParams) {
		// Record the result
		recordActionResult(actToolParams);
	}

	/**
	 * Extract error message from tool result and set it on the step
	 * @param result The tool result JSON string
	 * @param toolName The name of the tool (for logging)
	 * @return The extracted error message, or the result itself if extraction fails
	 */
	private String extractAndSetErrorMessage(String result, String toolName) {
		try {
			@SuppressWarnings("unchecked")
			Map<String, Object> errorData = objectMapper.readValue(result, Map.class);
			String errorMessage = (String) errorData.get("errorMessage");
			if (errorMessage != null && !errorMessage.isEmpty()) {
				step.setErrorMessage(errorMessage);
				log.info("{} extracted errorMessage for stepId: {}, errorMessage: {}", toolName, step.getStepId(),
						errorMessage);
				return errorMessage;
			}
		}
		catch (Exception e) {
			log.warn("Failed to parse errorMessage from {} result: {}", toolName, result, e);
		}
		// Fallback: use the result as errorMessage
		step.setErrorMessage(result);
		return result;
	}

	/**
	 * Record thinking and action for error reporting tools to make them visible in
	 * frontend
	 * @param param The ActToolParam containing tool information
	 * @param thinkInput Description of the error context
	 * @param thinkOutput Description of what tool was called
	 * @param errorMessage The actual error message
	 */
	private void recordErrorToolThinkingAndAction(ActToolParam param, String thinkInput, String thinkOutput,
			String errorMessage) {
		try {
			String stepId = step.getStepId();
			String thinkActId = planIdDispatcher.generateThinkActId();
			String finalErrorMessage = step.getErrorMessage() != null ? step.getErrorMessage() : errorMessage;

			ThinkActRecordParams errorParams = new ThinkActRecordParams(thinkActId, stepId, thinkInput, thinkOutput,
					finalErrorMessage, List.of(param));
			planExecutionRecorder.recordThinkingAndAction(step, errorParams);
			log.info("Recorded thinking and action for error tool, stepId: {}", stepId);
		}
		catch (Exception e) {
			log.warn("Failed to record thinking and action for error tool", e);
		}
	}

	/**
	 * Handle LLM timeout (3 retries exhausted) by simulating full flow with
	 * SystemErrorReportTool
	 * @return AgentExecResult with error information
	 */
	private AgentExecResult handleLlmTimeoutWithSystemErrorReport() {
		log.error("Handling LLM timeout with SystemErrorReportTool");

		try {
			// Create SystemErrorReportTool instance
			SystemErrorReportTool errorTool = new SystemErrorReportTool(getCurrentPlanId(), objectMapper);

			// Build error message from latest exception
			String errorMessage = buildErrorMessageFromLatestException();

			// Create tool input
			Map<String, Object> errorInput = Map.of("errorMessage", errorMessage);

			// Execute the error report tool
			ToolExecuteResult toolResult = errorTool.run(errorInput);

			// Simulate post-tool flow (memory processing, recording, etc.)
			String result = simulatePostToolFlow(errorTool, toolResult, errorMessage);

			// Extract error message for step
			try {
				@SuppressWarnings("unchecked")
				Map<String, Object> errorData = objectMapper.readValue(toolResult.getOutput(), Map.class);
				String extractedErrorMessage = (String) errorData.get("errorMessage");
				if (extractedErrorMessage != null && !extractedErrorMessage.isEmpty()) {
					step.setErrorMessage(extractedErrorMessage);
				}
			}
			catch (Exception e) {
				log.warn("Failed to parse errorMessage from SystemErrorReportTool result", e);
				step.setErrorMessage(errorMessage);
			}

			// Record thinking and action for SystemErrorReportTool to make it visible in
			// frontend
			String toolCallId = planIdDispatcher.generateToolCallId();
			String parametersJson = objectMapper.writeValueAsString(errorInput);
			ActToolParam param = new ActToolParam(SystemErrorReportTool.name, parametersJson, toolResult.getOutput(),
					toolCallId);
			String finalErrorMessage = step.getErrorMessage() != null ? step.getErrorMessage() : errorMessage;
			recordErrorToolThinkingAndAction(param, "LLM timeout after 3 retries",
					"SystemErrorReportTool called to report LLM timeout error", finalErrorMessage);

			return new AgentExecResult(result, AgentState.FAILED);
		}
		catch (Exception e) {
			log.error("Failed to handle LLM timeout with SystemErrorReportTool", e);
			String fallbackError = "LLM timeout error: " + buildErrorMessageFromLatestException();
			step.setErrorMessage(fallbackError);
			return new AgentExecResult(fallbackError, AgentState.FAILED);
		}
	}

	@Override
	protected String simulatePostToolFlow(Object tool, ToolExecuteResult toolResult, String errorMessage) {
		// Override to provide DynamicAgent-specific post-tool flow
		// This simulates what normally happens after tool execution:
		// 1. Process memory (if applicable)
		// 2. Record action result

		// For SystemErrorReportTool, we need to create a mock ActToolParam for recording
		if (tool instanceof SystemErrorReportTool) {
			try {
				String toolCallId = planIdDispatcher.generateToolCallId();
				String parametersJson = objectMapper.writeValueAsString(Map.of("errorMessage", errorMessage));
				ActToolParam param = new ActToolParam(SystemErrorReportTool.name, parametersJson,
						toolResult.getOutput(), toolCallId);

				// Record the action result
				recordActionResult(List.of(param));
			}
			catch (Exception e) {
				log.warn("Failed to record SystemErrorReportTool result", e);
			}
		}

		return toolResult.getOutput();
	}

	/**
	 * Check if the tool result is repeating and force compress memory if detected This
	 * helps break potential loops where the agent keeps getting the same result
	 * @param result The tool execution result to check
	 */
	private void checkAndHandleRepeatedResult(String result) {
		if (result == null || result.trim().isEmpty()) {
			return;
		}

		// Add to recent results list without normalization
		recentToolResults.add(result);

		// Keep only the last REPEATED_RESULT_THRESHOLD results
		if (recentToolResults.size() > REPEATED_RESULT_THRESHOLD) {
			recentToolResults.remove(0);
		}

		// Check if we have enough samples and if they're all the same
		if (recentToolResults.size() >= REPEATED_RESULT_THRESHOLD) {
			boolean allSame = true;
			String firstResult = recentToolResults.get(0);

			for (int i = 1; i < recentToolResults.size(); i++) {
				if (!firstResult.equals(recentToolResults.get(i))) {
					allSame = false;
					break;
				}
			}

			if (allSame) {
				log.warn(
						"🔁 Detected repeated tool result {} times for planId: {}. Forcing memory compression to break potential loop.",
						REPEATED_RESULT_THRESHOLD, getCurrentPlanId());

				// Force compress agent memory to break the loop
				if (conversationMemoryLimitService != null) {
					conversationMemoryLimitService.forceCompressAgentMemory(
							llmService.getAgentMemory(lynxeProperties.getMaxMemory()), getCurrentPlanId());
				}

				// Clear the recent results after compression
				recentToolResults.clear();
				log.info("✅ Forced memory compression completed for planId: {}", getCurrentPlanId());
			}
		}
	}

	private void processUserInputToMemory(UserMessage userMessage) {
		if (userMessage != null && userMessage.getText() != null) {
			// Process the user message to update memory
			String userInput = userMessage.getText();

			if (!StringUtils.isBlank(userInput)) {
				// Add user input to memory

				llmService.getAgentMemory(lynxeProperties.getMaxMemory()).add(getCurrentPlanId(), userMessage);

			}
		}
	}

	private void processMemory(ToolExecutionResult toolExecutionResult) {
		if (toolExecutionResult == null) {
			return;
		}
		// Process the conversation history to update memory
		List<Message> messages = toolExecutionResult.conversationHistory();
		if (messages.isEmpty()) {
			return;
		}
		// clear current plan memory
		llmService.getAgentMemory(lynxeProperties.getMaxMemory()).clear(getCurrentPlanId());
		for (Message message : messages) {
			// exclude all system message
			if (message instanceof SystemMessage) {
				continue;
			}
			// exclude env data message
			if (message instanceof UserMessage userMessage
					&& userMessage.getMetadata().containsKey(CURRENT_STEP_ENV_DATA_KEY)) {
				continue;
			}
			// only keep assistant message and tool_call message
			llmService.getAgentMemory(lynxeProperties.getMaxMemory()).add(getCurrentPlanId(), message);
		}
	}

	@Override
	public AgentExecResult run() {
		return super.run();
	}

	@Override
	protected void handleCompletedExecution(List<AgentExecResult> results) {
		super.handleCompletedExecution(results);
		// Note: Final result will be saved to conversation memory in
		// PlanFinalizer.handlePostExecution()
	}

	@Override
	public String getName() {
		return this.agentName;
	}

	@Override
	public String getDescription() {
		return this.agentDescription;
	}

	@Override
	protected Message getNextStepWithEnvMessage() {
		if (StringUtils.isBlank(this.nextStepPrompt)) {
			return new UserMessage("");
		}
		PromptTemplate promptTemplate = new SystemPromptTemplate(this.nextStepPrompt);
		Message userMessage = promptTemplate.createMessage(getMergedData());
		return userMessage;
	}

	private Map<String, Object> getMergedData() {
		Map<String, Object> data = new HashMap<>();
		data.putAll(getInitSettingData());
		data.put(AbstractPlanExecutor.EXECUTION_ENV_STRING_KEY, convertEnvDataToString());
		return data;
	}

	@Override
	protected Message getThinkMessage() {
		Message baseThinkPrompt = super.getThinkMessage();
		Message nextStepWithEnvMessage = getNextStepWithEnvMessage();
		SystemMessage thinkMessage = new SystemMessage("""
				<SystemInfo>
				%s
				</SystemInfo>

				<AgentInfo>
				%s
				</AgentInfo>
				""".formatted(baseThinkPrompt.getText(), nextStepWithEnvMessage.getText()));
		return thinkMessage;
	}

	/**
	 * Current step env data
	 * @return User message for current step environment data
	 */
	private Message currentStepEnvMessage() {
		String currentStepEnv = """
				- Current step environment information:
				{current_step_env_data}
				""";
		PromptTemplate template = new PromptTemplate(currentStepEnv);
		Message stepEnvMessage = template.createMessage(getMergedData());
		// mark as current step env data
		stepEnvMessage.getMetadata().put(CURRENT_STEP_ENV_DATA_KEY, Boolean.TRUE);
		return stepEnvMessage;
	}

	public ToolCallBackContext getToolCallBackContext(String toolKey) {
		Map<String, ToolCallBackContext> toolCallBackContext = toolCallbackProvider.getToolCallBackContext();
		if (toolCallBackContext.containsKey(toolKey)) {
			return toolCallBackContext.get(toolKey);
		}
		else {
			log.warn("Tool callback for {} not found in the map.", toolKey);
			return null;
		}
	}

	@Override
	public List<ToolCallback> getToolCallList() {
		List<ToolCallback> toolCallbacks = new ArrayList<>();
		Map<String, ToolCallBackContext> toolCallBackContext = toolCallbackProvider.getToolCallBackContext();
		for (String toolKey : availableToolKeys) {
			if (toolCallBackContext.containsKey(toolKey)) {
				ToolCallBackContext toolCallback = toolCallBackContext.get(toolKey);
				if (toolCallback != null) {
					toolCallbacks.add(toolCallback.getToolCallback());
				}
			}
			else {
				log.warn("Tool callback for {} not found in the map.", toolKey);
			}
		}
		return toolCallbacks;
	}

	public void addEnvData(String key, String value) {
		Map<String, Object> data = super.getInitSettingData();
		if (data == null) {
			throw new IllegalStateException("Data map is null. Cannot add environment data.");
		}
		data.put(key, value);
	}

	public void setToolCallbackProvider(ToolCallbackProvider toolCallbackProvider) {
		this.toolCallbackProvider = toolCallbackProvider;
	}

	protected String collectEnvData(String toolCallName) {
		log.info("🔍 collectEnvData called for tool: {}", toolCallName);
		Map<String, ToolCallBackContext> toolCallBackContext = toolCallbackProvider.getToolCallBackContext();

		// Convert serviceGroup.toolName format to toolName*index* format if needed
		String lookupKey = toolCallName;
		try {
			String convertedKey = serviceGroupIndexService.convertToolKeyToQualifiedKey(toolCallName);
			if (convertedKey != null && !convertedKey.equals(toolCallName)) {
				lookupKey = convertedKey;
				log.debug("Converted tool key from '{}' to '{}' for collectEnvData", toolCallName, lookupKey);
			}
		}
		catch (Exception e) {
			log.debug("Failed to convert tool key '{}' in collectEnvData: {}", toolCallName, e.getMessage());
		}

		ToolCallBackContext context = toolCallBackContext.get(lookupKey);
		if (context != null) {
			ToolCallBiFunctionDef<?> functionInstance = context.getFunctionInstance();
			// Use getCurrentToolStateStringWithErrorHandler which provides unified error
			// handling
			// This method is available as a default method in the interface
			String envData = functionInstance.getCurrentToolStateStringWithErrorHandler();
			return envData != null ? envData : "";
		}
		// If corresponding tool callback context is not found, return empty string
		log.warn("⚠️ No context found for tool: {} (lookup key: {})", toolCallName, lookupKey);
		return "";
	}

	public void collectAndSetEnvDataForTools() {

		Map<String, Object> toolEnvDataMap = new HashMap<>();

		Map<String, Object> oldMap = getEnvData();
		toolEnvDataMap.putAll(oldMap);

		// Overwrite old data with new data
		for (String toolKey : availableToolKeys) {
			String envData = collectEnvData(toolKey);
			toolEnvDataMap.put(toolKey, envData);
		}
		// log.debug("Collected tool environment data: {}", toolEnvDataMap);

		setEnvData(toolEnvDataMap);
	}

	public String convertEnvDataToString() {
		StringBuilder envDataStringBuilder = new StringBuilder();

		for (String toolKey : availableToolKeys) {
			Object value = getEnvData().get(toolKey);
			if (value == null || value.toString().isEmpty()) {
				continue; // Skip tools with no data
			}
			envDataStringBuilder.append(toolKey).append(" context information:\n");
			envDataStringBuilder.append("    ").append(value.toString()).append("\n");
		}

		return envDataStringBuilder.toString();
	}

	// Add a method to wait for user input or handle timeout.
	/**
	 * Save user request (stepText) to conversation memory
	 */
	private void saveUserRequestToConversationMemory() {
		// Skip if already saved (prevents duplicate saves during retries)
		if (userRequestSavedToConversationMemory) {
			log.debug("User request already saved to conversation memory, skipping");
			return;
		}

		// Skip if conversation memory is disabled
		if (!lynxeProperties.getEnableConversationMemory()) {
			log.debug("Conversation memory is disabled, skipping user request save");
			return;
		}

		if (getConversationId() == null || getConversationId().trim().isEmpty()) {
			log.debug("No conversationId available, skipping user request save");
			return;
		}

		// Get stepText from initSettingData
		Object stepTextObj = getInitSettingData().get(AbstractPlanExecutor.STEP_TEXT_KEY);
		if (stepTextObj == null) {
			log.debug("No stepText found in initSettingData, skipping user request save");
			return;
		}

		String stepText = stepTextObj.toString();
		if (stepText == null || stepText.trim().isEmpty()) {
			log.debug("stepText is empty, skipping user request save");
			return;
		}

		try {
			UserMessage userMessage = new UserMessage(stepText);
			llmService.addToConversationMemoryWithLimit(lynxeProperties.getMaxMemory(), getConversationId(),
					userMessage);
			userRequestSavedToConversationMemory = true; // Mark as saved
			log.info("Saved user request to conversation memory for conversationId: {}, request length: {}",
					getConversationId(), stepText.length());
		}
		catch (Exception e) {
			log.warn("Failed to save user request to conversation memory for conversationId: {}", getConversationId(),
					e);
		}
	}

	private void waitForUserInputOrTimeout(FormInputTool formInputTool) {
		log.info("Waiting for user input for planId: {}...", getCurrentPlanId());
		long startTime = System.currentTimeMillis();
		long lastInterruptionCheck = startTime;
		// Get timeout from LynxeProperties and convert to milliseconds
		long userInputTimeoutMs = getLynxeProperties().getUserInputTimeout() * 1000L;
		long interruptionCheckIntervalMs = 2000L; // Check for interruption every 2
													// seconds

		while (formInputTool.getInputState() == FormInputTool.InputState.AWAITING_USER_INPUT) {
			long currentTime = System.currentTimeMillis();

			// Check for interruption periodically
			if (currentTime - lastInterruptionCheck >= interruptionCheckIntervalMs) {
				if (agentInterruptionHelper != null
						&& !agentInterruptionHelper.checkInterruptionAndContinue(getRootPlanId())) {
					log.info("User input wait interrupted for rootPlanId: {}", getRootPlanId());
					formInputTool.handleInputTimeout(); // Treat interruption as timeout
					break;
				}
				lastInterruptionCheck = currentTime;
			}

			if (currentTime - startTime > userInputTimeoutMs) {
				log.warn("Timeout waiting for user input for planId: {}", getCurrentPlanId());
				formInputTool.handleInputTimeout(); // This will change its state to
				// INPUT_TIMEOUT
				break;
			}
			try {
				// Poll for input state change. In a real scenario, this might involve
				// a more sophisticated mechanism like a Future or a callback from the UI.
				TimeUnit.MILLISECONDS.sleep(500); // Check every 500ms
			}
			catch (InterruptedException e) {
				log.warn("Interrupted while waiting for user input for planId: {}", getCurrentPlanId());
				Thread.currentThread().interrupt();
				formInputTool.handleInputTimeout(); // Treat interruption as timeout for
				// simplicity
				break;
			}
		}
		if (formInputTool.getInputState() == FormInputTool.InputState.INPUT_RECEIVED) {
			log.info("User input received for planId: {}", getCurrentPlanId());
		}
		else if (formInputTool.getInputState() == FormInputTool.InputState.INPUT_TIMEOUT) {
			log.warn("User input timed out for planId: {}", getCurrentPlanId());
		}
	}

}
