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

import java.nio.file.Files;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.cloud.ai.lynxe.tool.AbstractBaseTool;
import com.alibaba.cloud.ai.lynxe.tool.code.ToolExecuteResult;
import com.alibaba.cloud.ai.lynxe.tool.excelProcessor.IExcelProcessingService;
import com.alibaba.cloud.ai.lynxe.tool.filesystem.UnifiedDirectoryManager;
import com.alibaba.cloud.ai.lynxe.tool.i18n.ToolI18nService;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Markdown Converter Tool - Converts various file types to Markdown format
 *
 * Features: - Supports Word documents (.doc, .docx) - Supports Excel files (.xlsx, .xls)
 * - Converts content to clean Markdown format - Saves converted files with .md extension
 * - Handles additional requirements for conversion
 */
public class MarkdownConverterTool extends AbstractBaseTool<MarkdownConverterTool.MarkdownConverterInput> {

	private static final Logger log = LoggerFactory.getLogger(MarkdownConverterTool.class);

	private static final String TOOL_NAME = "markdown_converter";

	private final UnifiedDirectoryManager directoryManager;

	private final PdfOcrProcessor ocrProcessor;

	private final ImageOcrProcessor imageOcrProcessor;

	private final IExcelProcessingService excelProcessingService;

	private final ObjectMapper objectMapper;

	private final ToolI18nService toolI18nService;

	public MarkdownConverterTool(UnifiedDirectoryManager directoryManager, PdfOcrProcessor ocrProcessor,
			ImageOcrProcessor imageOcrProcessor, IExcelProcessingService excelProcessingService,
			ObjectMapper objectMapper, ToolI18nService toolI18nService) {
		this.directoryManager = directoryManager;
		this.ocrProcessor = ocrProcessor;
		this.imageOcrProcessor = imageOcrProcessor;
		this.excelProcessingService = excelProcessingService;
		this.objectMapper = objectMapper;
		this.toolI18nService = toolI18nService;
	}

	/**
	 * Input class for markdown conversion operations
	 */
	public static class MarkdownConverterInput {

		@JsonProperty("filename")
		private String filename;

		@JsonProperty("additionalRequirement")
		private String additionalRequirement;

		@JsonProperty("forceLlmForPdf")
		private Boolean forceLlmForPdf;

		// Getters and setters
		public String getFilename() {
			return filename;
		}

		public void setFilename(String filename) {
			this.filename = filename;
		}

		public String getAdditionalRequirement() {
			return additionalRequirement;
		}

		public void setAdditionalRequirement(String additionalRequirement) {
			this.additionalRequirement = additionalRequirement;
		}

		public Boolean getForceLlmForPdf() {
			return forceLlmForPdf;
		}

		public void setForceLlmForPdf(Boolean forceLlmForPdf) {
			this.forceLlmForPdf = forceLlmForPdf;
		}

	}

	@Override
	public ToolExecuteResult run(MarkdownConverterInput input) {
		String filename = input.getFilename();
		String additionalRequirement = input.getAdditionalRequirement();
		Boolean forceLlmForPdf = input.getForceLlmForPdf();

		log.info("MarkdownConverterTool processing file: {} with additional requirement: {}, forceLlmForPdf: {}",
				filename, additionalRequirement, forceLlmForPdf);

		try {
			// Step 1: Validate input
			if (filename == null || filename.trim().isEmpty()) {
				return new ToolExecuteResult("Error: filename is required");
			}

			// Step 2: Get file extension
			String extension = getFileExtension(filename);
			if (extension.isEmpty()) {
				return new ToolExecuteResult("Error: File has no extension: " + filename);
			}

			// Step 3: Find the file in root plan directory
			Path sourceFile = findFileInRootPlan(filename);
			if (sourceFile == null || !Files.exists(sourceFile)) {
				return new ToolExecuteResult("Error: File not found: " + filename
						+ ". Please ensure the file exists in the root plan directory (rootPlanId/).");
			}

			// Step 4: Dispatch to appropriate processor
			String ext = extension.toLowerCase().substring(1);
			return switch (ext) {
				case "doc", "docx" -> processWordToMarkdown(sourceFile, additionalRequirement);
				case "xlsx", "xls" -> processExcelToMarkdown(sourceFile, additionalRequirement);
				case "pdf" -> processPdfToMarkdown(sourceFile, additionalRequirement, forceLlmForPdf);
				case "jpg", "jpeg", "png", "gif" -> processImageToMarkdown(sourceFile, additionalRequirement);
				case "eml" -> processEmlToMarkdown(sourceFile, additionalRequirement);
				case "txt", "md", "json", "xml", "yaml", "yml", "log", "java", "py", "js", "html", "css" ->
					processTextToMarkdown(sourceFile, additionalRequirement);
				default -> new ToolExecuteResult("Error: Unsupported file type: " + extension
						+ ". Supported types: .doc, .docx, .xlsx, .xls, .pdf, .jpg, .jpeg, .png, .gif, .eml, .txt, .md, .json, .xml, .yaml, .yml, .log, .java, .py, .js, .html, .css");
			};

		}
		catch (Exception e) {
			log.error("Error converting file to markdown: {}", filename, e);
			return new ToolExecuteResult("Error: " + e.getMessage());
		}
	}

