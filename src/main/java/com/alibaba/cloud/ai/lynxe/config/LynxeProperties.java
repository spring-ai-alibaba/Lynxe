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

package com.alibaba.cloud.ai.lynxe.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import com.alibaba.cloud.ai.lynxe.config.entity.ConfigInputType;

@Component
@ConfigurationProperties(prefix = "lynxe")
public class LynxeProperties {

	@Lazy
	@Autowired
	private IConfigService configService;

	// Browser Settings
	// Begin-------------------------------------------------------------------------------------------

	@ConfigProperty(group = "lynxe", subGroup = "browser", key = "headless", path = "lynxe.browser.headless",
			description = "lynxe.browser.headless.description", defaultValue = "false",
			inputType = ConfigInputType.CHECKBOX,
			options = { @ConfigOption(value = "true", label = "lynxe.browser.headless.option.true"),
					@ConfigOption(value = "false", label = "lynxe.browser.headless.option.false") })
	private volatile Boolean browserHeadless;

	public Boolean getBrowserHeadless() {
		String configPath = "lynxe.browser.headless";
		String value = configService.getConfigValue(configPath);
		if (value != null) {
			browserHeadless = Boolean.valueOf(value);
		}
		return browserHeadless;
	}

	public void setBrowserHeadless(Boolean browserHeadless) {
		this.browserHeadless = browserHeadless;
	}

	@ConfigProperty(group = "lynxe", subGroup = "browser", key = "requestTimeout",
			path = "lynxe.browser.requestTimeout", description = "lynxe.browser.requestTimeout.description",
			defaultValue = "30", inputType = ConfigInputType.NUMBER)
	private volatile Integer browserRequestTimeout;

	public Integer getBrowserRequestTimeout() {
		String configPath = "lynxe.browser.requestTimeout";
		String value = configService.getConfigValue(configPath);
		if (value != null) {
			browserRequestTimeout = Integer.valueOf(value);
		}
		return browserRequestTimeout;
	}

	public void setBrowserRequestTimeout(Integer browserRequestTimeout) {
		this.browserRequestTimeout = browserRequestTimeout;
	}

	@ConfigProperty(group = "lynxe", subGroup = "general", key = "debugDetail", path = "lynxe.general.debugDetail",
			description = "lynxe.general.debugDetail.description", defaultValue = "false",
			inputType = ConfigInputType.CHECKBOX,
			options = { @ConfigOption(value = "true", label = "lynxe.general.debugDetail.option.true"),
					@ConfigOption(value = "false", label = "lynxe.general.debugDetail.option.false") })
	private volatile Boolean debugDetail;

	public Boolean getDebugDetail() {
		String configPath = "lynxe.general.debugDetail";
		String value = configService.getConfigValue(configPath);
		if (value != null) {
			debugDetail = Boolean.valueOf(value);
		}
		return debugDetail;
	}

	public void setDebugDetail(Boolean debugDetail) {
		this.debugDetail = debugDetail;
	}

	// Browser Settings
	// End---------------------------------------------------------------------------------------------

	// General Settings
	// Begin---------------------------------------------------------------------------------------
	@ConfigProperty(group = "lynxe", subGroup = "general", key = "openBrowser", path = "lynxe.general.openBrowser",
			description = "lynxe.general.openBrowser.description", defaultValue = "true",
			inputType = ConfigInputType.CHECKBOX,
			options = { @ConfigOption(value = "true", label = "lynxe.general.openBrowser.option.true"),
					@ConfigOption(value = "false", label = "lynxe.general.openBrowser.option.false") })
	private volatile Boolean openBrowserAuto;

	public Boolean getOpenBrowserAuto() {
		String configPath = "lynxe.general.openBrowser";
		String value = configService.getConfigValue(configPath);
		if (value != null) {
			openBrowserAuto = Boolean.valueOf(value);
		}
		// Default to true if not configured
		if (openBrowserAuto == null) {
			openBrowserAuto = true;
		}
		return openBrowserAuto;
	}

	public void setOpenBrowserAuto(Boolean openBrowserAuto) {
		this.openBrowserAuto = openBrowserAuto;
	}

