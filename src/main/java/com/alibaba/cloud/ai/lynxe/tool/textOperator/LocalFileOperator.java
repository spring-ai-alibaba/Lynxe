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
import com.alibaba.cloud.ai.lynxe.tool.innerStorage.SmartContentSavingService;
import com.alibaba.cloud.ai.lynxe.tool.shortUrl.ShortUrlService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Local file operator that only performs operations within the current plan ID folder.
 * Unlike TextFileOperator which supports hierarchical access, this operator is restricted
 * to the current plan's directory only for enhanced security and isolation.
 */
public class LocalFileOperator extends AbstractBaseTool<LocalFileOperator.LocalFileInput> {

	private static final Logger log = LoggerFactory.getLogger(LocalFileOperator.class);

	private static final String TOOL_NAME = "local_file_operator";

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
	 * Input class for local file operations
	 */
	public static class LocalFileInput {

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

	public LocalFileOperator(TextFileService textFileService, SmartContentSavingService innerStorageService,
			ObjectMapper objectMapper, ShortUrlService shortUrlService) {
		this.textFileService = textFileService;
		this.innerStorageService = innerStorageService;
		this.objectMapper = objectMapper;
		this.shortUrlService = shortUrlService;
	}

	public ToolExecuteResult run(String toolInput) {
		log.info("LocalFileOperator toolInput: {}", toolInput);
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

			// Replace short URLs in filePath
			filePath = replaceShortUrls(filePath);

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
			log.error("LocalFileOperator execution failed", e);
			return new ToolExecuteResult("Tool execution failed: " + e.getMessage());
		}
	}

