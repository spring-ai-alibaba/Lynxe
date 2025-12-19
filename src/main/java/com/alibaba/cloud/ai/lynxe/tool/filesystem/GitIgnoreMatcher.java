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
package com.alibaba.cloud.ai.lynxe.tool.filesystem;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Utility for parsing and matching .gitignore patterns. Supports common gitignore syntax
 * including wildcards, directory patterns, negation, and path-relative matching.
 *
 * This component provides: - Parsing of .gitignore files from directory trees - Pattern
 * matching for files and directories - Caching of parsed patterns for performance -
 * Support for multiple ignore files (.gitignore, .ignore) - Path-relative pattern
 * matching (patterns in subdir/.gitignore only apply to that subdir)
 */
@Component
public class GitIgnoreMatcher {

	private static final Logger log = LoggerFactory.getLogger(GitIgnoreMatcher.class);

	/**
	 * Ignore file names to look for
	 */
	private static final String[] IGNORE_FILE_NAMES = { ".gitignore", ".ignore" };

	/**
	 * Cache of parsed patterns per directory
	 */
	private final Map<Path, List<IgnorePattern>> patternCache = new HashMap<>();

	/**
	 * Root path for this matcher instance
	 */
	private Path rootPath;

	/**
	 * Whether ignore checking is enabled
	 */
	private boolean enabled = true;

	/**
	 * Create a new GitIgnoreMatcher instance
	 */
	public GitIgnoreMatcher() {
	}

	/**
	 * Initialize the matcher with a root path and load ignore files
	 * @param rootPath The root path to search for ignore files
	 * @param enabled Whether ignore checking is enabled
	 */
	public void initialize(Path rootPath, boolean enabled) {
		this.rootPath = rootPath != null ? rootPath.toAbsolutePath().normalize() : null;
		this.enabled = enabled;
		if (enabled && this.rootPath != null) {
			loadIgnoreFiles(this.rootPath);
		}
	}

	/**
	 * Check if a file or directory should be ignored
	 * @param filePath The file or directory path to check
	 * @return true if the file/directory should be ignored
	 */
	public boolean isIgnored(Path filePath) {
		if (!enabled || rootPath == null || filePath == null) {
			return false;
		}

		try {
			// Resolve symlinks to get the real path for consistent comparison
			Path absolutePath;
			try {
				absolutePath = filePath.toRealPath();
			}
			catch (IOException e) {
				// If symlink resolution fails, fall back to absolute path
				absolutePath = filePath.toAbsolutePath().normalize();
			}

			if (!absolutePath.startsWith(rootPath)) {
				// File is outside root, don't apply ignore rules
				return false;
			}

			Path relativePath = rootPath.relativize(absolutePath);
			return isIgnored(relativePath, absolutePath);
		}
		catch (Exception e) {
			log.debug("Error checking if path is ignored: {}", filePath, e);
			return false;
		}
	}

	/**
	 * Check if a directory should be skipped entirely
	 * @param dirPath The directory path to check
	 * @return true if the directory should be skipped
	 */
	public boolean shouldSkipDirectory(Path dirPath) {
		if (!enabled || rootPath == null || dirPath == null) {
			return false;
		}

		try {
			// Resolve symlinks to get the real path for consistent comparison
			Path absolutePath;
			try {
				absolutePath = dirPath.toRealPath();
			}
			catch (IOException e) {
				// If symlink resolution fails, fall back to absolute path
				absolutePath = dirPath.toAbsolutePath().normalize();
			}

			if (!absolutePath.startsWith(rootPath)) {
				return false;
			}

			Path relativePath = rootPath.relativize(absolutePath);
			// Check if directory name itself matches an ignore pattern
			return isIgnored(relativePath, absolutePath);
		}
		catch (Exception e) {
			log.debug("Error checking if directory should be skipped: {}", dirPath, e);
			return false;
		}
	}

	/**
	 * Internal method to check if a path is ignored
	 * @param relativePath Path relative to root
	 * @param absolutePath Absolute path
	 * @return true if ignored
	 */
	private boolean isIgnored(Path relativePath, Path absolutePath) {
		// Check patterns from root down to the file's directory
		boolean ignored = false;

		// Check root patterns first
		List<IgnorePattern> rootPatterns = getPatternsForDirectory(rootPath);
		if (!rootPatterns.isEmpty()) {
			String pathString = relativePath.toString().replace('\\', '/');
			ignored = checkPatterns(pathString, relativePath, rootPatterns, rootPath, ignored);
		}

		// Check patterns in each parent directory (where .gitignore files might exist)
		Path currentPath = rootPath;
		for (Path segment : relativePath) {
			Path nextPath = currentPath.resolve(segment);

			// Check patterns in the current directory (which might contain a .gitignore)
			List<IgnorePattern> dirPatterns = getPatternsForDirectory(currentPath);
			if (!dirPatterns.isEmpty()) {
				// Get relative path from current directory
				Path dirRelativePath = currentPath.relativize(absolutePath);
				String dirRelativeString = dirRelativePath.toString().replace('\\', '/');
				ignored = checkPatterns(dirRelativeString, dirRelativePath, dirPatterns, currentPath, ignored);
			}

			currentPath = nextPath;
		}

		return ignored;
	}

