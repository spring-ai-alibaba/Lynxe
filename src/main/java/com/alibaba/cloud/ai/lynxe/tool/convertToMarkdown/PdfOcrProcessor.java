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

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import javax.imageio.ImageIO;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.core.io.InputStreamResource;
import org.springframework.util.MimeTypeUtils;

import com.alibaba.cloud.ai.lynxe.config.LynxeProperties;
import com.alibaba.cloud.ai.lynxe.llm.LlmService;
import com.alibaba.cloud.ai.lynxe.runtime.executor.ImageRecognitionExecutorPool;
import com.alibaba.cloud.ai.lynxe.tool.AbstractBaseTool;
import com.alibaba.cloud.ai.lynxe.tool.code.ToolExecuteResult;
import com.alibaba.cloud.ai.lynxe.tool.filesystem.UnifiedDirectoryManager;

/**
 * PDF OCR Processor using OpenAI Image Model
 *
 * Converts PDF files to text using OCR processing with OpenAI's image models. This
 * processor renders PDF pages as images and uses OCR to extract text content.
 */
public class PdfOcrProcessor {

	private static final Logger log = LoggerFactory.getLogger(PdfOcrProcessor.class);

	private final UnifiedDirectoryManager directoryManager;

	private final LlmService llmService;

	private final LynxeProperties lynxeProperties;

	private final ImageRecognitionExecutorPool imageRecognitionExecutorPool;

	// Temporary boolean flag to indicate whether converted images are saved to temp
	// folder
	private boolean saveImagesToTempFolder = true;

	// Image format name for consistency across all image operations
	private String imageFormatName = "JPEG";

	public PdfOcrProcessor(UnifiedDirectoryManager directoryManager, LlmService llmService,
			LynxeProperties lynxeProperties, ImageRecognitionExecutorPool imageRecognitionExecutorPool) {
		this.directoryManager = directoryManager;
		this.llmService = llmService;
		this.lynxeProperties = lynxeProperties;
		this.imageRecognitionExecutorPool = imageRecognitionExecutorPool;
		log.info("PdfOcrProcessor initialized with all dependencies");
	}

