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
import java.nio.file.FileSystemLoopException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Utility for detecting and handling symbolic links to prevent circular references and
 * infinite loops during directory traversal operations.
 *
 * This component provides: - Detection of symbolic links - Detection of circular
 * references (symlinks pointing back to ancestors) - Safe directory traversal with
 * symlink cycle prevention - Visited path tracking to avoid processing the same directory
 * twice
 */
@Component
public class SymbolicLinkDetector {

	private static final Logger log = LoggerFactory.getLogger(SymbolicLinkDetector.class);

	/**
	 * Check if a path is a symbolic link
	 * @param path The path to check
	 * @return true if the path is a symbolic link
	 */
	public boolean isSymbolicLink(Path path) {
		return Files.isSymbolicLink(path);
	}

	/**
	 * Check if a symbolic link creates a circular reference by pointing to an ancestor
	 * directory. This prevents infinite loops when traversing directory trees.
	 * @param symlinkPath The symbolic link path to check
	 * @param rootPath The root path of the traversal (to determine ancestors)
	 * @return true if the symlink points to an ancestor and would create a cycle
	 */
	public boolean isCircularReference(Path symlinkPath, Path rootPath) {
		if (!Files.isSymbolicLink(symlinkPath)) {
			return false;
		}

		try {
			// Resolve the symbolic link to its target
			Path targetPath = Files.readSymbolicLink(symlinkPath);

			// Convert to absolute paths for comparison
			Path absoluteTarget = symlinkPath.getParent().resolve(targetPath).toRealPath();
			Path absoluteRoot = rootPath.toRealPath();

			// Get the symlink's parent path
			Path symlinkParent = symlinkPath.toRealPath().getParent();

			// Check if target is an ancestor of the symlink
			// Case 1: Target equals or is ancestor of root (points outside/up)
			if (absoluteRoot.startsWith(absoluteTarget)) {
				log.debug("Circular reference detected: symlink {} points to ancestor of root: {}", symlinkPath,
						absoluteTarget);
				return true;
			}

			// Case 2: Target is an ancestor of symlink itself
			if (symlinkParent != null && symlinkParent.startsWith(absoluteTarget)) {
				log.debug("Circular reference detected: symlink {} points to its ancestor: {}", symlinkPath,
						absoluteTarget);
				return true;
			}

			return false;
		}
		catch (IOException e) {
			log.warn("Error checking circular reference for symlink: {}", symlinkPath, e);
			// If we can't resolve, treat as potential circular reference (safe default)
			return true;
		}
	}

	/**
	 * Check if a symbolic link target is within the allowed root directory. This prevents
	 * symlinks from pointing outside the safe directory scope.
	 * @param symlinkPath The symbolic link path
	 * @param allowedRoot The root directory that targets must be within
	 * @return true if the symlink target is outside the allowed root
	 */
	public boolean isSymlinkOutsideRoot(Path symlinkPath, Path allowedRoot) {
		if (!Files.isSymbolicLink(symlinkPath)) {
			return false;
		}

		try {
			Path targetPath = Files.readSymbolicLink(symlinkPath);
			Path absoluteTarget = symlinkPath.getParent().resolve(targetPath).toRealPath();
			Path absoluteRoot = allowedRoot.toRealPath();

			return !absoluteTarget.startsWith(absoluteRoot);
		}
		catch (IOException e) {
			log.warn("Error checking symlink target for: {}", symlinkPath, e);
			// If we can't resolve, treat as outside (safe default)
			return true;
		}
	}

	/**
	 * Get information about a symbolic link for logging/debugging
	 * @param symlinkPath The symbolic link path
	 * @return String describing the symlink and its target
	 */
	public String getSymlinkInfo(Path symlinkPath) {
		if (!Files.isSymbolicLink(symlinkPath)) {
			return symlinkPath + " (not a symlink)";
		}

		try {
			Path target = Files.readSymbolicLink(symlinkPath);
			Path absoluteTarget = symlinkPath.getParent().resolve(target).normalize();
			return String.format("%s -> %s (target: %s)", symlinkPath, target, absoluteTarget);
		}
		catch (IOException e) {
			return symlinkPath + " (error reading symlink: " + e.getMessage() + ")";
		}
	}

