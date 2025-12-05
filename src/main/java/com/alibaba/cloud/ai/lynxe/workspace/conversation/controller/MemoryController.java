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
package com.alibaba.cloud.ai.lynxe.workspace.conversation.controller;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.alibaba.cloud.ai.lynxe.config.LynxeProperties;
import com.alibaba.cloud.ai.lynxe.llm.LlmService;
import com.alibaba.cloud.ai.lynxe.recorder.entity.vo.PlanExecutionRecord;
import com.alibaba.cloud.ai.lynxe.recorder.service.PlanHierarchyReaderService;
import com.alibaba.cloud.ai.lynxe.workspace.conversation.entity.po.MemoryEntity;
import com.alibaba.cloud.ai.lynxe.workspace.conversation.entity.po.MemoryPlanMapping;
import com.alibaba.cloud.ai.lynxe.workspace.conversation.entity.vo.Memory;
import com.alibaba.cloud.ai.lynxe.workspace.conversation.entity.vo.MemoryResponse;
import com.alibaba.cloud.ai.lynxe.workspace.conversation.repository.MemoryRepository;
import com.alibaba.cloud.ai.lynxe.workspace.conversation.service.MemoryService;

/**
 * @author dahua
 * @time 2025/8/5
 * @desc memory controller
 */
@RestController
@RequestMapping("/api/memories")
@CrossOrigin(origins = "*") // Add cross-origin support
public class MemoryController {

	private static final Logger logger = LoggerFactory.getLogger(MemoryController.class);

	@Autowired
	private MemoryService memoryService;

	@Autowired
	private MemoryRepository memoryRepository;

	@Autowired
	private PlanHierarchyReaderService planHierarchyReaderService;

	@Autowired(required = false)
	private LlmService llmService;

	@Autowired(required = false)
	private LynxeProperties lynxeProperties;

	@GetMapping
	public ResponseEntity<MemoryResponse> getAllMemories() {
		try {
			List<Memory> memories = memoryService.getMemories();
			return ResponseEntity.ok(MemoryResponse.success(memories));
		}
		catch (Exception e) {
			return ResponseEntity.ok(MemoryResponse.error("Failed to retrieve memories: " + e.getMessage()));
		}
	}

	@GetMapping("/single")
	public ResponseEntity<MemoryResponse> singleMemory(@RequestParam String conversationId) {
		try {
			Memory memory = memoryService.singleMemory(conversationId);
			return ResponseEntity.ok(MemoryResponse.success(memory));
		}
		catch (IllegalArgumentException e) {
			return ResponseEntity.ok(MemoryResponse.notFound());
		}
		catch (Exception e) {
			return ResponseEntity.ok(MemoryResponse.error("Failed to retrieve memory: " + e.getMessage()));
		}
	}

	@PostMapping
	public ResponseEntity<MemoryResponse> createMemory(@RequestBody Memory memory) {
		try {
			Memory createdMemory = memoryService.saveMemory(memory);
			return ResponseEntity.ok(MemoryResponse.created(createdMemory));
		}
		catch (Exception e) {
			return ResponseEntity.ok(MemoryResponse.error("Failed to create memory: " + e.getMessage()));
		}
	}

	@PutMapping
	public ResponseEntity<MemoryResponse> updateMemory(@RequestBody Memory memory) {
		try {
			Memory updatedMemory = memoryService.updateMemory(memory);
			return ResponseEntity.ok(MemoryResponse.updated(updatedMemory));
		}
		catch (IllegalArgumentException e) {
			return ResponseEntity.ok(MemoryResponse.notFound());
		}
		catch (Exception e) {
			return ResponseEntity.ok(MemoryResponse.error("Failed to update memory: " + e.getMessage()));
		}
	}

	@DeleteMapping("/{conversationId}")
	public ResponseEntity<MemoryResponse> deleteMemory(@PathVariable String conversationId) {
		try {
			memoryService.deleteMemory(conversationId);
			return ResponseEntity.ok(MemoryResponse.deleted());
		}
		catch (Exception e) {
			return ResponseEntity.ok(MemoryResponse.error("Failed to delete memory: " + e.getMessage()));
		}
	}

	/**
	 * @deprecated This endpoint is deprecated. Conversation IDs are now automatically
	 * generated by the backend when not provided in request. Use the main execution
	 * endpoints which accept conversationId as an optional parameter.
	 */
	@Deprecated
	@GetMapping("/generate-id")
	public ResponseEntity<MemoryResponse> generateConversationId() {
		try {
			String conversationId = memoryService.generateConversationId();
			// Create a simple response with the generated ID
			MemoryResponse response = new MemoryResponse(true, "Conversation ID generated successfully");
			response.setData(new Memory(conversationId, "Generated Conversation"));
			return ResponseEntity.ok(response);
		}
		catch (Exception e) {
			return ResponseEntity.ok(MemoryResponse.error("Failed to generate conversation ID: " + e.getMessage()));
		}
	}

