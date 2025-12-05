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
package com.alibaba.cloud.ai.lynxe.workspace.conversation.entity.po;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

/**
 * @author dahua
 * @time 2025/8/5
 * @desc memory entity - Stores conversation metadata and references to plan executions
 */
@Entity
@Table(name = "dynamic_memories", indexes = { @Index(name = "idx_create_time", columnList = "createTime") })
public class MemoryEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private String conversationId;

	@Column(nullable = false)
	private String memoryName;

	@Column(nullable = false)
	private Date createTime;

	/**
	 * List of plan mappings associated with this conversation Each mapping contains a
	 * rootPlanId and createTime timestamp The rootPlanId corresponds to a complete dialog
	 * round (user query + assistant response) The plan execution records contain all the
	 * actual message content and execution details
	 */
	@OneToMany(mappedBy = "memory", cascade = CascadeType.ALL, fetch = FetchType.EAGER, orphanRemoval = true)
	@OrderBy("createTime ASC")
	private List<MemoryPlanMapping> planMappings = new ArrayList<>();

	/**
	 * Note: The @OneToMany relationship to ConversationMessage has been removed. This
	 * entity now only maintains references to root plan IDs. The actual conversation
	 * content is retrieved through PlanExecutionRecords using the rootPlanIds list. This
	 * design is more maintainable and avoids data duplication.
	 */

	public MemoryEntity() {
		this.createTime = new Date();
	}

	public MemoryEntity(String conversationId, String memoryName) {
		this.conversationId = conversationId;
		this.memoryName = memoryName;
		this.createTime = new Date();
	}

	public Long getId() {
		return id;
	}

	public void setId(Long id) {
		this.id = id;
	}

	public String getConversationId() {
		return conversationId;
	}

	public void setConversationId(String conversationId) {
		this.conversationId = conversationId;
	}

	public String getMemoryName() {
		return memoryName;
	}

	public void setMemoryName(String memoryName) {
		this.memoryName = memoryName;
	}

	public Date getCreateTime() {
		return createTime;
	}

	public void setCreateTime(Date createTime) {
		this.createTime = createTime;
	}

	/**
	 * Get list of plan mappings (includes rootPlanId and createTime)
	 * @return List of MemoryPlanMapping
	 */
	public List<MemoryPlanMapping> getPlanMappings() {
		return planMappings;
	}

	public void setPlanMappings(List<MemoryPlanMapping> planMappings) {
		this.planMappings = planMappings;
	}

	/**
	 * Get list of root plan IDs (for backward compatibility)
	 * @return List of root plan ID strings
	 */
	public List<String> getRootPlanIds() {
		if (planMappings == null) {
			return new ArrayList<>();
		}
		return planMappings.stream().map(MemoryPlanMapping::getRootPlanId).collect(Collectors.toList());
	}

	/**
	 * Set root plan IDs (for backward compatibility) Creates new mappings with current
	 * timestamp for each ID
	 * @param rootPlanIds List of root plan IDs
	 */
	public void setRootPlanIds(List<String> rootPlanIds) {
		if (rootPlanIds == null) {
			this.planMappings = new ArrayList<>();
			return;
		}
		// Clear existing mappings
		this.planMappings.clear();
		// Create new mappings for each rootPlanId
		for (String rootPlanId : rootPlanIds) {
			if (rootPlanId != null && !rootPlanId.trim().isEmpty()) {
				MemoryPlanMapping mapping = new MemoryPlanMapping(this, rootPlanId);
				this.planMappings.add(mapping);
			}
		}
	}

	/**
	 * Add a root plan ID to this conversation with current timestamp
	 * @param rootPlanId The root plan ID to add
	 */
	public void addRootPlanId(String rootPlanId) {
		if (rootPlanId != null && !rootPlanId.trim().isEmpty()) {
			// Check if mapping already exists
			boolean exists = planMappings.stream().anyMatch(mapping -> rootPlanId.equals(mapping.getRootPlanId()));
			if (!exists) {
				MemoryPlanMapping mapping = new MemoryPlanMapping(this, rootPlanId);
				planMappings.add(mapping);
			}
		}
	}

	/**
	 * Remove a root plan ID from this conversation
	 * @param rootPlanId The root plan ID to remove
	 */
	public void removeRootPlanId(String rootPlanId) {
		if (rootPlanId != null && planMappings != null) {
			planMappings.removeIf(mapping -> rootPlanId.equals(mapping.getRootPlanId()));
		}
	}

}
