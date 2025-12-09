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

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFPicture;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.cloud.ai.lynxe.tool.code.ToolExecuteResult;
import com.alibaba.cloud.ai.lynxe.tool.filesystem.UnifiedDirectoryManager;

/**
 * Word to Markdown Processor
 *
 * Converts Word documents (.doc, .docx) to Markdown format Uses Apache POI to extract
 * text content and images, then formats as Markdown Images are saved in a subfolder with
 * the same name as the filename (without extension)
 */
public class WordToMarkdownProcessor {

	private static final Logger log = LoggerFactory.getLogger(WordToMarkdownProcessor.class);

	private final UnifiedDirectoryManager directoryManager;

	public WordToMarkdownProcessor(UnifiedDirectoryManager directoryManager) {
		this.directoryManager = directoryManager;
	}

	/**
	 * Convert Word document to Markdown
	 * @param sourceFile The source Word document file
	 * @param additionalRequirement Optional additional requirements for conversion
	 * @param currentPlanId Current plan ID for file operations
	 * @return ToolExecuteResult with conversion status
	 */
	public ToolExecuteResult convertToMarkdown(Path sourceFile, String additionalRequirement, String currentPlanId) {
		try {
			log.info("Converting Word document to Markdown: {}", sourceFile.getFileName());

			// Step 0: Check if content.md already exists
			String originalFilename = sourceFile.getFileName().toString();
			String markdownFilename = generateMarkdownFilename(originalFilename);
			if (markdownFileExists(currentPlanId, markdownFilename)) {
				log.info("Markdown file already exists, skipping conversion: {}", markdownFilename);
				return new ToolExecuteResult(
						"Skipped conversion - content.md file already exists: " + markdownFilename);
			}

			// Step 1: Read the Word document using Apache POI
			String content = extractWordContent(sourceFile, currentPlanId);
			if (content == null || content.trim().isEmpty()) {
				return new ToolExecuteResult("Error: Could not extract content from Word document");
			}

			// Step 2: Convert content to Markdown format
			String markdownContent = convertToMarkdownFormat(content, additionalRequirement);

			// Step 3: Generate output filename (already declared above)
			markdownFilename = generateMarkdownFilename(originalFilename);

			// Step 4: Save Markdown file
			Path outputFile = saveMarkdownFile(markdownContent, markdownFilename, currentPlanId);
			if (outputFile == null) {
				return new ToolExecuteResult("Error: Failed to save Markdown file");
			}

			// Step 5: Return success result
			String result = String.format(
					"Successfully converted Word document to Markdown\n\n" + "**Output File**: %s\n\n",
					markdownFilename);

			// Add content if less than 1000 characters
			if (markdownContent.length() < 1000) {
				result += "**Content**:\n\n" + markdownContent;
			}

			log.info("Word to Markdown conversion completed: {} -> {}", originalFilename, markdownFilename);
			return new ToolExecuteResult(result);

		}
		catch (Exception e) {
			log.error("Error converting Word document to Markdown: {}", sourceFile.getFileName(), e);
			return new ToolExecuteResult("Error: " + e.getMessage());
		}
	}

