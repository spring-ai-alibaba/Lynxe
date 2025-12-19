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

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Request DTO for image generation tool
 */
public class ImageGenerationRequest {

	/**
	 * Text prompt for image generation (required)
	 */
	@JsonProperty("prompt")
	private String prompt;

	/**
	 * Model name (optional, uses default from config if not specified) Supported models:
	 */
	@JsonProperty("model")
	private String model;

	/**
	 * Image size (optional) "256x256", "512x512", "1024x1024", "1792x1024", "1024x1792"
	 * Default: "1024x1024"
	 */
	@JsonProperty("size")
	private String size;

	/**
	 * Quality setting (optional) Values: "standard" or "hd" Default: "standard"
	 */
	@JsonProperty("quality")
	private String quality;

	/**
	 * Number of images to generate (optional) 1-10
	 */
	@JsonProperty("n")
	private Integer n;

	public ImageGenerationRequest() {
	}

	public ImageGenerationRequest(String prompt) {
		this.prompt = prompt;
	}

	public String getPrompt() {
		return prompt;
	}

	public void setPrompt(String prompt) {
		this.prompt = prompt;
	}

	public String getModel() {
		return model;
	}

	public void setModel(String model) {
		this.model = model;
	}

	public String getSize() {
		return size;
	}

	public void setSize(String size) {
		this.size = size;
	}

	public String getQuality() {
		return quality;
	}

	public void setQuality(String quality) {
		this.quality = quality;
	}

	public Integer getN() {
		return n;
	}

	public void setN(Integer n) {
		this.n = n;
	}

}