	/**
	 * Check patterns against a path
	 * @param pathString Path string to check
	 * @param relativePath Path object for directory checks
	 * @param patterns Patterns to check
	 * @param baseDir Directory containing the .gitignore file
	 * @param currentState Current ignored state
	 * @return New ignored state after applying patterns
	 */
	private boolean checkPatterns(String pathString, Path relativePath, List<IgnorePattern> patterns, Path baseDir,
			boolean currentState) {
		boolean result = currentState;

		for (IgnorePattern pattern : patterns) {
			if (pattern.matches(pathString, relativePath, baseDir)) {
				if (pattern.isNegation()) {
					result = false; // Negation un-ignores
				}
				else {
					result = true; // Pattern matches, ignore it
				}
			}
		}

		return result;
	}

	/**
	 * Get patterns for a directory (with caching)
	 * @param dirPath Directory path
	 * @return List of ignore patterns
	 */
	private List<IgnorePattern> getPatternsForDirectory(Path dirPath) {
		return patternCache.computeIfAbsent(dirPath, this::loadPatternsForDirectory);
	}

	/**
	 * Load patterns from ignore files in a directory
	 * @param dirPath Directory to check for ignore files
	 * @return List of parsed patterns
	 */
	private List<IgnorePattern> loadPatternsForDirectory(Path dirPath) {
		List<IgnorePattern> patterns = new ArrayList<>();

		for (String ignoreFileName : IGNORE_FILE_NAMES) {
			Path ignoreFile = dirPath.resolve(ignoreFileName);
			if (Files.exists(ignoreFile) && Files.isRegularFile(ignoreFile)) {
				try {
					List<IgnorePattern> filePatterns = parseIgnoreFile(ignoreFile, dirPath);
					patterns.addAll(filePatterns);
				}
				catch (IOException e) {
					log.warn("Error reading ignore file: {}", ignoreFile, e);
				}
			}
		}

		return patterns;
	}

	/**
	 * Load all ignore files from the root directory tree
	 * @param rootPath Root path to search
	 */
	private void loadIgnoreFiles(Path rootPath) {
		if (!Files.exists(rootPath) || !Files.isDirectory(rootPath)) {
			return;
		}

		try {
			// Load ignore files recursively
			Files.walkFileTree(rootPath, new java.nio.file.SimpleFileVisitor<Path>() {
				@Override
				public java.nio.file.FileVisitResult preVisitDirectory(Path dir,
						java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
					// Load patterns for this directory (will cache them)
					getPatternsForDirectory(dir);
					return java.nio.file.FileVisitResult.CONTINUE;
				}
			});
		}
		catch (IOException e) {
			log.warn("Error loading ignore files from: {}", rootPath, e);
		}
	}

	/**
	 * Parse an ignore file and return patterns
	 * @param ignoreFile Path to ignore file
	 * @param baseDir Directory containing the ignore file (for relative patterns)
	 * @return List of parsed patterns
	 * @throws IOException If file cannot be read
	 */
	private List<IgnorePattern> parseIgnoreFile(Path ignoreFile, Path baseDir) throws IOException {
		List<IgnorePattern> patterns = new ArrayList<>();

		try (BufferedReader reader = Files.newBufferedReader(ignoreFile)) {
			String line;
			int lineNumber = 0;
			while ((line = reader.readLine()) != null) {
				lineNumber++;
				line = line.trim();

				// Skip empty lines and comments
				if (line.isEmpty() || line.startsWith("#")) {
					continue;
				}

				try {
					IgnorePattern pattern = new IgnorePattern(line, baseDir, rootPath);
					patterns.add(pattern);
				}
				catch (Exception e) {
					log.debug("Error parsing ignore pattern at line {} in {}: {}", lineNumber, ignoreFile, line, e);
				}
			}
		}

		return patterns;
	}

	/**
	 * Clear the pattern cache (useful for testing or when files change)
	 */
	public void clearCache() {
		patternCache.clear();
	}

	/**
	 * Represents a single ignore pattern
	 */
	private static class IgnorePattern {

		private final String originalPattern;

		private final Pattern regexPattern;

		private final boolean negation;

		private final boolean directoryOnly;

		private final boolean anchored;

		/**
		 * Create an ignore pattern from a pattern string
		 * @param pattern Pattern string from .gitignore
		 * @param baseDir Directory containing the .gitignore file
		 * @param rootPath Root path for the search
		 */
		IgnorePattern(String pattern, Path baseDir, Path rootPath) {
			this.originalPattern = pattern;

			// Check for negation
			if (pattern.startsWith("!")) {
				this.negation = true;
				pattern = pattern.substring(1);
			}
			else {
				this.negation = false;
			}

			// Check if pattern ends with / (directory only)
			if (pattern.endsWith("/")) {
				this.directoryOnly = true;
				pattern = pattern.substring(0, pattern.length() - 1);
			}
			else {
				this.directoryOnly = false;
			}

			// Check if pattern starts with / (anchored to root)
			if (pattern.startsWith("/")) {
				this.anchored = true;
				pattern = pattern.substring(1);
			}
			else {
				this.anchored = false;
			}

			// Convert to regex
			this.regexPattern = compilePattern(pattern);
		}