	/**
	 * Process Word documents to Markdown
	 */
	private ToolExecuteResult processWordToMarkdown(Path sourceFile, String additionalRequirement) {
		try {
			WordToMarkdownProcessor processor = new WordToMarkdownProcessor(directoryManager);
			return processor.convertToMarkdown(sourceFile, additionalRequirement, rootPlanId);
		}
		catch (Exception e) {
			log.error("Word to Markdown conversion failed: {}", sourceFile.getFileName(), e);
			return new ToolExecuteResult("Word conversion error: " + e.getMessage());
		}
	}

	/**
	 * Process Excel files to Markdown
	 */
	private ToolExecuteResult processExcelToMarkdown(Path sourceFile, String additionalRequirement) {
		try {
			ExcelToMarkdownProcessor processor = new ExcelToMarkdownProcessor(directoryManager, excelProcessingService,
					objectMapper);
			return processor.convertToMarkdown(sourceFile, additionalRequirement, rootPlanId);
		}
		catch (Exception e) {
			log.error("Excel to Markdown conversion failed: {}", sourceFile.getFileName(), e);
			return new ToolExecuteResult("Excel conversion error: " + e.getMessage());
		}
	}

	/**
	 * Process PDF files to Markdown
	 */
	private ToolExecuteResult processPdfToMarkdown(Path sourceFile, String additionalRequirement,
			Boolean forceLlmForPdf) {
		try {
			PdfToMarkdownProcessor processor = new PdfToMarkdownProcessor(directoryManager, ocrProcessor);
			boolean forceLlm = forceLlmForPdf != null && forceLlmForPdf;
			return processor.convertToMarkdown(sourceFile, additionalRequirement, rootPlanId, forceLlm);
		}
		catch (Exception e) {
			log.error("PDF to Markdown conversion failed: {}", sourceFile.getFileName(), e);
			return new ToolExecuteResult("PDF conversion error: " + e.getMessage());
		}
	}

	/**
	 * Process image files to Markdown using OCR
	 */
	private ToolExecuteResult processImageToMarkdown(Path sourceFile, String additionalRequirement) {
		try {
			if (imageOcrProcessor == null) {
				return new ToolExecuteResult("Error: Image OCR processor is not available");
			}

			// Generate markdown filename
			String markdownFilename = generateMarkdownFilename(sourceFile.getFileName().toString());
			return imageOcrProcessor.convertImageToTextWithOcr(sourceFile, additionalRequirement, rootPlanId,
					markdownFilename);
		}
		catch (Exception e) {
			log.error("Image to Markdown conversion failed: {}", sourceFile.getFileName(), e);
			return new ToolExecuteResult("Image conversion error: " + e.getMessage());
		}
	}

	/**
	 * Process EML email files to Markdown
	 */
	private ToolExecuteResult processEmlToMarkdown(Path sourceFile, String additionalRequirement) {
		try {
			EmlToMarkdownProcessor processor = new EmlToMarkdownProcessor(directoryManager);
			return processor.convertToMarkdown(sourceFile, additionalRequirement, rootPlanId);
		}
		catch (Exception e) {
			log.error("EML to Markdown conversion failed: {}", sourceFile.getFileName(), e);
			return new ToolExecuteResult("EML conversion error: " + e.getMessage());
		}
	}

