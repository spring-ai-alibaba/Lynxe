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
package com.alibaba.cloud.ai.lynxe.tool.browser;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.cloud.ai.lynxe.config.LynxeProperties;
import com.alibaba.cloud.ai.lynxe.tool.AbstractBaseTool;
import com.alibaba.cloud.ai.lynxe.tool.browser.actions.BrowserRequestVO;
import com.alibaba.cloud.ai.lynxe.tool.browser.actions.ClickByElementAction;
import com.alibaba.cloud.ai.lynxe.tool.browser.actions.CloseTabAction;
import com.alibaba.cloud.ai.lynxe.tool.browser.actions.ExecuteJsAction;
import com.alibaba.cloud.ai.lynxe.tool.browser.actions.GetElementPositionByNameAction;
import com.alibaba.cloud.ai.lynxe.tool.browser.actions.GetTextAction;
import com.alibaba.cloud.ai.lynxe.tool.browser.actions.InputTextAction;
import com.alibaba.cloud.ai.lynxe.tool.browser.actions.KeyEnterAction;
import com.alibaba.cloud.ai.lynxe.tool.browser.actions.MoveToAndClickAction;
import com.alibaba.cloud.ai.lynxe.tool.browser.actions.NavigateAction;
import com.alibaba.cloud.ai.lynxe.tool.browser.actions.NewTabAction;
import com.alibaba.cloud.ai.lynxe.tool.browser.actions.RefreshAction;
import com.alibaba.cloud.ai.lynxe.tool.browser.actions.ScreenShotAction;
import com.alibaba.cloud.ai.lynxe.tool.browser.actions.ScrollAction;
import com.alibaba.cloud.ai.lynxe.tool.browser.actions.SwitchTabAction;
import com.alibaba.cloud.ai.lynxe.tool.browser.actions.WriteCurrentWebContentAction;
import com.alibaba.cloud.ai.lynxe.tool.code.ToolExecuteResult;
import com.alibaba.cloud.ai.lynxe.tool.i18n.ToolI18nService;
import com.alibaba.cloud.ai.lynxe.tool.innerStorage.SmartContentSavingService;
import com.alibaba.cloud.ai.lynxe.tool.textOperator.TextFileService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.TimeoutError;

public class BrowserUseTool extends AbstractBaseTool<BrowserRequestVO> {

	private static final Logger log = LoggerFactory.getLogger(BrowserUseTool.class);

	private final ChromeDriverService chromeDriverService;

	private final SmartContentSavingService innerStorageService;

	private final ObjectMapper objectMapper;

	private final com.alibaba.cloud.ai.lynxe.tool.shortUrl.ShortUrlService shortUrlService;

	private final TextFileService textFileService;

	private final ToolI18nService toolI18nService;

	public BrowserUseTool(ChromeDriverService chromeDriverService, SmartContentSavingService innerStorageService,
			ObjectMapper objectMapper, com.alibaba.cloud.ai.lynxe.tool.shortUrl.ShortUrlService shortUrlService,
			TextFileService textFileService, ToolI18nService toolI18nService) {
		this.chromeDriverService = chromeDriverService;
		this.innerStorageService = innerStorageService;
		this.objectMapper = objectMapper;
		this.shortUrlService = shortUrlService;
		this.textFileService = textFileService;
		this.toolI18nService = toolI18nService;
	}

	public DriverWrapper getDriver() {
		try {
			DriverWrapper driver = chromeDriverService.getDriver(currentPlanId);
			if (driver == null) {
				throw new RuntimeException("Failed to get driver for planId: " + currentPlanId);
			}
			return driver;
		}
		catch (Exception e) {
			log.error("Error getting driver for planId {}: {}", currentPlanId, e.getMessage(), e);
			throw new RuntimeException("Failed to get driver for planId: " + currentPlanId, e);
		}
	}

