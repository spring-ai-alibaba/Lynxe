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

import java.net.URI;
import java.net.URISyntaxException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.microsoft.playwright.Page;

public class AriaElementHelper {

	private static final Logger log = LoggerFactory.getLogger(AriaElementHelper.class);

	/**
	 * Replace aria-id-num patterns with [idx=num] in ARIA snapshot text This method
	 * processes the snapshot text and replaces patterns like: - button "aria-id-1" ->
	 * button [idx=1] - link "aria-id-5" [checked] -> link [idx=5] [checked]
	 * @param snapshot The original ARIA snapshot text
	 * @return The snapshot text with aria-id-num replaced by [idx=num]
	 */
	public static String replaceAriaIdWithIdx(String snapshot) {
		if (snapshot == null || snapshot.isEmpty()) {
			return snapshot;
		}

		// Pattern to match: role "aria-id-N" followed by optional attributes
		// This will match lines like:
		// - button "aria-id-1"
		// - link "aria-id-5" [checked]
		// - textbox "aria-id-10" [disabled] [required]
		// The pattern captures: role, number, and any trailing attributes
		Pattern pattern = Pattern.compile("(\\w+)\\s+\"aria-id-(\\d+)\"((?:\\s+\\[[^\\]]+\\])*)");
		Matcher matcher = pattern.matcher(snapshot);

		StringBuffer result = new StringBuffer();
		while (matcher.find()) {
			String role = matcher.group(1);
			String num = matcher.group(2);
			String attributes = matcher.group(3); // May be null or empty
			// Replace "aria-id-N" with [idx=N], preserving any existing attributes
			String replacement = role + " [idx=" + num + "]" + (attributes != null ? attributes : "");
			matcher.appendReplacement(result, replacement);
		}
		matcher.appendTail(result);

		return result.toString();
	}

	/**
	 * Parse page, replace aria-id-num with [idx=num], and return processed snapshot This
	 * method encapsulates the common pattern of parsing a page and preparing it for use
	 * @param page The page to parse
	 * @param options Snapshot options (if null, default options will be used)
	 * @param compressUrl If true, compress URLs in the snapshot (replace with short URLs)
	 * @param shortUrlService ShortUrlService instance to store URL mappings (required if
	 * compressUrl is true)
	 * @param rootPlanId Root plan ID for URL lifetime management
	 * @return Processed YAML snapshot string with aria-id-num replaced by [idx=num], or
	 * null if parsing failed
	 */
	public static String parsePageAndAssignRefs(Page page, AriaSnapshotOptions options, boolean compressUrl,
			com.alibaba.cloud.ai.lynxe.tool.shortUrl.ShortUrlService shortUrlService, String rootPlanId) {
		if (page == null) {
			log.warn("Cannot parse null page");
			return null;
		}

		try {
			// Use default options if none provided
			if (options == null) {
				options = new AriaSnapshotOptions().setSelector("body").setTimeout(30000);
			}

			// Generate ARIA snapshot (now returns error message string on timeout instead
			// of throwing)
			String snapshot = AriaSnapshot.ariaSnapshot(page, options);
			if (snapshot != null && !snapshot.isEmpty()) {
				// Check if snapshot is an error message (contains timeout or error
				// keywords)
				// If it's an error message, return it directly without processing
				if (snapshot.contains("timed out") || snapshot.contains("Failed to generate ARIA snapshot")) {
					log.debug("ARIA snapshot returned error message, returning as-is");
					return snapshot;
				}

				// Replace aria-id-num with [idx=num] in the snapshot text
				String processedSnapshot = replaceAriaIdWithIdx(snapshot);

				// If compressUrl is true, compress URLs in the snapshot
				if (compressUrl) {
					if (shortUrlService == null) {
						log.warn("compressUrl is true but shortUrlService is null, skipping URL compression");
					}
					else {
						processedSnapshot = compressUrlsInSnapshot(processedSnapshot, page, shortUrlService,
								rootPlanId);
					}
				}

				log.debug("Replaced aria-id-num patterns with [idx=num] in snapshot");
				return processedSnapshot;
			}
		}
		catch (Exception e) {
			// This catch block should rarely be hit now since AriaSnapshot returns error
			// messages
			// instead of throwing exceptions, but keep it for safety
			log.warn("Failed to parse page and assign refs: {}", e.getMessage());
			return String.format("Error parsing page: %s. You can continue with available page information.",
					e.getMessage());
		}

		return null;
	}

	/**
	 * Compress URLs in snapshot: replace with short URLs (http://s@Url.a/1, etc.)
	 * @param snapshot The snapshot text
	 * @param page The page to get current URL from
	 * @param shortUrlService ShortUrlService instance to store URL mappings
	 * @param rootPlanId Root plan ID for URL lifetime management
	 * @return Snapshot with URLs compressed
	 */
	private static String compressUrlsInSnapshot(String snapshot, Page page,
			com.alibaba.cloud.ai.lynxe.tool.shortUrl.ShortUrlService shortUrlService, String rootPlanId) {
		if (snapshot == null || snapshot.isEmpty() || page == null || shortUrlService == null) {
			return snapshot;
		}

		try {
			// Get current page URL to resolve relative URLs
			String currentUrl = page.url();
			if (currentUrl == null || currentUrl.isEmpty()) {
				log.warn("Cannot compress URLs: current page URL is null or empty");
				return snapshot;
			}

			// Pattern to match /url: followed by space and any characters
			Pattern urlPattern = Pattern.compile("(/url:\\s+)([^\\s<>\"'\\[\\]{}|\\\\^`\\n]+)",
					Pattern.CASE_INSENSITIVE);
			Matcher matcher = urlPattern.matcher(snapshot);

			StringBuffer result = new StringBuffer();
			while (matcher.find()) {
				String prefix = matcher.group(1);
				String url = matcher.group(2);

				// Skip empty URLs
				if (url == null || url.trim().isEmpty() || url.equals("\"\"")) {
					matcher.appendReplacement(result, Matcher.quoteReplacement(matcher.group(0)));
					continue;
				}

				// Resolve relative URLs to absolute URLs
				String absoluteUrl = resolveUrl(url, currentUrl);

				// Get or create short URL from ShortUrlService (always use rootPlanId)
				String shortUrl = shortUrlService.addUrlMapping(rootPlanId, absoluteUrl);

				// Replace with short URL
				matcher.appendReplacement(result, Matcher.quoteReplacement(prefix + shortUrl));
			}
			matcher.appendTail(result);

			log.debug("Compressed URLs in snapshot using http://s@Url.a/ format");
			return result.toString();
		}
		catch (Exception e) {
			log.warn("Failed to compress URLs in snapshot: {}", e.getMessage());
			return snapshot; // Return original snapshot on error
		}
	}

	/**
	 * Resolve relative URL to absolute URL
	 * @param url The URL (may be relative or absolute)
	 * @param baseUrl The base URL to resolve relative URLs against
	 * @return Absolute URL
	 */
	private static String resolveUrl(String url, String baseUrl) {
		if (url == null || url.isEmpty()) {
			return url;
		}

		// If already absolute URL, return as is
		if (url.startsWith("http://") || url.startsWith("https://")) {
			return url;
		}

		// Resolve relative URL
		try {
			URI baseUri = new URI(baseUrl);
			URI resolvedUri = baseUri.resolve(url);
			return resolvedUri.toString();
		}
		catch (URISyntaxException e) {
			log.warn("Failed to resolve URL {} against base {}: {}", url, baseUrl, e.getMessage());
			return url; // Return original if resolution fails
		}
	}

}
