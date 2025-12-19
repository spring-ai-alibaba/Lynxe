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
package com.alibaba.cloud.ai.lynxe.tool.image;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.image.ImageGeneration;
import org.springframework.ai.image.ImageModel;
import org.springframework.ai.image.ImagePrompt;
import org.springframework.ai.image.ImageResponse;
import org.springframework.ai.model.SimpleApiKey;
import org.springframework.ai.openai.OpenAiImageModel;
import org.springframework.ai.openai.OpenAiImageOptions;
import org.springframework.ai.openai.api.OpenAiImageApi;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import com.alibaba.cloud.ai.lynxe.model.entity.DynamicModelEntity;
import com.alibaba.cloud.ai.lynxe.model.repository.DynamicModelRepository;
import com.alibaba.cloud.ai.lynxe.tool.AbstractBaseTool;
import com.alibaba.cloud.ai.lynxe.tool.code.ToolExecuteResult;
import com.alibaba.cloud.ai.lynxe.tool.i18n.ToolI18nService;
import com.fasterxml.jackson.databind.ObjectMapper;

@Component
public class ImageGenerationTool extends AbstractBaseTool<ImageGenerationRequest> {

	private static final Logger log = LoggerFactory.getLogger(ImageGenerationTool.class);

	private final DynamicModelRepository dynamicModelRepository;

	private final ObjectProvider<RestClient.Builder> restClientBuilderProvider;

	private final ObjectMapper objectMapper;

	private final ToolI18nService toolI18nService;

	public ImageGenerationTool(DynamicModelRepository dynamicModelRepository,
			ObjectProvider<RestClient.Builder> restClientBuilderProvider, ObjectMapper objectMapper,
			ToolI18nService toolI18nService) {
		this.dynamicModelRepository = dynamicModelRepository;
		this.restClientBuilderProvider = restClientBuilderProvider;
		this.objectMapper = objectMapper;
		this.toolI18nService = toolI18nService;
	}

	@Override
	public String getServiceGroup() {
		return "default-service-group";
	}

	@Override
	public String getName() {
		return "image_generate";
	}

	@Override
	public String getDescription() {
		return toolI18nService.getDescription("image-generate-tool");
	}

	@Override
	public String getParameters() {
		return toolI18nService.getParameters("image-generate-tool");
	}

	@Override
	public Class<ImageGenerationRequest> getInputType() {
		return ImageGenerationRequest.class;
	}

