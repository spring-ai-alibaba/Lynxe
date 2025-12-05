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
package com.alibaba.cloud.ai.lynxe.tool.convertToMarkdown;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.cloud.ai.lynxe.tool.code.ToolExecuteResult;
import com.alibaba.cloud.ai.lynxe.tool.filesystem.UnifiedDirectoryManager;

/**
 * Text to Markdown Processor
 *
 * Converts text files (.txt, .md, .json, .xml, .yaml, .yml, .log, .java, .py, .js, .html,
 * .css) to Markdown format Handles various text formats and converts them to clean
 * Markdown
 */
public class TextToMarkdownProcessor {

	private static final Logger log = LoggerFactory.getLogger(TextToMarkdownProcessor.class);

	private final UnifiedDirectoryManager directoryManager;

	public TextToMarkdownProcessor(UnifiedDirectoryManager directoryManager) {
		this.directoryManager = directoryManager;
	}

	/**
	 * Convert text file to Markdown
	 * @param sourceFile The source text file
	 * @param additionalRequirement Optional additional requirements for conversion
	 * @param currentPlanId Current plan ID for file operations
	 * @return ToolExecuteResult with conversion status
	 */
	public ToolExecuteResult convertToMarkdown(Path sourceFile, String additionalRequirement, String currentPlanId) {
		try {
			log.info("Converting text file to Markdown: {}", sourceFile.getFileName());

			// Step 0: Check if content.md already exists
			String originalFilename = sourceFile.getFileName().toString();
			String markdownFilename = generateMarkdownFilename(originalFilename);
			if (markdownFileExists(currentPlanId, markdownFilename)) {
				log.info("Markdown file already exists, skipping conversion: {}", markdownFilename);
				return new ToolExecuteResult(
						"Skipped conversion - content.md file already exists: " + markdownFilename);
			}

			// Step 1: Read the text file
			String content = readTextFile(sourceFile);
			if (content == null || content.trim().isEmpty()) {
				return new ToolExecuteResult("Error: Could not read content from text file");
			}

			// Step 2: Convert content to Markdown format
			String markdownContent = convertToMarkdownFormat(content, sourceFile, additionalRequirement);

			// Step 3: Generate output filename (already declared above)
			markdownFilename = generateMarkdownFilename(originalFilename);

			// Step 4: Save Markdown file
			Path outputFile = saveMarkdownFile(markdownContent, markdownFilename, currentPlanId);
			if (outputFile == null) {
				return new ToolExecuteResult("Error: Failed to save Markdown file");
			}

			// Step 5: Return success result
			String result = String.format(
					"Successfully converted text file to Markdown\n\n" + "**Output File**: %s\n\n", markdownFilename);

			// Add content if less than 1000 characters
			if (markdownContent.length() < 1000) {
				result += "**Content**:\n\n" + markdownContent;
			}

			log.info("Text to Markdown conversion completed: {} -> {}", originalFilename, markdownFilename);
			return new ToolExecuteResult(result);

		}
		catch (Exception e) {
			log.error("Error converting text file to Markdown: {}", sourceFile.getFileName(), e);
			return new ToolExecuteResult("Error: " + e.getMessage());
		}
	}

	/**
	 * Check if markdown file already exists
	 */
	private boolean markdownFileExists(String currentPlanId, String markdownFilename) {
		try {
			Path rootPlanDir = directoryManager.getRootPlanDirectory(currentPlanId);
			Path markdownFile = rootPlanDir.resolve(markdownFilename);
			return Files.exists(markdownFile);
		}
		catch (Exception e) {
			log.error("Error checking if markdown file exists: {}", markdownFilename, e);
			return false;
		}
	}

	/**
	 * Read text file content
	 */
	private String readTextFile(Path sourceFile) {
		try {
			return Files.readString(sourceFile);
		}
		catch (IOException e) {
			log.error("Error reading text file: {}", sourceFile.getFileName(), e);
			return null;
		}
	}