	@ConfigProperty(group = "lynxe", subGroup = "browser", key = "enableShortUrl",
			path = "lynxe.browser.enableShortUrl", description = "lynxe.browser.enableShortUrl.description",
			defaultValue = "true", inputType = ConfigInputType.CHECKBOX,
			options = { @ConfigOption(value = "true", label = "lynxe.browser.enableShortUrl.option.true"),
					@ConfigOption(value = "false", label = "lynxe.browser.enableShortUrl.option.false") })
	private volatile Boolean enableShortUrl;

	public Boolean getEnableShortUrl() {
		String configPath = "lynxe.browser.enableShortUrl";
		String value = configService.getConfigValue(configPath);
		if (value != null) {
			enableShortUrl = Boolean.valueOf(value);
		}
		// Default to true if not configured
		if (enableShortUrl == null) {
			enableShortUrl = true;
		}
		return enableShortUrl;
	}

	public void setEnableShortUrl(Boolean enableShortUrl) {
		this.enableShortUrl = enableShortUrl;
	}

	// General Settings
	// End-----------------------------------------------------------------------------------------

	// Agent Settings
	// Begin---------------------------------------------------------------------------------------------

	@ConfigProperty(group = "lynxe", subGroup = "agent", key = "maxSteps", path = "lynxe.maxSteps",
			description = "lynxe.agent.maxSteps.description", defaultValue = "30", inputType = ConfigInputType.NUMBER)
	private volatile Integer maxSteps;

	public Integer getMaxSteps() {
		String configPath = "lynxe.maxSteps";
		String value = configService.getConfigValue(configPath);
		if (value != null) {
			maxSteps = Integer.valueOf(value);
		}
		return maxSteps;
	}

	public void setMaxSteps(Integer maxSteps) {
		this.maxSteps = maxSteps;
	}

	@ConfigProperty(group = "lynxe", subGroup = "agent", key = "userInputTimeout",
			path = "lynxe.agent.userInputTimeout", description = "lynxe.agent.userInputTimeout.description",
			defaultValue = "300", inputType = ConfigInputType.NUMBER)
	private volatile Integer userInputTimeout;

	public Integer getUserInputTimeout() {
		String configPath = "lynxe.agent.userInputTimeout";
		String value = configService.getConfigValue(configPath);
		if (value != null) {
			userInputTimeout = Integer.valueOf(value);
		}
		// Ensure a default value if not configured and not set
		if (userInputTimeout == null) {
			// Attempt to parse the default value specified in the annotation,
			// or use a hardcoded default if parsing fails or is complex to retrieve here.
			// For simplicity, directly using the intended default.
			userInputTimeout = 300;
		}
		return userInputTimeout;
	}

	public void setUserInputTimeout(Integer userInputTimeout) {
		this.userInputTimeout = userInputTimeout;
	}

	@ConfigProperty(group = "lynxe", subGroup = "agent", key = "maxMemory", path = "lynxe.agent.maxMemory",
			description = "lynxe.agent.maxMemory.description", defaultValue = "1000",
			inputType = ConfigInputType.NUMBER)
	private volatile Integer maxMemory;

	public Integer getMaxMemory() {
		String configPath = "lynxe.agent.maxMemory";
		String value = configService.getConfigValue(configPath);
		if (value != null) {
			maxMemory = Integer.valueOf(value);
		}
		if (maxMemory == null) {
			maxMemory = 1000;
		}
		return maxMemory;
	}

	public void setMaxMemory(Integer maxMemory) {
		this.maxMemory = maxMemory;
	}

	@ConfigProperty(group = "lynxe", subGroup = "general", key = "enableConversationMemory",
			path = "lynxe.general.enableConversationMemory",
			description = "lynxe.general.enableConversationMemory.description", defaultValue = "true",
			inputType = ConfigInputType.CHECKBOX,
			options = { @ConfigOption(value = "true", label = "lynxe.general.enableConversationMemory.option.true"),
					@ConfigOption(value = "false", label = "lynxe.general.enableConversationMemory.option.false") })
	private volatile Boolean enableConversationMemory;

	public Boolean getEnableConversationMemory() {
		String configPath = "lynxe.general.enableConversationMemory";
		String value = configService.getConfigValue(configPath);
		if (value != null) {
			enableConversationMemory = Boolean.valueOf(value);
		}
		// Default to true if not configured
		if (enableConversationMemory == null) {
			enableConversationMemory = true;
		}
		return enableConversationMemory;
	}

	public void setEnableConversationMemory(Boolean enableConversationMemory) {
		this.enableConversationMemory = enableConversationMemory;
	}

