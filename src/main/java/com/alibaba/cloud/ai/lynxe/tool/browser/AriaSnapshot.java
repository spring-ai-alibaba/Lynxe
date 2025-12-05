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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.TimeoutError;

/**
 * Utility class for generating ARIA snapshots of pages, using Playwright's native
 * ariaSnapshot functionality similar to Playwright's internal implementation.
 *
 * <p>
 * Usage:
 * </p>
 * <pre>{@code
 * // Get ARIA snapshot of a page
 * String snapshot = AriaSnapshot.ariaSnapshot(page);
 *
 * // Or with options
 * AriaSnapshotOptions options = new AriaSnapshotOptions()
 *     .setSelector("body")
 *     .setTimeout(30000);
 * String snapshot = AriaSnapshot.ariaSnapshot(page, options);
 * }</pre>
 */
public class AriaSnapshot {

	private static final Logger log = LoggerFactory.getLogger(AriaSnapshot.class);

	/**
	 * Generate ARIA snapshot of a page using default options
	 * @param page The page to snapshot
	 * @return ARIA snapshot as string
	 */
	public static String ariaSnapshot(Page page) {
		return ariaSnapshot(page, null);
	}

	/**
	 * Generate ARIA snapshot of a page using Playwright's native ariaSnapshot method This
	 * follows the same pattern as Playwright's internal implementation using
	 * frame.sendMessage("ariaSnapshot", params, frame.timeout(options.timeout))
	 * @param page The page to snapshot
	 * @param options Snapshot options
	 * @return ARIA snapshot as string
	 */
	public static String ariaSnapshot(Page page, AriaSnapshotOptions options) {
		if (page == null) {
			throw new IllegalArgumentException("Page cannot be null");
		}

		if (options == null) {
			options = new AriaSnapshotOptions();
		}

		try {
			log.debug("Generating ARIA snapshot with selector: {}, timeout: {}", options.getSelector(),
					options.getTimeout());

			// Inject unique data-aria-id IDs into all elements before taking snapshot
			// Force replace data-aria-id to ensure consistent IDs
			page.evaluate("""
					(() => {
						const elements = document.querySelectorAll('*');
						let counter = 1;
						elements.forEach((el) => {
							el.setAttribute('aria-label', 'aria-id-' + counter);
							counter++;
						});
					})();
					""");

			// Wait for selector if timeout is specified (similar to frame.timeout in
			// Playwright)
			if (options.getTimeout() != null && options.getTimeout() > 0) {
				try {
					page.waitForSelector(options.getSelector(),
							new Page.WaitForSelectorOptions().setTimeout(options.getTimeout()));
				}
				catch (Exception e) {
					log.warn("Selector wait timeout or failed, continuing anyway: {}", e.getMessage());
				}
			}

			// Use Playwright's native locator.ariaSnapshot() method
			// This internally uses frame.sendMessage("ariaSnapshot", params,
			// frame.timeout(options.timeout))
			Locator locator = page.locator(options.getSelector());

			// Call Playwright's native ariaSnapshot method
			String snapshot = locator.ariaSnapshot();
			return snapshot != null ? snapshot : "";
		}
		catch (TimeoutError e) {
			// Handle timeout gracefully - return error message instead of throwing
			// exception
			// This allows the flow to continue without interruption
			String timeoutMessage = String.format(
					"ARIA snapshot generation timed out after %dms. The page may be too complex or still loading. "
							+ "You can continue with the available page information (URL, title, tabs).",
					options.getTimeout() != null ? options.getTimeout() : 30000);
			log.warn("ARIA snapshot timeout (non-fatal): {}", e.getMessage());
			return timeoutMessage;
		}
		catch (Exception e) {
			// For other exceptions, also return error message instead of throwing
			// This ensures the flow continues even if snapshot generation fails
			String errorMessage = String.format(
					"Failed to generate ARIA snapshot: %s. You can continue with the available page information (URL, title, tabs).",
					e.getMessage());
			log.warn("ARIA snapshot generation failed (non-fatal): {}", e.getMessage());
			return errorMessage;
		}
	}

}
