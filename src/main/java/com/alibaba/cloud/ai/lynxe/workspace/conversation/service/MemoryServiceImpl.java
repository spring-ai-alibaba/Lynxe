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
package com.alibaba.cloud.ai.lynxe.workspace.conversation.service;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.alibaba.cloud.ai.lynxe.workspace.conversation.entity.po.MemoryEntity;
import com.alibaba.cloud.ai.lynxe.workspace.conversation.entity.vo.Memory;
import com.alibaba.cloud.ai.lynxe.workspace.conversation.repository.MemoryRepository;

/**
 * @author dahua
 * @time 2025/8/5
 * @desc memory service impl
 */
@Service
@Transactional
public class MemoryServiceImpl implements MemoryService {

	private static final Logger logger = LoggerFactory.getLogger(MemoryServiceImpl.class);

	@Autowired
	private MemoryRepository memoryRepository;

	@Autowired
	private ChatMemory chatMemory;

	/**
	 * Convert MemoryEntity to Memory VO
	 */
	private Memory convertToMemory(MemoryEntity entity) {
		if (entity == null) {
			return null;
		}
		Memory memory = new Memory();
		memory.setId(entity.getId());
		memory.setConversationId(entity.getConversationId());
		memory.setMemoryName(entity.getMemoryName());

		// Convert Date to LocalDateTime
		if (entity.getCreateTime() != null) {
			memory.setCreateTime(entity.getCreateTime().toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime());
		}

		// Copy root plan IDs
		if (entity.getRootPlanIds() != null) {
			memory.setRootPlanIds(new ArrayList<>(entity.getRootPlanIds()));
		}

		return memory;
	}

	/**
	 * Convert Memory VO to MemoryEntity
	 */
	private MemoryEntity convertToEntity(Memory memory) {
		if (memory == null) {
			return null;
		}
		MemoryEntity entity = new MemoryEntity();
		entity.setId(memory.getId());
		entity.setConversationId(memory.getConversationId());
		entity.setMemoryName(memory.getMemoryName());

		// Convert LocalDateTime to Date
		if (memory.getCreateTime() != null) {
			entity.setCreateTime(java.sql.Timestamp.valueOf(memory.getCreateTime()));
		}

		// Copy root plan IDs
		if (memory.getRootPlanIds() != null) {
			entity.setRootPlanIds(new ArrayList<>(memory.getRootPlanIds()));
		}

		return entity;
	}

	@Override
	public List<Memory> getMemories() {
		// Query top 15 memories directly from database (sorted by createTime DESC,
		// filtered for non-null)
		List<MemoryEntity> memoryEntities = memoryRepository.findTop15Memories();

		// Convert to Memory VO
		return memoryEntities.stream().map(this::convertToMemory).collect(Collectors.toList());
	}

	@Override
	public void deleteMemory(String conversationId) {
		chatMemory.clear(conversationId);
		memoryRepository.deleteByConversationId(conversationId);
	}

	@Override
	public Memory saveMemory(Memory memory) {
		MemoryEntity findEntity = memoryRepository.findByConversationId(memory.getConversationId());
		if (findEntity != null) {
			// Update existing entity
			findEntity.setMemoryName(memory.getMemoryName());
		}
		else {
			findEntity = convertToEntity(memory);
		}
		MemoryEntity saveEntity = memoryRepository.save(findEntity);
		return convertToMemory(saveEntity);
	}

	@Override
	public Memory updateMemory(Memory memory) {
		MemoryEntity findEntity = memoryRepository.findByConversationId(memory.getConversationId());
		if (findEntity == null) {
			throw new IllegalArgumentException("Memory not found with ID: " + memory.getConversationId());
		}
		findEntity.setMemoryName(memory.getMemoryName());
		MemoryEntity saveEntity = memoryRepository.save(findEntity);
		return convertToMemory(saveEntity);
	}

	@Override
	public Memory singleMemory(String conversationId) {
		MemoryEntity findEntity = memoryRepository.findByConversationId(conversationId);
		if (findEntity == null) {
			throw new IllegalArgumentException("Memory not found with ID: " + conversationId);
		}
		return convertToMemory(findEntity);
	}

