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
package com.alibaba.cloud.ai.lynxe.tool.textOperator;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.cloud.ai.lynxe.tool.AbstractBaseTool;
import com.alibaba.cloud.ai.lynxe.tool.code.ToolExecuteResult;
import com.alibaba.cloud.ai.lynxe.tool.i18n.ToolI18nService;
import com.alibaba.cloud.ai.lynxe.tool.innerStorage.SmartContentSavingService;
import com.alibaba.cloud.ai.lynxe.tool.shortUrl.ShortUrlService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Global file operator that performs operations on files. This operator provides access
 * to files that can be accessed across all sub-plans within the same execution context.
 *
 * Keywords: global files, root directory, root folder, root plan directory, global file
 * operations, root file access, cross-plan files.
 *
 * Use this tool for operations on global files, root directory files, or root folder
 * files.
 */
public class GlobalFileOperator extends AbstractBaseTool<GlobalFileOperator.GlobalFileInput> {

	private static final Logger log = LoggerFactory.getLogger(GlobalFileOperator.class);

	private static final String TOOL_NAME = "global_file_operator";

	/**
	 * Set of supported text file extensions
	 */
	private static final Set<String> SUPPORTED_EXTENSIONS = new HashSet<>(Set.of(".txt", ".md", ".markdown", // Plain
																												// text
																												// and
																												// Markdown
			".java", ".py", ".js", ".ts", ".jsx", ".tsx", // Common programming languages
			".html", ".htm", ".mhtml", ".css", ".scss", ".sass", ".less", // Web-related
			".xml", ".json", ".yaml", ".yml", ".properties", // Configuration files
			".sql", ".sh", ".bat", ".cmd", // Scripts and database
			".log", ".conf", ".ini", // Logs and configuration
			".gradle", ".pom", ".mvn", // Build tools
			".csv", ".rst", ".adoc", // Documentation and data
			".cpp", ".c", ".h", ".go", ".rs", ".php", ".rb", ".swift", ".kt", ".scala" // Additional
																						// programming
																						// languages
	));

	/**
	 * Input class for global file operations
	 */
	public static class GlobalFileInput {

		private String action;

		@com.fasterxml.jackson.annotation.JsonProperty("file_path")
		private String filePath;

		private String content;

		@com.fasterxml.jackson.annotation.JsonProperty("source_text")
		private String sourceText;

		@com.fasterxml.jackson.annotation.JsonProperty("target_text")
		private String targetText;

		@com.fasterxml.jackson.annotation.JsonProperty("start_line")
		private Integer startLine;

		@com.fasterxml.jackson.annotation.JsonProperty("end_line")
		private Integer endLine;

		@com.fasterxml.jackson.annotation.JsonProperty("pattern")
		private String pattern;

		@com.fasterxml.jackson.annotation.JsonProperty("case_sensitive")
		private Boolean caseSensitive;

		@com.fasterxml.jackson.annotation.JsonProperty("whole_word")
		private Boolean wholeWord;

		@com.fasterxml.jackson.annotation.JsonProperty("context_lines")
		private Integer contextLines;

		// Getters and setters
		public String getAction() {
			return action;
		}

		public void setAction(String action) {
			this.action = action;
		}

		public String getFilePath() {
			return filePath;
		}

		public void setFilePath(String filePath) {
			this.filePath = filePath;
		}

		public String getContent() {
			return content;
		}

		public void setContent(String content) {
			this.content = content;
		}

		public String getSourceText() {
			return sourceText;
		}

		public void setSourceText(String sourceText) {
			this.sourceText = sourceText;
		}

		public String getTargetText() {
			return targetText;
		}

		public void setTargetText(String targetText) {
			this.targetText = targetText;
		}

		public Integer getStartLine() {
			return startLine;
		}

		public void setStartLine(Integer startLine) {
			this.startLine = startLine;
		}

		public Integer getEndLine() {
			return endLine;
		}

		public void setEndLine(Integer endLine) {
			this.endLine = endLine;
		}

