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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

import org.apache.james.mime4j.dom.Body;
import org.apache.james.mime4j.dom.Entity;
import org.apache.james.mime4j.dom.Message;
import org.apache.james.mime4j.dom.Multipart;
import org.apache.james.mime4j.dom.SingleBody;
import org.apache.james.mime4j.dom.TextBody;
import org.apache.james.mime4j.dom.address.AddressList;
import org.apache.james.mime4j.dom.address.MailboxList;
import org.apache.james.mime4j.message.DefaultMessageBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.cloud.ai.lynxe.tool.code.ToolExecuteResult;
import com.alibaba.cloud.ai.lynxe.tool.filesystem.UnifiedDirectoryManager;

/**
 * EML to Markdown Processor
 *
 * Converts EML (email message) files to Markdown format using Apache James Mime4j library
 * for proper parsing and decoding of email encodings (RFC 2047, Base64,
 * Quoted-Printable).
 */
public class EmlToMarkdownProcessor {

	private static final Logger log = LoggerFactory.getLogger(EmlToMarkdownProcessor.class);

	private final UnifiedDirectoryManager directoryManager;

	public EmlToMarkdownProcessor(UnifiedDirectoryManager directoryManager) {
		this.directoryManager = directoryManager;
	}

	/**
	 * Convert EML file to Markdown
	 * @param sourceFile The source EML file
	 * @param additionalRequirement Optional additional requirements for conversion
	 * @param currentPlanId Current plan ID for file operations
	 * @return ToolExecuteResult with conversion status
	 */
	public ToolExecuteResult convertToMarkdown(Path sourceFile, String additionalRequirement, String currentPlanId) {
		try {
			log.info("Converting EML file to Markdown: {}", sourceFile.getFileName());

			// Step 0: Check if content.md already exists
			String originalFilename = sourceFile.getFileName().toString();
			String markdownFilename = generateMarkdownFilename(originalFilename);
			if (markdownFileExists(currentPlanId, markdownFilename)) {
				log.info("Markdown file already exists, skipping conversion: {}", markdownFilename);
				return new ToolExecuteResult(
						"Skipped conversion - content.md file already exists: " + markdownFilename);
			}

			// Step 1: Parse EML file using Mime4j
			Message message = parseEmlFile(sourceFile);
			if (message == null) {
				// Fallback to raw content if parsing fails
				log.warn("Failed to parse EML file with Mime4j, falling back to raw content");
				return convertRawEmlToMarkdown(sourceFile, originalFilename, additionalRequirement, currentPlanId);
			}

			// Step 2: Convert to Markdown format
			String markdownContent = convertToMarkdownFormat(message, originalFilename, additionalRequirement);

			// Step 3: Generate output filename
			markdownFilename = generateMarkdownFilename(originalFilename);

			// Step 4: Save Markdown file
			Path outputFile = saveMarkdownFile(markdownContent, markdownFilename, currentPlanId);
			if (outputFile == null) {
				return new ToolExecuteResult("Error: Failed to save Markdown file");
			}

			// Step 5: Return success result
			String result = String.format("Successfully converted EML file to Markdown\n\n" + "**Output File**: %s\n\n",
					markdownFilename);

			// Add content if less than 1000 characters
			if (markdownContent.length() < 1000) {
				result += "**Content**:\n\n" + markdownContent;
			}

			log.info("EML to Markdown conversion completed: {} -> {}", originalFilename, markdownFilename);
			return new ToolExecuteResult(result);

		}
		catch (Exception e) {
			log.error("Error converting EML file to Markdown: {}", sourceFile.getFileName(), e);
			// Fallback to raw content on error
			try {
				return convertRawEmlToMarkdown(sourceFile, sourceFile.getFileName().toString(), additionalRequirement,
						currentPlanId);
			}
			catch (Exception fallbackError) {
				log.error("Fallback conversion also failed", fallbackError);
				return new ToolExecuteResult("Error: " + e.getMessage());
			}
		}
	}

	/**
	 * Parse EML file using Mime4j DefaultMessageBuilder
	 */
	private Message parseEmlFile(Path sourceFile) {
		try (InputStream inputStream = Files.newInputStream(sourceFile)) {
			DefaultMessageBuilder builder = new DefaultMessageBuilder();
			Message message = builder.parseMessage(inputStream);
			log.debug("Successfully parsed EML file: {}", sourceFile.getFileName());
			return message;
		}
		catch (Exception e) {
			log.warn("Failed to parse EML file with Mime4j: {}", e.getMessage());
			return null;
		}
	}