	@Override
	public ToolExecuteResult run(ImageGenerationRequest request) {
		log.info("ImageGenerationTool request received: prompt={}, model={}, size={}, quality={}, n={}",
				request != null ? request.getPrompt() : null, request != null ? request.getModel() : null,
				request != null ? request.getSize() : null, request != null ? request.getQuality() : null,
				request != null ? request.getN() : null);

		try {
			// Validate prompt
			if (request == null || request.getPrompt() == null || request.getPrompt().trim().isEmpty()) {
				return new ToolExecuteResult("Prompt is required for image generation");
			}

			// Get model configuration
			DynamicModelEntity modelEntity = getModelEntity(request.getModel());
			if (modelEntity == null) {
				return new ToolExecuteResult("Model configuration not found. Please configure a model first.");
			}
			// Create ImageModel directly (similar to how PdfOcrProcessor uses ChatClient)
			ImageModel imageModel = createImageModel(modelEntity);

			// Build OpenAiImageOptions
			OpenAiImageOptions.Builder optionsBuilder = OpenAiImageOptions.builder();
			if (request.getModel() != null) {
				optionsBuilder.model(request.getModel());
			}
			if (request.getSize() != null) {
				// Parse size string (e.g., "1024x1024") into width and height
				String[] dimensions = request.getSize().split("x");
				if (dimensions.length == 2) {
					try {
						int width = Integer.parseInt(dimensions[0]);
						int height = Integer.parseInt(dimensions[1]);
						optionsBuilder.width(width).height(height);
					}
					catch (NumberFormatException e) {
						log.warn("Invalid size format: {}, using default 1024x1024", request.getSize());
						optionsBuilder.width(1024).height(1024);
					}
				}
				else {
					optionsBuilder.width(1024).height(1024); // Default
				}
			}
			else {
				optionsBuilder.width(1024).height(1024);
			}
			if (request.getQuality() != null) {
				optionsBuilder.quality(request.getQuality());
			}
			else {
				optionsBuilder.quality("standard"); // Default
			}
			if (request.getN() != null && request.getN() > 1) {
				optionsBuilder.N(request.getN());
			}

			OpenAiImageOptions options = optionsBuilder.build();

			// Create ImagePrompt and call ImageModel
			ImagePrompt imagePrompt = new ImagePrompt(request.getPrompt(), options);
			ImageResponse response = imageModel.call(imagePrompt);

			// Extract image results
			// In Spring AI 1.0.1, getResults() returns List<ImageGeneration>
			List<ImageGeneration> results = response.getResults();
			if (results == null || results.isEmpty()) {
				return new ToolExecuteResult("No image generated in response");
			}

			// Extract image URLs from results
			// In Spring AI 1.0.1, ImageGeneration.getOutput() returns an object with
			// getUrl() method
			List<String> imageUrls = new ArrayList<>();
			for (ImageGeneration generation : results) {
				// getOutput() returns the image data which has getUrl() method
				Object output = generation.getOutput();
				if (output != null) {
					// Based on Spring AI 1.0.1 API documentation, output should have
					// getUrl() method
					// We'll use a safe approach to extract the URL
					String imageUrl = extractImageUrl(output);
					if (imageUrl != null && !imageUrl.isEmpty()) {
						imageUrls.add(imageUrl);
					}
				}
			}

			if (imageUrls.isEmpty()) {
				return new ToolExecuteResult("No image URLs found in response");
			}

			// Return result as JSON string
			Map<String, Object> resultMap = new HashMap<>();
			resultMap.put("images", imageUrls);
			resultMap.put("count", imageUrls.size());
			resultMap.put("prompt", request.getPrompt());
			if (request.getModel() != null) {
				resultMap.put("model", request.getModel());
			}
			if (request.getSize() != null) {
				resultMap.put("size", request.getSize());
			}
			if (request.getQuality() != null) {
				resultMap.put("quality", request.getQuality());
			}

			String resultJson = objectMapper.writeValueAsString(resultMap);
			log.info("Image generation successful: {} image(s) generated", imageUrls.size());
			return new ToolExecuteResult(resultJson);

		}
		catch (IllegalArgumentException e) {
			log.error("Invalid argument in image generation: {}", e.getMessage(), e);
			return new ToolExecuteResult("Image generation failed: Invalid argument - " + e.getMessage());
		}
		catch (RuntimeException e) {
			log.error("Runtime error during image generation: {}", e.getMessage(), e);
			// Include root cause if available
			Throwable cause = e.getCause();
			String errorMessage = "Image generation failed: " + e.getMessage();
			if (cause != null && cause.getMessage() != null) {
				errorMessage += " (Cause: " + cause.getMessage() + ")";
			}
			return new ToolExecuteResult(errorMessage);
		}
		catch (Exception e) {
			log.error("Unexpected error during image generation: {}", e.getMessage(), e);
			// Include root cause if available
			Throwable cause = e.getCause();
			String errorMessage = "Image generation failed: " + e.getMessage();
			if (cause != null && cause.getMessage() != null) {
				errorMessage += " (Cause: " + cause.getMessage() + ")";
			}
			return new ToolExecuteResult(errorMessage);
		}
	}

	/**
	 * Get model entity from repository, similar to how LlmService does it
	 * @param modelName Optional model name, uses default if null
	 * @return DynamicModelEntity or null if not found
	 */
	private DynamicModelEntity getModelEntity(String modelName) {
		try {
			if (modelName != null && !modelName.trim().isEmpty()) {
				// Try to find by model name
				List<DynamicModelEntity> models = dynamicModelRepository.findAll();
				for (DynamicModelEntity model : models) {
					if (modelName.equals(model.getModelName())) {
						return model;
					}
				}
				log.warn("Model with name '{}' not found, using default model", modelName);
			}

			// Use default model
			DynamicModelEntity defaultModel = dynamicModelRepository.findByIsDefaultTrue();
			if (defaultModel != null) {
				return defaultModel;
			}

			// Fallback to first available model
			List<DynamicModelEntity> availableModels = dynamicModelRepository.findAll();
			if (!availableModels.isEmpty()) {
				log.info("Using first available model: {}", availableModels.get(0).getModelName());
				return availableModels.get(0);
			}

			log.error("No model configuration found in repository");
			return null;
		}
		catch (Exception e) {
			log.error("Error getting model entity from repository", e);
			return null;
		}
	}