		public String getPattern() {
			return pattern;
		}

		public void setPattern(String pattern) {
			this.pattern = pattern;
		}

		public Boolean getCaseSensitive() {
			return caseSensitive;
		}

		public void setCaseSensitive(Boolean caseSensitive) {
			this.caseSensitive = caseSensitive;
		}

		public Boolean getWholeWord() {
			return wholeWord;
		}

		public void setWholeWord(Boolean wholeWord) {
			this.wholeWord = wholeWord;
		}

		public Integer getContextLines() {
			return contextLines;
		}

		public void setContextLines(Integer contextLines) {
			this.contextLines = contextLines;
		}

	}

	private final TextFileService textFileService;

	private final SmartContentSavingService innerStorageService;

	private final ObjectMapper objectMapper;

	private final ShortUrlService shortUrlService;

	private final ToolI18nService toolI18nService;

	public GlobalFileOperator(TextFileService textFileService, SmartContentSavingService innerStorageService,
			ObjectMapper objectMapper, ShortUrlService shortUrlService, ToolI18nService toolI18nService) {
		this.textFileService = textFileService;
		this.innerStorageService = innerStorageService;
		this.objectMapper = objectMapper;
		this.shortUrlService = shortUrlService;
		this.toolI18nService = toolI18nService;
	}

	public ToolExecuteResult run(String toolInput) {
		log.info("GlobalFileOperator toolInput: {}", toolInput);
		try {
			Map<String, Object> toolInputMap = objectMapper.readValue(toolInput,
					new TypeReference<Map<String, Object>>() {
					});

			String action = (String) toolInputMap.get("action");
			String filePath = (String) toolInputMap.get("file_path");

			// Basic parameter validation
			if (action == null) {
				return new ToolExecuteResult("Error: action parameter is required");
			}
			// file_path is optional for list_files action
			if (filePath == null && !"list_files".equals(action)) {
				return new ToolExecuteResult("Error: file_path parameter is required");
			}

			return switch (action) {
				case "replace" -> {
					String sourceText = (String) toolInputMap.get("source_text");
					String targetText = (String) toolInputMap.get("target_text");

					if (sourceText == null || targetText == null) {
						yield new ToolExecuteResult(
								"Error: replace operation requires source_text and target_text parameters");
					}

					// Replace short URLs in sourceText and targetText
					sourceText = replaceShortUrls(sourceText);
					targetText = replaceShortUrls(targetText);

					yield replaceText(filePath, sourceText, targetText);
				}
				case "get_text" -> {
					Integer startLine = (Integer) toolInputMap.get("start_line");
					Integer endLine = (Integer) toolInputMap.get("end_line");

					if (startLine == null || endLine == null) {
						yield new ToolExecuteResult(
								"Error: get_text operation requires start_line and end_line parameters");
					}

					yield getTextByLines(filePath, startLine, endLine);
				}
				case "get_all_text" -> getAllText(filePath);
				case "append" -> {
					String appendContent = (String) toolInputMap.get("content");

					if (appendContent == null) {
						yield new ToolExecuteResult("Error: append operation requires content parameter");
					}

					// Replace short URLs in appendContent
					appendContent = replaceShortUrls(appendContent);

					yield appendToFile(filePath, appendContent);
				}
				case "delete" -> deleteFile(filePath);
				case "count_words" -> countWords(filePath);
				case "list_files" -> listFiles(filePath != null ? filePath : "");
				case "grep" -> {
					String pattern = (String) toolInputMap.get("pattern");
					Boolean caseSensitive = (Boolean) toolInputMap.get("case_sensitive");
					Boolean wholeWord = (Boolean) toolInputMap.get("whole_word");
					Integer contextLines = (Integer) toolInputMap.get("context_lines");

					if (pattern == null) {
						yield new ToolExecuteResult("Error: grep operation requires pattern parameter");
					}

					// Replace short URLs in pattern
					pattern = replaceShortUrls(pattern);

					yield grepText(filePath, pattern, caseSensitive != null ? caseSensitive : false,
							wholeWord != null ? wholeWord : false, contextLines != null ? contextLines : 0);
				}
				default -> new ToolExecuteResult("Unknown operation: " + action
						+ ". Supported operations: replace, get_text, get_all_text, append, delete, count_words, list_files, grep");
			};
		}
		catch (Exception e) {
			log.error("GlobalFileOperator execution failed", e);
			return new ToolExecuteResult("Tool execution failed: " + e.getMessage());
		}
	}