	/**
	 * Get browser operation timeout configuration
	 * @return Timeout in seconds, returns default value of 30 seconds if not configured
	 */
	private Integer getBrowserTimeout() {
		Integer timeout = getLynxeProperties().getBrowserRequestTimeout();
		return timeout != null ? timeout : 30; // Default timeout is 30 seconds
	}

	private final String name = "browser_use";

	// Track if run method has been called at least once
	private volatile boolean hasRunAtLeastOnce = false;

	public static synchronized BrowserUseTool getInstance(ChromeDriverService chromeDriverService,
			SmartContentSavingService innerStorageService, ObjectMapper objectMapper,
			com.alibaba.cloud.ai.lynxe.tool.shortUrl.ShortUrlService shortUrlService, TextFileService textFileService,
			ToolI18nService toolI18nService) {
		BrowserUseTool instance = new BrowserUseTool(chromeDriverService, innerStorageService, objectMapper,
				shortUrlService, textFileService, toolI18nService);
		return instance;
	}

	/**
	 * Get ShortUrlService instance
	 * @return ShortUrlService
	 */
	public com.alibaba.cloud.ai.lynxe.tool.shortUrl.ShortUrlService getShortUrlService() {
		return shortUrlService;
	}

	public ToolExecuteResult run(BrowserRequestVO requestVO) {
		String action = null;
		try {
			log.info("BrowserUseTool requestVO: action={}", requestVO.getAction());

			// Mark that run has been called at least once
			hasRunAtLeastOnce = true;

			// Get parameters from RequestVO
			action = requestVO.getAction();
			if (action == null || action.trim().isEmpty()) {
				return new ToolExecuteResult("Action parameter is required and cannot be empty");
			}

			// Validate driver availability before executing any action
			try {
				DriverWrapper driver = getDriver();
				if (driver == null) {
					return new ToolExecuteResult("Browser driver is not available");
				}

				// Check if browser is still connected
				if (driver.getBrowser() == null || !driver.getBrowser().isConnected()) {
					return new ToolExecuteResult("Browser is not connected. Please try again or restart the browser.");
				}

				// Check if current page is valid
				Page currentPage = driver.getCurrentPage();
				if (currentPage == null || currentPage.isClosed()) {
					return new ToolExecuteResult("Current page is not available. Please navigate to a page first.");
				}
			}
			catch (Exception e) {
				log.error("Driver validation failed for action '{}': {}", action, e.getMessage(), e);
				return new ToolExecuteResult("Browser driver validation failed: " + e.getMessage());
			}

			ToolExecuteResult result;
			try {
				switch (action) {
					case "navigate": {
						result = executeActionWithRetry(() -> new NavigateAction(this).execute(requestVO), action);
						break;
					}
					case "click": {
						result = executeActionWithRetry(() -> new ClickByElementAction(this).execute(requestVO),
								action);
						break;
					}
					case "input_text": {
						result = executeActionWithRetry(() -> new InputTextAction(this).execute(requestVO), action);
						break;
					}
					case "key_enter": {
						result = executeActionWithRetry(() -> new KeyEnterAction(this).execute(requestVO), action);
						break;
					}
					case "screenshot": {
						result = executeActionWithRetry(() -> new ScreenShotAction(this).execute(requestVO), action);
						break;
					}
					case "get_text": {
						result = executeActionWithRetry(() -> new GetTextAction(this).execute(requestVO), action);
						// Text content may be long, use intelligent processing
						try {
							SmartContentSavingService.SmartProcessResult processedResult = innerStorageService
								.processContent(currentPlanId, result.getOutput(), "get_text");
							return new ToolExecuteResult(processedResult.getSummary());
						}
						catch (Exception e) {
							log.warn("Failed to process get_text content intelligently: {}", e.getMessage());
							return result; // Return original result if processing fails
						}
					}
					case "execute_js": {
						result = executeActionWithRetry(() -> new ExecuteJsAction(this).execute(requestVO), action);
						// JS execution results may be long, use intelligent processing
						try {
							SmartContentSavingService.SmartProcessResult processedResult = innerStorageService
								.processContent(currentPlanId, result.getOutput(), "execute_js");
							return new ToolExecuteResult(processedResult.getSummary());
						}
						catch (Exception e) {
							log.warn("Failed to process execute_js content intelligently: {}", e.getMessage());
							return result; // Return original result if processing fails
						}
					}
					case "scroll": {
						result = executeActionWithRetry(() -> new ScrollAction(this).execute(requestVO), action);
						break;
					}
					case "new_tab": {
						result = executeActionWithRetry(() -> new NewTabAction(this).execute(requestVO), action);
						break;
					}
					case "close_tab": {
						result = executeActionWithRetry(() -> new CloseTabAction(this).execute(requestVO), action);
						break;
					}
					case "switch_tab": {
						result = executeActionWithRetry(() -> new SwitchTabAction(this).execute(requestVO), action);
						break;
					}
					case "refresh": {
						result = executeActionWithRetry(() -> new RefreshAction(this).execute(requestVO), action);
						break;
					}
					case "get_element_position": {
						result = executeActionWithRetry(
								() -> new GetElementPositionByNameAction(this, objectMapper).execute(requestVO),
								action);
						break;
					}
					case "move_to_and_click": {
						result = executeActionWithRetry(() -> new MoveToAndClickAction(this).execute(requestVO),
								action);
						break;
					}
					case "get_web_content": {
						result = executeActionWithRetry(
								() -> new WriteCurrentWebContentAction(this, textFileService).execute(requestVO),
								action);
						break;
					}
					default:
						return new ToolExecuteResult("Unknown action: " + action);
				}
			}
			catch (TimeoutError e) {
				log.error("Timeout error executing action '{}': {}", action, e.getMessage(), e);
				return new ToolExecuteResult("Browser action '" + action + "' timed out: " + e.getMessage());
			}
			catch (PlaywrightException e) {
				log.error("Playwright error executing action '{}': {}", action, e.getMessage(), e);
				return new ToolExecuteResult(
						"Browser action '" + action + "' failed due to Playwright error: " + e.getMessage());
			}
			catch (Exception e) {
				log.error("Unexpected error executing action '{}': {}", action, e.getMessage(), e);
				return new ToolExecuteResult("Browser action '" + action + "' failed: " + e.getMessage());
			}

			// For other operations, also perform intelligent processing (but thresholds
			// usually won't be exceeded)
			try {
				SmartContentSavingService.SmartProcessResult processedResult = innerStorageService
					.processContent(currentPlanId, result.getOutput(), action);
				return new ToolExecuteResult(processedResult.getSummary());
			}
			catch (Exception e) {
				log.warn("Failed to process content intelligently for action '{}': {}", action, e.getMessage());
				return result; // Return original result if processing fails
			}

		}
		catch (TimeoutError e) {
			log.error("Timeout error in browser tool for action '{}': {}", action, e.getMessage(), e);
			return new ToolExecuteResult("Browser operation timed out: " + e.getMessage());
		}
		catch (PlaywrightException e) {
			log.error("Playwright error in browser tool for action '{}': {}", action, e.getMessage(), e);
			return new ToolExecuteResult("Browser operation failed due to Playwright error: " + e.getMessage());
		}
		catch (Exception e) {
			log.error("Unexpected error in browser tool for action '{}': {}", action, e.getMessage(), e);
			return new ToolExecuteResult("Browser operation failed: " + e.getMessage());
		}
	}