	/**
	 * Create ImageModel instance directly, similar to how openAiImageModel works in
	 * LlmService
	 * @param dynamicModelEntity Model entity with configuration
	 * @return ImageModel instance
	 */
	private ImageModel createImageModel(DynamicModelEntity dynamicModelEntity) {
		if (dynamicModelEntity == null) {
			throw new IllegalArgumentException("DynamicModelEntity cannot be null");
		}

		// Normalize baseUrl - remove trailing slashes (similar to LlmService)
		String baseUrl = normalizeBaseUrl(dynamicModelEntity.getBaseUrl());
		if (baseUrl == null || baseUrl.trim().isEmpty()) {
			throw new IllegalArgumentException("Base URL cannot be null or empty");
		}

		String apiKey = dynamicModelEntity.getApiKey();
		if (apiKey == null || apiKey.trim().isEmpty()) {
			throw new IllegalArgumentException("API key cannot be null or empty");
		}

		// Normalize baseUrl for image API endpoint
		// OpenAiImageApi internally uses /v1/images/generations
		// If baseUrl ends with /v1, we need to remove it to avoid duplicate /v1
		// This follows the same logic as normalizeCompletionsPath in LlmService
		String normalizedBaseUrl = normalizeBaseUrlForApiEndpoint(baseUrl);

		// Build OpenAiImageApi - image generation endpoint is typically
		// /v1/images/generations
		// but OpenAiImageApi handles this internally, so we just need the base URL
		OpenAiImageApi.Builder imageApiBuilder = OpenAiImageApi.builder()
			.baseUrl(normalizedBaseUrl != null ? normalizedBaseUrl : "https://api.openai.com")
			.apiKey(new SimpleApiKey(dynamicModelEntity.getApiKey()));

		// Prepare headers with Accept: application/json to ensure JSON response
		// This fixes the issue where server returns HTML error page instead of JSON
		MultiValueMap<String, String> multiValueMap = new LinkedMultiValueMap<>();

		// Add Accept header to ensure JSON response
		multiValueMap.add("Accept", MediaType.APPLICATION_JSON_VALUE);

		// Add custom headers if present (these may override Accept if specified)
		Map<String, String> headers = dynamicModelEntity.getHeaders();
		if (headers != null && !headers.isEmpty()) {
			headers.forEach((key, value) -> multiValueMap.add(key, value));
		}

		// Set headers on the builder
		imageApiBuilder.headers(multiValueMap);

		// Use RestClient builder (OpenAiImageApi uses RestClient, not WebClient)
		RestClient.Builder restClientBuilder = restClientBuilderProvider.getIfAvailable(RestClient::builder);
		if (restClientBuilder != null) {
			imageApiBuilder.restClientBuilder(restClientBuilder);
		}

		OpenAiImageApi imageApi = imageApiBuilder.build();

		// Create OpenAiImageModel
		return new OpenAiImageModel(imageApi);
	}

	/**
	 * Extract image URL from output object
	 * @param output The output object from ImageGeneration
	 * @return Image URL or null if not found
	 */
	private String extractImageUrl(Object output) {
		try {
			// Try to get URL using reflection (Spring AI 1.0.1 API)
			java.lang.reflect.Method getUrlMethod = output.getClass().getMethod("getUrl");
			Object urlObj = getUrlMethod.invoke(output);
			if (urlObj != null) {
				return urlObj.toString();
			}
			// Try getB64Json() as fallback
			try {
				java.lang.reflect.Method getB64JsonMethod = output.getClass().getMethod("getB64Json");
				Object b64JsonObj = getB64JsonMethod.invoke(output);
				if (b64JsonObj != null) {
					return b64JsonObj.toString();
				}
			}
			catch (Exception e) {
				log.debug("getB64Json() method not found", e);
			}
		}
		catch (Exception e) {
			log.warn("Failed to extract URL from image output: {}", e.getMessage());
		}
		// Fallback: try to convert output to string
		return output != null ? output.toString() : null;
	}

	@Override
	public void cleanup(String planId) {
		if (planId != null) {
			log.info("Cleaning up image generation resources for plan: {}", planId);
		}
	}

	@Override
	public boolean isSelectable() {
		return true;
	}

	@Override
	public String getCurrentToolStateString() {
		try {
			StringBuilder stateBuilder = new StringBuilder();
			stateBuilder.append("\n=== Image Generation Tool Current State ===\n");
			stateBuilder.append("Tool is ready to generate images from text prompts.\n");
			stateBuilder.append("Default size: 1024x1024\n");
			stateBuilder.append("Default quality: standard\n");
			stateBuilder.append("Supported sizes: 256x256, 512x512, 1024x1024, 1792x1024, 1024x1792\n");
			stateBuilder.append("Supported quality: standard, hd\n");
			stateBuilder.append("Supported number of images: 1-10\n");
			stateBuilder.append("\n=== End Image Generation Tool State ===\n");
			return stateBuilder.toString();
		}
		catch (Exception e) {
			log.error("Failed to get image generation tool state", e);
			return String.format("Image generation tool state error: %s", e.getMessage());
		}
	}

}
