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

import java.util.List;

import com.alibaba.cloud.ai.lynxe.workspace.conversation.entity.vo.Memory;

/**
 * @author dahua
 * @time 2025/8/5
 * @desc memory service interface
 */
public interface MemoryService {

	List<Memory> getMemories();

	void deleteMemory(String conversationId);

	Memory saveMemory(Memory memory);

	Memory updateMemory(Memory memory);

	Memory singleMemory(String conversationId);

	/**
	 * Generate a unique conversation ID
	 * @return unique conversation ID
	 */
	String generateConversationId();

	/**
	 * Add a root plan ID to a conversation's memory
	 * @param conversationId The conversation ID
	 * @param rootPlanId The root plan ID to add
	 */
	void addRootPlanIdToConversation(String conversationId, String rootPlanId);

	/**
	 * Add a chat ID to a conversation's memory with memory name This method is
	 * specifically for chat scenarios where we need to set a meaningful memory name based
	 * on the user's input, unlike plan execution which uses plan step requirements
	 * @param conversationId The conversation ID
	 * @param chatId The chat ID to add (similar to rootPlanId for plan execution)
	 * @param memoryName The memory name to set (typically derived from user input)
	 */
	void addChatToConversation(String conversationId, String chatId, String memoryName);

}