	/**
	 * Convert text content to Markdown format
	 */
	private String convertToMarkdownFormat(String content, Path sourceFile, String additionalRequirement) {
		StringBuilder markdown = new StringBuilder();
		String filename = sourceFile.getFileName().toString();
		String extension = getFileExtension(filename);

		// Add header based on file type
		markdown.append("# ").append(getFileTypeTitle(extension)).append("\n\n");

		if (additionalRequirement != null && !additionalRequirement.trim().isEmpty()) {
			markdown.append("**Additional Requirements**: ").append(additionalRequirement).append("\n\n");
		}

		markdown.append("**Source File**: `").append(filename).append("`\n\n");
		markdown.append("---\n\n");

		// Process content based on file type
		String processedContent = processContentByType(content, extension);
		markdown.append(processedContent);

		markdown.append("\n---\n\n");
		markdown.append("*This document was automatically converted from ")
			.append(extension)
			.append(" to Markdown format.*\n");

		return markdown.toString();
	}

	/**
	 * Process content based on file type
	 */
	private String processContentByType(String content, String extension) {
		String ext = extension.toLowerCase().substring(1);

		return switch (ext) {
			case "json" -> processJsonContent(content);
			case "xml" -> processXmlContent(content);
			case "yaml", "yml" -> processYamlContent(content);
			case "java", "py", "js", "html", "css" -> processCodeContent(content, ext);
			case "log" -> processLogContent(content);
			case "md" -> processMarkdownContent(content);
			default -> processPlainTextContent(content);
		};
	}

	/**
	 * Process JSON content
	 */
	private String processJsonContent(String content) {
		StringBuilder markdown = new StringBuilder();
		markdown.append("## JSON Content\n\n");
		markdown.append("```json\n").append(content).append("\n```\n\n");
		return markdown.toString();
	}

	/**
	 * Process XML content
	 */
	private String processXmlContent(String content) {
		StringBuilder markdown = new StringBuilder();
		markdown.append("## XML Content\n\n");
		markdown.append("```xml\n").append(content).append("\n```\n\n");
		return markdown.toString();
	}

	/**
	 * Process YAML content
	 */
	private String processYamlContent(String content) {
		StringBuilder markdown = new StringBuilder();
		markdown.append("## YAML Content\n\n");
		markdown.append("```yaml\n").append(content).append("\n```\n\n");
		return markdown.toString();
	}

	/**
	 * Process code content
	 */
	private String processCodeContent(String content, String language) {
		StringBuilder markdown = new StringBuilder();
		markdown.append("## ").append(language.toUpperCase()).append(" Code\n\n");
		markdown.append("```").append(language).append("\n").append(content).append("\n```\n\n");
		return markdown.toString();
	}

	/**
	 * Process log content
	 */
	private String processLogContent(String content) {
		StringBuilder markdown = new StringBuilder();
		markdown.append("## Log Content\n\n");

		String[] lines = content.split("\n");
		for (String line : lines) {
			String trimmedLine = line.trim();
			if (trimmedLine.isEmpty()) {
				markdown.append("\n");
				continue;
			}

			// Format log entries
			if (trimmedLine.contains("ERROR") || trimmedLine.contains("FATAL")) {
				markdown.append("❌ **ERROR**: ").append(trimmedLine).append("\n\n");
			}
			else if (trimmedLine.contains("WARN")) {
				markdown.append("⚠️ **WARNING**: ").append(trimmedLine).append("\n\n");
			}
			else if (trimmedLine.contains("INFO")) {
				markdown.append("ℹ️ **INFO**: ").append(trimmedLine).append("\n\n");
			}
			else {
				markdown.append(trimmedLine).append("\n");
			}
		}

		return markdown.toString();
	}

	/**
	 * Process existing Markdown content
	 */
	private String processMarkdownContent(String content) {
		StringBuilder markdown = new StringBuilder();
		markdown.append("## Markdown Content\n\n");
		markdown.append(content);
		return markdown.toString();
	}

