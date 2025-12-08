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

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

/**
 * Utility class to clean Chrome browsing history from userDataDir while preserving
 * cookies.
 *
 * Chrome stores browsing history in the following files: - Default/History (main history
 * database) - Default/History-journal (WAL journal file) - Default/History Provider Cache
 * (cache directory) - Default/History Index (index files)
 *
 * Cookies are stored separately in: - Default/Cookies - Default/Cookies-journal
 */
public class ChromeHistoryCleaner {

	private static final Logger logger = LoggerFactory.getLogger(ChromeHistoryCleaner.class);

	// History-related files and directories to delete
	private static final String[] HISTORY_FILES = { "History", "History-journal", "History Provider Cache",
			"History Index", "History Index *", // Pattern for History Index files
			"Favicons", // Often associated with history
			"Top Sites", // Top sites list
			"Shortcuts" // Shortcuts database
	};

	// Files to preserve (cookies and other important data)
	// Note: This list is for documentation purposes - we preserve these by NOT deleting
	// them
	@SuppressWarnings("unused")
	private static final String[] PRESERVE_FILES = { "Cookies", "Cookies-journal", "Login Data", "Login Data-journal",
			"Web Data", "Web Data-journal", "Local Storage", "Session Storage", "IndexedDB", "Cache", "Code Cache",
			"GPUCache" };

	/**
	 * Cleans browsing history from the specified userDataDir. This method should be
	 * called AFTER closing the browser context.
	 * @param userDataDir Path to the Chrome user data directory
	 * @return true if cleaning was successful, false otherwise
	 */
	public static boolean cleanHistory(String userDataDir) {
		if (userDataDir == null || userDataDir.isEmpty()) {
			logger.warn("userDataDir is null or empty");
			return false;
		}

		Path userDataPath = Paths.get(userDataDir);
		if (!Files.exists(userDataPath)) {
			logger.warn("userDataDir does not exist: {}", userDataDir);
			return false;
		}

		// Chrome stores profile data in "Default" directory (or "Profile 1", "Profile 2",
		// etc.)
		// For persistent contexts, it's usually "Default"
		Path defaultProfilePath = userDataPath.resolve("Default");

		if (!Files.exists(defaultProfilePath)) {
			logger.warn("Default profile directory does not exist: {}", defaultProfilePath);
			return false;
		}

		List<String> deletedFiles = new ArrayList<>();
		List<String> errors = new ArrayList<>();

		try {
			// Delete history files
			for (String historyFile : HISTORY_FILES) {
				if (historyFile.contains("*")) {
					// Handle pattern matching (e.g., "History Index *")
					String baseName = historyFile.replace("*", "").trim();
					deleteFilesByPattern(defaultProfilePath, baseName, deletedFiles, errors);
				}
				else {
					Path filePath = defaultProfilePath.resolve(historyFile);
					deleteFileIfExists(filePath, deletedFiles, errors);
				}
			}

			// Also clean history from other profile directories if they exist
			try (DirectoryStream<Path> profiles = Files.newDirectoryStream(userDataPath,
					entry -> Files.isDirectory(entry) && entry.getFileName().toString().startsWith("Profile"))) {
				for (Path profilePath : profiles) {
					cleanHistoryFromProfile(profilePath, deletedFiles, errors);
				}
			}
			catch (IOException e) {
				logger.warn("Error accessing profile directories: {}", e.getMessage());
			}

			logger.info("History cleaning completed. Deleted {} files.", deletedFiles.size());
			if (!errors.isEmpty()) {
				logger.warn("Encountered {} errors during cleaning.", errors.size());
				errors.forEach(error -> logger.warn("Error: {}", error));
			}

			return errors.isEmpty();

		}
		catch (Exception e) {
			logger.error("Error cleaning history: {}", e.getMessage(), e);
			return false;
		}
	}

	/**
	 * Cleans history from a specific profile directory.
	 */
	private static void cleanHistoryFromProfile(Path profilePath, List<String> deletedFiles, List<String> errors) {
		for (String historyFile : HISTORY_FILES) {
			if (historyFile.contains("*")) {
				String baseName = historyFile.replace("*", "").trim();
				deleteFilesByPattern(profilePath, baseName, deletedFiles, errors);
			}
			else {
				Path filePath = profilePath.resolve(historyFile);
				deleteFileIfExists(filePath, deletedFiles, errors);
			}
		}
	}

	/**
	 * Deletes a file if it exists.
	 */
	private static void deleteFileIfExists(Path filePath, List<String> deletedFiles, List<String> errors) {
		try {
			if (Files.exists(filePath)) {
				if (Files.isDirectory(filePath)) {
					deleteDirectoryRecursively(filePath);
					deletedFiles.add(filePath.toString());
					logger.debug("Deleted directory: {}", filePath);
				}
				else {
					Files.delete(filePath);
					deletedFiles.add(filePath.toString());
					logger.debug("Deleted file: {}", filePath);
				}
			}
		}
		catch (IOException e) {
			errors.add("Failed to delete " + filePath + ": " + e.getMessage());
			logger.warn("Failed to delete {}: {}", filePath, e.getMessage());
		}
	}

	/**
	 * Deletes files matching a pattern (e.g., "History Index").
	 */
	private static void deleteFilesByPattern(Path directory, String baseName, List<String> deletedFiles,
			List<String> errors) {
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory,
				entry -> entry.getFileName().toString().startsWith(baseName))) {
			for (Path path : stream) {
				deleteFileIfExists(path, deletedFiles, errors);
			}
		}
		catch (IOException e) {
			errors.add("Error listing files with pattern " + baseName + ": " + e.getMessage());
			logger.warn("Error listing files with pattern {}: {}", baseName, e.getMessage());
		}
	}

	/**
	 * Recursively deletes a directory and all its contents.
	 */
	private static void deleteDirectoryRecursively(Path directory) throws IOException {
		if (Files.exists(directory)) {
			Files.walkFileTree(directory, new SimpleFileVisitor<Path>() {
				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
					Files.delete(file);
					return FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
					Files.delete(dir);
					return FileVisitResult.CONTINUE;
				}
			});
		}
	}

	/**
	 * Verifies that cookies are still present after cleaning.
	 * @param userDataDir Path to the Chrome user data directory
	 * @return true if cookies file exists, false otherwise
	 */
	public static boolean verifyCookiesPreserved(String userDataDir) {
		if (userDataDir == null || userDataDir.isEmpty()) {
			return false;
		}

		Path cookiesPath = Paths.get(userDataDir).resolve("Default").resolve("Cookies");
		boolean exists = Files.exists(cookiesPath);

		if (exists) {
			logger.info("Cookies file verified: {}", cookiesPath);
		}
		else {
			logger.warn("Cookies file not found: {}", cookiesPath);
		}

		return exists;
	}

}