	@Override
	public ToolExecuteResult run(GlobalFileInput input) {
		log.info("GlobalFileOperator input: action={}, filePath={}", input.getAction(), input.getFilePath());
		try {
			String action = input.getAction();
			String filePath = input.getFilePath();

			// Basic parameter validation
			if (action == null) {
				return new ToolExecuteResult("Error: action parameter is required");
			}
			// file_path is optional for list_files action
			if (filePath == null && !"list_files".equals(action)) {
				return new ToolExecuteResult("Error: file_path parameter is required");
			}

			// Replace short URLs in filePath
			filePath = replaceShortUrls(filePath);

			return switch (action) {
				case "replace" -> {
					String sourceText = input.getSourceText();
					String targetText = input.getTargetText();

					if (sourceText == null || targetText == null) {
						yield new ToolExecuteResult(
								"Error: replace operation requires source_text and target_text parameters");
					}

					// Replace short URLs in sourceText and targetText
					sourceText = replaceShortUrls(sourceText);
					targetText = replaceShortUrls(targetText);

					yield replaceText(filePath, sourceText, targetText);
				}
				case "get_text" -> {
					Integer startLine = input.getStartLine();
					Integer endLine = input.getEndLine();

					if (startLine == null || endLine == null) {
						yield new ToolExecuteResult(
								"Error: get_text operation requires start_line and end_line parameters");
					}

					yield getTextByLines(filePath, startLine, endLine);
				}
				case "get_all_text" -> getAllText(filePath);
				case "append" -> {
					String appendContent = input.getContent();

					if (appendContent == null) {
						yield new ToolExecuteResult("Error: append operation requires content parameter");
					}

					// Replace short URLs in appendContent
					appendContent = replaceShortUrls(appendContent);

					yield appendToFile(filePath, appendContent);
				}
				case "delete" -> deleteFile(filePath);
				case "count_words" -> countWords(filePath);
				case "list_files" -> listFiles(filePath != null ? filePath : "");
				case "grep" -> {
					String pattern = input.getPattern();
					Boolean caseSensitive = input.getCaseSensitive();
					Boolean wholeWord = input.getWholeWord();
					Integer contextLines = input.getContextLines();

					if (pattern == null) {
						yield new ToolExecuteResult("Error: grep operation requires pattern parameter");
					}

					// Replace short URLs in pattern
					pattern = replaceShortUrls(pattern);

					yield grepText(filePath, pattern, caseSensitive != null ? caseSensitive : false,
							wholeWord != null ? wholeWord : false, contextLines != null ? contextLines : 0);
				}
				default -> new ToolExecuteResult("Unknown operation: " + action
						+ ". Supported operations: replace, get_text, get_all_text, append, delete, count_words, list_files, grep");
			};
		}
		catch (Exception e) {
			log.error("GlobalFileOperator execution failed", e);
			return new ToolExecuteResult("Tool execution failed: " + e.getMessage());
		}
	}

