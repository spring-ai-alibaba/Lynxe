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
package com.alibaba.cloud.ai.lynxe.tool.browser.actions;

import com.alibaba.cloud.ai.lynxe.tool.browser.BrowserUseTool;
import com.alibaba.cloud.ai.lynxe.tool.code.ToolExecuteResult;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;

public class ClickByElementAction extends BrowserAction {

	private final static org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ClickByElementAction.class);

	public ClickByElementAction(BrowserUseTool browserUseTool) {
		super(browserUseTool);
	}

	@Override
	public ToolExecuteResult execute(BrowserRequestVO request) throws Exception {
		Integer index = request.getIndex();
		if (index == null) {
			return new ToolExecuteResult("Index is required for 'click' action");
		}

		// Check if element exists
		if (!elementExistsByIdx(index)) {
			return new ToolExecuteResult("Element with index " + index + " not found in ARIA snapshot");
		}

		Page page = getCurrentPage();
		Locator locator = getLocatorByIdx(index);
		if (locator == null) {
			return new ToolExecuteResult("Failed to create locator for element with index " + index);
		}

		String clickResultMessage = clickAndSwitchToNewTabIfOpened(page, () -> {
			try {
				// Use a reasonable timeout for element operations (max 10 seconds)
				int elementTimeout = getElementTimeoutMs();
				log.debug("Using element timeout: {}ms for click operations", elementTimeout);

				// For other elements, use standard waiting strategy
				// Wait for element to be visible and enabled before clicking
				locator.waitFor(new Locator.WaitForOptions()
						.setTimeout(elementTimeout)
						.setState(com.microsoft.playwright.options.WaitForSelectorState.VISIBLE));

				// Try to scroll element into view if needed (non-blocking)
				try {
					locator.scrollIntoViewIfNeeded(new Locator.ScrollIntoViewIfNeededOptions().setTimeout(3000));
					log.debug("Element scrolled into view successfully");
				} catch (com.microsoft.playwright.TimeoutError scrollError) {
					log.warn("Failed to scroll element into view, but will attempt to click anyway: {}", scrollError.getMessage());
				}

				// Check if element is visible and enabled
				if (!locator.isVisible()) {
					throw new RuntimeException("Element is not visible");
				}

				// Click with explicit timeout and force option
				locator.click(new Locator.ClickOptions()
						.setTimeout(elementTimeout)
						.setForce(false)); // Keep force=false to ensure element is truly clickable

				// Add small delay to ensure the action is processed
				Thread.sleep(500);

			}
			catch (com.microsoft.playwright.TimeoutError e) {
				log.error("Timeout waiting for element with idx {} to be ready for click: {}", index, e.getMessage());
				throw new RuntimeException("Timeout waiting for element to be ready for click: " + e.getMessage(), e);
			}
			catch (Exception e) {
				log.error("Error during click on element with idx {}: {}", index, e.getMessage());
				if (e instanceof RuntimeException) {
					throw (RuntimeException) e;
				}
				throw new RuntimeException("Error clicking element: " + e.getMessage(), e);
			}
		});
		return new ToolExecuteResult("Successfully clicked element at index " + index + " " + clickResultMessage);
	}

}
