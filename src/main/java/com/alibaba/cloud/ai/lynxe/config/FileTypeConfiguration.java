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

import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;

/**
 * File Type Configuration Manager - Centrally manages all supported file types
 *
 * Advantages: 1. Centralized management: All file type configurations in one place 2.
 * Type classification: Manage file types by functional categories 3. Dynamic extension:
 * Easy to add new file types 4. Code reuse: Multiple components can share this
 * configuration
 */
@Component
public class FileTypeConfiguration {

	// Document files
	public static final Set<String> DOCUMENTS = Set.of("pdf", "txt", "md", "markdown", "rst", "adoc", "doc", "docx",
			"eml");

	// Spreadsheet files
	public static final Set<String> SPREADSHEETS = Set.of("xlsx", "xls", "csv", "tsv");

	// Programming files
	public static final Set<String> CODE_FILES = Set.of("java", "py", "js", "ts", "cpp", "c", "h", "go", "rs", "php",
			"rb", "swift", "kt", "scala");

	// Web files
	public static final Set<String> WEB_FILES = Set.of("html", "htm", "css", "scss", "sass", "less", "mhtml");

	// Configuration files
	public static final Set<String> CONFIG_FILES = Set.of("json", "xml", "yaml", "yml", "toml", "ini", "conf");

	// Other files
	public static final Set<String> OTHER_FILES = Set.of("log", "zip", "jpg", "jpeg", "png", "gif");

	// Blocked file types (security considerations)
	public static final Set<String> BLOCKED_FILES = Set.of("exe", "bat", "cmd", "com", "scr", "dll", "msi", "jar");

	// All supported file types
	public static final Set<String> ALL_SUPPORTED = Stream
		.of(DOCUMENTS, SPREADSHEETS, CODE_FILES, WEB_FILES, CONFIG_FILES, OTHER_FILES)
		.flatMap(Set::stream)
		.collect(Collectors.toSet());

	/**
	 * Check if file is allowed for upload
	 */
	public static boolean isUploadAllowed(String filename) {
		String extension = getFileExtension(filename);
		return !BLOCKED_FILES.contains(extension) && ALL_SUPPORTED.contains(extension);
	}

	/**
	 * Check if file is blocked
	 */
	public static boolean isBlocked(String filename) {
		String extension = getFileExtension(filename);
		return BLOCKED_FILES.contains(extension);
	}

	/**
	 * Get file type category
	 */
	public static String getFileCategory(String filename) {
		String extension = getFileExtension(filename);

		if (DOCUMENTS.contains(extension))
			return "Document";
		if (SPREADSHEETS.contains(extension))
			return "Spreadsheet";
		if (CODE_FILES.contains(extension))
			return "Code";
		if (WEB_FILES.contains(extension))
			return "Web";
		if (CONFIG_FILES.contains(extension))
			return "Config";
		if (OTHER_FILES.contains(extension))
			return "Other";
		if (BLOCKED_FILES.contains(extension))
			return "Blocked";

		return "Unknown";
	}

	/**
	 * Get file extension (without dot, lowercase)
	 */
	private static String getFileExtension(String filename) {
		if (filename == null || filename.isEmpty()) {
			return "";
		}

		int lastDotIndex = filename.lastIndexOf('.');
		if (lastDotIndex == -1 || lastDotIndex == filename.length() - 1) {
			return "";
		}

		return filename.substring(lastDotIndex + 1).toLowerCase();
	}

	/**
	 * Get all supported file extensions (for frontend display)
	 */
	public static String getSupportedExtensionsString() {
		return ALL_SUPPORTED.stream().map(ext -> "." + ext).sorted().collect(Collectors.joining(", "));
	}

	/**
	 * Get all blocked file extensions (for frontend display)
	 */
	public static String getBlockedExtensionsString() {
		return BLOCKED_FILES.stream().map(ext -> "." + ext).sorted().collect(Collectors.joining(", "));
	}

}