	/**
	 * Replace short URLs in a string with real URLs
	 * @param text The text that may contain short URLs
	 * @return The text with short URLs replaced by real URLs
	 */
	private String replaceShortUrls(String text) {
		if (text == null || text.isEmpty() || this.rootPlanId == null || this.rootPlanId.isEmpty()
				|| this.shortUrlService == null) {
			return text;
		}

		// Check if short URL feature is enabled
		Boolean enableShortUrl = textFileService.getLynxeProperties().getEnableShortUrl();
		if (enableShortUrl == null || !enableShortUrl) {
			return text; // Skip replacement if disabled
		}

		// Pattern to match short URLs: http://s@Url.a/ followed by digits
		Pattern shortUrlPattern = Pattern.compile(Pattern.quote(ShortUrlService.SHORT_URL_PREFIX) + "\\d+");
		Matcher matcher = shortUrlPattern.matcher(text);
		StringBuffer result = new StringBuffer();

		while (matcher.find()) {
			String shortUrl = matcher.group();
			String realUrl = shortUrlService.getRealUrl(this.rootPlanId, shortUrl);
			if (realUrl != null) {
				matcher.appendReplacement(result, Matcher.quoteReplacement(realUrl));
				log.debug("Replaced short URL {} with real URL {}", shortUrl, realUrl);
			}
			else {
				log.warn("Short URL not found in mapping: {}", shortUrl);
				// Keep the short URL if mapping not found
				matcher.appendReplacement(result, Matcher.quoteReplacement(shortUrl));
			}
		}
		matcher.appendTail(result);

		return result.toString();
	}

	/**
	 * Normalize file path by removing plan ID prefixes and relative path indicators
	 */
	private String normalizeFilePath(String filePath) {
		if (filePath == null || filePath.isEmpty()) {
			return filePath;
		}

		// Remove leading slashes and relative path indicators
		String normalized = filePath.trim();
		while (normalized.startsWith("/")) {
			normalized = normalized.substring(1);
		}

		// Remove "./" prefix if present
		if (normalized.startsWith("./")) {
			normalized = normalized.substring(2);
		}

		// Remove plan ID prefix (e.g., "plan-1763035234741/")
		if (normalized.matches("^plan-[^/]+/.*")) {
			normalized = normalized.replaceFirst("^plan-[^/]+/", "");
		}

		return normalized;
	}

	/**
	 * Validate and get the absolute path within the plan/subplan directory
	 */
	private Path validateGlobalPath(String filePath) throws IOException {
		if (this.rootPlanId == null || this.rootPlanId.isEmpty()) {
			throw new IOException("Error: rootPlanId is required for global file operations but is null or empty");
		}

		// Normalize the file path to remove plan ID prefixes
		String normalizedPath = normalizeFilePath(filePath);

		// Check file type for non-directory operations
		if (!normalizedPath.isEmpty() && !normalizedPath.endsWith("/") && !isSupportedFileType(normalizedPath)) {
			throw new IOException("Unsupported file type. Only text-based files are supported.");
		}

		// Get root plan directory
		Path rootPlanDirectory = textFileService.getRootPlanDirectory(this.rootPlanId);

		// For GlobalFileOperator, check root plan directory first, then subplan directory
		// if applicable
		// This allows accessing files in root plan directory even when in subplan context
		Path rootPlanPath = rootPlanDirectory.resolve(normalizedPath).normalize();

		// Ensure root plan path stays within root plan directory
		if (!rootPlanPath.startsWith(rootPlanDirectory)) {
			throw new IOException("Access denied: Invalid file path");
		}

		// If file exists in root plan directory, use it
		if (Files.exists(rootPlanPath)) {
			return rootPlanPath;
		}

		// If currentPlanId exists and differs from rootPlanId, check subplan directory
		if (this.currentPlanId != null && !this.currentPlanId.isEmpty()
				&& !this.currentPlanId.equals(this.rootPlanId)) {
			Path subplanDirectory = rootPlanDirectory.resolve(this.currentPlanId);
			Path subplanPath = subplanDirectory.resolve(normalizedPath).normalize();

			// Ensure subplan path stays within subplan directory
			if (!subplanPath.startsWith(subplanDirectory)) {
				throw new IOException("Access denied: Invalid file path");
			}

			// If file exists in subplan directory, use it
			if (Files.exists(subplanPath)) {
				return subplanPath;
			}
		}

		// If file doesn't exist in either location, return root plan path for creation
		// (new files are created in root plan directory)
		return rootPlanPath;
	}

