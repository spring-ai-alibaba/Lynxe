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
package com.alibaba.cloud.ai.lynxe.planning.model.po;

import java.time.LocalDateTime;

import com.alibaba.cloud.ai.lynxe.planning.model.enums.PlanTemplateAccessLevel;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * Coordinator Tool Entity Class (merged with PlanTemplate) This entity now contains both
 * coordinator tool and plan template data
 */
@Entity
@Table(name = "coordinator_tools",
		uniqueConstraints = { @UniqueConstraint(columnNames = { "service_group", "tool_name" }),
				@UniqueConstraint(columnNames = { "plan_template_id" }) })
public class FuncAgentToolEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "plan_template_id", nullable = false, length = 50, unique = true)
	private String planTemplateId;

	@Column(name = "tool_name", nullable = false, length = 3000)
	private String toolName;

	@Column(name = "tool_description", nullable = false, length = 200)
	private String toolDescription;

	@Column(name = "input_schema", columnDefinition = "VARCHAR(2048)")
	private String inputSchema;

	@Column(name = "enable_internal_toolcall", nullable = false)
	private Boolean enableInternalToolcall = false;

	@Column(name = "enable_http_service", nullable = false)
	private Boolean enableHttpService = false;

	@Column(name = "enable_in_conversation", nullable = false, columnDefinition = "BOOLEAN DEFAULT FALSE")
	private Boolean enableInConversation = false;

	@Column(name = "enable_mcp_service", nullable = false)
	private Boolean enableMcpService = false;

	@Column(name = "service_group", length = 100)
	private String serviceGroup;

	@Convert(converter = com.alibaba.cloud.ai.lynxe.planning.model.converter.PlanTemplateAccessLevelConverter.class)
	@Column(name = "access_level", length = 50)
	private PlanTemplateAccessLevel accessLevel;

	@Column(name = "create_time", nullable = false)
	private LocalDateTime createTime;

	@Column(name = "update_time", nullable = false)
	private LocalDateTime updateTime;

	@Column(name = "version", length = 50)
	private String version;

	public FuncAgentToolEntity() {
		this.createTime = LocalDateTime.now();
		this.updateTime = LocalDateTime.now();
		this.enableInternalToolcall = false;
		this.enableHttpService = false;
		this.enableInConversation = false;
		this.enableMcpService = false;
		this.accessLevel = PlanTemplateAccessLevel.EDITABLE;
	}

	// Getters and Setters
	public Long getId() {
		return id;
	}

	public void setId(Long id) {
		this.id = id;
	}

	public String getToolName() {
		return toolName;
	}

	public void setToolName(String toolName) {
		this.toolName = toolName;
	}

	public String getToolDescription() {
		return toolDescription;
	}

	public void setToolDescription(String toolDescription) {
		this.toolDescription = toolDescription;
	}

	public String getInputSchema() {
		return inputSchema;
	}

	public void setInputSchema(String inputSchema) {
		this.inputSchema = inputSchema;
	}

	public String getPlanTemplateId() {
		return planTemplateId;
	}

	public void setPlanTemplateId(String planTemplateId) {
		this.planTemplateId = planTemplateId;
	}

	public Boolean getEnableInternalToolcall() {
		return enableInternalToolcall;
	}

	public void setEnableInternalToolcall(Boolean enableInternalToolcall) {
		this.enableInternalToolcall = enableInternalToolcall;
	}

	public Boolean getEnableHttpService() {
		return enableHttpService;
	}

	public void setEnableHttpService(Boolean enableHttpService) {
		this.enableHttpService = enableHttpService;
	}

	public Boolean getEnableInConversation() {
		return enableInConversation;
	}

	public void setEnableInConversation(Boolean enableInConversation) {
		this.enableInConversation = enableInConversation;
	}

	public Boolean getEnableMcpService() {
		return enableMcpService;
	}

	public void setEnableMcpService(Boolean enableMcpService) {
		this.enableMcpService = enableMcpService;
	}

	public LocalDateTime getCreateTime() {
		return createTime;
	}

	public void setCreateTime(LocalDateTime createTime) {
		this.createTime = createTime;
	}

	public LocalDateTime getUpdateTime() {
		return updateTime;
	}

	public void setUpdateTime(LocalDateTime updateTime) {
		this.updateTime = updateTime;
	}

	public String getServiceGroup() {
		return serviceGroup;
	}

	public void setServiceGroup(String serviceGroup) {
		this.serviceGroup = serviceGroup;
	}

	public PlanTemplateAccessLevel getAccessLevel() {
		return accessLevel != null ? accessLevel : PlanTemplateAccessLevel.EDITABLE;
	}

	public void setAccessLevel(PlanTemplateAccessLevel accessLevel) {
		this.accessLevel = accessLevel != null ? accessLevel : PlanTemplateAccessLevel.EDITABLE;
	}

	/**
	 * Convenience method to set access level from string value
	 * @param accessLevelString String value of access level
	 */
	public void setAccessLevel(String accessLevelString) {
		this.accessLevel = PlanTemplateAccessLevel.fromValue(accessLevelString);
	}

	public String getVersion() {
		return version;
	}

	public void setVersion(String version) {
		this.version = version;
	}

	/**
	 * Automatically set update time when updating
	 */
	@PreUpdate
	public void preUpdate() {
		this.updateTime = LocalDateTime.now();
	}

	@Override
	public String toString() {
		return "FuncAgentToolEntity{" + "id=" + id + ", planTemplateId='" + planTemplateId + '\'' + ", toolName='"
				+ toolName + '\'' + ", toolDescription='" + toolDescription + '\'' + ", enableInternalToolcall="
				+ enableInternalToolcall + ", enableHttpService=" + enableHttpService + ", enableInConversation="
				+ enableInConversation + ", enableMcpService=" + enableMcpService + ", serviceGroup='" + serviceGroup
				+ '\'' + ", accessLevel=" + accessLevel + ", version='" + version + '\'' + ", createTime=" + createTime
				+ ", updateTime=" + updateTime + '}';
	}

}