	/**
	 * Create a FileVisitor that safely handles symbolic links during directory traversal.
	 * This visitor tracks visited directories to prevent infinite loops.
	 * @param rootPath The root path being traversed
	 * @param onFile Callback for each regular file encountered
	 * @param onDirectory Callback for each directory encountered (before entering)
	 * @return A FileVisitor that handles symlinks safely
	 */
	public SimpleFileVisitor<Path> createSafeFileVisitor(Path rootPath, FileCallback onFile,
			DirectoryCallback onDirectory) {
		return createSafeFileVisitor(rootPath, onFile, onDirectory, null);
	}

	/**
	 * Create a FileVisitor that safely handles symbolic links during directory traversal.
	 * This visitor tracks visited directories to prevent infinite loops and respects
	 * ignore file rules.
	 * @param rootPath The root path being traversed
	 * @param onFile Callback for each regular file encountered
	 * @param onDirectory Callback for each directory encountered (before entering)
	 * @param gitIgnoreMatcher Optional GitIgnoreMatcher to respect ignore file rules
	 * @return A FileVisitor that handles symlinks safely
	 */
	public SimpleFileVisitor<Path> createSafeFileVisitor(Path rootPath, FileCallback onFile,
			DirectoryCallback onDirectory, GitIgnoreMatcher gitIgnoreMatcher) {

		// Track visited real paths to prevent cycles
		Set<Path> visitedRealPaths = new HashSet<>();

		return new SimpleFileVisitor<Path>() {
			@Override
			public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
				// Get the real path to detect cycles
				Path realPath;
				try {
					realPath = dir.toRealPath();
				}
				catch (IOException e) {
					log.warn("Cannot resolve real path for directory: {}, skipping", dir);
					return FileVisitResult.SKIP_SUBTREE;
				}

				// Check if we've already visited this directory (cycle detection)
				if (visitedRealPaths.contains(realPath)) {
					log.warn("Cycle detected: already visited {}, skipping", realPath);
					return FileVisitResult.SKIP_SUBTREE;
				}

				// Mark as visited
				visitedRealPaths.add(realPath);

				// Check if directory should be skipped based on ignore rules
				if (gitIgnoreMatcher != null && gitIgnoreMatcher.shouldSkipDirectory(dir)) {
					log.debug("Skipping directory due to ignore rules: {}", dir);
					return FileVisitResult.SKIP_SUBTREE;
				}

				// Check if this is a symbolic link
				if (Files.isSymbolicLink(dir)) {
					// Check for circular reference
					if (isCircularReference(dir, rootPath)) {
						log.warn("Skipping circular symlink: {}", getSymlinkInfo(dir));
						return FileVisitResult.SKIP_SUBTREE;
					}
					log.debug("Following symlink: {}", getSymlinkInfo(dir));
				}

				// Call the directory callback if provided
				if (onDirectory != null) {
					FileVisitResult result = onDirectory.onDirectory(dir, attrs);
					if (result != null) {
						return result;
					}
				}

				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
				// Check if file should be ignored based on ignore rules
				if (gitIgnoreMatcher != null && gitIgnoreMatcher.isIgnored(file)) {
					log.debug("Skipping file due to ignore rules: {}", file);
					return FileVisitResult.CONTINUE;
				}

				if (onFile != null) {
					onFile.onFile(file, attrs);
				}
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
				// Handle FileSystemLoopException specifically
				if (exc instanceof FileSystemLoopException) {
					log.warn("File system loop detected: {}, skipping", file);
					return FileVisitResult.SKIP_SUBTREE;
				}

				// Log other errors and continue
				log.warn("Error accessing file: {}, error: {}", file, exc.getMessage());
				return FileVisitResult.CONTINUE;
			}
		};
	}

	/**
	 * Callback interface for processing files during traversal
	 */
	@FunctionalInterface
	public interface FileCallback {

		void onFile(Path file, BasicFileAttributes attrs) throws IOException;

	}

	/**
	 * Callback interface for processing directories during traversal. Return null or
	 * CONTINUE to continue traversal, or another FileVisitResult to control behavior.
	 */
	@FunctionalInterface
	public interface DirectoryCallback {

		FileVisitResult onDirectory(Path directory, BasicFileAttributes attrs) throws IOException;

	}

}