	/**
	 * Check if file type is supported
	 */
	private boolean isSupportedFileType(String filePath) {
		if (filePath == null || filePath.isEmpty()) {
			return false;
		}

		String extension = getFileExtension(filePath);
		return SUPPORTED_EXTENSIONS.contains(extension.toLowerCase());
	}

	/**
	 * Get file extension
	 */
	private String getFileExtension(String filePath) {
		if (filePath == null || filePath.isEmpty()) {
			return "";
		}

		int lastDotIndex = filePath.lastIndexOf('.');
		if (lastDotIndex == -1 || lastDotIndex == filePath.length() - 1) {
			return "";
		}

		return filePath.substring(lastDotIndex);
	}

	/**
	 * Delete a file
	 */
	private ToolExecuteResult deleteFile(String filePath) {
		try {
			Path absolutePath = validateGlobalPath(filePath);

			if (!Files.exists(absolutePath)) {
				return new ToolExecuteResult("Error: File does not exist: " + filePath);
			}

			Files.delete(absolutePath);

			log.info("Deleted file: {}", absolutePath);
			return new ToolExecuteResult("File deleted successfully: " + filePath);
		}
		catch (IOException e) {
			log.error("Error deleting file: {}", filePath, e);
			return new ToolExecuteResult("Error deleting file: " + e.getMessage());
		}
	}

	/**
	 * List files in the plan/subplan directory
	 */
	private ToolExecuteResult listFiles(String directoryPath) {
		try {
			if (this.rootPlanId == null || this.rootPlanId.isEmpty()) {
				return new ToolExecuteResult("Error: rootPlanId is required for global file operations");
			}

			// Normalize the directory path to remove plan ID prefixes
			String normalizedPath = normalizeFilePath(directoryPath != null ? directoryPath : "");

			// For list_files, always use root plan directory as the base
			// This allows listing directories like "linked_external" that exist at root
			// plan level
			Path rootPlanDirectory = textFileService.getRootPlanDirectory(this.rootPlanId);

			// If a subdirectory path is provided, resolve it within root plan directory
			Path targetDirectory = rootPlanDirectory;
			if (normalizedPath != null && !normalizedPath.isEmpty() && !normalizedPath.equals(".")
					&& !normalizedPath.equals("root")) {
				targetDirectory = rootPlanDirectory.resolve(normalizedPath).normalize();

				// Ensure the target directory stays within root plan directory
				if (!targetDirectory.startsWith(rootPlanDirectory)) {
					return new ToolExecuteResult("Error: Directory path is invalid");
				}
			}

			// Ensure directory exists - create if needed for root plan directory
			if (!Files.exists(targetDirectory)) {
				if (normalizedPath == null || normalizedPath.isEmpty() || normalizedPath.equals(".")
						|| normalizedPath.equals("root")) {
					// Create root plan directory if it doesn't exist
					Files.createDirectories(targetDirectory);
				}
				else {
					return new ToolExecuteResult("Error: Directory does not exist: " + normalizedPath);
				}
			}

			if (!Files.isDirectory(targetDirectory)) {
				return new ToolExecuteResult("Error: Path is not a directory: " + normalizedPath);
			}

			StringBuilder result = new StringBuilder();
			result.append("Files: \n");
			if (normalizedPath != null && !normalizedPath.isEmpty() && !normalizedPath.equals(".")
					&& !normalizedPath.equals("root")) {
				result.append(normalizedPath).append("\n");
			}

			java.util.List<Path> files = Files.list(targetDirectory).sorted().toList();

			if (files.isEmpty()) {
				result.append("(empty directory)\n");
			}
			else {
				for (Path path : files) {
					try {
						String fileName = path.getFileName().toString();
						if (Files.isDirectory(path)) {
							result.append(String.format("📁 %s/\n", fileName));
						}
						else {
							long size = Files.size(path);
							String sizeStr = formatFileSize(size);
							result.append(String.format("📄 %s (%s)\n", fileName, sizeStr));
						}
					}
					catch (IOException e) {
						result.append(String.format("❌ %s (error reading)\n", path.getFileName()));
					}
				}
			}

			return new ToolExecuteResult(result.toString());
		}
		catch (IOException e) {
			String pathToLog = normalizeFilePath(directoryPath != null ? directoryPath : "");
			log.error("Error listing files: {}", pathToLog, e);
			return new ToolExecuteResult("Error listing files: " + e.getMessage());
		}
	}