	/**
	 * Convert PDF file to text using OCR processing
	 * @param sourceFile The source PDF file
	 * @param additionalRequirement Optional additional requirements for OCR processing
	 * @param currentPlanId Current plan ID for file operations
	 * @param targetFilename Optional target filename (if null, will generate _ocr.txt
	 * filename)
	 * @return ToolExecuteResult with OCR processing status and extracted text
	 */
	public ToolExecuteResult convertPdfToTextWithOcr(Path sourceFile, String additionalRequirement,
			String currentPlanId, String targetFilename) {
		try {
			log.info("Starting OCR processing for PDF file: {}", sourceFile.getFileName());

			// Step 1: Check if OCR result already exists
			String originalFilename = sourceFile.getFileName().toString();
			String ocrFilename = (targetFilename != null) ? targetFilename : generateOcrFilename(originalFilename);
			if (ocrFileExists(currentPlanId, ocrFilename)) {
				log.info("OCR result file already exists, skipping processing: {}", ocrFilename);
				return new ToolExecuteResult("Skipped OCR processing - result file already exists: " + ocrFilename);
			}

			// Step 2: Convert PDF pages to images
			List<BufferedImage> pageImages = convertPdfToImages(sourceFile);
			if (pageImages.isEmpty()) {
				return new ToolExecuteResult("Error: Could not convert PDF pages to images");
			}

			// Step 3: Process each page with OCR using parallel execution
			StringBuilder extractedText = new StringBuilder();

			int processedPages = 0;
			List<CompletableFuture<String>> ocrTasks = new ArrayList<>();

			// Submit all OCR tasks to the dedicated executor pool
			if (imageRecognitionExecutorPool != null) {
				for (int i = 0; i < pageImages.size(); i++) {
					final int pageIndex = i;
					final BufferedImage pageImage = pageImages.get(i);

					CompletableFuture<String> ocrTask = imageRecognitionExecutorPool.submitTask(() -> {
						log.info("Processing page {} of {} with OCR", pageIndex + 1, pageImages.size());
						return processImageWithOcrWithRetry(pageImage, pageIndex + 1, additionalRequirement);
					});

					ocrTasks.add(ocrTask);
				}
			}
			else {
				// Throw exception if executor pool is not available
				String errorMessage = "ImageRecognitionExecutorPool not available - OCR processing cannot continue";
				log.error(errorMessage);
				throw new IllegalStateException(errorMessage);
			}

			// Wait for all OCR tasks to complete
			for (int i = 0; i < ocrTasks.size(); i++) {
				try {
					String pageText = ocrTasks.get(i).get();
					if (pageText != null && !pageText.trim().isEmpty()) {
						extractedText.append("## Page ").append(i + 1).append("\n\n");
						extractedText.append(pageText).append("\n\n");
						processedPages++;
					}
				}
				catch (InterruptedException | ExecutionException e) {
					log.error("Error processing page {} with OCR: {}", i + 1, e.getMessage());
					// Continue with next page instead of failing completely
				}
			}

			if (processedPages == 0) {
				return new ToolExecuteResult("Error: No text could be extracted from any page using OCR");
			}

			// Format content based on target filename
			if (targetFilename != null && targetFilename.endsWith(".md")) {
				// Format as markdown
				extractedText.append("\n---\n\n");
				extractedText.append(
						"*This document was automatically converted from PDF to Markdown format using OCR processing.*\n");
			}
			else {
				// Format as plain text
				extractedText.append("---\n\n");
				extractedText.append("*This document was processed using OCR with OpenAI Image Model.*\n");
				extractedText.append("*Total pages processed: ")
					.append(processedPages)
					.append(" of ")
					.append(pageImages.size())
					.append("*\n");
			}

			// Step 4: Save OCR result
			Path outputFile = saveOcrResult(extractedText.toString(), ocrFilename, currentPlanId);
			if (outputFile == null) {
				return new ToolExecuteResult("Error: Failed to save OCR result file");
			}

			// Step 5: Return success result
			// Normalize filename to remove any ./ prefix for consistent output
			String normalizedFilename = normalizeFilename(ocrFilename);
			String result;
			if (targetFilename != null && targetFilename.endsWith(".md")) {
				result = String.format("Successfully converted PDF file to Markdown using OCR\n\n"
						+ "**Output File**: %s\n\n" + "**Processing Method**: OCR with OpenAI Image Model\n\n",
						normalizedFilename);
			}
			else {
				result = String.format(
						"Successfully processed PDF with OCR\n\n" + "**Output File**: %s\n\n"
								+ "**Pages Processed**: %d of %d\n\n",
						normalizedFilename, processedPages, pageImages.size());
			}

			// Add content preview if less than 1000 characters
			String content = extractedText.toString();
			if (content.length() < 1000) {
				result += "**Content Preview**:\n\n" + content;
			}
			else {
				result += "**Content Preview**:\n\n" + content.substring(0, 1000)
						+ "...\n\n*[Content truncated - see full result in output file]*";
			}

			log.info("PDF OCR processing completed: {} -> {}", originalFilename, ocrFilename);
			return new ToolExecuteResult(result);

		}
		catch (Exception e) {
			log.error("Error processing PDF with OCR: {}", sourceFile.getFileName(), e);
			return new ToolExecuteResult("Error: " + e.getMessage());
		}
	}

	/**
	 * Convert PDF file to text using OCR processing (backward compatibility method)
	 * @param sourceFile The source PDF file
	 * @param additionalRequirement Optional additional requirements for OCR processing
	 * @param currentPlanId Current plan ID for file operations
	 * @return ToolExecuteResult with OCR processing status and extracted text
	 */
	public ToolExecuteResult convertPdfToTextWithOcr(Path sourceFile, String additionalRequirement,
			String currentPlanId) {
		return convertPdfToTextWithOcr(sourceFile, additionalRequirement, currentPlanId, null);
	}