	/**
	 * Get conversation history (plan execution records and chat messages) for a specific
	 * conversation ID
	 * @param conversationId The conversation ID
	 * @return List of plan execution records for this conversation, including chat
	 * messages
	 */
	@GetMapping("/{conversationId}/history")
	public ResponseEntity<?> getConversationHistory(@PathVariable String conversationId) {
		try {
			logger.info("Retrieving conversation history for conversationId: {}", conversationId);

			List<PlanExecutionRecord> allRecords = new ArrayList<>();
			// Map to store createTime for each rootPlanId (used for sorting fallback)
			Map<String, LocalDateTime> planIdCreateTimeMap = new HashMap<>();

			// Get plan execution records and chat records
			try {
				Memory memory = memoryService.singleMemory(conversationId);
				if (memory != null) {
					List<String> rootPlanIds = memory.getRootPlanIds();
					if (rootPlanIds != null && !rootPlanIds.isEmpty()) {
						logger.info("Found {} rootPlanIds for conversationId: {}", rootPlanIds.size(), conversationId);

						// Get createTime mapping from MemoryEntity for sorting
						MemoryEntity memoryEntity = memoryRepository.findByConversationId(conversationId);
						if (memoryEntity != null && memoryEntity.getPlanMappings() != null) {
							for (MemoryPlanMapping mapping : memoryEntity.getPlanMappings()) {
								if (mapping.getCreateTime() != null) {
									LocalDateTime createTime = mapping.getCreateTime()
										.toInstant()
										.atZone(ZoneId.systemDefault())
										.toLocalDateTime();
									planIdCreateTimeMap.put(mapping.getRootPlanId(), createTime);
								}
								else {
									// For existing records without createTime, use
									// current time as fallback
									// This handles migration from old schema
									logger.debug(
											"Mapping for rootPlanId {} has null createTime, using current time as fallback",
											mapping.getRootPlanId());
									planIdCreateTimeMap.put(mapping.getRootPlanId(), LocalDateTime.now());
								}
							}
							logger.debug("Retrieved {} plan mappings with createTime for conversationId: {}",
									planIdCreateTimeMap.size(), conversationId);
						}

						// Get chat messages if available
						List<Message> chatMessages = null;
						if (llmService != null && lynxeProperties != null
								&& lynxeProperties.getEnableConversationMemory()) {
							try {
								org.springframework.ai.chat.memory.ChatMemory conversationMemory = llmService
									.getConversationMemoryWithLimit(lynxeProperties.getMaxMemory(), conversationId);
								chatMessages = conversationMemory.get(conversationId);
								if (chatMessages != null) {
									logger.debug("Retrieved {} chat messages for conversationId: {}",
											chatMessages.size(), conversationId);
								}
							}
							catch (Exception e) {
								logger.warn("Failed to retrieve chat messages for conversationId: {}", conversationId,
										e);
							}
						}

						// Process each rootPlanId
						// Use an index to track which chat messages have been used
						int chatMessageIndex = 0;
						for (String rootPlanId : rootPlanIds) {
							if (rootPlanId == null || rootPlanId.trim().isEmpty()) {
								continue;
							}

							// Check if it's a chat ID (starts with "chat-")
							if (rootPlanId.startsWith("chat-")) {
								// Create PlanExecutionRecord for chat
								PlanExecutionRecord chatRecord = createChatRecord(rootPlanId, chatMessages,
										chatMessageIndex);
								if (chatRecord != null) {
									allRecords.add(chatRecord);
									// Increment index to skip the messages used for this
									// chat
									chatMessageIndex += 2; // User message + Assistant
															// message
									logger.debug("Created chat record for chatId: {}", rootPlanId);
								}
								else {
									logger.warn("Failed to create chat record for chatId: {}", rootPlanId);
								}
							}
							else {
								// It's a plan ID, retrieve plan execution record
								try {
									PlanExecutionRecord record = planHierarchyReaderService
										.readPlanTreeByRootId(rootPlanId);
									if (record != null) {
										allRecords.add(record);
									}
									else {
										logger.warn("No plan execution record found for rootPlanId: {}", rootPlanId);
									}
								}
								catch (Exception e) {
									logger.error("Error retrieving plan record for rootPlanId: {}", rootPlanId, e);
								}
							}
						}

						logger.info("Retrieved {} total records (plans + chats) for conversationId: {}",
								allRecords.size(), conversationId);
					}
				}
			}
			catch (IllegalArgumentException e) {
				logger.debug("No memory found for conversationId: {}, will check for chat messages only",
						conversationId);
			}

			// Sort all records by startTime (or createTime as fallback) to maintain
			// chronological order
			// Use createTime from memory_plan_mappings if startTime is not available
			allRecords.sort(Comparator.comparing((PlanExecutionRecord record) -> {
				// First try to use startTime from plan execution record
				if (record.getStartTime() != null) {
					return record.getStartTime();
				}
				// Fallback to createTime from memory_plan_mappings
				String rootPlanId = record.getRootPlanId() != null ? record.getRootPlanId() : record.getCurrentPlanId();
				if (rootPlanId != null && planIdCreateTimeMap.containsKey(rootPlanId)) {
					LocalDateTime createTime = planIdCreateTimeMap.get(rootPlanId);
					logger.debug("Using createTime {} for rootPlanId {} (startTime not available)", createTime,
							rootPlanId);
					return createTime;
				}
				// Last resort: use minimum time
				return LocalDateTime.MIN;
			}));

			logger.info("Successfully retrieved {} plan execution records for conversationId: {}", allRecords.size(),
					conversationId);

			return ResponseEntity.ok(allRecords);
		}
		catch (Exception e) {
			logger.error("Error retrieving conversation history for conversationId: {}", conversationId, e);
			return ResponseEntity.status(500).body("Failed to retrieve conversation history: " + e.getMessage());
		}
	}