	/**
	 * Process plain text content
	 */
	private String processPlainTextContent(String content) {
		StringBuilder markdown = new StringBuilder();
		String[] lines = content.split("\n");
		boolean inList = false;

		for (String line : lines) {
			String trimmedLine = line.trim();
			if (trimmedLine.isEmpty()) {
				markdown.append("\n");
				inList = false;
				continue;
			}

			// Detect headers
			if (isHeader(trimmedLine)) {
				markdown.append("## ").append(trimmedLine).append("\n\n");
				inList = false;
				continue;
			}

			// Detect lists
			if (isListItem(trimmedLine)) {
				if (!inList) {
					markdown.append("\n");
				}
				markdown.append("- ").append(trimmedLine.replaceFirst("^[\\-\\*\\+]\\s*", "")).append("\n");
				inList = true;
				continue;
			}

			// Detect numbered lists
			if (isNumberedListItem(trimmedLine)) {
				if (!inList) {
					markdown.append("\n");
				}
				markdown.append(trimmedLine).append("\n");
				inList = true;
				continue;
			}

			// Regular paragraph
			if (inList) {
				markdown.append("\n");
				inList = false;
			}
			markdown.append(trimmedLine).append("\n\n");
		}

		return markdown.toString();
	}

	/**
	 * Get file type title
	 */
	private String getFileTypeTitle(String extension) {
		String ext = extension.toLowerCase().substring(1);
		return switch (ext) {
			case "txt" -> "Text Document";
			case "md" -> "Markdown Document";
			case "json" -> "JSON Data";
			case "xml" -> "XML Document";
			case "yaml", "yml" -> "YAML Configuration";
			case "log" -> "Log File";
			case "java" -> "Java Source Code";
			case "py" -> "Python Script";
			case "js" -> "JavaScript Code";
			case "html" -> "HTML Document";
			case "css" -> "CSS Stylesheet";
			default -> "Text File";
		};
	}

	/**
	 * Check if line looks like a header
	 */
	private boolean isHeader(String line) {
		return line.length() > 3 && line.length() < 100 && !line.startsWith("-") && !line.startsWith("*")
				&& !line.startsWith("+") && !line.matches("^\\d+\\.") && Character.isUpperCase(line.charAt(0));
	}

	/**
	 * Check if line is a list item
	 */
	private boolean isListItem(String line) {
		return line.matches("^[\\-\\*\\+]\\s+.+");
	}

	/**
	 * Check if line is a numbered list item
	 */
	private boolean isNumberedListItem(String line) {
		return line.matches("^\\d+\\.\\s+.+");
	}

	/**
	 * Get file extension including the dot
	 */
	private String getFileExtension(String fileName) {
		int lastDotIndex = fileName.lastIndexOf('.');
		return lastDotIndex > 0 ? fileName.substring(lastDotIndex) : "";
	}

	/**
	 * Generate markdown filename by replacing extension with .md
	 */
	private String generateMarkdownFilename(String originalFilename) {
		int lastDotIndex = originalFilename.lastIndexOf('.');
		if (lastDotIndex > 0) {
			return originalFilename.substring(0, lastDotIndex) + ".md";
		}
		return originalFilename + ".md";
	}

	/**
	 * Save Markdown content to file
	 */
	private Path saveMarkdownFile(String content, String filename, String currentPlanId) {
		try {
			Path rootPlanDir = directoryManager.getRootPlanDirectory(currentPlanId);
			Path outputFile = rootPlanDir.resolve(filename);

			Files.write(outputFile, content.getBytes("UTF-8"), StandardOpenOption.CREATE,
					StandardOpenOption.TRUNCATE_EXISTING);

			log.info("Markdown file saved: {}", outputFile);
			return outputFile;
		}
		catch (IOException e) {
			log.error("Error saving Markdown file: {}", filename, e);
			return null;
		}
	}

}