	/**
	 * Process text files to Markdown
	 */
	private ToolExecuteResult processTextToMarkdown(Path sourceFile, String additionalRequirement) {
		try {
			TextToMarkdownProcessor processor = new TextToMarkdownProcessor(directoryManager);
			return processor.convertToMarkdown(sourceFile, additionalRequirement, rootPlanId);
		}
		catch (Exception e) {
			log.error("Text to Markdown conversion failed: {}", sourceFile.getFileName(), e);
			return new ToolExecuteResult("Text conversion error: " + e.getMessage());
		}
	}

	/**
	 * Find file in root plan directory (same as GlobalFileOperator) Checks root plan
	 * directory first, then subplan directory if applicable
	 */
	private Path findFileInRootPlan(String filename) {
		try {
			if (this.rootPlanId == null || this.rootPlanId.isEmpty()) {
				log.error("rootPlanId is required for file operations but is null or empty");
				return null;
			}

			// Normalize the file path to remove plan ID prefixes and relative path
			// indicators
			String normalizedPath = normalizeFilePath(filename);

			// Get the root plan directory (same as GlobalFileOperator)
			Path rootPlanDirectory = directoryManager.getRootPlanDirectory(rootPlanId);

			// Check root plan directory first
			Path rootPlanPath = rootPlanDirectory.resolve(normalizedPath).normalize();

			// Ensure the path stays within the root plan directory
			if (!rootPlanPath.startsWith(rootPlanDirectory)) {
				log.warn("File path is outside root plan directory: {}", filename);
				return null;
			}

			// If file exists in root plan directory, use it
			if (Files.exists(rootPlanPath)) {
				log.info("Found file in root plan directory: {}", filename);
				return rootPlanPath;
			}

			// If currentPlanId exists and differs from rootPlanId, check subplan
			// directory
			if (this.currentPlanId != null && !this.currentPlanId.isEmpty()
					&& !this.currentPlanId.equals(this.rootPlanId)) {
				Path subplanDirectory = rootPlanDirectory.resolve(this.currentPlanId);
				Path subplanPath = subplanDirectory.resolve(normalizedPath).normalize();

				// Ensure subplan path stays within subplan directory
				if (subplanPath.startsWith(subplanDirectory)) {
					// If file exists in subplan directory, use it
					if (Files.exists(subplanPath)) {
						log.info("Found file in subplan directory: {}", filename);
						return subplanPath;
					}
				}
			}

			log.warn("File not found in root plan directory: {}", filename);
			return null;
		}
		catch (Exception e) {
			log.error("Error finding file: {}", filename, e);
			return null;
		}
	}

	/**
	 * Normalize file path by removing plan ID prefixes and relative path indicators (same
	 * as GlobalFileOperator)
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
	 * Get file extension including the dot
	 */
	private String getFileExtension(String fileName) {
		int lastDotIndex = fileName.lastIndexOf('.');
		return lastDotIndex > 0 ? fileName.substring(lastDotIndex) : "";
	}

	/**
	 * Generate markdown filename by replacing extension with .md
	 */
	protected String generateMarkdownFilename(String originalFilename) {
		int lastDotIndex = originalFilename.lastIndexOf('.');
		if (lastDotIndex > 0) {
			return originalFilename.substring(0, lastDotIndex) + ".md";
		}
		return originalFilename + ".md";
	}

	// ===== Tool Interface Implementation =====

	@Override
	public String getName() {
		return TOOL_NAME;
	}

	@Override
	public String getDescription() {
		return toolI18nService.getDescription("markdown-converter-tool");
	}

	@Override
	public String getParameters() {
		return toolI18nService.getParameters("markdown-converter-tool");
	}

	@Override
	public Class<MarkdownConverterInput> getInputType() {
		return MarkdownConverterInput.class;
	}

	@Override
	public void cleanup(String planId) {
		log.info("MarkdownConverterTool cleanup for planId: {}", planId);
	}

	@Override
	public String getCurrentToolStateString() {
		return "";
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