	/**
	 * Format file size in human-readable format
	 */
	private String formatFileSize(long size) {
		if (size < 1024)
			return size + " B";
		if (size < 1024 * 1024)
			return String.format("%.1f KB", size / 1024.0);
		if (size < 1024 * 1024 * 1024)
			return String.format("%.1f MB", size / (1024.0 * 1024));
		return String.format("%.1f GB", size / (1024.0 * 1024 * 1024));
	}

	/**
	 * Replace text in file
	 */
	private ToolExecuteResult replaceText(String filePath, String sourceText, String targetText) {
		try {
			Path absolutePath = validateGlobalPath(filePath);

			// Create file if it doesn't exist
			if (!Files.exists(absolutePath)) {
				Files.createDirectories(absolutePath.getParent());
				Files.createFile(absolutePath);
				log.info("Created new file automatically: {}", absolutePath);
			}

			String content = Files.readString(absolutePath);
			String newContent = content.replace(sourceText, targetText);
			Files.writeString(absolutePath, newContent);

			// Force flush to disk
			try (FileChannel channel = FileChannel.open(absolutePath, StandardOpenOption.WRITE)) {
				channel.force(true);
			}

			log.info("Text replaced in file: {}", absolutePath);
			return new ToolExecuteResult("Text replaced successfully in file: " + filePath);
		}
		catch (IOException e) {
			log.error("Error replacing text in file: {}", filePath, e);
			return new ToolExecuteResult("Error replacing text in file: " + e.getMessage());
		}
	}

	/**
	 * Get text by line range
	 */
	private ToolExecuteResult getTextByLines(String filePath, Integer startLine, Integer endLine) {
		try {
			// Parameter validation
			if (startLine < 1 || endLine < 1) {
				return new ToolExecuteResult("Error: Line numbers must start from 1");
			}
			if (startLine > endLine) {
				return new ToolExecuteResult("Error: Start line number cannot be greater than end line number");
			}

			// Check 500-line limit
			int requestedLines = endLine - startLine + 1;
			if (requestedLines > 500) {
				return new ToolExecuteResult(
						"Error: Maximum 500 lines per request. Please adjust line range or make multiple calls. Current requested lines: "
								+ requestedLines);
			}

			Path absolutePath = validateGlobalPath(filePath);

			// Create file if it doesn't exist
			if (!Files.exists(absolutePath)) {
				Files.createDirectories(absolutePath.getParent());
				Files.createFile(absolutePath);
				log.info("Created new file automatically: {}", absolutePath);
			}

			java.util.List<String> lines = Files.readAllLines(absolutePath);

			if (lines.isEmpty()) {
				return new ToolExecuteResult("File is empty");
			}

			// Validate line number range
			if (startLine > lines.size()) {
				return new ToolExecuteResult(
						"Error: Start line number exceeds file range (file has " + lines.size() + " lines)");
			}

			// Adjust end line number (not exceeding total file lines)
			int actualEndLine = Math.min(endLine, lines.size());

			StringBuilder result = new StringBuilder();
			result.append(String.format("File: %s (Lines %d-%d, Total %d lines)\n", filePath, startLine, actualEndLine,
					lines.size()));
			result.append("=".repeat(50)).append("\n");

			for (int i = startLine - 1; i < actualEndLine; i++) {
				result.append(String.format("%4d: %s\n", i + 1, lines.get(i)));
			}

			// If file has more content, prompt user
			if (actualEndLine < lines.size()) {
				result.append("\nNote: File has more content (lines ")
					.append(actualEndLine + 1)
					.append("-")
					.append(lines.size())
					.append("), you can continue calling get_text to retrieve.");
			}

			return new ToolExecuteResult(result.toString());
		}
		catch (IOException e) {
			log.error("Error retrieving text lines from file: {}", filePath, e);
			return new ToolExecuteResult("Error retrieving text lines from file: " + e.getMessage());
		}
	}