	/**
	 * Fallback method: Convert raw EML content to Markdown
	 */
	private ToolExecuteResult convertRawEmlToMarkdown(Path sourceFile, String filename, String additionalRequirement,
			String currentPlanId) throws IOException {
		String content = Files.readString(sourceFile);
		String markdownContent = convertRawContentToMarkdown(content, filename, additionalRequirement);
		String markdownFilename = generateMarkdownFilename(filename);
		Path outputFile = saveMarkdownFile(markdownContent, markdownFilename, currentPlanId);
		if (outputFile == null) {
			return new ToolExecuteResult("Error: Failed to save Markdown file");
		}
		return new ToolExecuteResult("Successfully converted EML file to Markdown (raw mode)\n\n" + "**Output File**: "
				+ markdownFilename + "\n\n");
	}

	/**
	 * Convert parsed Message to Markdown format
	 */
	private String convertToMarkdownFormat(Message message, String filename, String additionalRequirement) {
		StringBuilder markdown = new StringBuilder();

		// Add title
		markdown.append("# Email Message (EML)\n\n");

		if (additionalRequirement != null && !additionalRequirement.trim().isEmpty()) {
			markdown.append("**Additional Requirements**: ").append(additionalRequirement).append("\n\n");
		}

		markdown.append("**Source File**: `").append(filename).append("`\n\n");
		markdown.append("---\n\n");

		// Extract and display headers
		markdown.append("## Email Headers\n\n");
		extractHeaders(markdown, message);

		markdown.append("---\n\n");

		// Extract and display body
		markdown.append("## Email Body\n\n");
		extractBody(markdown, message);

		markdown.append("---\n\n");
		markdown.append("*This document was automatically converted from EML to Markdown format using Mime4j.*\n");

		return markdown.toString();
	}

	/**
	 * Extract and display email headers (automatically decoded by Mime4j)
	 */
	private void extractHeaders(StringBuilder markdown, Message message) {
		// Subject (automatically decoded from RFC 2047)
		String subject = message.getSubject();
		if (subject != null && !subject.trim().isEmpty()) {
			markdown.append("**Subject**: ").append(subject).append("\n\n");
		}

		// From (automatically decoded) - returns MailboxList
		MailboxList from = message.getFrom();
		if (from != null && !from.isEmpty()) {
			markdown.append("**From**: ").append(from.toString()).append("\n\n");
		}

		// To (automatically decoded)
		AddressList to = message.getTo();
		if (to != null && !to.isEmpty()) {
			markdown.append("**To**: ").append(formatAddressList(to)).append("\n\n");
		}

		// Cc
		AddressList cc = message.getCc();
		if (cc != null && !cc.isEmpty()) {
			markdown.append("**Cc**: ").append(formatAddressList(cc)).append("\n\n");
		}

		// Bcc
		AddressList bcc = message.getBcc();
		if (bcc != null && !bcc.isEmpty()) {
			markdown.append("**Bcc**: ").append(formatAddressList(bcc)).append("\n\n");
		}

		// Reply-To
		AddressList replyTo = message.getReplyTo();
		if (replyTo != null && !replyTo.isEmpty()) {
			markdown.append("**Reply-To**: ").append(formatAddressList(replyTo)).append("\n\n");
		}

		// Date
		if (message.getDate() != null) {
			markdown.append("**Date**: ").append(message.getDate().toString()).append("\n\n");
		}

		// Message-ID
		String messageId = message.getMessageId();
		if (messageId != null && !messageId.trim().isEmpty()) {
			markdown.append("**Message-ID**: ").append(messageId).append("\n\n");
		}
	}

	/**
	 * Format AddressList for display
	 */
	private String formatAddressList(AddressList addressList) {
		if (addressList == null || addressList.isEmpty()) {
			return "";
		}
		// AddressList.toString() already formats addresses correctly
		return addressList.toString();
	}

	/**
	 * Extract and display email body (handles multipart and single part)
	 */
	private void extractBody(StringBuilder markdown, Message message) {
		Body body = message.getBody();
		if (body == null) {
			markdown.append("*No body content found.*\n\n");
			return;
		}

		if (body instanceof Multipart) {
			// Handle multipart messages
			extractMultipartBody(markdown, (Multipart) body);
		}
		else if (body instanceof SingleBody) {
			// Handle single part message
			extractSingleBody(markdown, (SingleBody) body);
		}
		else {
			markdown.append("*Unsupported body type.*\n\n");
		}
	}

