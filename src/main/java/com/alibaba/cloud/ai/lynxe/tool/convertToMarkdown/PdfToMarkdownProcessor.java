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

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import org.apache.commons.lang3.StringUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.cloud.ai.lynxe.tool.code.ToolExecuteResult;
import com.alibaba.cloud.ai.lynxe.tool.filesystem.UnifiedDirectoryManager;

/**
 * PDF to Markdown Processor
 *
 * Converts PDF files to Markdown format. Extracts text content directly from PDF files
 * and formats it as Markdown. Uses OCR processing when traditional text extraction fails
 * or when OCR is specifically requested.
 */
public class PdfToMarkdownProcessor {

	private static final Logger log = LoggerFactory.getLogger(PdfToMarkdownProcessor.class);

	private final UnifiedDirectoryManager directoryManager;

	private final PdfOcrProcessor ocrProcessor;

	public PdfToMarkdownProcessor(UnifiedDirectoryManager directoryManager, PdfOcrProcessor ocrProcessor) {
		this.directoryManager = directoryManager;
		this.ocrProcessor = ocrProcessor;
	}

	/**
	 * Convert PDF file to Markdown using OCR processing
	 * @param sourceFile The source PDF file
	 * @param additionalRequirement Optional additional requirements for conversion
	 * @param currentPlanId Current plan ID for file operations
	 * @return ToolExecuteResult with OCR conversion status
	 */
	private ToolExecuteResult convertToMarkdownWithOcr(Path sourceFile, String additionalRequirement,
			String currentPlanId) {
		try {
			log.info("Converting PDF file to Markdown using OCR: {}", sourceFile.getFileName());

			// Generate output filename first
			String originalFilename = sourceFile.getFileName().toString();
			String markdownFilename = generateMarkdownFilename(originalFilename);

			// Use OCR processor to extract text directly to markdown file
			ToolExecuteResult ocrResult = ocrProcessor.convertPdfToTextWithOcr(sourceFile, additionalRequirement,
					currentPlanId, markdownFilename);

			if (!ocrResult.getOutput().toLowerCase().contains("successfully")) {
				return ocrResult; // Return OCR error
			}

			// OCR processor has already saved the markdown file, so we can return the
			// result directly
			log.info("PDF to Markdown OCR conversion completed: {} -> {}", originalFilename, markdownFilename);
			return ocrResult;

		}
		catch (Exception e) {
			log.error("Error converting PDF file to Markdown with OCR: {}", sourceFile.getFileName(), e);
			return new ToolExecuteResult("Error: " + e.getMessage());
		}
	}

	/**
	 * Convert PDF file to Markdown using traditional text extraction or OCR
	 * @param sourceFile The source PDF file
	 * @param additionalRequirement Optional additional requirements for conversion
	 * @param currentPlanId Current plan ID for file operations
	 * @return ToolExecuteResult with conversion status
	 */
	public ToolExecuteResult convertToMarkdown(Path sourceFile, String additionalRequirement, String currentPlanId) {
		return convertToMarkdown(sourceFile, additionalRequirement, currentPlanId, false);
	}