	/**
	 * Create a PlanExecutionRecord for a chat conversation
	 * @param chatId The chat ID (format: "chat-{timestamp}_{random}_{threadId}")
	 * @param chatMessages All chat messages from ChatMemory (can be null)
	 * @param startIndex Starting index in chatMessages to look for the message pair
	 * @return PlanExecutionRecord representing the chat, or null if creation fails
	 */
	private PlanExecutionRecord createChatRecord(String chatId, List<Message> chatMessages, int startIndex) {
		try {
			// Extract timestamp from chat ID (format:
			// "chat-{timestamp}_{random}_{threadId}")
			long chatTimestamp = extractTimestampFromChatId(chatId);

			PlanExecutionRecord record = new PlanExecutionRecord();
			record.setCurrentPlanId(chatId);
			record.setRootPlanId(chatId);
			record.setCompleted(true);
			record.setTitle("Chat Conversation");

			// Set start time from chat ID timestamp
			if (chatTimestamp > 0) {
				LocalDateTime startTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(chatTimestamp),
						ZoneId.systemDefault());
				record.setStartTime(startTime);
				// Assume chat completed shortly after start (e.g., 5 seconds later)
				record.setEndTime(startTime.plusSeconds(5));
			}
			else {
				// Fallback to current time if timestamp extraction fails
				record.setStartTime(LocalDateTime.now());
				record.setEndTime(LocalDateTime.now());
			}

			// Try to find user and assistant messages for this chat
			String userRequest = null;
			String summary = null;

			if (chatMessages != null && !chatMessages.isEmpty() && startIndex < chatMessages.size()) {
				// Look for a user-assistant message pair starting from startIndex
				for (int i = startIndex; i < chatMessages.size(); i++) {
					Message message = chatMessages.get(i);
					if (message instanceof UserMessage) {
						// Check if this user message is followed by an assistant message
						if (i + 1 < chatMessages.size() && chatMessages.get(i + 1) instanceof AssistantMessage) {
							UserMessage userMsg = (UserMessage) message;
							AssistantMessage assistantMsg = (AssistantMessage) chatMessages.get(i + 1);

							// Use this pair
							userRequest = userMsg.getText();
							summary = assistantMsg.getText();
							break; // Found the pair, stop searching
						}
					}
				}
			}

			// Set user request and summary
			if (userRequest != null && !userRequest.trim().isEmpty()) {
				record.setUserRequest(userRequest);
			}
			else {
				record.setUserRequest("Chat message");
			}

			if (summary != null && !summary.trim().isEmpty()) {
				record.setSummary(summary);
			}
			else {
				record.setSummary("Chat response");
			}

			return record;
		}
		catch (Exception e) {
			logger.error("Error creating chat record for chatId: {}", chatId, e);
			return null;
		}
	}

	/**
	 * Extract timestamp from chat ID Format: "chat-{timestamp}_{random}_{threadId}"
	 * @param chatId The chat ID
	 * @return Timestamp in milliseconds, or 0 if extraction fails
	 */
	private long extractTimestampFromChatId(String chatId) {
		try {
			if (chatId == null || !chatId.startsWith("chat-")) {
				return 0;
			}

			// Remove "chat-" prefix
			String withoutPrefix = chatId.substring(5);
			// Extract the first number (timestamp) before the first underscore
			int underscoreIndex = withoutPrefix.indexOf('_');
			if (underscoreIndex > 0) {
				String timestampStr = withoutPrefix.substring(0, underscoreIndex);
				return Long.parseLong(timestampStr);
			}
			else {
				// If no underscore, try to parse the whole string as timestamp
				return Long.parseLong(withoutPrefix);
			}
		}
		catch (Exception e) {
			logger.warn("Failed to extract timestamp from chatId: {}", chatId, e);
			return 0;
		}
	}

}
