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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.alibaba.cloud.ai.lynxe.config.LynxeProperties;

/**
 * Unified directory manager for all file system operations across tools. Provides a
 * centralized way to manage working directories, plan directories, and task directories
 * with security constraints.
 */
@Service
public class UnifiedDirectoryManager {

	private static final Logger log = LoggerFactory.getLogger(UnifiedDirectoryManager.class);

	private final LynxeProperties lynxeProperties;

	private final String workingDirectoryPath;

	// Directory structure constants
	private static final String EXTENSIONS_DIR = "extensions";

	private static final String INNER_STORAGE_DIR = "inner_storage";

	/**
	 * Fixed directory name for external linked folder mapping
	 */
	private static final String LINKED_EXTERNAL_DIR = "linked_external";

	/**
	 * Get the linked external directory path for a root plan. This is the symbolic link
	 * to the external folder configured in LynxeProperties.
	 * @param rootPlanId The root plan ID
	 * @return Path object of the linked external directory
	 * @throws IOException if the linked external directory is not available
	 */
	public Path getLinkedExternalDirectory(String rootPlanId) throws IOException {
		if (rootPlanId == null || rootPlanId.trim().isEmpty()) {
			throw new IllegalArgumentException("rootPlanId cannot be null or empty");
		}
		Path rootPlanDir = getRootPlanDirectory(rootPlanId);
		Path linkedExternalDir = rootPlanDir.resolve(LINKED_EXTERNAL_DIR);

		// Check if linked external directory exists
		if (!Files.exists(linkedExternalDir)) {
			String externalFolder = lynxeProperties.getExternalLinkedFolder();
			if (externalFolder == null || externalFolder.trim().isEmpty()) {
				throw new IOException(
						"External linked folder is not configured. Please set 'lynxe.general.externalLinkedFolder' in system settings.");
			}
			throw new IOException("Linked external directory does not exist: " + linkedExternalDir
					+ ". The external folder link may not have been created yet.");
		}

		// Check if it's a valid symbolic link or directory
		if (!Files.isDirectory(linkedExternalDir)) {
			throw new IOException("Linked external path is not a directory: " + linkedExternalDir);
		}

		return linkedExternalDir;
	}

	public UnifiedDirectoryManager(LynxeProperties lynxeProperties) {
		this.lynxeProperties = lynxeProperties;
		this.workingDirectoryPath = getWorkingDirectory("");
	}

	/**
	 * Get the main working directory (baseDir/extensions)
	 * @return Absolute path of the working directory
	 */
	public String getWorkingDirectoryPath() {
		return workingDirectoryPath;
	}

	/**
	 * Get the working directory path as a Path object
	 * @return Path object of the working directory
	 */
	public Path getWorkingDirectory() {
		return Paths.get(workingDirectoryPath);
	}

	/**
	 * Get the root plan directory (baseDir/extensions/inner_storage/rootPlanId) This
	 * directory is accessible by the current task and all its subtasks
	 * @param rootPlanId The root plan ID
	 * @return Path object of the root plan directory
	 */
	public Path getRootPlanDirectory(String rootPlanId) {
		if (rootPlanId == null || rootPlanId.trim().isEmpty()) {
			throw new IllegalArgumentException("rootPlanId cannot be null or empty");
		}
		Path rootPlanDir = getWorkingDirectory().resolve(INNER_STORAGE_DIR).resolve(rootPlanId);
		// Ensure directory exists and create external folder link if configured
		try {
			ensureDirectoryExists(rootPlanDir);
			ensureExternalFolderLink(rootPlanDir);
		}
		catch (IOException e) {
			log.warn("Failed to ensure root plan directory or external folder link for rootPlanId={}, path={}: {}",
					rootPlanId, rootPlanDir, e.getMessage(), e);
		}
		return rootPlanDir;
	}

	/**
	 * Get a subtask directory under the root plan directory
	 * (baseDir/extensions/inner_storage/rootPlanId/subTaskId)
	 * @param rootPlanId The root plan ID
	 * @param subTaskId The subtask ID
	 * @return Path object of the subtask directory
	 */
	public Path getSubTaskDirectory(String rootPlanId, String subTaskId) {
		if (subTaskId == null || subTaskId.trim().isEmpty()) {
			throw new IllegalArgumentException("subTaskId cannot be null or empty");
		}
		return getRootPlanDirectory(rootPlanId).resolve(subTaskId);
	}