	/**
	 * Convert PDF file to Markdown using traditional text extraction or OCR
	 * @param sourceFile The source PDF file
	 * @param additionalRequirement Optional additional requirements for conversion
	 * @param currentPlanId Current plan ID for file operations
	 * @param forceLlm If true, force using LLM/OCR processing instead of auto-detection
	 * @return ToolExecuteResult with conversion status
	 */
	public ToolExecuteResult convertToMarkdown(Path sourceFile, String additionalRequirement, String currentPlanId,
			boolean forceLlm) {
		try {
			log.info("Converting PDF file to Markdown: {}, forceLlm: {}", sourceFile.getFileName(), forceLlm);

			// Step 0: Check if content.md already exists
			String originalFilename = sourceFile.getFileName().toString();
			String markdownFilename = generateMarkdownFilename(originalFilename);
			if (markdownFileExists(currentPlanId, markdownFilename)) {
				log.info("Markdown file already exists, skipping conversion: {}", markdownFilename);
				return new ToolExecuteResult(
						"Skipped conversion - content.md file already exists: " + markdownFilename);
			}

			// Step 1: If forceLlm is true, directly use OCR processing
			if (forceLlm) {
				log.info("Force LLM/OCR processing requested for PDF: {}", sourceFile.getFileName());
				return convertToMarkdownWithOcr(sourceFile, additionalRequirement, currentPlanId);
			}

			// Step 2: Try traditional text extraction first
			String content = extractPdfContent(sourceFile);

			// Step 3: Check if OCR processing is needed
			if (needsOcrProcessing(sourceFile, content)) {
				log.info("OCR processing needed for PDF: {}", sourceFile.getFileName());
				return convertToMarkdownWithOcr(sourceFile, additionalRequirement, currentPlanId);
			}

			// Step 4: Convert content to Markdown format
			String markdownContent = convertToMarkdownFormat(content, additionalRequirement);

			// Step 5: Save Markdown file
			Path outputFile = saveMarkdownFile(markdownContent, markdownFilename, currentPlanId);
			if (outputFile == null) {
				return new ToolExecuteResult("Error: Failed to save Markdown file");
			}

			// Step 6: Return success result
			// Normalize filename to remove any ./ prefix for consistent output
			String normalizedFilename = normalizeFilename(markdownFilename);
			String result = String.format("Successfully converted PDF file to Markdown\n\n" + "**Output File**: %s\n\n"
					+ "**Processing Method**: Traditional Text Extraction\n\n", normalizedFilename);

			// Add content if less than 1000 characters
			if (markdownContent.length() < 1000) {
				result += "**Content**:\n\n" + markdownContent;
			}

			log.info("PDF to Markdown conversion completed: {} -> {}", originalFilename, markdownFilename);
			return new ToolExecuteResult(result);

		}
		catch (Exception e) {
			log.error("Error converting PDF file to Markdown: {}", sourceFile.getFileName(), e);
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
	 * Extract content from PDF file using traditional text extraction
	 * @param sourceFile The source PDF file
	 * @return Extracted text content or null if extraction fails
	 */
	private String extractPdfContent(Path sourceFile) {
		try {
			log.info("Extracting text content from PDF: {}", sourceFile.getFileName());

			try (PDDocument document = PDDocument.load(new File(sourceFile.toAbsolutePath().toString()))) {
				PDFTextStripper pdfStripper = new PDFTextStripper();
				String documentContentStr = pdfStripper.getText(document);

				if (StringUtils.isEmpty(documentContentStr)) {
					log.warn("No text content found in PDF: {}", sourceFile.getFileName());
					return null;
				}

				log.info("Successfully extracted {} characters from PDF: {}", documentContentStr.length(),
						sourceFile.getFileName());
				return documentContentStr;
			}
		}
		catch (Exception e) {
			log.error("Error extracting content from PDF: {}", sourceFile.getFileName(), e);
			return null;
		}
	}

	/**
	 * Determine if OCR processing is needed for the PDF
	 * @param sourceFile The source PDF file
	 * @param extractedContent The content extracted using traditional method
	 * @return true if OCR processing is recommended
	 */
	private boolean needsOcrProcessing(Path sourceFile, String extractedContent) {
		// If no content was extracted, definitely need OCR
		if (StringUtils.isEmpty(extractedContent)) {
			log.info("No content extracted, OCR processing needed for: {}", sourceFile.getFileName());
			return true;
		}

		// If content is very short (less than 100 characters), might need OCR
		if (extractedContent.trim().length() < 100) {
			log.info("Very short content extracted ({} chars), OCR processing recommended for: {}",
					extractedContent.trim().length(), sourceFile.getFileName());
			return true;
		}

		// If content contains mostly whitespace or special characters, might need OCR
		String cleanContent = extractedContent.replaceAll("\\s+", "");
		if (cleanContent.length() < extractedContent.length() * 0.3) {
			log.info("Content contains mostly whitespace, OCR processing recommended for: {}",
					sourceFile.getFileName());
			return true;
		}

		log.info("Content extraction successful, OCR processing not needed for: {}", sourceFile.getFileName());
		return false;
	}

	/**
	 * Convert extracted content to Markdown format
	 */
	private String convertToMarkdownFormat(String content, String additionalRequirement) {
		StringBuilder markdown = new StringBuilder();

		// Add header
		markdown.append("# PDF Document Conversion\n\n");

		if (additionalRequirement != null && !additionalRequirement.trim().isEmpty()) {
			markdown.append("**Additional Requirements**: ").append(additionalRequirement).append("\n\n");
		}

		markdown.append("---\n\n");

		// Process content line by line
		String[] lines = content.split("\n");
		boolean inList = false;
		boolean inCodeBlock = false;

		for (String line : lines) {
			String trimmedLine = line.trim();

			if (trimmedLine.isEmpty()) {
				markdown.append("\n");
				inList = false;
				continue;
			}

			// Detect code blocks
			if (trimmedLine.startsWith("```") || trimmedLine.startsWith("`")) {
				inCodeBlock = !inCodeBlock;
				markdown.append(line).append("\n");
				continue;
			}

			if (inCodeBlock) {
				markdown.append(line).append("\n");
				continue;
			}

			// Detect headers (lines that look like titles)
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

		markdown.append("\n---\n\n");
		markdown.append("*This document was automatically converted from PDF to Markdown format.*\n");

		return markdown.toString();
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
	 * Normalize filename to add ./ prefix for consistent output format
	 */
	private String normalizeFilename(String filename) {
		if (filename == null) {
			return "";
		}
		// Add ./ prefix if not present
		if (!filename.startsWith("./")) {
			return "./" + filename;
		}
		return filename;
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