	/**
	 * Convert PDF pages to images using PDFBox with optimized settings and parallel
	 * processing
	 * @param sourceFile The source PDF file
	 * @return List of BufferedImage objects representing PDF pages
	 */
	private List<BufferedImage> convertPdfToImages(Path sourceFile) {
		List<BufferedImage> images = new ArrayList<>();

		try (PDDocument document = PDDocument.load(sourceFile.toFile())) {
			int pageCount = document.getNumberOfPages();
			log.info("Converting {} pages to images with parallel processing", pageCount);

			// Use parallel processing for PDF to image conversion
			List<CompletableFuture<BufferedImage>> conversionTasks = new ArrayList<>();

			// Create temp folder if image saving is enabled
			final Path tempFolder = saveImagesToTempFolder ? createTempImageFolder(sourceFile.getFileName().toString())
					: null;

			// Submit all conversion tasks to the executor pool
			if (imageRecognitionExecutorPool != null) {
				for (int pageIndex = 0; pageIndex < pageCount; pageIndex++) {
					final int currentPageIndex = pageIndex;
					final PDDocument docRef = document; // Reference for the task

					CompletableFuture<BufferedImage> conversionTask = imageRecognitionExecutorPool.submitTask(() -> {
						try {
							// Validate page index and document state
							if (currentPageIndex < 0 || currentPageIndex >= docRef.getNumberOfPages()) {
								log.error("Invalid page index {} for document with {} pages", currentPageIndex + 1,
										docRef.getNumberOfPages());
								return null;
							}

							if (docRef.isEncrypted()) {
								log.warn("Document is encrypted, page {} may not render correctly",
										currentPageIndex + 1);
							}

							PDFRenderer pdfRenderer = new PDFRenderer(docRef);

							// Use optimized DPI and image type from LynxeProperties
							float dpi = getOptimizedDpi();
							ImageType imageType = getConfiguredImageType();

							// Render page with retry logic and fallback settings
							BufferedImage image = renderPageWithRetry(pdfRenderer, currentPageIndex, dpi, imageType);

							if (image == null) {
								log.error("Failed to render page {} after all retry attempts", currentPageIndex + 1);
								return null;
							}

							// Optimize the image for OCR processing
							BufferedImage optimizedImage = optimizeImageForOcr(image);

							log.debug("Converted page {} to image ({}x{}) with DPI: {}, Type: {}", currentPageIndex + 1,
									optimizedImage.getWidth(), optimizedImage.getHeight(), dpi, imageType);

							// Save image to temp folder if enabled (with optimization)
							if (saveImagesToTempFolder && tempFolder != null) {
								try {
									saveImageToTempFolderOptimized(optimizedImage, currentPageIndex + 1, tempFolder);
								}
								catch (IOException e) {
									log.error("Failed to save page {} image to temp folder: {}", currentPageIndex + 1,
											e.getMessage(), e);
								}
							}

							return optimizedImage;
						}
						catch (IllegalArgumentException e) {
							log.error("Invalid argument error converting page {} to image: {}", currentPageIndex + 1,
									e.getMessage(), e);
							return null;
						}
						catch (Exception e) {
							log.error("Error converting page {} to image: {}", currentPageIndex + 1, e.getMessage(), e);
							return null; // Return null for failed conversions
						}
					});

					conversionTasks.add(conversionTask);
				}
			}
			else {
				// Throw exception if executor pool is not available
				String errorMessage = "ImageRecognitionExecutorPool not available - PDF conversion cannot continue";
				log.error(errorMessage);
				throw new IllegalStateException(errorMessage);
			}

			// Wait for all conversion tasks to complete
			for (int i = 0; i < conversionTasks.size(); i++) {
				try {
					BufferedImage image = conversionTasks.get(i).get();
					if (image != null) {
						images.add(image);
					}
					else {
						log.warn("Failed to convert page {} to image", i + 1);
					}
				}
				catch (InterruptedException | ExecutionException e) {
					log.error("Error processing page {} conversion: {}", i + 1, e.getMessage());
					// Continue with next page instead of failing completely
				}
			}

			if (saveImagesToTempFolder && tempFolder != null) {
				log.info("All converted images saved to temp folder: {}", tempFolder);
			}

		}
		catch (IOException e) {
			log.error("Error converting PDF to images: {}", e.getMessage(), e);
		}

		return images;
	}

	/**
	 * Create temporary folder for saving converted images
	 * @param pdfFilename The original PDF filename
	 * @return Path to the created temp folder
	 */
	private Path createTempImageFolder(String pdfFilename) {
		try {
			// Create temp folder in user home directory
			String homeDir = System.getProperty("user.home");
			String folderName = "pdf_images/" + System.currentTimeMillis();
			Path tempFolder = Paths.get(homeDir, folderName);

			Files.createDirectories(tempFolder);
			log.info("Created temp folder for images: {}", tempFolder);
			return tempFolder;
		}
		catch (IOException e) {
			log.error("Failed to create temp folder for images: {}", e.getMessage());
			return null;
		}
	}