	/**
	 * Execute action with retry mechanism for better reliability
	 */
	private ToolExecuteResult executeActionWithRetry(ActionExecutor executor, String actionName) {
		int maxRetries = 2;
		int retryDelay = 1000; // 1 second

		for (int attempt = 1; attempt <= maxRetries; attempt++) {
			try {
				return executor.execute();
			}
			catch (TimeoutError e) {
				if (attempt == maxRetries) {
					log.error("Action '{}' timed out after {} attempts: {}", actionName, maxRetries, e.getMessage());
					throw e;
				}
				log.warn("Action '{}' timed out on attempt {}, retrying: {}", actionName, attempt, e.getMessage());
			}
			catch (PlaywrightException e) {
				// Some Playwright exceptions are not worth retrying
				if (e.getMessage().contains("Target page, context or browser has been closed")
						|| e.getMessage().contains("Browser has been closed")
						|| e.getMessage().contains("Context has been closed")) {
					log.error("Action '{}' failed due to closed browser/context: {}", actionName, e.getMessage());
					throw e;
				}

				if (attempt == maxRetries) {
					log.error("Action '{}' failed after {} attempts: {}", actionName, maxRetries, e.getMessage());
					throw e;
				}
				log.warn("Action '{}' failed on attempt {}, retrying: {}", actionName, attempt, e.getMessage());
			}
			catch (RuntimeException e) {
				// For runtime exceptions, don't retry
				log.error("Action '{}' failed with non-retryable error: {}", actionName, e.getMessage());
				throw e;
			}
			catch (Exception e) {
				// For checked exceptions, wrap and don't retry
				log.error("Action '{}' failed with non-retryable error: {}", actionName, e.getMessage());
				throw new RuntimeException("Action failed: " + actionName, e);
			}

			// Wait before retry
			try {
				Thread.sleep(retryDelay);
			}
			catch (InterruptedException ie) {
				Thread.currentThread().interrupt();
				throw new RuntimeException("Interrupted during retry delay for action: " + actionName, ie);
			}
		}

		// Should never reach here
		throw new RuntimeException("Unexpected end of retry loop for action: " + actionName);
	}