	/**
	 * Get all text from file
	 */
	private ToolExecuteResult getAllText(String filePath) {
		try {
			Path absolutePath = validateGlobalPath(filePath);

			// Create file if it doesn't exist
			if (!Files.exists(absolutePath)) {
				Files.createDirectories(absolutePath.getParent());
				Files.createFile(absolutePath);
				log.info("Created new file automatically: {}", absolutePath);
			}

			String content = Files.readString(absolutePath);

			// Force flush to disk to ensure data consistency
			try (FileChannel channel = FileChannel.open(absolutePath, StandardOpenOption.READ)) {
				channel.force(true);
			}

			// Use InnerStorageService to intelligently process content
			SmartContentSavingService.SmartProcessResult processedResult = innerStorageService
				.processContent(this.rootPlanId, content, "get_all_text");

			return new ToolExecuteResult(processedResult.getSummary());
		}
		catch (IOException e) {
			log.error("Error retrieving all text from file: {}", filePath, e);
			return new ToolExecuteResult("Error retrieving all text from file: " + e.getMessage());
		}
	}

	/**
	 * Append content to file
	 */
	private ToolExecuteResult appendToFile(String filePath, String content) {
		try {
			if (content == null || content.isEmpty()) {
				return new ToolExecuteResult("Error: No content to append");
			}

			Path absolutePath = validateGlobalPath(filePath);

			// Create file if it doesn't exist
			if (!Files.exists(absolutePath)) {
				Files.createDirectories(absolutePath.getParent());
				Files.createFile(absolutePath);
			}

			Files.writeString(absolutePath, "\n" + content, StandardOpenOption.APPEND, StandardOpenOption.CREATE);

			// Force flush to disk
			try (FileChannel channel = FileChannel.open(absolutePath, StandardOpenOption.WRITE)) {
				channel.force(true);
			}

			log.info("Content appended to file: {}", absolutePath);
			return new ToolExecuteResult("Content appended successfully to file: " + filePath);
		}
		catch (IOException e) {
			log.error("Error appending to file: {}", filePath, e);
			return new ToolExecuteResult("Error appending to file: " + e.getMessage());
		}
	}

	/**
	 * Count characters in file
	 */
	private ToolExecuteResult countWords(String filePath) {
		try {
			Path absolutePath = validateGlobalPath(filePath);

			// Create file if it doesn't exist
			if (!Files.exists(absolutePath)) {
				Files.createDirectories(absolutePath.getParent());
				Files.createFile(absolutePath);
				log.info("Created new file automatically: {}", absolutePath);
			}

			String content = Files.readString(absolutePath);
			int characterCount = content.length();

			return new ToolExecuteResult(String.format("Total character count in file: %d", characterCount));
		}
		catch (IOException e) {
			log.error("Error counting characters in file: {}", filePath, e);
			return new ToolExecuteResult("Error counting characters in file: " + e.getMessage());
		}
	}