	/**
	 * Save converted image to temporary folder with optimization
	 * @param image The BufferedImage to save
	 * @param pageNumber The page number
	 * @param tempFolder The temp folder path
	 */
	private void saveImageToTempFolderOptimized(BufferedImage image, int pageNumber, Path tempFolder)
			throws IOException {
		String filename = String.format("page_%03d.%s", pageNumber, imageFormatName.toLowerCase());
		Path imageFile = tempFolder.resolve(filename);

		// Use configured format for consistency
		ImageIO.write(image, imageFormatName, imageFile.toFile());
		log.debug("Saved page {} image to: {} ({} format)", pageNumber, imageFile, imageFormatName);
	}

	/**
	 * Process a single image with OCR using ChatClient with retry mechanism
	 * @param image The BufferedImage to process
	 * @param pageNumber The page number for logging
	 * @param additionalRequirement Optional additional requirements for OCR processing
	 * @return Extracted text or null if failed
	 */
	private String processImageWithOcrWithRetry(BufferedImage image, int pageNumber, String additionalRequirement) {
		int maxRetryAttempts = getConfiguredMaxRetryAttempts();

		for (int attempt = 1; attempt <= maxRetryAttempts; attempt++) {
			try {
				log.debug("OCR attempt {} of {} for page {}", attempt, maxRetryAttempts, pageNumber);
				String result = processImageWithOcr(image, pageNumber, additionalRequirement);

				if (result != null && !result.trim().isEmpty()) {
					if (attempt > 1) {
						log.info("OCR succeeded on attempt {} for page {}", attempt, pageNumber);
					}
					return result;
				}

				log.warn("OCR attempt {} failed for page {} - empty result", attempt, pageNumber);

			}
			catch (Exception e) {
				log.warn("OCR attempt {} failed for page {}: {}", attempt, pageNumber, e.getMessage());

				// If this is the last attempt, log the error
				if (attempt == maxRetryAttempts) {
					log.error("All {} OCR attempts failed for page {}", maxRetryAttempts, pageNumber, e);
				}
			}

			// Add a small delay between retry attempts (except for the last attempt)
			if (attempt < maxRetryAttempts) {
				try {
					Thread.sleep(1000); // 1 second delay between retries
				}
				catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					log.warn("OCR retry interrupted for page {}", pageNumber);
					break;
				}
			}
		}

