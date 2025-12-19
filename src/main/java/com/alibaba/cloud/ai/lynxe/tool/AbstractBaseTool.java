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
package com.alibaba.cloud.ai.lynxe.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;

import com.alibaba.cloud.ai.lynxe.tool.code.ToolExecuteResult;

/**
 * Abstract base class for tools providing common functionality All concrete tool
 * implementations should extend this class
 *
 * @param <I> Tool input type
 */
public abstract class AbstractBaseTool<I> implements ToolCallBiFunctionDef<I> {

	private static final Logger log = LoggerFactory.getLogger(AbstractBaseTool.class);

	/**
	 * Current plan ID for the tool execution context
	 */
	protected String currentPlanId;

	/**
	 * Root plan ID is the global parent of the whole execution plan
	 */
	protected String rootPlanId;

	/**
	 * Whether the tool is selectable in front end UI
	 * @return
	 */
	public abstract boolean isSelectable();

	@Override
	public boolean isReturnDirect() {
		return false;
	}

	@Override
	public void setCurrentPlanId(String planId) {
		this.currentPlanId = planId;
	}

	@Override
	public void setRootPlanId(String rootPlanId) {
		this.rootPlanId = rootPlanId;
	}

	/**
	 * Get the current plan ID
	 * @return the current plan ID
	 */
	public String getCurrentPlanId() {
		return this.currentPlanId;
	}

	/**
	 * Get the root plan ID
	 * @return the root plan ID
	 */
	public String getRootPlanId() {
		return this.rootPlanId;
	}

	/**
	 * Default implementation delegates to run method Subclasses can override this method
	 * if needed
	 */
	@Override
	public ToolExecuteResult apply(I input, ToolContext toolContext) {
		return run(input);
	}

	/**
	 * Abstract method that subclasses must implement to define tool-specific execution
	 * logic
	 * @param input Tool input parameters
	 * @return Tool execution result
	 */
	public abstract ToolExecuteResult run(I input);

	/**
	 * Get the description information of the tool with service group appended Default
	 * implementation appends serviceGroup_toolName to the description if serviceGroup is
	 * not null or empty
	 * @return Returns the functional description of the tool with service group appended
	 * at the end
	 */
	@Override
	public String getDescriptionWithServiceGroup() {
		String description = getDescription();
		String serviceGroup = getServiceGroup();
		String toolName = getName();
		if (serviceGroup != null && !serviceGroup.trim().isEmpty() && toolName != null) {
			return description + " [" + serviceGroup + "_" + toolName + "]";
		}
		return description;
	}

	/**
	 * Get the current status string of the tool
	 * @return Returns a string describing the current status of the tool
	 */
	public abstract String getCurrentToolStateString();

	/**
	 * Get the current tool state string with unified error handling This method wraps
	 * getCurrentToolStateString() with error handling to ensure exceptions don't
	 * interrupt the execution flow
	 * @return Tool state string, or error message if an exception occurs
	 */
	public String getCurrentToolStateStringWithErrorHandler() {
		try {
			// Call the original getCurrentToolStateString() method
			String stateString = getCurrentToolStateString();
			return stateString != null ? stateString : "";
		}
		catch (Exception e) {
			// Handle any exception gracefully - return error message instead of throwing
			// This ensures the flow continues even if tool state retrieval fails
			String toolName = getName();
			String errorMessage = String.format(
					"Error getting tool state for '%s': %s. You can continue with available information.",
					toolName != null ? toolName : "unknown tool", e.getMessage());
			log.warn("Error getting tool state string for tool '{}' (non-fatal): {}", toolName, e.getMessage(), e);
			return errorMessage;
		}
	}

	/**
	 * Normalize baseUrl by removing trailing slashes
	 * @param baseUrl The base URL to normalize
	 * @return Normalized base URL
	 */
	public static String normalizeBaseUrl(String baseUrl) {
		if (baseUrl == null || baseUrl.trim().isEmpty()) {
			return baseUrl;
		}
		String normalized = baseUrl.trim();
		// Remove trailing slashes
		while (normalized.endsWith("/")) {
			normalized = normalized.substring(0, normalized.length() - 1);
		}
		return normalized;
	}

	/**
	 * Normalize baseUrl for API endpoints that internally use /v1 prefix to avoid
	 * duplicate /v1 segments Similar to normalizeCompletionsPath logic in LlmService.
	 * This is useful for APIs like OpenAiImageApi which internally uses
	 * /v1/images/generations. If baseUrl ends with /v1, we remove it to prevent duplicate
	 * /v1 in the final URL
	 * @param baseUrl The normalized base URL (should already have trailing slashes
	 * removed)
	 * @return Base URL normalized for API endpoints that use /v1 prefix internally
	 */
	public static String normalizeBaseUrlForApiEndpoint(String baseUrl) {
		if (baseUrl == null || baseUrl.trim().isEmpty()) {
			return baseUrl;
		}

		// If baseUrl ends with /v1, remove it to avoid duplicate /v1
		// Example: "https://openrouter.ai/api/v1" -> "https://openrouter.ai/api"
		// This works for APIs that internally add /v1 prefix to their endpoints
		if (baseUrl.endsWith("/v1")) {
			String normalized = baseUrl.substring(0, baseUrl.length() - 3); // Remove
																			// "/v1"
			log.info("Normalized baseUrl for API endpoint from '{}' to '{}' to avoid duplicate /v1 in URL", baseUrl,
					normalized);
			return normalized;
		}

		return baseUrl;
	}

}
