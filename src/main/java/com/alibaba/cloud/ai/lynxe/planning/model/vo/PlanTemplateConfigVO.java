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
package com.alibaba.cloud.ai.lynxe.planning.model.vo;

import java.util.ArrayList;
import java.util.List;

import com.alibaba.cloud.ai.lynxe.planning.model.enums.PlanTemplateAccessLevel;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Plan Template Configuration VO Class Represents the structure of plan template JSON
 * configuration files (e.g., default_user_input.json)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class PlanTemplateConfigVO {

	private String title;

	private List<StepConfig> steps;

	private String planType;

	@JsonProperty("planTemplateId")
	private String planTemplateId;

	@JsonProperty("accessLevel")
	private PlanTemplateAccessLevel accessLevel;

	@JsonProperty("readOnly")
	private Boolean readOnly; // Deprecated: kept for backward compatibility, use
								// accessLevel instead

	private String serviceGroup;

	private ToolConfigVO toolConfig;

	private String createTime;

	private String updateTime;

	/**
	 * Default constructor
	 */
	public PlanTemplateConfigVO() {
		this.steps = new ArrayList<>();
		this.planType = "dynamic_agent";
		this.accessLevel = PlanTemplateAccessLevel.EDITABLE;
		this.readOnly = false;
	}

	// Getters and Setters

	public String getTitle() {
		return title;
	}

	public void setTitle(String title) {
		this.title = title;
	}

	public List<StepConfig> getSteps() {
		return steps;
	}

	public void setSteps(List<StepConfig> steps) {
		this.steps = steps != null ? steps : new ArrayList<>();
	}

	public String getPlanType() {
		return planType;
	}

	public void setPlanType(String planType) {
		this.planType = planType;
	}

	public String getPlanTemplateId() {
		return planTemplateId;
	}

	public void setPlanTemplateId(String planTemplateId) {
		this.planTemplateId = planTemplateId;
	}

	public PlanTemplateAccessLevel getAccessLevel() {
		// If accessLevel is not set but readOnly is set, convert it
		if (accessLevel == null && readOnly != null) {
			return readOnly ? PlanTemplateAccessLevel.READ_ONLY : PlanTemplateAccessLevel.EDITABLE;
		}
		return accessLevel != null ? accessLevel : PlanTemplateAccessLevel.EDITABLE;
	}

	public void setAccessLevel(PlanTemplateAccessLevel accessLevel) {
		this.accessLevel = accessLevel != null ? accessLevel : PlanTemplateAccessLevel.EDITABLE;
		// Also update readOnly for backward compatibility
		this.readOnly = accessLevel == PlanTemplateAccessLevel.READ_ONLY;
	}

	/**
	 * Convenience method to set access level from string value
	 * @param accessLevelString String value of access level
	 */
	public void setAccessLevel(String accessLevelString) {
		this.accessLevel = PlanTemplateAccessLevel.fromValue(accessLevelString);
		// Also update readOnly for backward compatibility
		this.readOnly = this.accessLevel == PlanTemplateAccessLevel.READ_ONLY;
	}

	/**
	 * @deprecated Use getAccessLevel() instead. This method is kept for backward
	 * compatibility.
	 */
	@Deprecated
	public Boolean getReadOnly() {
		// If readOnly is not set but accessLevel is set, convert it
		if (readOnly == null && accessLevel != null) {
			return accessLevel == PlanTemplateAccessLevel.READ_ONLY;
		}
		return readOnly != null ? readOnly : false;
	}

	/**
	 * @deprecated Use setAccessLevel() instead. This method is kept for backward
	 * compatibility.
	 */
	@Deprecated
	public void setReadOnly(Boolean readOnly) {
		this.readOnly = readOnly != null ? readOnly : false;
		// Also update accessLevel
		this.accessLevel = readOnly ? PlanTemplateAccessLevel.READ_ONLY : PlanTemplateAccessLevel.EDITABLE;
	}

	public ToolConfigVO getToolConfig() {
		return toolConfig;
	}

	public void setToolConfig(ToolConfigVO toolConfig) {
		this.toolConfig = toolConfig;
	}

	public String getServiceGroup() {
		return serviceGroup;
	}

	public void setServiceGroup(String serviceGroup) {
		this.serviceGroup = serviceGroup;
	}

	public String getCreateTime() {
		return createTime;
	}

	public void setCreateTime(String createTime) {
		this.createTime = createTime;
	}

	public String getUpdateTime() {
		return updateTime;
	}

	public void setUpdateTime(String updateTime) {
		this.updateTime = updateTime;
	}

	/**
	 * Step Configuration Class Represents a single step in the plan template
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class StepConfig {

		private String stepRequirement;

		private String agentName;

		private String modelName;

		private String terminateColumns;

		private List<String> selectedToolKeys;

		/**
		 * Default constructor
		 */
		public StepConfig() {
		}

		// Getters and Setters

		public String getStepRequirement() {
			return stepRequirement;
		}

		public void setStepRequirement(String stepRequirement) {
			this.stepRequirement = stepRequirement;
		}

		public String getAgentName() {
			return agentName;
		}

		public void setAgentName(String agentName) {
			this.agentName = agentName;
		}

		public String getModelName() {
			return modelName;
		}

		public void setModelName(String modelName) {
			this.modelName = modelName;
		}

		public String getTerminateColumns() {
			return terminateColumns;
		}

		public void setTerminateColumns(String terminateColumns) {
			this.terminateColumns = terminateColumns;
		}

		public List<String> getSelectedToolKeys() {
			return selectedToolKeys;
		}

		public void setSelectedToolKeys(List<String> selectedToolKeys) {
			this.selectedToolKeys = selectedToolKeys;
		}

		@Override
		public String toString() {
			return "StepConfig{" + "stepRequirement='" + stepRequirement + '\'' + ", agentName='" + agentName + '\''
					+ ", modelName='" + modelName + '\'' + ", terminateColumns='" + terminateColumns + '\''
					+ ", selectedToolKeys=" + selectedToolKeys + '}';
		}

	}

	/**
	 * Tool Configuration VO Class Represents the tool configuration section in plan
	 * template JSON
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class ToolConfigVO {

		private String toolDescription;

		private Boolean enableInternalToolcall;

		private Boolean enableHttpService;

		private Boolean enableInConversation;

		private String publishStatus;

		private List<InputSchemaParam> inputSchema;

		/**
		 * Default constructor
		 */
		public ToolConfigVO() {
			this.enableInternalToolcall = true;
			this.enableHttpService = false;
			this.enableInConversation = false;
			this.publishStatus = "PUBLISHED";
			this.inputSchema = new ArrayList<>();
		}

		// Getters and Setters

		public String getToolDescription() {
			return toolDescription;
		}

		public void setToolDescription(String toolDescription) {
			this.toolDescription = toolDescription;
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

		public String getPublishStatus() {
			return publishStatus;
		}

		public void setPublishStatus(String publishStatus) {
			this.publishStatus = publishStatus;
		}

		public List<InputSchemaParam> getInputSchema() {
			return inputSchema;
		}

		public void setInputSchema(List<InputSchemaParam> inputSchema) {
			this.inputSchema = inputSchema != null ? inputSchema : new ArrayList<>();
		}

		@Override
		public String toString() {
			return "ToolConfigVO{" + "toolDescription='" + toolDescription + '\'' + ", enableInternalToolcall="
					+ enableInternalToolcall + ", enableHttpService=" + enableHttpService + ", enableInConversation="
					+ enableInConversation + ", publishStatus='" + publishStatus + '\'' + ", inputSchema=" + inputSchema
					+ '}';
		}

	}

	/**
	 * Input Schema Parameter Class Represents a single parameter in the input schema
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class InputSchemaParam {

		private String name;

		private String description;

		private String type;

		private Boolean required;

		/**
		 * Default constructor
		 */
		public InputSchemaParam() {
			this.type = "string";
			this.required = true;
		}

		// Getters and Setters

		public String getName() {
			return name;
		}

		public void setName(String name) {
			this.name = name;
		}

		public String getDescription() {
			return description;
		}

		public void setDescription(String description) {
			this.description = description;
		}

		public String getType() {
			return type;
		}

		public void setType(String type) {
			this.type = type;
		}

		public Boolean getRequired() {
			return required;
		}

		public void setRequired(Boolean required) {
			this.required = required;
		}

		@Override
		public String toString() {
			return "InputSchemaParam{" + "name='" + name + '\'' + ", description='" + description + '\'' + ", type='"
					+ type + '\'' + ", required=" + required + '}';
		}

	}

	@Override
	public String toString() {
		return "PlanTemplateConfigVO{" + "title='" + title + '\'' + ", steps=" + steps + ", planType='" + planType
				+ '\'' + ", planTemplateId='" + planTemplateId + '\'' + ", accessLevel=" + getAccessLevel()
				+ ", toolConfig=" + toolConfig + '}';
	}

}