		/**
		 * Check if this pattern matches a path
		 * @param pathString Path string (with forward slashes)
		 * @param relativePath Path object for directory checks
		 * @param baseDir Directory containing the .gitignore file
		 * @return true if matches
		 */
		boolean matches(String pathString, Path relativePath, Path baseDir) {
			// Check if this is a directory-only pattern and we're checking a file
			if (directoryOnly) {
				try {
					Path fullPath = baseDir.resolve(relativePath);
					if (Files.exists(fullPath) && Files.isRegularFile(fullPath)) {
						// Pattern is directory-only but path is a file
						// Check if any parent directory matches
						Path parent = relativePath.getParent();
						if (parent != null) {
							String parentString = parent.toString().replace('\\', '/');
							return matchesPath(parentString);
						}
						return false;
					}
				}
				catch (Exception e) {
					// If we can't determine, try matching anyway
				}
			}

			return matchesPath(pathString);
		}

		/**
		 * Check if pattern matches a path string
		 * @param pathString Path string to match
		 * @return true if matches
		 */
		private boolean matchesPath(String pathString) {
			if (anchored) {
				// Pattern must match from start of path
				return regexPattern.matcher(pathString).find(0);
			}
			else {
				// Pattern can match anywhere
				// For patterns like "target", check if any segment matches
				// For patterns like "**/node_modules/**", check full path
				if (originalPattern.contains("**")) {
					return regexPattern.matcher(pathString).find();
				}
				else {
					// Check each segment
					String[] segments = pathString.split("/");
					for (String segment : segments) {
						if (regexPattern.matcher(segment).matches()) {
							return true;
						}
					}
					// Also check full path
					return regexPattern.matcher(pathString).find();
				}
			}
		}

		/**
		 * Convert gitignore pattern to regex
		 * @param pattern Gitignore pattern
		 * @return Compiled regex pattern
		 */
		private Pattern compilePattern(String pattern) {
			StringBuilder regex = new StringBuilder();

			// Handle ** (matches zero or more directories) - must be done carefully
			// Replace **/ with pattern that matches zero or more directories
			// Replace /** with pattern that matches everything under
			// Replace standalone ** with .*
			boolean hasDoubleStar = pattern.contains("**");

			// Convert to regex character by character
			for (int i = 0; i < pattern.length(); i++) {
				char c = pattern.charAt(i);
				if (c == '*' && i + 1 < pattern.length() && pattern.charAt(i + 1) == '*') {
					// Handle **
					if (i + 2 < pattern.length() && pattern.charAt(i + 2) == '/') {
						// **/ - matches zero or more directories
						regex.append("(.*/)?");
						i += 2; // Skip * and /
					}
					else if (i > 0 && pattern.charAt(i - 1) == '/') {
						// /** - matches everything under
						regex.append("/.*");
						i++; // Skip second *
					}
					else {
						// Standalone **
						regex.append(".*");
						i++; // Skip second *
					}
				}
				else if (c == '*') {
					regex.append("[^/]*"); // Match any characters except /
				}
				else if (c == '?') {
					regex.append("[^/]"); // Match single character except /
				}
				else if (c == '.') {
					regex.append("\\.");
				}
				else if (c == '+') {
					regex.append("\\+");
				}
				else if (c == '(') {
					regex.append("\\(");
				}
				else if (c == ')') {
					regex.append("\\)");
				}
				else if (c == '[') {
					regex.append("\\[");
				}
				else if (c == ']') {
					regex.append("\\]");
				}
				else if (c == '{') {
					regex.append("\\{");
				}
				else if (c == '}') {
					regex.append("\\}");
				}
				else if (c == '^') {
					regex.append("\\^");
				}
				else if (c == '$') {
					regex.append("\\$");
				}
				else if (c == '|') {
					regex.append("\\|");
				}
				else {
					if (Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '/') {
						regex.append(c);
					}
					else {
						regex.append("\\").append(c);
					}
				}
			}

			// Compile pattern
			try {
				if (anchored) {
					// Anchored patterns must match from start
					return Pattern.compile("^" + regex.toString());
				}
				else {
					// Non-anchored patterns can match anywhere
					// For patterns with **, we want to match anywhere
					// For simple patterns, we want to match segments or full path
					if (hasDoubleStar) {
						return Pattern.compile(regex.toString());
					}
					else {
						// Allow matching at start or after /
						return Pattern.compile("(^|/)" + regex.toString() + "(/|$)");
					}
				}
			}
			catch (Exception e) {
				log.warn("Error compiling pattern: {}", originalPattern, e);
				// Return a pattern that never matches
				return Pattern.compile("(?!)");
			}
		}

		boolean isNegation() {
			return negation;
		}

		boolean isDirectoryOnly() {
			return directoryOnly;
		}

	}

}
