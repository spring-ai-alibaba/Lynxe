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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.cloud.ai.lynxe.tool.code.ToolExecuteResult;
import com.alibaba.cloud.ai.lynxe.tool.excelProcessor.ExcelProcessorTool;
import com.alibaba.cloud.ai.lynxe.tool.excelProcessor.IExcelProcessingService;
import com.alibaba.cloud.ai.lynxe.tool.filesystem.UnifiedDirectoryManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Excel to Markdown Processor
 *
 * Converts Excel files (.xlsx, .xls) to Markdown format Uses ExcelProcessorTool to
 * extract data and formats it as Markdown tables
 */
public class ExcelToMarkdownProcessor {

	private static final Logger log = LoggerFactory.getLogger(ExcelToMarkdownProcessor.class);

	private final UnifiedDirectoryManager directoryManager;

	private final IExcelProcessingService excelProcessingService;

	private final ObjectMapper objectMapper;

	public ExcelToMarkdownProcessor(UnifiedDirectoryManager directoryManager,
			IExcelProcessingService excelProcessingService, ObjectMapper objectMapper) {
		this.directoryManager = directoryManager;
		this.excelProcessingService = excelProcessingService;
		this.objectMapper = objectMapper;
	}

	/**
	 * Convert Excel file to Markdown
	 * @param sourceFile The source Excel file
	 * @param additionalRequirement Optional additional requirements for conversion
	 * @param currentPlanId Current plan ID for file operations
	 * @return ToolExecuteResult with conversion status
	 */
	public ToolExecuteResult convertToMarkdown(Path sourceFile, String additionalRequirement, String currentPlanId) {
		try {
			log.info("Converting Excel file to Markdown: {}", sourceFile.getFileName());

			// Step 0: Check if content.md already exists
			String originalFilename = sourceFile.getFileName().toString();
			String markdownFilename = generateMarkdownFilename(originalFilename);
			if (markdownFileExists(currentPlanId, markdownFilename)) {
				log.info("Markdown file already exists, skipping conversion: {}", markdownFilename);
				return new ToolExecuteResult(
						"Skipped conversion - content.md file already exists: " + markdownFilename);
			}

			// Step 1: Get Excel structure and data
			String structureInfo = getExcelStructure(sourceFile, currentPlanId);
			String dataContent = getExcelData(sourceFile, currentPlanId, structureInfo);

			if (structureInfo == null && dataContent == null) {
				return new ToolExecuteResult("Error: Could not extract content from Excel file");
			}

			// Step 2: Convert to Markdown format
			String markdownContent = convertToMarkdownFormat(structureInfo, dataContent, additionalRequirement);

			// Step 3: Generate output filename (already declared above)
			markdownFilename = generateMarkdownFilename(originalFilename);

			// Step 4: Save Markdown file
			Path outputFile = saveMarkdownFile(markdownContent, markdownFilename, currentPlanId);
			if (outputFile == null) {
				return new ToolExecuteResult("Error: Failed to save Markdown file");
			}

			// Step 5: Return success result
			String result = String.format(
					"Successfully converted Excel file to Markdown\n\n" + "**Output File**: %s\n\n", markdownFilename);

			// Add content if less than 1000 characters
			if (markdownContent.length() < 1000) {
				result += "**Content**:\n\n" + markdownContent;
			}

			log.info("Excel to Markdown conversion completed: {} -> {}", originalFilename, markdownFilename);
			return new ToolExecuteResult(result);

		}
		catch (Exception e) {
			log.error("Error converting Excel file to Markdown: {}", sourceFile.getFileName(), e);
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
	 * Get Excel file structure information
	 */
	private String getExcelStructure(Path sourceFile, String currentPlanId) {
		try {
			IExcelProcessingService excelService = excelProcessingService;
			ExcelProcessorTool excelTool = new ExcelProcessorTool(excelService);
			ExcelProcessorTool.ExcelInput input = new ExcelProcessorTool.ExcelInput();
			input.setAction("get_structure");
			input.setFilePath(sourceFile.toString());

			excelTool.setCurrentPlanId(currentPlanId);
			excelTool.setRootPlanId(currentPlanId);

			ToolExecuteResult result = excelTool.run(input);
			return result != null ? result.getOutput() : null;
		}
		catch (Exception e) {
			log.warn("Could not get Excel structure: {}", e.getMessage());
			return null;
		}
	}

	/**
	 * Get Excel file data content Tries to read data from all worksheets found in the
	 * structure
	 */
	private String getExcelData(Path sourceFile, String currentPlanId, String structureInfo) {
		try {
			// Extract worksheet names from structure info
			List<String> worksheetNames = extractWorksheetNames(structureInfo);
			if (worksheetNames.isEmpty()) {
				// Fallback: try common worksheet names
				worksheetNames.add("Sheet1");
			}

			IExcelProcessingService excelService = excelProcessingService;
			ExcelProcessorTool excelTool = new ExcelProcessorTool(excelService);
			excelTool.setCurrentPlanId(currentPlanId);
			excelTool.setRootPlanId(currentPlanId);

			// Try to read data from each worksheet
			List<String> allDataResults = new ArrayList<>();
			for (String worksheetName : worksheetNames) {
				try {
					ExcelProcessorTool.ExcelInput input = new ExcelProcessorTool.ExcelInput();
					input.setAction("read_data");
					input.setFilePath(sourceFile.toString());
					input.setWorksheetName(worksheetName);

					ToolExecuteResult result = excelTool.run(input);
					if (result != null && result.getOutput() != null) {
						// Check if result contains error
						if (!result.getOutput().contains("\"error\"")) {
							allDataResults.add(result.getOutput());
							log.info("Successfully read data from worksheet: {}", worksheetName);
						}
						else {
							log.warn("Error reading worksheet {}: {}", worksheetName, result.getOutput());
						}
					}
				}
				catch (Exception e) {
					log.warn("Could not read data from worksheet {}: {}", worksheetName, e.getMessage());
					// Continue to next worksheet
				}
			}

			if (allDataResults.isEmpty()) {
				log.warn("Could not read data from any worksheet");
				return null;
			}

			// Combine all worksheet data
			if (allDataResults.size() == 1) {
				return allDataResults.get(0);
			}
			else {
				// Multiple worksheets: combine them
				return combineWorksheetData(allDataResults);
			}
		}
		catch (Exception e) {
			log.warn("Could not get Excel data: {}", e.getMessage());
			return null;
		}
	}

	/**
	 * Extract worksheet names from structure info JSON
	 */
	private List<String> extractWorksheetNames(String structureInfo) {
		List<String> worksheetNames = new ArrayList<>();
		if (structureInfo == null || structureInfo.trim().isEmpty()) {
			return worksheetNames;
		}

		try {
			// Try to parse as JSON
			JsonNode rootNode = objectMapper.readTree(structureInfo);

			// Navigate to structure field: data.structure
			JsonNode dataNode = rootNode.get("data");
			if (dataNode != null) {
				JsonNode structureNode = dataNode.get("structure");
				if (structureNode != null && structureNode.isObject()) {
					Iterator<String> fieldNames = structureNode.fieldNames();
					while (fieldNames.hasNext()) {
						worksheetNames.add(fieldNames.next());
					}
				}
			}

			// Alternative: try direct structure field
			if (worksheetNames.isEmpty()) {
				JsonNode structureNode = rootNode.get("structure");
				if (structureNode != null && structureNode.isObject()) {
					Iterator<String> fieldNames = structureNode.fieldNames();
					while (fieldNames.hasNext()) {
						worksheetNames.add(fieldNames.next());
					}
				}
			}
		}
		catch (Exception e) {
			log.debug("Could not parse structure info as JSON, trying text extraction: {}", e.getMessage());
			// Fallback: try to extract from text
			// Look for patterns like "RI":[...] or "AIR":[...]
			if (structureInfo.contains("\"RI\"")) {
				worksheetNames.add("RI");
			}
			if (structureInfo.contains("\"AIR\"")) {
				worksheetNames.add("AIR");
			}
		}

		log.info("Extracted worksheet names: {}", worksheetNames);
		return worksheetNames;
	}

	/**
	 * Combine data from multiple worksheets Each dataResult is a JSON string from
	 * ExcelProcessorTool, format: {"message":"...", "data":{"action":"read_data",
	 * "worksheet_name":"...", "data":[[...]], ...}} We extract the actual data array and
	 * worksheet name from each result
	 */
	private String combineWorksheetData(List<String> dataResults) {
		try {
			List<Map<String, Object>> worksheetsData = new ArrayList<>();

			for (String dataResult : dataResults) {
				try {
					JsonNode rootNode = objectMapper.readTree(dataResult);
					JsonNode dataNode = rootNode.get("data");
					if (dataNode != null) {
						Map<String, Object> worksheetInfo = new HashMap<>();
						worksheetInfo.put("worksheet_name", dataNode.get("worksheet_name").asText());
						worksheetInfo.put("data", objectMapper.treeToValue(dataNode.get("data"), List.class));
						worksheetInfo.put("rows_read", dataNode.get("rows_read").asInt());
						worksheetsData.add(worksheetInfo);
					}
				}
				catch (Exception e) {
					log.warn("Failed to parse worksheet data result: {}", e.getMessage());
					// Keep original string as fallback
					Map<String, Object> worksheetInfo = new HashMap<>();
					worksheetInfo.put("raw_data", dataResult);
					worksheetsData.add(worksheetInfo);
				}
			}

			Map<String, Object> combined = new HashMap<>();
			combined.put("worksheets", worksheetsData);
			combined.put("worksheet_count", worksheetsData.size());

			return objectMapper.writeValueAsString(combined);
		}
		catch (Exception e) {
			log.error("Failed to combine worksheet data: {}", e.getMessage());
			// Fallback: return simple concatenation
			StringBuilder combined = new StringBuilder();
			combined.append("{\"worksheets\":[");
			for (int i = 0; i < dataResults.size(); i++) {
				if (i > 0) {
					combined.append(",");
				}
				combined.append("\"").append(dataResults.get(i).replace("\"", "\\\"")).append("\"");
			}
			combined.append("]}");
			return combined.toString();
		}
	}

	/**
	 * Convert Excel content to Markdown format
	 */
	private String convertToMarkdownFormat(String structureInfo, String dataContent, String additionalRequirement) {
		StringBuilder markdown = new StringBuilder();

		// Add header
		markdown.append("# Excel Data Conversion\n\n");

		if (additionalRequirement != null && !additionalRequirement.trim().isEmpty()) {
			markdown.append("**Additional Requirements**: ").append(additionalRequirement).append("\n\n");
		}

		// Add structure information if available
		if (structureInfo != null && !structureInfo.trim().isEmpty()) {
			markdown.append("## File Structure\n\n");
			markdown.append(convertStructureToMarkdown(structureInfo));
			markdown.append("\n");
		}

		// Add data content if available
		if (dataContent != null && !dataContent.trim().isEmpty()) {
			markdown.append("## Data Content\n\n");
			markdown.append(convertDataToMarkdown(dataContent));
			markdown.append("\n");
		}

		// Add footer
		markdown.append("---\n\n");
		markdown.append("*This document was automatically converted from Excel to Markdown format.*\n");

		return markdown.toString();
	}

	/**
	 * Convert structure information to Markdown
	 */
	private String convertStructureToMarkdown(String structureInfo) {
		StringBuilder markdown = new StringBuilder();
		String[] lines = structureInfo.split("\n");

		for (String line : lines) {
			String trimmedLine = line.trim();
			if (trimmedLine.isEmpty()) {
				markdown.append("\n");
				continue;
			}

			// Convert structure info to readable format
			if (trimmedLine.contains("worksheets") || trimmedLine.contains("sheets")) {
				markdown.append("### ").append(trimmedLine).append("\n\n");
			}
			else if (trimmedLine.contains("rows") || trimmedLine.contains("columns")) {
				markdown.append("- ").append(trimmedLine).append("\n");
			}
			else {
				markdown.append(trimmedLine).append("\n");
			}
		}

		return markdown.toString();
	}

	/**
	 * Convert data content to Markdown tables Handles both single worksheet and multiple
	 * worksheets data
	 */
	private String convertDataToMarkdown(String dataContent) {
		StringBuilder markdown = new StringBuilder();

		// Try to parse as JSON first (for structured data from ExcelProcessorTool)
		try {
			JsonNode rootNode = objectMapper.readTree(dataContent);

			// Check if it's multiple worksheets format
			JsonNode worksheetsNode = rootNode.get("worksheets");
			if (worksheetsNode != null && worksheetsNode.isArray()) {
				// Multiple worksheets
				for (JsonNode worksheetNode : worksheetsNode) {
					String worksheetName = worksheetNode.has("worksheet_name")
							? worksheetNode.get("worksheet_name").asText() : "Unknown";
					JsonNode dataNode = worksheetNode.get("data");

					if (dataNode != null && dataNode.isArray()) {
						markdown.append("### Worksheet: ").append(worksheetName).append("\n\n");
						markdown.append(convertDataArrayToMarkdownTable(dataNode));
						markdown.append("\n");
					}
				}
				return markdown.toString();
			}

			// Check if it's single worksheet format from ExcelProcessorTool
			JsonNode dataNode = rootNode.get("data");
			if (dataNode != null) {
				JsonNode worksheetDataNode = dataNode.get("data");
				if (worksheetDataNode != null && worksheetDataNode.isArray()) {
					String worksheetName = dataNode.has("worksheet_name") ? dataNode.get("worksheet_name").asText()
							: "Sheet1";
					markdown.append("### Worksheet: ").append(worksheetName).append("\n\n");
					markdown.append(convertDataArrayToMarkdownTable(worksheetDataNode));
					return markdown.toString();
				}
			}
		}
		catch (Exception e) {
			log.debug("Data content is not JSON format, using text parsing: {}", e.getMessage());
		}

		// Fallback: treat as plain text and parse line by line
		String[] lines = dataContent.split("\n");
		boolean inTable = false;
		int tableRowCount = 0;

		for (String line : lines) {
			String trimmedLine = line.trim();
			if (trimmedLine.isEmpty()) {
				if (inTable) {
					markdown.append("\n");
					inTable = false;
					tableRowCount = 0;
				}
				continue;
			}

			// Check if this looks like table data
			if (isTableRow(trimmedLine)) {
				if (!inTable) {
					markdown.append("### Data Table\n\n");
					inTable = true;
					tableRowCount = 0;
				}

				// Convert to Markdown table format
				String[] cells = splitTableRow(trimmedLine);
				if (cells.length > 0) {
					if (tableRowCount == 0) {
						// Header row
						markdown.append("| ").append(String.join(" | ", cells)).append(" |\n");
						markdown.append("| ").append("--- |".repeat(cells.length)).append("\n");
					}
					else {
						// Data row
						markdown.append("| ").append(String.join(" | ", cells)).append(" |\n");
					}
					tableRowCount++;
				}
			}
			else {
				if (inTable) {
					markdown.append("\n");
					inTable = false;
					tableRowCount = 0;
				}
				markdown.append(trimmedLine).append("\n");
			}
		}

		if (inTable) {
			markdown.append("\n");
		}

		return markdown.toString();
	}

	/**
	 * Convert JSON data array to Markdown table
	 */
	private String convertDataArrayToMarkdownTable(JsonNode dataArray) {
		if (dataArray == null || !dataArray.isArray() || dataArray.size() == 0) {
			return "*No data available*\n\n";
		}

		StringBuilder markdown = new StringBuilder();

		// First row is header
		JsonNode firstRow = dataArray.get(0);
		if (firstRow != null && firstRow.isArray()) {
			List<String> headers = new ArrayList<>();
			for (JsonNode cell : firstRow) {
				headers.add(cell.asText(""));
			}

			if (!headers.isEmpty()) {
				// Header row
				markdown.append("| ").append(String.join(" | ", headers)).append(" |\n");
				markdown.append("| ").append("--- |".repeat(headers.size())).append("\n");

				// Data rows
				for (int i = 1; i < dataArray.size(); i++) {
					JsonNode row = dataArray.get(i);
					if (row != null && row.isArray()) {
						List<String> cells = new ArrayList<>();
						for (JsonNode cell : row) {
							cells.add(cell.asText(""));
						}
						markdown.append("| ").append(String.join(" | ", cells)).append(" |\n");
					}
				}
			}
		}

		markdown.append("\n");
		return markdown.toString();
	}

	/**
	 * Check if line looks like a table row
	 */
	private boolean isTableRow(String line) {
		// Simple heuristic: contains multiple separators (tabs, commas, or multiple
		// spaces)
		return line.contains("\t") || (line.contains(",") && line.split(",").length > 2)
				|| (line.contains("  ") && line.split("  ").length > 2);
	}

	/**
	 * Split table row into cells
	 */
	private String[] splitTableRow(String line) {
		// Try different separators
		if (line.contains("\t")) {
			return line.split("\t");
		}
		else if (line.contains(",")) {
			return line.split(",");
		}
		else if (line.contains("  ")) {
			return line.split("\\s{2,}"); // Split on 2 or more spaces
		}
		else {
			return new String[] { line };
		}
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