	@Override
	public ToolExecuteResult run(LocalFileInput input) {
		log.info("LocalFileOperator input: action={}, filePath={}", input.getAction(), input.getFilePath());
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
			log.error("LocalFileOperator execution failed", e);
			return new ToolExecuteResult("Tool execution failed: " + e.getMessage());
		}
	}

	/**
	 * Replace short URLs in a string with real URLs
	 * @param text The text that may contain short URLs
	 * @return The text with short URLs replaced by real URLs
	 */
	private String replaceShortUrls(String text) {
		if (text == null || text.isEmpty() || this.currentPlanId == null || this.currentPlanId.isEmpty()
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
			String realUrl = shortUrlService.getRealUrl(this.currentPlanId, shortUrl);
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
	 * Validate and get the absolute path within the current plan directory only
	 */
	private Path validateLocalPath(String filePath) throws IOException {
		if (this.currentPlanId == null || this.currentPlanId.isEmpty()) {
			throw new IOException("Error: currentPlanId is required for local file operations but is null or empty");
		}

		// Check file type
		if (!isSupportedFileType(filePath)) {
			throw new IOException("Unsupported file type. Only text-based files are supported.");
		}

		// Get the current plan directory (not hierarchical - only current plan)
		Path planDirectory = textFileService.getRootPlanDirectory(this.currentPlanId);
		Path absolutePath = planDirectory.resolve(filePath).normalize();

		// Ensure the path stays within the plan directory
		if (!absolutePath.startsWith(planDirectory)) {
			throw new IOException("Access denied: File path must be within the current plan directory");
		}

		return absolutePath;
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
			Path absolutePath = validateLocalPath(filePath);

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
	 * Replace text in file
	 */
	private ToolExecuteResult replaceText(String filePath, String sourceText, String targetText) {
		try {
			Path absolutePath = validateLocalPath(filePath);

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
			return new ToolExecuteResult("Text replaced successfully in: " + filePath);
		}
		catch (IOException e) {
			log.error("Error replacing text in file: {}", filePath, e);
			return new ToolExecuteResult("Error replacing text: " + e.getMessage());
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

			Path absolutePath = validateLocalPath(filePath);

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
			return new ToolExecuteResult("Error retrieving text lines: " + e.getMessage());
		}
	}

	/**
	 * Get all text from file
	 */
	private ToolExecuteResult getAllText(String filePath) {
		try {
			Path absolutePath = validateLocalPath(filePath);

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
				.processContent(this.currentPlanId, content, "get_all_text");

			return new ToolExecuteResult(processedResult.getSummary());
		}
		catch (IOException e) {
			log.error("Error retrieving all text from file: {}", filePath, e);
			return new ToolExecuteResult("Error retrieving all text: " + e.getMessage());
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

			Path absolutePath = validateLocalPath(filePath);

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
			return new ToolExecuteResult("Content appended successfully to: " + filePath);
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
			Path absolutePath = validateLocalPath(filePath);

			// Create file if it doesn't exist
			if (!Files.exists(absolutePath)) {
				Files.createDirectories(absolutePath.getParent());
				Files.createFile(absolutePath);
				log.info("Created new file automatically: {}", absolutePath);
			}

			String content = Files.readString(absolutePath);
			int characterCount = content.length();

			return new ToolExecuteResult(String.format("Total character count: %d", characterCount));
		}
		catch (IOException e) {
			log.error("Error counting characters in file: {}", filePath, e);
			return new ToolExecuteResult("Error counting characters: " + e.getMessage());
		}
	}

	/**
	 * Search for text patterns in file (grep functionality)
	 * @param contextLines Number of context lines to show above and below each match (0 = no context)
	 */
	private ToolExecuteResult grepText(String filePath, String pattern, boolean caseSensitive, boolean wholeWord,
			int contextLines) {
		try {
			Path absolutePath = validateLocalPath(filePath);

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

			// Track which lines have been printed to avoid duplicates when showing context
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
			return new ToolExecuteResult("Error performing grep search: " + e.getMessage());
		}
	}

	/**
	 * List files in the current plan directory
	 */
	private ToolExecuteResult listFiles(String directoryPath) {
		try {
			if (this.currentPlanId == null || this.currentPlanId.isEmpty()) {
				return new ToolExecuteResult("Error: currentPlanId is required for local file operations");
			}

			// Get the current plan directory
			Path planDirectory = textFileService.getRootPlanDirectory(this.currentPlanId);

			// If a subdirectory path is provided, resolve it within plan directory
			Path targetDirectory = planDirectory;
			if (directoryPath != null && !directoryPath.isEmpty()) {
				targetDirectory = planDirectory.resolve(directoryPath).normalize();

				// Ensure the target directory stays within plan directory
				if (!targetDirectory.startsWith(planDirectory)) {
					return new ToolExecuteResult("Error: Directory path must be within the current plan directory");
				}
			}

			// Check if directory exists - don't create it for list operation
			if (!Files.exists(targetDirectory)) {
				String displayPath = directoryPath == null || directoryPath.isEmpty() ? "root" : directoryPath;
				return new ToolExecuteResult("Error: Directory does not exist: " + displayPath);
			}

			if (!Files.isDirectory(targetDirectory)) {
				return new ToolExecuteResult("Error: Path is not a directory: " + directoryPath);
			}

			StringBuilder result = new StringBuilder();
			String displayPath = directoryPath == null || directoryPath.isEmpty() ? "." : directoryPath;
			result.append(String.format("Files in current plan directory: %s\n", displayPath));
			result.append("=".repeat(50)).append("\n");

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
			log.error("Error listing files: {}", directoryPath, e);
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

	@Override
	public String getCurrentToolStateString() {
		try {
			if (this.currentPlanId == null || this.currentPlanId.isEmpty()) {
				return "Current Local File Operation State:\n- Error: No current plan ID available";
			}

			return String
				.format("""
						Current Local File Operation State:
						- Scope: Current plan directory only (no hierarchical access)
						- Operations are automatically handled (no manual file opening/closing required)
						- Available operations: replace, get_text, get_all_text, append, delete, count_words, list_files, grep
						""");
		}
		catch (Exception e) {
			return String.format(
					"""
							Current Local File Operation State:
							- Error getting working directory: %s
							- Available operations: replace, get_text, get_all_text, append, delete, count_words, list_files, grep
							""",
					e.getMessage());
		}
	}

	@Override
	public String getName() {
		return TOOL_NAME;
	}

	@Override
	public String getDescription() {
		return """
				Perform various operations on text files within the current plan directory only.
				This operator provides enhanced security by restricting all operations to the current plan's folder.

				Supported operations:
				- delete: Delete an existing file, requires file_path parameter
				- list_files: List files and directories in the current plan directory, optional file_path parameter (defaults to current plan root)
				- replace: Replace specific text in file, requires source_text and target_text parameters
				- get_text: Get content from specified line range in file, requires start_line and end_line parameters
				  Limitation: Maximum 500 lines per call, use multiple calls for more content
				- get_all_text: Get all content from file
				  Note: If file content is too long, it will be automatically stored in temporary file and return file path
				- append: Append content to file, requires content parameter
				- count_words: Count characters in current file
				- list_files: List files and directories in the current plan directory, optional file_path parameter (defaults to current plan root)
				- grep: Search for text patterns in file, similar to Linux grep command
				  Parameters: pattern (required), case_sensitive (optional, default false), whole_word (optional, default false), context_lines (optional, default 0, number of lines to show above and below each match)

				Security Features:
				- All operations are restricted to the current plan directory
				- No hierarchical access to parent or other plan directories
				- Path traversal attacks are prevented by path validation

				Supported file types include:
				- Text files (.txt)
				- Markdown files (.md, .markdown)
				- Web files (.html, .css, .scss, .sass, .less)
				- Programming files (.java, .py, .js, .ts, .cpp, .c, .h, .go, .rs, .php, .rb, .swift, .kt, .scala)
				- Configuration files (.json, .xml, .yaml, .yml, .toml, .ini, .conf)
				- Documentation files (.rst, .adoc)
				""";
	}

	@Override
	public String getParameters() {
		return """
				{
				    "type": "object",
				    "oneOf": [
				        {
				            "type": "object",
				            "properties": {
				                "action": {
				                    "type": "string",
				                    "const": "delete"
				                },
				                "file_path": {
				                    "type": "string",
				                    "description": "File path to delete (relative to current plan directory)"
				                }
				            },
				            "required": ["action", "file_path"],
				            "additionalProperties": false
				        },
				        {
				            "type": "object",
				            "properties": {
				                "action": {
				                    "type": "string",
				                    "const": "replace"
				                },
				                "file_path": {
				                    "type": "string",
				                    "description": "File path to operate on"
				                },
				                "source_text": {
				                    "type": "string",
				                    "description": "Text to be replaced"
				                },
				                "target_text": {
				                    "type": "string",
				                    "description": "Replacement text"
				                }
				            },
				            "required": ["action", "file_path", "source_text", "target_text"],
				            "additionalProperties": false
				        },
				        {
				           "type": "object",
				           "properties": {
				               "action": {
				                   "type": "string",
				                   "const": "get_text"
				               },
				               "file_path": {
				                   "type": "string",
				                   "description": "File path to read"
				               },
				               "start_line": {
				                   "type": "integer",
				                   "description": "Starting line number (starts from 1)"
				               },
				               "end_line": {
				                   "type": "integer",
				                   "description": "Ending line number (inclusive). Note: Maximum 500 lines per call"
				               }
				           },
				           "required": ["action", "file_path", "start_line", "end_line"],
				           "additionalProperties": false
				       },
				       {
				           "type": "object",
				           "properties": {
				               "action": {
				                   "type": "string",
				                   "const": "get_all_text"
				               },
				               "file_path": {
				                   "type": "string",
				                   "description": "File path to read all content"
				               }
				           },
				           "required": ["action", "file_path"],
				           "additionalProperties": false
				       },
				        {
				            "type": "object",
				            "properties": {
				                "action": {
				                    "type": "string",
				                    "const": "append"
				                },
				                "file_path": {
				                    "type": "string",
				                    "description": "File path to operate on"
				                },
				                "content": {
				                    "type": "string",
				                    "description": "Content to append to the file"
				                }
				            },
				            "required": ["action", "file_path", "content"],
				            "additionalProperties": false
				        },
				        {
				            "type": "object",
				            "properties": {
				                "action": {
				                    "type": "string",
				                    "const": "count_words"
				                },
				                "file_path": {
				                    "type": "string",
				                    "description": "File path to count characters in"
				                }
				            },
				            "required": ["action", "file_path"],
				            "additionalProperties": false
				        },
				        {
				            "type": "object",
				            "properties": {
				                "action": {
				                    "type": "string",
				                    "const": "list_files"
				                },
				                "file_path": {
				                    "type": "string",
				                    "description": "Directory path to list within current plan directory (optional, defaults to current plan root)"
				                }
				            },
				            "required": ["action"],
				            "additionalProperties": false
				        },
				        {
				            "type": "object",
				            "properties": {
				                "action": {
				                    "type": "string",
				                    "const": "grep"
				                },
				                "file_path": {
				                    "type": "string",
				                    "description": "File path to search in"
				                },
				                "pattern": {
				                    "type": "string",
				                    "description": "Text pattern to search for"
				                },
				                "case_sensitive": {
				                    "type": "boolean",
				                    "description": "Whether to perform case-sensitive search (default: false)"
				                },
				                "whole_word": {
				                    "type": "boolean",
				                    "description": "Whether to match whole words only (default: false)"
				                }
				            },
				            "required": ["action", "file_path", "pattern"],
				            "additionalProperties": false
				        }
				    ]
				}
				""";
	}

	@Override
	public Class<LocalFileInput> getInputType() {
		return LocalFileInput.class;
	}

	@Override
	public void cleanup(String planId) {
		if (planId != null) {
			log.info("Cleaning up local file resources for plan: {}", planId);
			// Local cleanup if needed - the TextFileService handles the main cleanup
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
