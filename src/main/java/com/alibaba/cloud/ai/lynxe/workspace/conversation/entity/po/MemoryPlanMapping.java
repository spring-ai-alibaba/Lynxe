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

import java.util.Date;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Temporal;
import jakarta.persistence.TemporalType;

/**
 * Entity for storing plan/chat ID mappings with creation timestamps Tracks when each
 * rootPlanId was added to a conversation
 *
 * @author dahua
 * @time 2025/12/04
 */
@Entity
@Table(name = "memory_plan_mappings")
public class MemoryPlanMapping {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.EAGER)
	@JoinColumn(name = "memory_id", nullable = false)
	private MemoryEntity memory;

	@Column(name = "root_plan_id", nullable = false)
	private String rootPlanId;

	@Column(name = "create_time", nullable = true)
	@Temporal(TemporalType.TIMESTAMP)
	private Date createTime;

	/**
	 * Set createTime before persisting if not already set This ensures all records have a
	 * creation timestamp
	 */
	@PrePersist
	protected void onCreate() {
		if (this.createTime == null) {
			this.createTime = new Date();
		}
	}

	public MemoryPlanMapping() {
		this.createTime = new Date();
	}

	public MemoryPlanMapping(MemoryEntity memory, String rootPlanId) {
		this.memory = memory;
		this.rootPlanId = rootPlanId;
		this.createTime = new Date();
	}

	public Long getId() {
		return id;
	}

	public void setId(Long id) {
		this.id = id;
	}

	public MemoryEntity getMemory() {
		return memory;
	}

	public void setMemory(MemoryEntity memory) {
		this.memory = memory;
	}

	public String getRootPlanId() {
		return rootPlanId;
	}

	public void setRootPlanId(String rootPlanId) {
		this.rootPlanId = rootPlanId;
	}

	public Date getCreateTime() {
		return createTime;
	}

	public void setCreateTime(Date createTime) {
		this.createTime = createTime;
	}

}