		log.error("All OCR attempts failed for page {}", pageNumber);
		return null;
	}

	/**
	 * Process a single image with OCR using ChatClient
	 * @param image The BufferedImage to process
	 * @param pageNumber The page number for logging
	 * @param additionalRequirement Optional additional requirements for OCR processing
	 * @return Extracted text or null if failed
	 */
	private String processImageWithOcr(BufferedImage image, int pageNumber, String additionalRequirement) {
		if (llmService == null) {
			log.error("LlmService is not initialized, cannot perform OCR");
			return null;
		}

		try {
			// Convert BufferedImage to InputStream for ChatClient using configured format
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			ImageIO.write(image, imageFormatName, baos);
			byte[] imageBytes = baos.toByteArray();

			// Create InputStream from byte array
			java.io.InputStream imageInputStream = new java.io.ByteArrayInputStream(imageBytes);

			log.debug("Processing page {} image for OCR ({} bytes, format: {})", pageNumber, imageBytes.length,
					imageFormatName);

			// Get the ChatClient from LlmService
			ChatClient chatClient = llmService.getDefaultDynamicAgentChatClient();
			// Use configured model name from LynxeProperties
			String modelName = getConfiguredModelName();
			ChatOptions chatOptions = ChatOptions.builder().model(modelName).build();

			// Use ChatClient to process the image with OCR
			// Include additional requirements in the system message if provided
			String extractedText = chatClient.prompt().options(chatOptions).system(systemMessage -> {
				systemMessage.text("You are an OCR (Optical Character Recognition) specialist.");
				systemMessage.text("Extract all visible text content from the provided image.");
				systemMessage.text("Return only the extracted text without any additional formatting or descriptions.");
				systemMessage.text("Preserve the structure and layout of the text as much as possible.");
				systemMessage.text("Focus on accurate text recognition and maintain readability.");
				if (additionalRequirement != null && !additionalRequirement.trim().isEmpty()) {
					systemMessage.text("Additional requirements: " + additionalRequirement);
				}
				systemMessage.text("If no text is visible in the image, return ''(empty string) ");
			})
				.user(userMessage -> userMessage.text("Please extract all text content from this image:")
					.media(MimeTypeUtils.parseMimeType("image/" + imageFormatName.toLowerCase()),
							new InputStreamResource(imageInputStream)))
				.call()
				.content();

			if (extractedText != null && !extractedText.trim().isEmpty()
					&& !extractedText.toLowerCase().contains("no text detected")) {
				log.debug("Successfully extracted text from page {} with OCR: {} characters", pageNumber,
						extractedText.length());
				return extractedText;
			}
			else {
				log.warn("No text extracted from page {} - empty or no text response", pageNumber);
				return null;
			}

		}
		catch (Exception e) {
			log.error("Error processing page {} with OCR using ChatClient: {}", pageNumber, e.getMessage(), e);
			return null;
		}
	}

	/**
	 * Check if OCR result file already exists
	 */
	private boolean ocrFileExists(String currentPlanId, String ocrFilename) {
		try {
			Path currentPlanDir = directoryManager.getRootPlanDirectory(currentPlanId);
			Path ocrFile = currentPlanDir.resolve(ocrFilename);
			return Files.exists(ocrFile);
		}
		catch (Exception e) {
			log.error("Error checking if OCR file exists: {}", ocrFilename, e);
			return false;
		}
	}

	/**
	 * Generate OCR result filename by replacing extension with _ocr.txt
	 */
	private String generateOcrFilename(String originalFilename) {
		int lastDotIndex = originalFilename.lastIndexOf('.');
		if (lastDotIndex > 0) {
			return originalFilename.substring(0, lastDotIndex) + "_ocr.txt";
		}
		return originalFilename + "_ocr.txt";
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
	 * Normalize baseUrl for API endpoints (uses common method from AbstractBaseTool) This
	 * method can be used when creating API clients that need baseUrl normalization
	 * @param baseUrl The base URL to normalize
	 * @return Normalized base URL for API endpoints
	 */
	private String normalizeBaseUrlForApi(String baseUrl) {
		// First normalize by removing trailing slashes
		String normalized = AbstractBaseTool.normalizeBaseUrl(baseUrl);
		// Then normalize for API endpoints that use /v1 prefix internally
		return AbstractBaseTool.normalizeBaseUrlForApiEndpoint(normalized);
	}

	/**
	 * Save OCR result to file
	 */
	private Path saveOcrResult(String content, String filename, String currentPlanId) {
		try {
			Path currentPlanDir = directoryManager.getRootPlanDirectory(currentPlanId);
			Path outputFile = currentPlanDir.resolve(filename);

			Files.write(outputFile, content.getBytes("UTF-8"), StandardOpenOption.CREATE,
					StandardOpenOption.TRUNCATE_EXISTING);

			log.info("OCR result file saved: {}", outputFile);
			return outputFile;
		}
		catch (IOException e) {
			log.error("Error saving OCR result file: {}", filename, e);
			return null;
		}
	}

	/**
	 * Set whether to save converted images to temporary folder
	 * @param saveImagesToTempFolder true to save images to temp folder, false otherwise
	 */
	public void setSaveImagesToTempFolder(boolean saveImagesToTempFolder) {
		this.saveImagesToTempFolder = saveImagesToTempFolder;
		log.info("Save images to temp folder setting changed to: {}", saveImagesToTempFolder);
	}

	/**
	 * Get whether converted images are saved to temporary folder
	 * @return true if images are saved to temp folder, false otherwise
	 */
	public boolean isSaveImagesToTempFolder() {
		return saveImagesToTempFolder;
	}

	/**
	 * Enable saving converted images to temporary folder
	 */
	public void enableImageSaving() {
		setSaveImagesToTempFolder(true);
	}

	/**
	 * Disable saving converted images to temporary folder
	 */
	public void disableImageSaving() {
		setSaveImagesToTempFolder(false);
	}

	/**
	 * Get current image saving status with detailed information
	 * @return Status string describing current image saving configuration
	 */
	public String getImageSavingStatus() {
		if (saveImagesToTempFolder) {
			return "Image saving ENABLED - Converted images will be saved to temp folder in ~ directory";
		}
		else {
			return "Image saving DISABLED - Images will not be saved to disk (memory only)";
		}
	}

	/**
	 * Toggle image saving status
	 * @return New status after toggle
	 */
	public boolean toggleImageSaving() {
		setSaveImagesToTempFolder(!saveImagesToTempFolder);
		return saveImagesToTempFolder;
	}

	/**
	 * Set the image format name for all image operations
	 * @param imageFormatName The image format name (e.g., "JPEG", "PNG", "BMP")
	 */
	public void setImageFormatName(String imageFormatName) {
		this.imageFormatName = imageFormatName;
		log.info("Image format changed to: {}", imageFormatName);
	}

	/**
	 * Get the current image format name
	 * @return Current image format name
	 */
	public String getImageFormatName() {
		return imageFormatName;
	}

	/**
	 * Get current image format status with detailed information
	 * @return Status string describing current image format configuration
	 */
	public String getImageFormatStatus() {
		return String.format("Image format: %s - Used for OCR processing and file saving", imageFormatName);
	}

	/**
	 * Get configured DPI from LynxeProperties
	 * @return configured DPI or default value
	 */
	private float getConfiguredDpi() {
		if (lynxeProperties != null) {
			Float configuredDpi = lynxeProperties.getImageRecognitionDpi();
			if (configuredDpi != null && configuredDpi > 0) {
				return configuredDpi;
			}
		}
		return 120.0f; // Default DPI (optimized for performance)
	}

	/**
	 * Get optimized DPI for better performance while maintaining OCR quality
	 * @return optimized DPI value
	 */
	private float getOptimizedDpi() {
		float configuredDpi = getConfiguredDpi();

		// Optimize DPI for better performance
		// 100-120 DPI is usually sufficient for OCR while being much faster
		if (configuredDpi > 120) {
			log.debug("Optimizing DPI from {} to 120 for better performance", configuredDpi);
			return 120.0f;
		}

		return configuredDpi;
	}

	/**
	 * Render PDF page to image with retry logic and fallback settings
	 * @param pdfRenderer The PDFRenderer instance
	 * @param pageIndex The page index to render
	 * @param initialDpi The initial DPI setting
	 * @param initialImageType The initial ImageType setting
	 * @return BufferedImage or null if all attempts fail
	 */
	private BufferedImage renderPageWithRetry(PDFRenderer pdfRenderer, int pageIndex, float initialDpi,
			ImageType initialImageType) {
		// Attempt 1: Use configured settings
		try {
			BufferedImage image = pdfRenderer.renderImageWithDPI(pageIndex, initialDpi, initialImageType);
			if (validateRenderedImage(image, pageIndex)) {
				return image;
			}
		}
		catch (Exception e) {
			log.debug("Initial render attempt failed for page {}: {}", pageIndex + 1, e.getMessage());
		}

		// Attempt 2: Lower DPI if initial DPI > 100
		if (initialDpi > 100) {
			try {
				float fallbackDpi = 100.0f;
				log.debug("Retrying page {} with lower DPI: {}", pageIndex + 1, fallbackDpi);
				BufferedImage image = pdfRenderer.renderImageWithDPI(pageIndex, fallbackDpi, initialImageType);
				if (validateRenderedImage(image, pageIndex)) {
					log.info("Page {} successfully rendered with fallback DPI {}", pageIndex + 1, fallbackDpi);
					return image;
				}
			}
			catch (Exception e) {
				log.debug("Fallback DPI render attempt failed for page {}: {}", pageIndex + 1, e.getMessage());
			}
		}

		// Attempt 3: Use RGB image type as fallback
		if (initialImageType != ImageType.RGB) {
			try {
				float dpi = initialDpi > 100 ? 100.0f : initialDpi;
				log.debug("Retrying page {} with RGB image type and DPI: {}", pageIndex + 1, dpi);
				BufferedImage image = pdfRenderer.renderImageWithDPI(pageIndex, dpi, ImageType.RGB);
				if (validateRenderedImage(image, pageIndex)) {
					log.info("Page {} successfully rendered with RGB fallback", pageIndex + 1);
					return image;
				}
			}
			catch (Exception e) {
				log.debug("RGB fallback render attempt failed for page {}: {}", pageIndex + 1, e.getMessage());
			}
		}

		return null;
	}

	/**
	 * Validate rendered image
	 * @param image The rendered BufferedImage
	 * @param pageIndex The page index for logging
	 * @return true if image is valid, false otherwise
	 */
	private boolean validateRenderedImage(BufferedImage image, int pageIndex) {
		if (image == null) {
			log.warn("Page {} rendered as null image", pageIndex + 1);
			return false;
		}
		if (image.getWidth() <= 0 || image.getHeight() <= 0) {
			log.warn("Page {} rendered with invalid dimensions: {}x{}", pageIndex + 1, image.getWidth(),
					image.getHeight());
			return false;
		}
		return true;
	}

	/**
	 * Optimize image for OCR processing by reducing size and improving quality
	 * @param originalImage The original BufferedImage
	 * @return Optimized BufferedImage for OCR
	 */
	private BufferedImage optimizeImageForOcr(BufferedImage originalImage) {
		int originalWidth = originalImage.getWidth();
		int originalHeight = originalImage.getHeight();

		// Calculate optimized dimensions (max 2000px on longest side for OCR)
		int maxDimension = 2000;
		int newWidth = originalWidth;
		int newHeight = originalHeight;

		if (originalWidth > maxDimension || originalHeight > maxDimension) {
			if (originalWidth > originalHeight) {
				newWidth = maxDimension;
				newHeight = (int) ((float) originalHeight * maxDimension / originalWidth);
			}
			else {
				newHeight = maxDimension;
				newWidth = (int) ((float) originalWidth * maxDimension / originalHeight);
			}
		}

		// Only resize if necessary
		if (newWidth != originalWidth || newHeight != originalHeight) {
			log.debug("Resizing image from {}x{} to {}x{} for OCR optimization", originalWidth, originalHeight,
					newWidth, newHeight);

			BufferedImage resizedImage = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB);
			java.awt.Graphics2D g2d = resizedImage.createGraphics();

			// Use high-quality rendering hints for better OCR results
			g2d.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
					java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			g2d.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING, java.awt.RenderingHints.VALUE_RENDER_QUALITY);
			g2d.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);

			g2d.drawImage(originalImage, 0, 0, newWidth, newHeight, null);
			g2d.dispose();

			return resizedImage;
		}

		return originalImage;
	}

	/**
	 * Get configured ImageType from LynxeProperties
	 * @return configured ImageType or default RGB
	 */
	private ImageType getConfiguredImageType() {
		if (lynxeProperties != null) {
			String configuredImageType = lynxeProperties.getImageRecognitionImageType();
			if (configuredImageType != null) {
				try {
					return ImageType.valueOf(configuredImageType.toUpperCase());
				}
				catch (IllegalArgumentException e) {
					log.warn("Invalid image type configured: {}, using default RGB", configuredImageType);
				}
			}
		}
		return ImageType.RGB; // Default image type
	}

	/**
	 * Get configured model name from LynxeProperties
	 * @return configured model name or default value
	 */
	private String getConfiguredModelName() {
		if (lynxeProperties != null) {
			String configuredModelName = lynxeProperties.getImageRecognitionModelName();
			if (configuredModelName != null && !configuredModelName.trim().isEmpty()) {
				return configuredModelName;
			}
		}
		return "qwen-vl-ocr-latest"; // Default model name
	}

	/**
	 * Get configured max retry attempts from LynxeProperties
	 * @return configured max retry attempts or default value
	 */
	private int getConfiguredMaxRetryAttempts() {
		if (lynxeProperties != null) {
			Integer configuredMaxRetryAttempts = lynxeProperties.getImageRecognitionMaxRetryAttempts();
			if (configuredMaxRetryAttempts != null && configuredMaxRetryAttempts > 0) {
				return configuredMaxRetryAttempts;
			}
		}
		return 3; // Default max retry attempts
	}

	/**
	 * Get current OCR processor configuration status
	 * @return Status string describing current OCR configuration
	 */
	public String getOcrConfigurationStatus() {
		StringBuilder status = new StringBuilder();
		status.append("OCR Processor Configuration:\n");
		status.append("- Model: ").append(getConfiguredModelName()).append("\n");
		status.append("- DPI: ").append(getConfiguredDpi()).append("\n");
		status.append("- Image Type: ").append(getConfiguredImageType()).append("\n");
		status.append("- Max Retry Attempts: ").append(getConfiguredMaxRetryAttempts()).append("\n");
		status.append("- Executor Pool Size: ")
			.append(imageRecognitionExecutorPool != null
					? String.valueOf(imageRecognitionExecutorPool.getCurrentPoolSize()) : "Not Available")
			.append("\n");
		return status.toString();
	}

}