	/**
	 * Get a specified directory with security validation. If LynxeProperties
	 * configuration does not allow external access, only directories within the working
	 * directory are allowed.
	 * @param targetPath The target directory path (absolute or relative)
	 * @return Path object of the validated directory
	 * @throws SecurityException if access is denied
	 * @throws IOException if path validation fails
	 */
	public Path getSpecifiedDirectory(String targetPath) throws IOException, SecurityException {
		if (targetPath == null || targetPath.trim().isEmpty()) {
			throw new IllegalArgumentException("targetPath cannot be null or empty");
		}

		Path target = Paths.get(targetPath);

		// If it's a relative path, resolve it against working directory
		if (!target.isAbsolute()) {
			target = getWorkingDirectory().resolve(targetPath);
		}

		target = target.normalize();

		// Security check: validate if target is within allowed scope
		if (!isPathAllowed(target)) {
			throw new SecurityException("Access denied: Path is outside allowed working directory scope: " + target);
		}

		return target;
	}

	/**
	 * Ensure a directory exists, creating it if necessary
	 * @param directory The directory path to ensure
	 * @throws IOException if directory creation fails
	 */
	public void ensureDirectoryExists(Path directory) throws IOException {
		if (!Files.exists(directory)) {
			Files.createDirectories(directory);
			log.debug("Created directory: {}", directory);
		}
	}

	/**
	 * Check if a relative path is accessing the linked_external directory
	 * @param relativePath The relative path to check
	 * @return true if the path is linked_external or starts with linked_external/
	 */
	public boolean isLinkedExternalPath(String relativePath) {
		if (relativePath == null || relativePath.isEmpty()) {
			return false;
		}
		return relativePath.equals(LINKED_EXTERNAL_DIR) || relativePath.startsWith(LINKED_EXTERNAL_DIR + "/");
	}

	/**
	 * Resolve a relative path within a root plan directory with proper handling of
	 * symbolic links. This method provides safe path resolution that: - Handles
	 * linked_external symbolic links without resolving them - Normalizes other paths to
	 * prevent path traversal attacks - Validates that the resolved path stays within the
	 * root plan directory
	 * @param rootPlanDirectory The root plan directory to resolve paths within
	 * @param relativePath The relative path to resolve
	 * @return The resolved Path object
	 * @throws IOException if the path is invalid or outside the root plan directory
	 */
	public Path resolveAndValidatePath(Path rootPlanDirectory, String relativePath) throws IOException {
		if (relativePath == null || relativePath.isEmpty()) {
			return rootPlanDirectory;
		}

		// Check if this is accessing linked_external directory (symbolic link to external
		// folder)
		boolean isLinkedExternal = isLinkedExternalPath(relativePath);

		Path resolvedPath;
		if (isLinkedExternal) {
			// For linked_external, don't use normalize() as it would resolve the symlink
			// and break the startsWith check. Use the symlink path directly.
			resolvedPath = rootPlanDirectory.resolve(relativePath);
		}
		else {
			// For normal paths, use normalize() to resolve any relative path elements
			resolvedPath = rootPlanDirectory.resolve(relativePath).normalize();
		}

		// Ensure the resolved path stays within root plan directory
		// For linked_external, this checks the symlink path, not the resolved target
		if (!resolvedPath.startsWith(rootPlanDirectory)) {
			throw new IOException("Access denied: Path is outside root plan directory: " + relativePath);
		}

		return resolvedPath;
	}

	/**
	 * Validate if a path is within the allowed working directory scope. This method
	 * enforces security by ensuring all file operations stay within the designated
	 * working directory. External access is not allowed through this method. Only
	 * LinkedFolderOperator can access external folders.
	 * @param targetPath The path to validate
	 * @return true if path is allowed (within working directory), false otherwise
	 */
	public boolean isPathAllowed(Path targetPath) {
		try {
			Path workingDir = getWorkingDirectory().toAbsolutePath().normalize();
			Path normalizedTarget = targetPath.toAbsolutePath().normalize();

			// Check if target is within working directory
			boolean isWithinWorkingDir = normalizedTarget.startsWith(workingDir);

			// Only allow paths within working directory
			// External access is not allowed through UnifiedDirectoryManager
			// Use LinkedFolderOperator for external folder access
			log.debug("Path validation - Working Dir: {}, Target: {}, Within: {}, Allowed: {}", workingDir,
					normalizedTarget, isWithinWorkingDir, isWithinWorkingDir);

			return isWithinWorkingDir;
		}
		catch (Exception e) {
			log.error("Error validating path: {}", targetPath, e);
			return false;
		}
	}