	/**
	 * Extract content from multipart message
	 */
	private void extractMultipartBody(StringBuilder markdown, Multipart multipart) {
		String textPlain = null;
		String textHtml = null;
		List<String> attachments = new ArrayList<>();

		for (Entity entity : multipart.getBodyParts()) {
			String mimeType = entity.getMimeType();
			if (mimeType == null) {
				continue;
			}

			if (mimeType.equals("text/plain")) {
				textPlain = extractTextBody(entity);
			}
			else if (mimeType.equals("text/html")) {
				textHtml = extractTextBody(entity);
			}
			else if (mimeType.startsWith("multipart/")) {
				// Nested multipart - recursively extract
				Body nestedBody = entity.getBody();
				if (nestedBody instanceof Multipart) {
					extractMultipartBody(markdown, (Multipart) nestedBody);
				}
			}
			else {
				// Likely an attachment
				String filename = getAttachmentFilename(entity);
				if (filename != null) {
					attachments.add(filename + " (" + mimeType + ")");
				}
			}
		}

		// Display text/plain if available, otherwise text/html
		if (textPlain != null && !textPlain.trim().isEmpty()) {
			markdown.append(textPlain).append("\n\n");
		}
		else if (textHtml != null && !textHtml.trim().isEmpty()) {
			markdown.append("```html\n").append(textHtml).append("\n```\n\n");
		}

		// Display HTML version if different from plain text
		if (textHtml != null && !textHtml.trim().isEmpty() && textPlain == null) {
			// Already displayed above
		}
		else if (textHtml != null && !textHtml.trim().isEmpty() && !textHtml.equals(textPlain)) {
			markdown.append("### HTML Version\n\n");
			markdown.append("```html\n").append(textHtml).append("\n```\n\n");
		}

		// Display attachments if any
		if (!attachments.isEmpty()) {
			markdown.append("### Attachments\n\n");
			for (String attachment : attachments) {
				markdown.append("- ").append(attachment).append("\n");
			}
			markdown.append("\n");
		}
	}

	/**
	 * Extract content from single body part
	 */
	private void extractSingleBody(StringBuilder markdown, SingleBody singleBody) {
		if (singleBody instanceof TextBody) {
			String content = extractTextFromBody((TextBody) singleBody);
			if (content != null && !content.trim().isEmpty()) {
				// Check if it's HTML
				String mimeType = singleBody.getParent() != null ? singleBody.getParent().getMimeType() : null;
				if (mimeType != null && mimeType.equals("text/html")) {
					markdown.append("```html\n").append(content).append("\n```\n\n");
				}
				else {
					markdown.append(content).append("\n\n");
				}
			}
			else {
				markdown.append("*No text content found.*\n\n");
			}
		}
		else {
			markdown.append("*Unsupported single body type.*\n\n");
		}
	}

	/**
	 * Extract text content from TextBody (automatically decoded by Mime4j)
	 */
	private String extractTextBody(Entity entity) {
		Body body = entity.getBody();
		if (body instanceof TextBody) {
			return extractTextFromBody((TextBody) body);
		}
		return null;
	}

	/**
	 * Extract text from TextBody
	 */
	private String extractTextFromBody(TextBody textBody) {
		try (InputStream inputStream = textBody.getInputStream()) {
			// Mime4j automatically decodes Base64 and Quoted-Printable
			ByteArrayOutputStream buffer = new ByteArrayOutputStream();
			byte[] data = new byte[8192];
			int nRead;
			while ((nRead = inputStream.read(data, 0, data.length)) != -1) {
				buffer.write(data, 0, nRead);
			}
			buffer.flush();
			byte[] bytes = buffer.toByteArray();
			String charset = textBody.getMimeCharset();
			return charset != null ? new String(bytes, charset) : new String(bytes, "UTF-8");
		}
		catch (IOException e) {
			log.warn("Failed to extract text from body: {}", e.getMessage());
			return null;
		}
	}

	/**
	 * Get attachment filename from entity
	 */
	private String getAttachmentFilename(Entity entity) {
		String filename = entity.getFilename();
		if (filename != null && !filename.trim().isEmpty()) {
			return filename;
		}
		// Try Content-Disposition header
		String disposition = entity.getHeader().getField("Content-Disposition") != null
				? entity.getHeader().getField("Content-Disposition").getBody() : null;
		if (disposition != null && disposition.contains("filename=")) {
			int start = disposition.indexOf("filename=") + 9;
			int end = disposition.indexOf(";", start);
			if (end == -1) {
				end = disposition.length();
			}
			return disposition.substring(start, end).replace("\"", "").trim();
		}
		return null;
	}

	/**
	 * Fallback: Convert raw EML content to Markdown
	 */
	private String convertRawContentToMarkdown(String content, String filename, String additionalRequirement) {
		StringBuilder markdown = new StringBuilder();

		markdown.append("# Email Message (EML)\n\n");

		if (additionalRequirement != null && !additionalRequirement.trim().isEmpty()) {
			markdown.append("**Additional Requirements**: ").append(additionalRequirement).append("\n\n");
		}

		markdown.append("**Source File**: `").append(filename).append("`\n\n");
		markdown.append("---\n\n");

		markdown.append("## Email Content (Raw)\n\n");
		markdown.append("```eml\n").append(content).append("\n```\n\n");

		markdown.append("---\n\n");
		markdown.append("*This document was automatically converted from EML to Markdown format (raw mode).*\n");

		return markdown.toString();
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