	/**
	 * Functional interface for action execution
	 */
	@FunctionalInterface
	private interface ActionExecutor {

		ToolExecuteResult execute() throws Exception;

	}

	private List<Map<String, Object>> getTabsInfo(Page page) {
		try {
			// Filter out closed pages to avoid including history
			return page.context().pages().stream().filter(p -> {
				try {
					return !p.isClosed();
				}
				catch (Exception e) {
					log.debug("Error checking if page is closed: {}", e.getMessage());
					return false;
				}
			}).map(p -> {
				Map<String, Object> tabInfo = new HashMap<>();
				try {
					tabInfo.put("url", p.url());
					tabInfo.put("title", p.title());
				}
				catch (PlaywrightException e) {
					log.warn("Failed to get tab info: {}", e.getMessage());
					tabInfo.put("url", "error: " + e.getMessage());
					tabInfo.put("title", "error: " + e.getMessage());
				}
				catch (Exception e) {
					log.warn("Unexpected error getting tab info: {}", e.getMessage());
					tabInfo.put("url", "error: " + e.getMessage());
					tabInfo.put("title", "error: " + e.getMessage());
				}
				return tabInfo;
			}).toList();
		}
		catch (PlaywrightException e) {
			log.warn("Failed to get pages from context: {}", e.getMessage());
			return List.of(Map.of("error", "Failed to get tabs: " + e.getMessage()));
		}
		catch (Exception e) {
			log.warn("Unexpected error getting tabs info: {}", e.getMessage());
			return List.of(Map.of("error", "Failed to get tabs: " + e.getMessage()));
		}
	}