	/**
	 * Extract content from Word document using Apache POI
	 * Processes both paragraphs and tables in document order
	 */
	private String extractWordContent(Path sourceFile, String currentPlanId) {
		try (FileInputStream fis = new FileInputStream(sourceFile.toFile());
				XWPFDocument document = new XWPFDocument(fis)) {

			StringBuilder content = new StringBuilder();
			String imageFolderName = getImageFolderName(sourceFile);
			Path imageFolder = createImageFolder(currentPlanId, imageFolderName);

			// Process all body elements (paragraphs and tables) in document order
			// Note: XWPFDocument doesn't provide direct access to body elements in order,
			// so we process paragraphs and tables separately
			// For better ordering, we would need to use document.getBodyElements() if available
			
			// Process all paragraphs
			List<XWPFParagraph> paragraphs = document.getParagraphs();
			for (XWPFParagraph paragraph : paragraphs) {
				String paragraphText = processParagraph(paragraph, imageFolder);
				if (!paragraphText.trim().isEmpty()) {
					content.append(paragraphText).append("\n");
				}
			}

			// Process all tables
			List<XWPFTable> tables = document.getTables();
			for (XWPFTable table : tables) {
				String tableMarkdown = processTable(table);
				if (!tableMarkdown.trim().isEmpty()) {
					content.append("\n").append(tableMarkdown).append("\n");
				}
			}

			return content.toString();
		}
		catch (Exception e) {
			log.error("Error extracting content from Word document: {}", sourceFile.getFileName(), e);
			return null;
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
	 * Get image folder name based on filename without extension
	 */
	private String getImageFolderName(Path sourceFile) {
		String filename = sourceFile.getFileName().toString();
		int lastDotIndex = filename.lastIndexOf('.');
		if (lastDotIndex > 0) {
			return filename.substring(0, lastDotIndex);
		}
		return filename;
	}

	/**
	 * Create image folder for storing extracted images
	 */
	private Path createImageFolder(String currentPlanId, String imageFolderName) {
		try {
			Path rootPlanDir = directoryManager.getRootPlanDirectory(currentPlanId);
			Path imageFolder = rootPlanDir.resolve(imageFolderName);
			Files.createDirectories(imageFolder);
			return imageFolder;
		}
		catch (IOException e) {
			log.error("Error creating image folder: {}", imageFolderName, e);
			return null;
		}
	}

	/**
	 * Process a single paragraph and handle images
	 */
	private String processParagraph(XWPFParagraph paragraph, Path imageFolder) {
		StringBuilder paragraphText = new StringBuilder();
		List<XWPFRun> runs = paragraph.getRuns();

		for (XWPFRun run : runs) {
			String text = run.getText(0);
			if (text != null && !text.trim().isEmpty()) {
				paragraphText.append(text);
			}

			// Handle images
			List<XWPFPicture> pictures = run.getEmbeddedPictures();
			for (XWPFPicture picture : pictures) {
				String imageMarkdown = saveImageAndGetMarkdown(picture, imageFolder);
				if (imageMarkdown != null) {
					paragraphText.append("\n").append(imageMarkdown).append("\n");
				}
			}
		}

		return paragraphText.toString();
	}

	/**
	 * Process a Word table and convert it to Markdown table format
	 */
	private String processTable(XWPFTable table) {
		try {
			StringBuilder tableMarkdown = new StringBuilder();
			List<XWPFTableRow> rows = table.getRows();

			if (rows.isEmpty()) {
				return "";
			}

			// Process each row
			boolean isFirstRow = true;
			for (XWPFTableRow row : rows) {
				List<XWPFTableCell> cells = row.getTableCells();
				if (cells.isEmpty()) {
					continue;
				}

				// Build table row
				StringBuilder rowMarkdown = new StringBuilder("|");
				for (XWPFTableCell cell : cells) {
					String cellText = getCellText(cell);
					// Escape pipe characters in cell content
					cellText = cellText.replace("|", "\\|");
					// Replace newlines with <br> for markdown tables
					cellText = cellText.replace("\n", "<br>");
					rowMarkdown.append(" ").append(cellText.trim()).append(" |");
				}
				rowMarkdown.append("\n");
				tableMarkdown.append(rowMarkdown);

				// Add separator row after first row (header)
				if (isFirstRow) {
					StringBuilder separator = new StringBuilder("|");
					for (int i = 0; i < cells.size(); i++) {
						separator.append(" --- |");
					}
					separator.append("\n");
					tableMarkdown.append(separator);
					isFirstRow = false;
				}
			}

			return tableMarkdown.toString();
		}
		catch (Exception e) {
			log.error("Error processing table: {}", e.getMessage());
			return "";
		}
	}

	/**
	 * Extract text content from a table cell
	 */
	private String getCellText(XWPFTableCell cell) {
		StringBuilder cellText = new StringBuilder();
		List<XWPFParagraph> paragraphs = cell.getParagraphs();
		for (XWPFParagraph paragraph : paragraphs) {
			String text = paragraph.getText();
			if (text != null && !text.trim().isEmpty()) {
				if (cellText.length() > 0) {
					cellText.append(" ");
				}
				cellText.append(text.trim());
			}
		}
		return cellText.toString();
	}

	/**
	 * Save image and return markdown reference
	 */
	private String saveImageAndGetMarkdown(XWPFPicture picture, Path imageFolder) {
		try {
			if (imageFolder == null) {
				return null;
			}

			byte[] imageData = picture.getPictureData().getData();
			String extension = getImageExtension(picture.getPictureData().getPictureType());
			String imageName = "image_" + System.currentTimeMillis() + extension;
			Path imagePath = imageFolder.resolve(imageName);

			try (FileOutputStream fos = new FileOutputStream(imagePath.toFile())) {
				fos.write(imageData);
			}

			String relativePath = imageFolder.getFileName().toString() + "/" + imageName;
			return "![" + imageName + "](" + relativePath + ")";

		}
		catch (Exception e) {
			log.error("Error saving image: {}", e.getMessage());
			return null;
		}
	}

	/**
	 * Get image extension based on picture type
	 */
	private String getImageExtension(int pictureType) {
		switch (pictureType) {
			case 1:
				return ".png";
			case 2:
				return ".jpg";
			case 3:
				return ".gif";
			case 4:
				return ".bmp";
			case 5:
				return ".emf";
			case 6:
				return ".wmf";
			default:
				return ".png";
		}
	}

	/**
	 * Convert extracted content to Markdown format
	 */
	private String convertToMarkdownFormat(String content, String additionalRequirement) {
		StringBuilder markdown = new StringBuilder();

		// Add header if additional requirements specify it
		if (additionalRequirement != null && !additionalRequirement.trim().isEmpty()) {
			markdown.append("# Document Conversion\n\n");
			markdown.append("**Additional Requirements**: ").append(additionalRequirement).append("\n\n");
			markdown.append("---\n\n");
		}

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