	/**
	 * Get working directory from base directory
	 * @param baseDir Base directory (if empty, use system property user.dir)
	 * @return Absolute path of the working directory
	 */
	private String getWorkingDirectory(String baseDir) {
		if (baseDir == null || baseDir.isEmpty()) {
			baseDir = System.getProperty("user.dir");
		}
		return Paths.get(baseDir, EXTENSIONS_DIR).toString();
	}

	/**
	 * Get the inner storage root directory
	 * @return Path object of the inner storage root directory
	 */
	public Path getInnerStorageRoot() {
		return getWorkingDirectory().resolve(INNER_STORAGE_DIR);
	}

	/**
	 * Create a relative path from working directory
	 * @param absolutePath The absolute path
	 * @return Relative path from working directory, or the original path if not within
	 * working directory
	 */
	public String getRelativePathFromWorkingDirectory(Path absolutePath) {
		try {
			Path workingDir = getWorkingDirectory().toAbsolutePath().normalize();
			Path normalized = absolutePath.toAbsolutePath().normalize();

			if (normalized.startsWith(workingDir)) {
				return workingDir.relativize(normalized).toString();
			}
			else {
				return absolutePath.toString();
			}
		}
		catch (Exception e) {
			log.error("Error getting relative path for: {}", absolutePath, e);
			return absolutePath.toString();
		}
	}

	/**
	 * Get LynxeProperties for configuration access
	 * @return LynxeProperties instance
	 */
	public LynxeProperties getLynxeProperties() {
		return lynxeProperties;
	}

	/**
	 * Clean up a subtask directory only
	 * @param rootPlanId The root plan ID
	 * @param subTaskId The subtask ID to clean up
	 * @throws IOException if directory deletion fails
	 */
	public void cleanupSubTaskDirectory(String rootPlanId, String subTaskId) throws IOException {
		Path subTaskDir = getSubTaskDirectory(rootPlanId, subTaskId);
		if (Files.exists(subTaskDir)) {
			deleteDirectoryRecursively(subTaskDir);
			log.info("Cleaned up subtask directory: {}", subTaskDir);
		}
	}

	/**
	 * Clean up the entire root plan directory and all its subtasks
	 * @param rootPlanId The root plan ID to clean up
	 * @throws IOException if directory deletion fails
	 */
	public void cleanupRootPlanDirectory(String rootPlanId) throws IOException {
		Path rootPlanDir = getRootPlanDirectory(rootPlanId);
		if (Files.exists(rootPlanDir)) {
			deleteDirectoryRecursively(rootPlanDir);
			log.info("Cleaned up root plan directory: {}", rootPlanDir);
		}
	}

	/**
	 * Recursively delete a directory and all its contents
	 * @param directory The directory to delete
	 * @throws IOException if deletion fails
	 */
	private void deleteDirectoryRecursively(Path directory) throws IOException {
		Files.walk(directory)
			.sorted((path1, path2) -> path2.compareTo(path1)) // Delete files before
																// directories
			.forEach(path -> {
				try {
					Files.delete(path);
				}
				catch (IOException e) {
					log.error("Failed to delete: {}", path, e);
				}
			});
	}

