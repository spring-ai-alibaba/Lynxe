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

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.FileSystemLoopException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.cloud.ai.lynxe.config.LynxeProperties;
import com.alibaba.cloud.ai.lynxe.tool.AbstractBaseTool;
import com.alibaba.cloud.ai.lynxe.tool.code.ToolExecuteResult;
import com.alibaba.cloud.ai.lynxe.tool.filesystem.GitIgnoreMatcher;
import com.alibaba.cloud.ai.lynxe.tool.filesystem.UnifiedDirectoryManager;
import com.alibaba.cloud.ai.lynxe.tool.i18n.ToolI18nService;
import com.alibaba.cloud.ai.lynxe.tool.innerStorage.SmartContentSavingService;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Grep Tool - Powerful text search tool based on ripgrep (rg) for precise text/regex
 * matching
 *
 * This tool provides powerful search capabilities similar to ripgrep, supporting: - Full
 * regular expression syntax (e.g., "log.*Error", "function\\s+\\w+") - Multiple pattern
 * search: Use | (OR operator) to search for multiple words/patterns in one query (e.g.,
 * "Repository|Service|Controller" matches any of these words) - Multiple output modes:
 * count (default), content - File filtering with glob patterns (e.g., "*.js",
 * "*.{ts,tsx}") or type parameter - Case-insensitive search option (-i flag) - Context
 * lines display (-A: after, -B: before, -C: around matches) - Multiline matching support
 * (. matches newlines) - Result limiting (head_limit parameter)
 *
 * Usage Scenarios: - Use Grep for: Precise text search, regex matching, known
 * symbol/variable lookup, searching for multiple patterns simultaneously using | operator
 * - Don't use Grep for: Semantic search (use SemanticSearch), file name search (use
 * Glob), reading known files (use Read)
 *
 * Output Formats: - content mode: Shows matching lines with ':' separator, context lines
 * with '-' separator - count mode: Shows match counts per file (e.g., "file.java: 5
 * matches")
 *
 * Note: Literal braces need escaping in patterns (use interface\\{\\} to find interface{}
 * in code)
 *
 * Keywords: grep, search, find text, regex, ripgrep, rg, pattern matching, text search,
 * exact match
 */
public class EnhancedGrep extends AbstractBaseTool<EnhancedGrep.GrepInput> {

	private static final Logger log = LoggerFactory.getLogger(EnhancedGrep.class);

	private static final String TOOL_NAME = "global_file_enhanced_grep";

	/**
	 * Maximum number of results to return (to prevent overwhelming output)
	 */
	private static final int DEFAULT_MAX_RESULTS = 1000;

	/**
	 * Predefined file type mappings (similar to ripgrep)
	 */
	private static final Map<String, List<String>> FILE_TYPE_EXTENSIONS = new HashMap<>();
	static {
		FILE_TYPE_EXTENSIONS.put("java", List.of(".java"));
		FILE_TYPE_EXTENSIONS.put("py", List.of(".py"));
		FILE_TYPE_EXTENSIONS.put("js", List.of(".js", ".jsx"));
		FILE_TYPE_EXTENSIONS.put("ts", List.of(".ts", ".tsx"));
		FILE_TYPE_EXTENSIONS.put("rust", List.of(".rs"));
		FILE_TYPE_EXTENSIONS.put("go", List.of(".go"));
		FILE_TYPE_EXTENSIONS.put("cpp", List.of(".cpp", ".cc", ".cxx", ".c", ".h", ".hpp"));
		FILE_TYPE_EXTENSIONS.put("md", List.of(".md", ".markdown"));
		FILE_TYPE_EXTENSIONS.put("json", List.of(".json"));
		FILE_TYPE_EXTENSIONS.put("xml", List.of(".xml"));
		FILE_TYPE_EXTENSIONS.put("yaml", List.of(".yaml", ".yml"));
		FILE_TYPE_EXTENSIONS.put("sql", List.of(".sql"));
		FILE_TYPE_EXTENSIONS.put("sh", List.of(".sh", ".bash"));
		FILE_TYPE_EXTENSIONS.put("css", List.of(".css", ".scss", ".sass", ".less"));
		FILE_TYPE_EXTENSIONS.put("html", List.of(".html", ".htm"));
		FILE_TYPE_EXTENSIONS.put("vue", List.of(".vue"));
	}

	/**
	 * Output mode enumeration
	 */
	public enum OutputMode {

		CONTENT, // Show matching lines with content
		COUNT // Show match counts per file

	}

	/**
	 * Input class for grep operations
	 */
	public static class GrepInput {

		@JsonProperty("pattern")
		private String pattern;

		@JsonProperty("path")
		private String path;

		@JsonProperty("glob")
		private String glob;

		@JsonProperty("type")
		private String type;

		@JsonProperty("-i")
		private Boolean caseInsensitive;

		@JsonProperty("output_mode")
		private String outputMode;

		@JsonProperty("-B")
		private Integer contextBefore;

		@JsonProperty("-A")
		private Integer contextAfter;

		@JsonProperty("-C")
		private Integer context;

		@JsonProperty("multiline")
		private Boolean multiline;

		@JsonProperty("head_limit")
		private Integer headLimit;

		// Getters and setters
		public String getPattern() {
			return pattern;
		}

		public void setPattern(String pattern) {
			this.pattern = pattern;
		}

		public String getPath() {
			return path;
		}

		public void setPath(String path) {
			this.path = path;
		}

		public String getGlob() {
			return glob;
		}

		public void setGlob(String glob) {
			this.glob = glob;
		}

		public String getType() {
			return type;
		}

		public void setType(String type) {
			this.type = type;
		}

		public Boolean getCaseInsensitive() {
			return caseInsensitive;
		}

		public void setCaseInsensitive(Boolean caseInsensitive) {
			this.caseInsensitive = caseInsensitive;
		}

		public String getOutputMode() {
			return outputMode;
		}

		public void setOutputMode(String outputMode) {
			this.outputMode = outputMode;
		}

		public Integer getContextBefore() {
			return contextBefore;
		}

		public void setContextBefore(Integer contextBefore) {
			this.contextBefore = contextBefore;
		}

		public Integer getContextAfter() {
			return contextAfter;
		}

		public void setContextAfter(Integer contextAfter) {
			this.contextAfter = contextAfter;
		}

		public Integer getContext() {
			return context;
		}

		public void setContext(Integer context) {
			this.context = context;
		}

		public Boolean getMultiline() {
			return multiline;
		}

		public void setMultiline(Boolean multiline) {
			this.multiline = multiline;
		}

		public Integer getHeadLimit() {
			return headLimit;
		}

		public void setHeadLimit(Integer headLimit) {
			this.headLimit = headLimit;
		}

	}

	/**
	 * Match result for a single line
	 */
	private static class MatchResult {

		String filePath;

		int lineNumber;

		String lineContent;

		boolean isMatchLine; // true for match, false for context

		public MatchResult(String filePath, int lineNumber, String lineContent, boolean isMatchLine) {
			this.filePath = filePath;
			this.lineNumber = lineNumber;
			this.lineContent = lineContent;
			this.isMatchLine = isMatchLine;
		}

	}

	private final TextFileService textFileService;

	private final ObjectMapper objectMapper;

	private final ToolI18nService toolI18nService;

	private final GitIgnoreMatcher gitIgnoreMatcher;

	private final LynxeProperties lynxeProperties;

	public EnhancedGrep(TextFileService textFileService, ObjectMapper objectMapper, ToolI18nService toolI18nService,
			GitIgnoreMatcher gitIgnoreMatcher, LynxeProperties lynxeProperties) {
		this.textFileService = textFileService;
		this.objectMapper = objectMapper;
		this.toolI18nService = toolI18nService;
		this.gitIgnoreMatcher = gitIgnoreMatcher;
		this.lynxeProperties = lynxeProperties;
	}

	@Override
	public ToolExecuteResult run(GrepInput input) {
		log.info("EnhancedGrep input: pattern={}, path={}", input.getPattern(), input.getPath());
		try {
			if (input.getPattern() == null || input.getPattern().isEmpty()) {
				return new ToolExecuteResult("Error: pattern parameter is required");
			}

			return executeGrep(input.getPattern(), input.getPath(), input.getGlob(), input.getType(),
					input.getCaseInsensitive() != null && input.getCaseInsensitive(),
					input.getOutputMode() != null ? input.getOutputMode() : "count", input.getContextBefore(),
					input.getContextAfter(), input.getContext(), input.getMultiline() != null && input.getMultiline(),
					input.getHeadLimit());
		}
		catch (Exception e) {
			log.error("EnhancedGrep execution failed", e);
			return new ToolExecuteResult("Tool execution failed: " + e.getMessage());
		}
	}

	/**
	 * Execute grep search with all parameters
	 */
	private ToolExecuteResult executeGrep(String pattern, String path, String glob, String type,
			boolean caseInsensitive, String outputMode, Integer contextBefore, Integer contextAfter, Integer context,
			boolean multiline, Integer headLimit) {
		try {
			// Validate and get search root path
			Path searchRoot = getSearchRoot(path);

			// Determine context lines
			int beforeLines = contextBefore != null ? contextBefore : (context != null ? context : 0);
			int afterLines = contextAfter != null ? contextAfter : (context != null ? context : 0);

			// Parse output mode
			OutputMode mode = parseOutputMode(outputMode);

			// Determine result limit
			int maxResults = headLimit != null ? headLimit : DEFAULT_MAX_RESULTS;

			// Compile regex pattern
			Pattern regexPattern = compilePattern(pattern, caseInsensitive, multiline);

			// Search files
			List<Path> filesToSearch = findFilesToSearch(searchRoot, glob, type);

			if (filesToSearch.isEmpty()) {
				return new ToolExecuteResult("No files found matching the criteria");
			}

			// Execute search based on mode
			return switch (mode) {
				case CONTENT ->
					searchContent(filesToSearch, regexPattern, beforeLines, afterLines, maxResults, multiline);
				case COUNT -> searchCount(filesToSearch, regexPattern, maxResults, multiline);
			};
		}
		catch (Exception e) {
			log.error("Error executing grep", e);
			return new ToolExecuteResult("Error executing grep: " + e.getMessage());
		}
	}

	/**
	 * Determine the root path for ignore file matching. If searching within
	 * linked_external, use the actual external folder root. Otherwise, use the search
	 * root.
	 * @param searchRoot The search root path
	 * @return Root path for ignore file matching
	 */
	private Path determineIgnoreRootPath(Path searchRoot) {
		if (searchRoot == null) {
			return null;
		}

		try {
			Path normalized = searchRoot.toAbsolutePath().normalize();
			String pathString = normalized.toString();

			// Check if we're searching within linked_external directory
			if (pathString.contains("linked_external")) {
				// Find the linked_external directory in the path
				Path current = normalized;
				while (current != null && current.getNameCount() > 0) {
					if ("linked_external".equals(current.getFileName().toString())) {
						// This is the linked_external symlink, use its target as the
						// ignore root
						// For symlinks, we want to use the actual target directory
						try {
							Path realPath = current.toRealPath();
							log.debug("Using external folder root for ignore patterns: {}", realPath);
							return realPath;
						}
						catch (IOException e) {
							log.debug("Could not resolve real path for linked_external, using as-is: {}", current, e);
							return current;
						}
					}
					current = current.getParent();
				}
			}

			// Default: use search root
			return normalized;
		}
		catch (Exception e) {
			log.warn("Error determining ignore root path, using search root: {}", searchRoot, e);
			return searchRoot;
		}
	}

	/**
	 * Get search root path
	 */
	private Path getSearchRoot(String path) throws IOException {
		if (path == null || path.isEmpty()) {
			// Use workspace root if available
			if (this.rootPlanId != null && !this.rootPlanId.isEmpty()) {
				return textFileService.getRootPlanDirectory(this.rootPlanId);
			}
			// Fallback to current directory
			return Paths.get(".");
		}

		// Normalize path: remove trailing slashes for consistent handling
		String normalizedPath = path;
		while (normalizedPath.endsWith("/") && normalizedPath.length() > 1) {
			normalizedPath = normalizedPath.substring(0, normalizedPath.length() - 1);
		}

		// If rootPlanId is available, resolve path relative to root plan directory
		if (this.rootPlanId != null && !this.rootPlanId.isEmpty()) {
			Path rootPlanDirectory = textFileService.getRootPlanDirectory(this.rootPlanId);
			UnifiedDirectoryManager directoryManager = textFileService.getUnifiedDirectoryManager();

			// Use the centralized method from UnifiedDirectoryManager
			return directoryManager.resolveAndValidatePath(rootPlanDirectory, normalizedPath);
		}

		// If no rootPlanId, treat path as absolute
		return Paths.get(normalizedPath);
	}

	/**
	 * Compile regex pattern with flags
	 */
	private Pattern compilePattern(String pattern, boolean caseInsensitive, boolean multiline) {
		int flags = 0;
		if (caseInsensitive) {
			flags |= Pattern.CASE_INSENSITIVE;
		}
		if (multiline) {
			flags |= Pattern.MULTILINE | Pattern.DOTALL;
		}
		return Pattern.compile(pattern, flags);
	}

	/**
	 * Parse output mode string
	 */
	private OutputMode parseOutputMode(String mode) {
		if (mode == null) {
			return OutputMode.COUNT;
		}
		return switch (mode.toLowerCase()) {
			case "content" -> OutputMode.CONTENT;
			default -> OutputMode.COUNT;
		};
	}

	/**
	 * Maximum depth for directory traversal to prevent excessive recursion
	 */
	private static final int MAX_DEPTH = 100;

	/**
	 * Maximum path length to prevent path explosion issues
	 */
	private static final int MAX_PATH_LENGTH = 1000;

	/**
	 * Find files to search based on glob pattern or file type
	 */
	private List<Path> findFilesToSearch(Path root, String glob, String type) throws IOException {
		List<Path> files = new ArrayList<>();

		// Determine file filter
		Set<String> extensions = new HashSet<>();
		if (type != null && FILE_TYPE_EXTENSIONS.containsKey(type.toLowerCase())) {
			extensions.addAll(FILE_TYPE_EXTENSIONS.get(type.toLowerCase()));
		}

		// Convert glob to pattern
		Pattern globPattern = null;
		if (glob != null && !glob.isEmpty()) {
			globPattern = compileGlobPattern(glob);
		}

		Pattern finalGlobPattern = globPattern;
		Set<String> finalExtensions = extensions;
		Path rootPath = root;

		// Initialize GitIgnoreMatcher if respectGitIgnore is enabled
		boolean respectGitIgnore = lynxeProperties.getRespectGitIgnore() != null
				&& lynxeProperties.getRespectGitIgnore();
		Path ignoreRootPath = determineIgnoreRootPath(root);
		gitIgnoreMatcher.initialize(ignoreRootPath, respectGitIgnore);

		// Walk directory tree, following symbolic links to traverse linked_external
		// Use walkFileTree to handle circular symlinks gracefully by skipping problematic
		// directories
		FileVisitor<Path> visitor = new SimpleFileVisitor<Path>() {
			@Override
			public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
				// Check path depth relative to root
				int depth = rootPath.relativize(dir).getNameCount();
				if (depth > MAX_DEPTH) {
					log.warn("Path depth {} exceeds maximum ({}). Skipping directory: {}", depth, MAX_DEPTH, dir);
					return FileVisitResult.SKIP_SUBTREE;
				}

				// Check path length to prevent path explosion
				String pathString = dir.toString();
				if (pathString.length() > MAX_PATH_LENGTH) {
					log.warn("Path length {} exceeds maximum ({}). Skipping directory: {}", pathString.length(),
							MAX_PATH_LENGTH, dir);
					return FileVisitResult.SKIP_SUBTREE;
				}

				// Check if directory should be skipped based on ignore rules
				if (respectGitIgnore && gitIgnoreMatcher.shouldSkipDirectory(dir)) {
					log.debug("Skipping directory due to ignore rules: {}", dir);
					return FileVisitResult.SKIP_SUBTREE;
				}

				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
				// Check path length for files as well
				String pathString = file.toString();
				if (pathString.length() > MAX_PATH_LENGTH) {
					log.warn("File path length {} exceeds maximum ({}). Skipping file: {}", pathString.length(),
							MAX_PATH_LENGTH, file);
					return FileVisitResult.CONTINUE;
				}

				// Skip if not a regular file
				if (!Files.isRegularFile(file)) {
					return FileVisitResult.CONTINUE;
				}

				// Skip hidden files
				if (isHidden(file)) {
					return FileVisitResult.CONTINUE;
				}

				// Check if file should be ignored based on ignore rules
				if (respectGitIgnore && gitIgnoreMatcher.isIgnored(file)) {
					log.debug("Skipping file due to ignore rules: {}", file);
					return FileVisitResult.CONTINUE;
				}

				// Apply type filter
				if (!finalExtensions.isEmpty()) {
					String fileName = file.getFileName().toString();
					if (finalExtensions.stream().noneMatch(fileName::endsWith)) {
						return FileVisitResult.CONTINUE;
					}
				}

				// Apply glob filter
				if (finalGlobPattern != null) {
					String fileName = file.getFileName().toString();
					if (!finalGlobPattern.matcher(fileName).matches()) {
						return FileVisitResult.CONTINUE;
					}
				}

				// Default: include text files only
				if (finalExtensions.isEmpty() && finalGlobPattern == null && !isTextFile(file)) {
					return FileVisitResult.CONTINUE;
				}

				// File matches all filters, add it
				files.add(file);
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
				// Handle circular symlinks by skipping the problematic directory
				if (exc instanceof FileSystemLoopException) {
					log.warn("Circular symlink detected: {}. Skipping this directory and continuing.", file);
					return FileVisitResult.SKIP_SUBTREE;
				}

				// Check if path is too long (might cause issues)
				String pathString = file.toString();
				if (pathString.length() > MAX_PATH_LENGTH) {
					log.warn("Path too long ({} chars): {}. Skipping and continuing.", pathString.length(), file);
					return FileVisitResult.SKIP_SUBTREE;
				}

				// For other IO errors, log and continue
				log.warn("Error accessing file/directory: {}. Skipping and continuing.", file, exc);
				return FileVisitResult.SKIP_SUBTREE;
			}
		};

		// Walk the directory tree with depth limit - all exceptions are handled by
		// visitFileFailed
		Files.walkFileTree(root, java.util.EnumSet.of(FileVisitOption.FOLLOW_LINKS), MAX_DEPTH, visitor);

		return files;
	}

	/**
	 * Check if path is hidden
	 */
	private boolean isHidden(Path path) {
		try {
			return Files.isHidden(path) || path.getFileName().toString().startsWith(".");
		}
		catch (IOException e) {
			return false;
		}
	}

	/**
	 * Check if file is text file (basic heuristic)
	 */
	private boolean isTextFile(Path path) {
		String fileName = path.getFileName().toString().toLowerCase();
		return fileName.endsWith(".txt") || fileName.endsWith(".md") || fileName.endsWith(".java")
				|| fileName.endsWith(".py") || fileName.endsWith(".js") || fileName.endsWith(".ts")
				|| fileName.endsWith(".jsx") || fileName.endsWith(".tsx") || fileName.endsWith(".json")
				|| fileName.endsWith(".xml") || fileName.endsWith(".yaml") || fileName.endsWith(".yml")
				|| fileName.endsWith(".properties") || fileName.endsWith(".log") || fileName.endsWith(".conf")
				|| fileName.endsWith(".sh") || fileName.endsWith(".css") || fileName.endsWith(".html")
				|| fileName.endsWith(".vue");
	}

	/**
	 * Compile glob pattern to regex
	 */
	private Pattern compileGlobPattern(String glob) {
		// Convert glob to regex
		StringBuilder regex = new StringBuilder("^");
		for (char c : glob.toCharArray()) {
			switch (c) {
				case '*':
					regex.append(".*");
					break;
				case '?':
					regex.append(".");
					break;
				case '.':
					regex.append("\\.");
					break;
				default:
					if (Character.isLetterOrDigit(c) || c == '_' || c == '-') {
						regex.append(c);
					}
					else {
						regex.append("\\").append(c);
					}
			}
		}
		regex.append("$");
		return Pattern.compile(regex.toString());
	}

	/**
	 * Maximum number of files to return in searchContent (sorted by match count)
	 */
	private static final int MAX_FILES_TO_RETURN = 15;

	/**
	 * Helper class to store file match information for sorting Can be used by both
	 * searchContent and searchCount methods
	 */
	private static class FileMatchInfo {

		Path file;

		List<MatchResult> matches; // Used by searchContent, can be null for searchCount

		int matchCount;

		/**
		 * Constructor for searchContent: uses matches list to calculate match count
		 */
		FileMatchInfo(Path file, List<MatchResult> matches) {
			this.file = file;
			this.matches = matches;
			// Count only match lines (not context lines)
			this.matchCount = (int) matches.stream().filter(m -> m.isMatchLine).count();
		}

		/**
		 * Constructor for searchCount: directly uses match count
		 */
		FileMatchInfo(Path file, int matchCount) {
			this.file = file;
			this.matches = null;
			this.matchCount = matchCount;
		}

	}

	/**
	 * Search and return content with matches Files are sorted by match count (descending)
	 * and only top 15 files are returned
	 */
	private ToolExecuteResult searchContent(List<Path> files, Pattern pattern, int beforeLines, int afterLines,
			int maxResults, boolean multiline) {
		StringBuilder result = new StringBuilder();
		int totalMatches = 0;

		// Get root plan directory for path conversion
		Path rootPlanDirectory = null;
		if (this.rootPlanId != null && !this.rootPlanId.isEmpty()) {
			rootPlanDirectory = textFileService.getRootPlanDirectory(this.rootPlanId);
		}

		// First pass: collect all file matches with their match counts
		List<FileMatchInfo> fileMatches = new ArrayList<>();
		for (Path file : files) {
			try {
				List<MatchResult> matches = searchFile(file, pattern, beforeLines, afterLines, multiline);
				if (!matches.isEmpty()) {
					fileMatches.add(new FileMatchInfo(file, matches));
				}
			}
			catch (IOException e) {
				log.warn("Error reading file: {}", file, e);
			}
		}

		if (fileMatches.isEmpty()) {
			return new ToolExecuteResult("No matches found");
		}

		// Sort files by match count (descending) - files with more matches first
		fileMatches.sort((a, b) -> Integer.compare(b.matchCount, a.matchCount));

		// Get total number of files with matches
		int totalFilesWithMatches = fileMatches.size();

		// Limit to top 15 files
		int filesToProcess = Math.min(fileMatches.size(), MAX_FILES_TO_RETURN);
		boolean hasMoreFiles = fileMatches.size() > MAX_FILES_TO_RETURN;

		// Second pass: output results for top files
		for (int i = 0; i < filesToProcess; i++) {
			FileMatchInfo fileInfo = fileMatches.get(i);
			if (totalMatches >= maxResults) {
				result.append(String.format("\n... (output limited to %d results)\n", maxResults));
				break;
			}

			// Convert absolute path to relative path
			String relativePath = getRelativePath(fileInfo.file, rootPlanDirectory);
			result.append(relativePath).append("\n");

			for (MatchResult match : fileInfo.matches) {
				if (totalMatches >= maxResults)
					break;

				String marker = match.isMatchLine ? ":" : "-";
				// Use SmartContentSavingService for truncation
				String displayContent = match.lineContent;
				if (this.rootPlanId != null && !this.rootPlanId.isEmpty()) {
					SmartContentSavingService.SmartProcessResult processed = textFileService.getInnerStorageService()
						.processContent(this.rootPlanId, match.lineContent, "grep_match");
					displayContent = processed.getSummary();
				}
				result.append(String.format("%d%s%s\n", match.lineNumber, marker, displayContent));
				totalMatches++;
			}
			result.append("\n");
		}

		// Add summary with file limit information
		if (hasMoreFiles) {
			int remainingFiles = totalFilesWithMatches - MAX_FILES_TO_RETURN;
			result.append(String.format(
					"Found %d matches in %d files (showing top %d files sorted by match count, %d more files with matches - too many files matched)\n",
					totalMatches, totalFilesWithMatches, MAX_FILES_TO_RETURN, remainingFiles));
		}
		else {
			result.append(String.format("Found %d matches in %d files\n", totalMatches, totalFilesWithMatches));
		}

		return new ToolExecuteResult(result.toString());
	}

	/**
	 * Search single file and return matches with context
	 */
	private List<MatchResult> searchFile(Path file, Pattern pattern, int beforeLines, int afterLines, boolean multiline)
			throws IOException {
		List<MatchResult> results = new ArrayList<>();

		if (multiline) {
			// Read entire file for multiline matching
			String content = Files.readString(file);
			Matcher matcher = pattern.matcher(content);

			// Find all matches and extract their content
			while (matcher.find()) {
				String matchedContent = matcher.group();
				int startPos = matcher.start();

				// Calculate line number from position
				int lineNumber = 1;
				for (int i = 0; i < startPos && i < content.length(); i++) {
					if (content.charAt(i) == '\n') {
						lineNumber++;
					}
				}

				// Replace newlines with \n for display
				String displayContent = matchedContent.replace("\n", "\\n").replace("\r", "");

				// Use SmartContentSavingService for truncation if rootPlanId is available
				if (this.rootPlanId != null && !this.rootPlanId.isEmpty()) {
					SmartContentSavingService.SmartProcessResult processed = textFileService.getInnerStorageService()
						.processContent(this.rootPlanId, displayContent, "grep_multiline_match");
					displayContent = processed.getSummary();
				}
				else {
					// Fallback truncation if no rootPlanId
					if (displayContent.length() > 500) {
						displayContent = displayContent.substring(0, 250) + "...[truncated]..."
								+ displayContent.substring(displayContent.length() - 200);
					}
				}

				results.add(new MatchResult(file.toString(), lineNumber, displayContent, true));
			}
		}
		else {
			// Line-by-line matching
			List<String> lines = Files.readAllLines(file);
			Set<Integer> printedLines = new HashSet<>();

			for (int i = 0; i < lines.size(); i++) {
				String line = lines.get(i);
				if (pattern.matcher(line).find()) {
					// Add context before
					for (int j = Math.max(0, i - beforeLines); j < i; j++) {
						if (!printedLines.contains(j)) {
							results.add(new MatchResult(file.toString(), j + 1, lines.get(j), false));
							printedLines.add(j);
						}
					}

					// Add match line
					if (!printedLines.contains(i)) {
						results.add(new MatchResult(file.toString(), i + 1, line, true));
						printedLines.add(i);
					}

					// Add context after
					for (int j = i + 1; j <= Math.min(lines.size() - 1, i + afterLines); j++) {
						if (!printedLines.contains(j)) {
							results.add(new MatchResult(file.toString(), j + 1, lines.get(j), false));
							printedLines.add(j);
						}
					}
				}
			}
		}

		return results;
	}

	/**
	 * Search and return match counts Files are sorted by match count (descending) and
	 * only top 15 files are returned
	 */
	private ToolExecuteResult searchCount(List<Path> files, Pattern pattern, int maxResults, boolean multiline) {
		StringBuilder result = new StringBuilder();
		int totalMatches = 0;

		// Get root plan directory for path conversion
		Path rootPlanDirectory = null;
		if (this.rootPlanId != null && !this.rootPlanId.isEmpty()) {
			rootPlanDirectory = textFileService.getRootPlanDirectory(this.rootPlanId);
		}

		// First pass: collect all file matches with their match counts
		List<FileMatchInfo> fileMatches = new ArrayList<>();
		for (Path file : files) {
			try {
				int count = countMatches(file, pattern, multiline);
				if (count > 0) {
					fileMatches.add(new FileMatchInfo(file, count));
					totalMatches += count;
				}
			}
			catch (IOException e) {
				log.warn("Error reading file: {}", file, e);
			}
		}

		if (fileMatches.isEmpty()) {
			return new ToolExecuteResult("No matches found");
		}

		// Sort files by match count (descending) - files with more matches first
		fileMatches.sort((a, b) -> Integer.compare(b.matchCount, a.matchCount));

		// Get total number of files with matches
		int totalFilesWithMatches = fileMatches.size();

		// Limit to top 15 files
		int filesToProcess = Math.min(fileMatches.size(), MAX_FILES_TO_RETURN);
		boolean hasMoreFiles = fileMatches.size() > MAX_FILES_TO_RETURN;

		// Second pass: output results for top files
		for (int i = 0; i < filesToProcess; i++) {
			FileMatchInfo fileInfo = fileMatches.get(i);
			// Convert absolute path to relative path
			String relativePath = getRelativePath(fileInfo.file, rootPlanDirectory);
			result.append(String.format("%s: %d\n", relativePath, fileInfo.matchCount));
		}

		// Add summary with file limit information
		if (hasMoreFiles) {
			int remainingFiles = totalFilesWithMatches - MAX_FILES_TO_RETURN;
			result.append(String.format(
					"\nTotal: %d matches in %d files (showing top %d files sorted by match count, %d more files with matches - too many files matched)\n",
					totalMatches, totalFilesWithMatches, MAX_FILES_TO_RETURN, remainingFiles));
		}
		else {
			result.append(String.format("\nTotal: %d matches in %d files\n", totalMatches, totalFilesWithMatches));
		}

		return new ToolExecuteResult(result.toString());
	}

	/**
	 * Convert absolute path to relative path relative to root plan directory
	 * @param filePath The absolute file path
	 * @param rootPlanDirectory The root plan directory (can be null)
	 * @return Relative path string
	 */
	private String getRelativePath(Path filePath, Path rootPlanDirectory) {
		if (rootPlanDirectory != null && filePath.startsWith(rootPlanDirectory)) {
			try {
				Path relativePath = rootPlanDirectory.relativize(filePath);
				// Convert path separators to forward slashes for consistency
				return relativePath.toString().replace('\\', '/');
			}
			catch (IllegalArgumentException e) {
				// If relativization fails, return the file name
				return filePath.getFileName().toString();
			}
		}
		// If no root plan directory or path doesn't start with it, return file name
		return filePath.getFileName().toString();
	}

	/**
	 * Count matches in a file
	 */
	private int countMatches(Path file, Pattern pattern, boolean multiline) throws IOException {
		if (multiline) {
			String content = Files.readString(file);
			Matcher matcher = pattern.matcher(content);
			int count = 0;
			while (matcher.find()) {
				count++;
			}
			return count;
		}
		else {
			int count = 0;
			try (BufferedReader reader = Files.newBufferedReader(file)) {
				String line;
				while ((line = reader.readLine()) != null) {
					Matcher matcher = pattern.matcher(line);
					while (matcher.find()) {
						count++;
					}
				}
			}
			return count;
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
		return toolI18nService.getDescription("enhanced-grep");
	}

	@Override
	public String getParameters() {
		return toolI18nService.getParameters("enhanced-grep");
	}

	@Override
	public Class<GrepInput> getInputType() {
		return GrepInput.class;
	}

	@Override
	public void cleanup(String planId) {
		if (planId != null) {
			log.info("Cleaning up EnhancedGrep resources for plan: {}", planId);
		}
	}

	@Override
	public String getServiceGroup() {
		return "file-operations";
	}

	@Override
	public boolean isSelectable() {
		return true;
	}

}
