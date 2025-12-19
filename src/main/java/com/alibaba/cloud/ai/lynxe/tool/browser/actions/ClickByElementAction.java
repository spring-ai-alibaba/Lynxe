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

				// Special handling for checkbox inputs: try to find and click the
				// associated label
				// This is especially important for Element UI checkboxes where the input
				// is hidden
				Object checkboxCheckResult = locator
					.evaluate("el => el && el.tagName === 'INPUT' && el.type === 'checkbox'");
				Boolean isCheckbox = checkboxCheckResult instanceof Boolean ? (Boolean) checkboxCheckResult : null;
				if (Boolean.TRUE.equals(isCheckbox)) {
					log.debug("Element at index {} is a checkbox input, attempting to find associated label", index);

					// Use JavaScript to find and click the label element instead
					// This handles Element UI checkboxes where the input is hidden
					// and standard HTML checkboxes with label associations
					try {
						Object jsClickResult = locator.evaluate(
								"""
										(el) => {
											// First, try to find parent label (works for Element UI and standard HTML)
											let label = el.closest('label');

											// If not found, try to find label by 'for' attribute matching input's id
											if (!label && el.id) {
												label = document.querySelector('label[for="' + el.id + '"]');
											}

											// For Element UI, also check for parent with class 'el-checkbox'
											// This handles cases where label wraps the checkbox
											if (!label) {
												let parent = el.parentElement;
												while (parent && parent !== document.body) {
													if (parent.tagName === 'LABEL' && parent.classList.contains('el-checkbox')) {
														label = parent;
														break;
													}
													parent = parent.parentElement;
												}
											}

											// If label found, click it
											if (label) {
												label.click();
												return true;
											}
											return false;
										}
										""");
						Boolean labelClicked = jsClickResult instanceof Boolean ? (Boolean) jsClickResult : null;

						if (Boolean.TRUE.equals(labelClicked)) {
							log.debug("Successfully clicked label for checkbox using JavaScript");
							Thread.sleep(500);
							return;
						}
						else {
							log.debug("No associated label found for checkbox, will try clicking input directly");
						}
					}
					catch (Exception jsError) {
						log.debug("JavaScript label click failed, will try clicking input directly: {}",
								jsError.getMessage());
					}
				}

				// For other elements, use standard waiting strategy
				// Wait for element to be visible and enabled before clicking
				locator.waitFor(new Locator.WaitForOptions().setTimeout(elementTimeout)
					.setState(com.microsoft.playwright.options.WaitForSelectorState.VISIBLE));

				// Try to scroll element into view if needed (non-blocking)
				try {
					locator.scrollIntoViewIfNeeded(new Locator.ScrollIntoViewIfNeededOptions().setTimeout(3000));
					log.debug("Element scrolled into view successfully");
				}
				catch (com.microsoft.playwright.TimeoutError scrollError) {
					log.warn("Failed to scroll element into view, but will attempt to click anyway: {}",
							scrollError.getMessage());
				}

				// Check if element is visible and enabled
				if (!locator.isVisible()) {
					throw new RuntimeException("Element is not visible");
				}

				// Click with explicit timeout and force option
				locator.click(new Locator.ClickOptions().setTimeout(elementTimeout).setForce(false)); // Keep
																										// force=false
																										// to
																										// ensure
																										// element
																										// is
																										// truly
																										// clickable

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