	@Override
	public String generateConversationId() {
		// Use a specific prefix for conversation IDs
		String conversationPrefix = "conversation-";

		// Generate unique conversation ID with multiple uniqueness factors:
		// 1. Specific prefix for conversations
		// 2. Current timestamp in nanoseconds for high precision
		// 3. Random component for additional uniqueness
		// 4. Thread ID to handle concurrent conversation creation
		long timestamp = System.nanoTime();
		int randomComponent = (int) (Math.random() * 10000);
		long threadId = Thread.currentThread().getId();

		String conversationId = String.format("%s%d_%d_%d", conversationPrefix, timestamp, randomComponent, threadId);

		logger.info("Generated unique conversation ID: {}", conversationId);

		return conversationId;
	}

	@Override
	public void addRootPlanIdToConversation(String conversationId, String rootPlanId) {
		if (conversationId == null || conversationId.trim().isEmpty()) {
			logger.warn("Cannot add rootPlanId to null or empty conversationId");
			return;
		}

		if (rootPlanId == null || rootPlanId.trim().isEmpty()) {
			logger.warn("Cannot add null or empty rootPlanId to conversation {}", conversationId);
			return;
		}

		try {
			MemoryEntity memoryEntity = memoryRepository.findByConversationId(conversationId);
			if (memoryEntity == null) {
				// Create new memory if it doesn't exist
				logger.info("Creating new memory for conversationId: {} with rootPlanId: {}", conversationId,
						rootPlanId);
				memoryEntity = new MemoryEntity(conversationId, "Conversation " + conversationId);
			}

			// Add the root plan ID
			memoryEntity.addRootPlanId(rootPlanId);
			memoryRepository.save(memoryEntity);

			logger.info("Added rootPlanId {} to conversation {}", rootPlanId, conversationId);
		}
		catch (Exception e) {
			logger.error("Failed to add rootPlanId {} to conversation {}", rootPlanId, conversationId, e);
		}
	}

	@Override
	public void addChatToConversation(String conversationId, String chatId, String memoryName) {
		if (conversationId == null || conversationId.trim().isEmpty()) {
			logger.warn("Cannot add chat to null or empty conversationId");
			return;
		}

		if (chatId == null || chatId.trim().isEmpty()) {
			logger.warn("Cannot add null or empty chatId to conversation {}", conversationId);
			return;
		}

		try {
			MemoryEntity memoryEntity = memoryRepository.findByConversationId(conversationId);
			if (memoryEntity == null) {
				// Create new memory with provided memory name
				String finalMemoryName = (memoryName != null && !memoryName.trim().isEmpty())
						? sanitizeMemoryName(memoryName) : "Chat Conversation";
				logger.info("Creating new memory for conversationId: {} with chatId: {} and memoryName: {}",
						conversationId, chatId, finalMemoryName);
				memoryEntity = new MemoryEntity(conversationId, finalMemoryName);
			}
			else {
				// Update memory name if provided and different from current
				if (memoryName != null && !memoryName.trim().isEmpty()) {
					String sanitizedMemoryName = sanitizeMemoryName(memoryName);
					if (!sanitizedMemoryName.equals(memoryEntity.getMemoryName())) {
						logger.debug("Updating memory name from '{}' to '{}' for conversationId: {}",
								memoryEntity.getMemoryName(), sanitizedMemoryName, conversationId);
						memoryEntity.setMemoryName(sanitizedMemoryName);
					}
				}
			}

			// Add the chat ID (similar to rootPlanId for plan execution)
			memoryEntity.addRootPlanId(chatId);
			memoryRepository.save(memoryEntity);

			logger.info("Added chatId {} to conversation {} with memoryName: {}", chatId, conversationId,
					memoryEntity.getMemoryName());
		}
		catch (Exception e) {
			logger.error("Failed to add chatId {} to conversation {}", chatId, conversationId, e);
		}
	}

	/**
	 * Sanitize memory name to ensure it's suitable for display Limits length and removes
	 * problematic characters
	 * @param memoryName The original memory name
	 * @return Sanitized memory name
	 */
	private String sanitizeMemoryName(String memoryName) {
		if (memoryName == null || memoryName.trim().isEmpty()) {
			return "Chat Conversation";
		}

		// Remove leading/trailing whitespace
		String sanitized = memoryName.trim();

		// Limit length to avoid UI issues
		if (sanitized.length() > 100) {
			sanitized = sanitized.substring(0, 100) + "...";
		}

		// Remove newlines and excessive whitespace
		sanitized = sanitized.replaceAll("\\s+", " ").trim();

		// Ensure it's not empty after sanitization
		if (sanitized.isEmpty()) {
			return "Chat Conversation";
		}

		return sanitized;
	}

}