	public Map<String, Object> getCurrentState(Page page) {
		Map<String, Object> state = new HashMap<>();

		try {
			// Validate page first
			if (page == null) {
				state.put("error", "Page is null");
				return state;
			}

			if (page.isClosed()) {
				state.put("error", "Page is closed");
				return state;
			}

			// Wait for page to load completely to avoid context destruction errors when
			// getting information during navigation
			try {
				Integer timeout = getBrowserTimeout();
				// First wait for DOM content loaded
				page.waitForLoadState(com.microsoft.playwright.options.LoadState.DOMCONTENTLOADED,
						new Page.WaitForLoadStateOptions().setTimeout(timeout * 1000));
				// Then wait for network idle to ensure all AJAX requests and dynamic
				// content updates are complete
				// This is especially important after actions like key_enter that trigger
				// searches
				try {
					page.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE,
							new Page.WaitForLoadStateOptions().setTimeout(3000)); // 3
																					// second
																					// timeout
																					// for
																					// network
																					// idle
				}
				catch (TimeoutError e) {
					// If network idle timeout, wait a bit more for dynamic content to
					// update
					log.debug("Network idle timeout, waiting for content updates: {}", e.getMessage());
					Thread.sleep(1000); // Wait 1 second for content to update
				}
			}
			catch (TimeoutError e) {
				log.warn("Page load state wait timeout, continuing anyway: {}", e.getMessage());
			}
			catch (PlaywrightException e) {
				log.warn("Playwright error waiting for load state, continuing anyway: {}", e.getMessage());
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				log.warn("Interrupted while waiting for page load");
			}
			catch (Exception loadException) {
				log.warn("Unexpected error waiting for load state, continuing anyway: {}", loadException.getMessage());
			}

			// Get basic information with error handling
			try {
				String currentUrl = page.url();
				String title = page.title();
				state.put("url", currentUrl != null ? currentUrl : "unknown");
				state.put("title", title != null ? title : "unknown");
			}
			catch (PlaywrightException e) {
				log.warn("Failed to get page URL/title: {}", e.getMessage());
				state.put("url", "error: " + e.getMessage());
				state.put("title", "error: " + e.getMessage());
			}
			catch (Exception e) {
				log.warn("Unexpected error getting page URL/title: {}", e.getMessage());
				state.put("url", "error: " + e.getMessage());
				state.put("title", "error: " + e.getMessage());
			}

			// Get tab information with error handling
			try {
				List<Map<String, Object>> tabs = getTabsInfo(page);
				state.put("tabs", tabs);
			}
			catch (PlaywrightException e) {
				log.warn("Failed to get tabs info: {}", e.getMessage());
				state.put("tabs", List.of(Map.of("error", "Failed to get tabs: " + e.getMessage())));
			}
			catch (Exception e) {
				log.warn("Unexpected error getting tabs info: {}", e.getMessage());
				state.put("tabs", List.of(Map.of("error", "Failed to get tabs: " + e.getMessage())));
			}

			// Wait a bit more before generating ARIA snapshot to ensure all dynamic
			// content
			// (like search results) is fully rendered
			try {
				Thread.sleep(500); // Additional wait for content rendering
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				log.warn("Interrupted while waiting for content rendering");
			}

			// Generate ARIA snapshot using the new AriaSnapshot utility with error
			// handling
			// Note: AriaSnapshot now returns error messages instead of throwing
			// exceptions
			// for timeouts, so the flow continues normally
			try {
				AriaSnapshotOptions snapshotOptions = new AriaSnapshotOptions().setSelector("body")
					.setTimeout(getBrowserTimeout() * 1000); // Convert to milliseconds

				// Use compressUrl based on configuration
				Boolean enableShortUrl = getLynxeProperties().getEnableShortUrl();
				boolean compressUrl = enableShortUrl != null ? enableShortUrl : true; // Default
																						// to
																						// true
				String snapshot = AriaElementHelper.parsePageAndAssignRefs(page, snapshotOptions, compressUrl,
						shortUrlService, rootPlanId);
				if (snapshot != null && !snapshot.trim().isEmpty()) {
					// Snapshot may contain error message if timeout occurred, which is
					// fine
					state.put("interactive_elements", snapshot);
				}
				else {
					state.put("interactive_elements", "No interactive elements found or snapshot is empty");
				}
			}
			catch (PlaywrightException e) {
				log.warn("Playwright error getting ARIA snapshot: {}", e.getMessage());
				state.put("interactive_elements", "Error getting interactive elements: " + e.getMessage()
						+ ". You can continue with available page information (URL, title, tabs).");
			}
			catch (Exception e) {
				log.warn("Unexpected error getting ARIA snapshot: {}", e.getMessage());
				state.put("interactive_elements", "Error getting interactive elements: " + e.getMessage()
						+ ". You can continue with available page information (URL, title, tabs).");
			}

			return state;

		}
		catch (Exception e) {
			log.error("Failed to get browser state: {}", e.getMessage(), e);
			state.put("error", "Failed to get browser state: " + e.getMessage());
			return state;
		}
	}

	@Override
	public String getServiceGroup() {
		return "default-service-group";
	}

	@Override
	public String getName() {
		return name;
	}

	@Override
	public String getDescription() {
		return toolI18nService.getDescription("browser-use-tool");
	}

	@Override
	public String getParameters() {
		return toolI18nService.getParameters("browser-use-tool");
	}

	@Override
	public Class<BrowserRequestVO> getInputType() {
		return BrowserRequestVO.class;
	}

	@SuppressWarnings("unchecked")
	@Override
	public String getCurrentToolStateString() {
		// Only initialize browser if run method has been called at least once
		if (!hasRunAtLeastOnce) {
			return """
					Browser tool context is empty
					""";
		}

		try {
			DriverWrapper driver = getDriver();
			Map<String, Object> state = getCurrentState(driver.getCurrentPage());
			// Build URL and title information
			String urlInfo = String.format("\n   URL: %s\n   Title: %s", state.get("url"), state.get("title"));

			// Build tab information

			List<Map<String, Object>> tabs = (List<Map<String, Object>>) state.get("tabs");
			String tabsInfo = (tabs != null) ? String.format("\n   %d tab(s) available", tabs.size()) : "";
			if (tabs != null) {
				for (int i = 0; i < tabs.size(); i++) {
					Map<String, Object> tab = tabs.get(i);
					String tabUrl = (String) tab.get("url");
					String tabTitle = (String) tab.get("title");
					tabsInfo += String.format("\n   [%d] %s: %s", i, tabTitle, tabUrl);
				}
			}
			// Get scroll information
			Object scrollInfoObj = state.get("scroll_info");
			String contentAbove = "";
			String contentBelow = "";
			if (scrollInfoObj instanceof Map<?, ?> scrollInfoMap) {

				Map<String, Object> scrollInfo = (Map<String, Object>) scrollInfoMap;
				Object pixelsAboveObj = scrollInfo.get("pixels_above");
				Object pixelsBelowObj = scrollInfo.get("pixels_below");

				if (pixelsAboveObj instanceof Long pixelsAbove) {
					contentAbove = pixelsAbove > 0 ? String.format(" (%d pixels)", pixelsAbove) : "";
				}
				if (pixelsBelowObj instanceof Long pixelsBelow) {
					contentBelow = pixelsBelow > 0 ? String.format(" (%d pixels)", pixelsBelow) : "";
				}
			}

			// Get interactive element information
			String elementsInfo = (String) state.get("interactive_elements");

			// Build final status string
			String retString = String.format("""

					- Current URL and page title:
					%s

					- Available tabs:
					%s

					- Interactive elements and their indices:
					%s

					- Content above%s or below%s the viewport (if indicated)

					- Any action results or errors:
					%s
					""", urlInfo, tabsInfo, elementsInfo != null ? elementsInfo : "", contentAbove, contentBelow,
					state.containsKey("error") ? state.get("error") : "");

			return retString;
		}
		catch (Exception e) {
			// Handle any unexpected errors gracefully - return a valid state string
			// This ensures the flow continues even if state retrieval fails
			log.warn("Error getting browser tool state string (non-fatal): {}", e.getMessage(), e);
			return String.format("""
					Browser tool state retrieval encountered an error: %s
					You can continue with available browser information or try again.
					""", e.getMessage());
		}
	}

	// cleanup method already exists, just ensure it conforms to interface specification
	@Override
	public void cleanup(String planId) {
		if (planId != null) {
			log.info("Cleaning up Chrome resources for plan: {}", planId);
			this.chromeDriverService.closeDriverForPlan(planId);
		}
	}

	public LynxeProperties getLynxeProperties() {
		return (LynxeProperties) this.chromeDriverService.getLynxeProperties();
	}

	@Override
	public boolean isSelectable() {
		return true;
	}

}