	@ConfigProperty(group = "lynxe", subGroup = "agent", key = "conversationMemoryMaxChars",
			path = "lynxe.agent.conversationMemoryMaxChars",
			description = "lynxe.agent.conversationMemoryMaxChars.description", defaultValue = "30000",
			inputType = ConfigInputType.NUMBER)
	private volatile Integer conversationMemoryMaxChars;

	public Integer getConversationMemoryMaxChars() {
		String configPath = "lynxe.agent.conversationMemoryMaxChars";
		String value = configService.getConfigValue(configPath);
		if (value != null) {
			conversationMemoryMaxChars = Integer.valueOf(value);
		}
		if (conversationMemoryMaxChars == null) {
			conversationMemoryMaxChars = 30000;
		}
		return conversationMemoryMaxChars;
	}

	public void setConversationMemoryMaxChars(Integer conversationMemoryMaxChars) {
		this.conversationMemoryMaxChars = conversationMemoryMaxChars;
	}

	@ConfigProperty(group = "lynxe", subGroup = "agent", key = "parallelToolCalls",
			path = "lynxe.agent.parallelToolCalls", description = "lynxe.agent.parallelToolCalls.description",
			defaultValue = "false", inputType = ConfigInputType.CHECKBOX,
			options = { @ConfigOption(value = "true", label = "lynxe.agent.parallelToolCalls.option.true"),
					@ConfigOption(value = "false", label = "lynxe.agent.parallelToolCalls.option.false") })
	private volatile Boolean parallelToolCalls;

	public Boolean getParallelToolCalls() {
		String configPath = "lynxe.agent.parallelToolCalls";
		String value = configService.getConfigValue(configPath);
		if (value != null) {
			parallelToolCalls = Boolean.valueOf(value);
		}
		if (value == null) {
			parallelToolCalls = false;
		}
		return parallelToolCalls;
	}

	public void setParallelToolCalls(Boolean parallelToolCalls) {
		this.parallelToolCalls = parallelToolCalls;
	}

	@ConfigProperty(group = "lynxe", subGroup = "agent", key = "executorPoolSize",
			path = "lynxe.agent.executorPoolSize", description = "lynxe.agent.executorPoolSize.description",
			defaultValue = "5", inputType = ConfigInputType.NUMBER)
	private volatile Integer executorPoolSize;

	public Integer getExecutorPoolSize() {
		String configPath = "lynxe.agent.executorPoolSize";
		String value = configService.getConfigValue(configPath);
		if (value != null) {
			executorPoolSize = Integer.valueOf(value);
		}
		// Ensure a default value if not configured and not set
		if (executorPoolSize == null) {
			executorPoolSize = 5;
		}
		return executorPoolSize;
	}

	public void setExecutorPoolSize(Integer executorPoolSize) {
		this.executorPoolSize = executorPoolSize;
	}

	@ConfigProperty(group = "lynxe", subGroup = "agent", key = "llmReadTimeout", path = "lynxe.agent.llmReadTimeout",
			description = "lynxe.agent.llmReadTimeout.description", defaultValue = "120",
			inputType = ConfigInputType.NUMBER)
	private volatile Integer llmReadTimeout;

	public Integer getLlmReadTimeout() {
		String configPath = "lynxe.agent.llmReadTimeout";
		String value = configService.getConfigValue(configPath);
		if (value != null) {
			llmReadTimeout = Integer.valueOf(value);
		}
		// Ensure a default value if not configured and not set
		if (llmReadTimeout == null) {
			llmReadTimeout = 120; // Default 120 seconds (2 minutes)
		}
		return llmReadTimeout;
	}

	public void setLlmReadTimeout(Integer llmReadTimeout) {
		this.llmReadTimeout = llmReadTimeout;
	}

	// Agent Settings
	// End-----------------------------------------------------------------------------------------------

	// Normal Settings
	// Begin--------------------------------------------------------------------------------------------

	// Normal Settings
	// End----------------------------------------------------------------------------------------------

	// File System Security SubGroup
	@ConfigProperty(group = "lynxe", subGroup = "general", key = "externalLinkedFolder",
			path = "lynxe.general.externalLinkedFolder", description = "lynxe.general.externalLinkedFolder.description",
			defaultValue = "", inputType = ConfigInputType.TEXT)
	private volatile String externalLinkedFolder = "";