	/**
	 * Ensure external folder symbolic link exists in root plan directory. Creates a
	 * symbolic link from rootPlanId/linked_external to the configured external folder.
	 * @param rootPlanDir The root plan directory
	 * @throws IOException if link creation fails
	 */
	private void ensureExternalFolderLink(Path rootPlanDir) throws IOException {
		String externalFolder = lynxeProperties.getExternalLinkedFolder();
		if (externalFolder == null || externalFolder.trim().isEmpty()) {
			// No external folder configured, nothing to do
			return;
		}

		// Normalize the path: remove trailing slashes and resolve to absolute path
		String normalizedFolder = externalFolder.trim();
		// Remove trailing slashes (but keep root slash for Unix)
		while (normalizedFolder.length() > 1 && normalizedFolder.endsWith("/")) {
			normalizedFolder = normalizedFolder.substring(0, normalizedFolder.length() - 1);
		}

		Path externalPath;
		try {
			externalPath = Paths.get(normalizedFolder).toAbsolutePath().normalize();
		}
		catch (Exception e) {
			log.error("Failed to resolve external folder path: '{}' (normalized: '{}')", externalFolder,
					normalizedFolder, e);
			return;
		}

		Path linkPath = rootPlanDir.resolve(LINKED_EXTERNAL_DIR);

		// Check if external folder exists with detailed logging
		boolean exists = Files.exists(externalPath);
		boolean isDirectory = exists && Files.isDirectory(externalPath);
		boolean isReadable = exists && Files.isReadable(externalPath);

		log.debug("Checking external folder: path={}, exists={}, isDirectory={}, isReadable={}", externalPath, exists,
				isDirectory, isReadable);

		if (!exists) {
			// Additional check: try to resolve parent if path might be a file
			Path parentPath = externalPath.getParent();
			boolean parentExists = parentPath != null && Files.exists(parentPath);
			log.warn(
					"External linked folder does not exist: {} (normalized from: '{}'), parent exists: {}, skipping link creation",
					externalPath, externalFolder, parentExists);
			return;
		}

		if (!isDirectory) {
			log.warn(
					"External linked folder is not a directory: {} (exists: {}, isDirectory: {}), skipping link creation",
					externalPath, exists, isDirectory);
			return;
		}

		if (!isReadable) {
			log.warn("External linked folder is not readable: {}, skipping link creation", externalPath);
			return;
		}

		// Check if link already exists
		if (Files.exists(linkPath)) {
			// Check if it's already a valid symbolic link pointing to the correct target
			try {
				if (Files.isSymbolicLink(linkPath)) {
					Path existingTarget = Files.readSymbolicLink(linkPath);
					Path existingTargetAbsolute = linkPath.getParent().resolve(existingTarget).normalize();
					if (existingTargetAbsolute.equals(externalPath)) {
						// Link already exists and points to correct target
						log.debug("External folder link already exists: {} -> {}", linkPath, externalPath);
						return;
					}
					else {
						// Link exists but points to wrong target, remove it
						log.info("Removing existing link with wrong target: {} -> {}", linkPath, existingTarget);
						Files.delete(linkPath);
					}
				}
				else {
					// Link path exists but is not a symbolic link, remove it
					log.info("Removing existing non-symbolic link path: {}", linkPath);
					if (Files.isDirectory(linkPath)) {
						deleteDirectoryRecursively(linkPath);
					}
					else {
						Files.delete(linkPath);
					}
				}
			}
			catch (IOException e) {
				log.warn("Error checking existing link: {}, will try to recreate", e.getMessage());
				try {
					if (Files.isDirectory(linkPath)) {
						deleteDirectoryRecursively(linkPath);
					}
					else {
						Files.delete(linkPath);
					}
				}
				catch (IOException deleteException) {
					log.error("Failed to remove existing link path: {}", linkPath, deleteException);
					throw deleteException;
				}
			}
		}

		// Create symbolic link
		try {
			Files.createSymbolicLink(linkPath, externalPath);
			log.info("Created external folder symbolic link: {} -> {}", linkPath, externalPath);
		}
		catch (UnsupportedOperationException e) {
			// Symbolic links not supported on this platform, log warning
			log.warn("Symbolic links are not supported on this platform, cannot create link: {} -> {}", linkPath,
					externalPath);
		}
		catch (IOException e) {
			log.error("Failed to create external folder symbolic link: {} -> {}", linkPath, externalPath, e);
			throw e;
		}
	}

	/**
	 * Remove the external folder symbolic link from root plan directory when plan task
	 * finishes
	 * @param rootPlanId The root plan ID
	 */
	public void removeExternalFolderLink(String rootPlanId) {
		if (rootPlanId == null || rootPlanId.trim().isEmpty()) {
			log.warn("removeExternalFolderLink called with null or empty rootPlanId");
			return;
		}

		try {
			// Build path directly without calling getRootPlanDirectory() to avoid
			// recreating the link
			Path rootPlanDir = getWorkingDirectory().resolve(INNER_STORAGE_DIR).resolve(rootPlanId);
			Path linkPath = rootPlanDir.resolve(LINKED_EXTERNAL_DIR);

			if (!Files.exists(linkPath)) {
				log.debug("Symbolic link does not exist, nothing to remove: {}", linkPath);
				return;
			}

			// Check if it's a symbolic link
			if (Files.isSymbolicLink(linkPath)) {
				Files.delete(linkPath);
				log.info("Removed external folder symbolic link: {}", linkPath);
			}
			else if (Files.isDirectory(linkPath)) {
				// If it's a directory (not a symlink), remove it recursively
				deleteDirectoryRecursively(linkPath);
				log.info("Removed external folder directory (not a symlink): {}", linkPath);
			}
			else {
				// If it's a file, just delete it
				Files.delete(linkPath);
				log.info("Removed external folder path: {}", linkPath);
			}
		}
		catch (IOException e) {
			log.warn("Failed to remove external folder symbolic link for rootPlanId={}: {}", rootPlanId,
					e.getMessage());
		}
	}

}