	/**
	 * Search for text patterns in file (grep functionality)
	 * @param contextLines Number of context lines to show above and below each match (0 =
	 * no context)
	 */
	private ToolExecuteResult grepText(String filePath, String pattern, boolean caseSensitive, boolean wholeWord,
			int contextLines) {
		try {
			Path absolutePath = validateGlobalPath(filePath);

			// Create file if it doesn't exist
			if (!Files.exists(absolutePath)) {
				Files.createDirectories(absolutePath.getParent());
				Files.createFile(absolutePath);
				log.info("Created new file automatically: {}", absolutePath);
			}

			java.util.List<String> lines = Files.readAllLines(absolutePath);

			if (lines.isEmpty()) {
				return new ToolExecuteResult("File is empty");
			}

			// Prepare pattern for matching
			String searchPattern = pattern;
			if (!caseSensitive) {
				searchPattern = pattern.toLowerCase();
			}
			if (wholeWord) {
				searchPattern = "\\b" + java.util.regex.Pattern.quote(searchPattern) + "\\b";
			}

			java.util.regex.Pattern regexPattern;
			if (wholeWord) {
				regexPattern = caseSensitive ? java.util.regex.Pattern.compile(searchPattern)
						: java.util.regex.Pattern.compile(searchPattern, java.util.regex.Pattern.CASE_INSENSITIVE);
			}
			else {
				regexPattern = caseSensitive
						? java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(searchPattern))
						: java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(searchPattern),
								java.util.regex.Pattern.CASE_INSENSITIVE);
			}

			StringBuilder result = new StringBuilder();
			result.append(String.format("Grep results for pattern '%s' in file: %s\n", pattern, filePath));
			if (contextLines > 0) {
				result.append(String.format("Showing %d line(s) of context around each match\n", contextLines));
			}
			result.append("=".repeat(60)).append("\n");

			// Track which lines have been printed to avoid duplicates when showing
			// context
			java.util.Set<Integer> printedLines = new java.util.HashSet<>();
			int matchCount = 0;

			for (int i = 0; i < lines.size(); i++) {
				String line = lines.get(i);
				String searchLine = caseSensitive ? line : line.toLowerCase();
				boolean isMatch = false;

				if (wholeWord) {
					isMatch = regexPattern.matcher(line).find();
				}
				else {
					isMatch = searchLine.contains(searchPattern);
				}

				if (isMatch) {
					matchCount++;

					// Show context lines before the match
					if (contextLines > 0) {
						for (int j = Math.max(0, i - contextLines); j < i; j++) {
							if (!printedLines.contains(j)) {
								result.append(String.format("%4d- %s\n", j + 1, lines.get(j)));
								printedLines.add(j);
							}
						}
					}

					// Show the matching line
					if (!printedLines.contains(i)) {
						result.append(String.format("%4d: %s\n", i + 1, line));
						printedLines.add(i);
					}

					// Show context lines after the match
					if (contextLines > 0) {
						for (int j = i + 1; j <= Math.min(lines.size() - 1, i + contextLines); j++) {
							if (!printedLines.contains(j)) {
								result.append(String.format("%4d+ %s\n", j + 1, lines.get(j)));
								printedLines.add(j);
							}
						}
						// Add separator between matches if context is shown
						if (i < lines.size() - 1) {
							result.append("--\n");
						}
					}
				}
			}

			if (matchCount == 0) {
				result.append("No matches found.\n");
			}
			else {
				result.append(String.format("\nTotal matches found: %d\n", matchCount));
			}

			return new ToolExecuteResult(result.toString());
		}
		catch (IOException e) {
			log.error("Error performing grep search in file: {}", filePath, e);
			return new ToolExecuteResult("Error performing grep search in file: " + e.getMessage());
		}
	}

	@Override
	public String getCurrentToolStateString() {
		return "";
	}

	@Override
	public String getName() {
		return TOOL_NAME;
	}

	@Override
	public String getDescription() {
		return toolI18nService.getDescription("global-file-operator");
	}

	@Override
	public String getParameters() {
		return toolI18nService.getParameters("global-file-operator");
	}

	@Override
	public Class<GlobalFileInput> getInputType() {
		return GlobalFileInput.class;
	}

	@Override
	public void cleanup(String planId) {
		if (planId != null) {
			log.info("Cleaning up file resources for plan: {}", planId);
			// Cleanup if needed - the TextFileService handles the main cleanup
		}
	}

	@Override
	public String getServiceGroup() {
		return "default-service-group";
	}

	@Override
	public boolean isSelectable() {
		return true;
	}

}