	public String getExternalLinkedFolder() {
		String configPath = "lynxe.general.externalLinkedFolder";
		String value = configService.getConfigValue(configPath);
		if (value != null) {
			externalLinkedFolder = value;
		}
		return externalLinkedFolder;
	}

	public void setExternalLinkedFolder(String externalLinkedFolder) {
		this.externalLinkedFolder = externalLinkedFolder;
	}

	// MCP Service Loader Settings
	// Begin--------------------------------------------------------------------------------------------

	@ConfigProperty(group = "lynxe", subGroup = "mcpServiceLoader", key = "connectionTimeoutSeconds",
			path = "lynxe.mcpServiceLoader.connectionTimeoutSeconds",
			description = "lynxe.mcpServiceLoader.connectionTimeoutSeconds.description", defaultValue = "20",
			inputType = ConfigInputType.NUMBER)
	private volatile Integer mcpConnectionTimeoutSeconds;

	public Integer getMcpConnectionTimeoutSeconds() {
		String configPath = "lynxe.mcpServiceLoader.connectionTimeoutSeconds";
		String value = configService.getConfigValue(configPath);
		if (value != null) {
			mcpConnectionTimeoutSeconds = Integer.valueOf(value);
		}
		// Ensure a default value if not configured and not set
		if (mcpConnectionTimeoutSeconds == null) {
			mcpConnectionTimeoutSeconds = 3;
		}
		return mcpConnectionTimeoutSeconds;
	}

	public void setMcpConnectionTimeoutSeconds(Integer mcpConnectionTimeoutSeconds) {
		this.mcpConnectionTimeoutSeconds = mcpConnectionTimeoutSeconds;
	}

	@ConfigProperty(group = "lynxe", subGroup = "mcpServiceLoader", key = "maxRetryCount",
			path = "lynxe.mcpServiceLoader.maxRetryCount",
			description = "lynxe.mcpServiceLoader.maxRetryCount.description", defaultValue = "3",
			inputType = ConfigInputType.NUMBER)
	private volatile Integer mcpMaxRetryCount;

	public Integer getMcpMaxRetryCount() {
		String configPath = "lynxe.mcpServiceLoader.maxRetryCount";
		String value = configService.getConfigValue(configPath);
		if (value != null) {
			mcpMaxRetryCount = Integer.valueOf(value);
		}
		// Ensure a default value if not configured and not set
		if (mcpMaxRetryCount == null) {
			mcpMaxRetryCount = 1;
		}
		return mcpMaxRetryCount;
	}

	public void setMcpMaxRetryCount(Integer mcpMaxRetryCount) {
		this.mcpMaxRetryCount = mcpMaxRetryCount;
	}

	@ConfigProperty(group = "lynxe", subGroup = "mcpServiceLoader", key = "maxConcurrentConnections",
			path = "lynxe.mcpServiceLoader.maxConcurrentConnections",
			description = "lynxe.mcpServiceLoader.maxConcurrentConnections.description", defaultValue = "10",
			inputType = ConfigInputType.NUMBER)
	private volatile Integer mcpMaxConcurrentConnections;

	public Integer getMcpMaxConcurrentConnections() {
		String configPath = "lynxe.mcpServiceLoader.maxConcurrentConnections";
		String value = configService.getConfigValue(configPath);
		if (value != null) {
			mcpMaxConcurrentConnections = Integer.valueOf(value);
		}
		// Ensure a default value if not configured and not set
		if (mcpMaxConcurrentConnections == null) {
			mcpMaxConcurrentConnections = 10;
		}
		return mcpMaxConcurrentConnections;
	}

	public void setMcpMaxConcurrentConnections(Integer mcpMaxConcurrentConnections) {
		this.mcpMaxConcurrentConnections = mcpMaxConcurrentConnections;
	}

	// MCP Service Loader Settings
	// End----------------------------------------------------------------------------------------------

	// Image Recognition Settings
	// Begin--------------------------------------------------------------------------------------------

	@ConfigProperty(group = "lynxe", subGroup = "imageRecognition", key = "poolSize",
			path = "lynxe.imageRecognition.poolSize", description = "lynxe.imageRecognition.poolSize.description",
			defaultValue = "4", inputType = ConfigInputType.NUMBER)
	private volatile Integer imageRecognitionPoolSize;

	public Integer getImageRecognitionPoolSize() {
		String configPath = "lynxe.imageRecognition.poolSize";
		String value = configService.getConfigValue(configPath);
		if (value != null) {
			imageRecognitionPoolSize = Integer.valueOf(value);
		}
		// Ensure a default value if not configured and not set
		if (imageRecognitionPoolSize == null) {
			imageRecognitionPoolSize = 4;
		}
		return imageRecognitionPoolSize;
	}

	public void setImageRecognitionPoolSize(Integer imageRecognitionPoolSize) {
		this.imageRecognitionPoolSize = imageRecognitionPoolSize;
	}

	@ConfigProperty(group = "lynxe", subGroup = "imageRecognition", key = "modelName",
			path = "lynxe.imageRecognition.modelName", description = "lynxe.imageRecognition.modelName.description",
			defaultValue = "qwen-vl-ocr-latest", inputType = ConfigInputType.TEXT)
	private volatile String imageRecognitionModelName;

	public String getImageRecognitionModelName() {
		String configPath = "lynxe.imageRecognition.modelName";
		String value = configService.getConfigValue(configPath);
		if (value != null) {
			imageRecognitionModelName = value;
		}
		// Ensure a default value if not configured and not set
		if (imageRecognitionModelName == null) {
			imageRecognitionModelName = "qwen-vl-ocr-latest";
		}
		return imageRecognitionModelName;
	}

	public void setImageRecognitionModelName(String imageRecognitionModelName) {
		this.imageRecognitionModelName = imageRecognitionModelName;
	}

	@ConfigProperty(group = "lynxe", subGroup = "imageRecognition", key = "dpi", path = "lynxe.imageRecognition.dpi",
			description = "lynxe.imageRecognition.dpi.description", defaultValue = "120.0",
			inputType = ConfigInputType.NUMBER)
	private volatile Float imageRecognitionDpi;

	public Float getImageRecognitionDpi() {
		String configPath = "lynxe.imageRecognition.dpi";
		String value = configService.getConfigValue(configPath);
		if (value != null) {
			imageRecognitionDpi = Float.valueOf(value);
		}
		// Ensure a default value if not configured and not set
		if (imageRecognitionDpi == null) {
			imageRecognitionDpi = 120.0f;
		}
		return imageRecognitionDpi;
	}

	public void setImageRecognitionDpi(Float imageRecognitionDpi) {
		this.imageRecognitionDpi = imageRecognitionDpi;
	}

	@ConfigProperty(group = "lynxe", subGroup = "imageRecognition", key = "imageType",
			path = "lynxe.imageRecognition.imageType", description = "lynxe.imageRecognition.imageType.description",
			defaultValue = "RGB", inputType = ConfigInputType.TEXT)
	private volatile String imageRecognitionImageType;

	public String getImageRecognitionImageType() {
		String configPath = "lynxe.imageRecognition.imageType";
		String value = configService.getConfigValue(configPath);
		if (value != null) {
			imageRecognitionImageType = value;
		}
		// Ensure a default value if not configured and not set
		if (imageRecognitionImageType == null) {
			imageRecognitionImageType = "RGB";
		}
		return imageRecognitionImageType;
	}

	public void setImageRecognitionImageType(String imageRecognitionImageType) {
		this.imageRecognitionImageType = imageRecognitionImageType;
	}

	@ConfigProperty(group = "lynxe", subGroup = "imageRecognition", key = "maxRetryAttempts",
			path = "lynxe.imageRecognition.maxRetryAttempts",
			description = "lynxe.imageRecognition.maxRetryAttempts.description", defaultValue = "3",
			inputType = ConfigInputType.NUMBER)
	private volatile Integer imageRecognitionMaxRetryAttempts;

	public Integer getImageRecognitionMaxRetryAttempts() {
		String configPath = "lynxe.imageRecognition.maxRetryAttempts";
		String value = configService.getConfigValue(configPath);
		if (value != null) {
			imageRecognitionMaxRetryAttempts = Integer.valueOf(value);
		}
		// Ensure a default value if not configured and not set
		if (imageRecognitionMaxRetryAttempts == null) {
			imageRecognitionMaxRetryAttempts = 3;
		}
		return imageRecognitionMaxRetryAttempts;
	}

	public void setImageRecognitionMaxRetryAttempts(Integer imageRecognitionMaxRetryAttempts) {
		this.imageRecognitionMaxRetryAttempts = imageRecognitionMaxRetryAttempts;
	}

	// Image Recognition Settings
	// End----------------------------------------------------------------------------------------------

}
