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
package com.alibaba.cloud.ai.lynxe.runtime.controller;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Version information controller Provides version information API for both frontend and
 * backend
 */
@RestController
@RequestMapping("/api/version")
@CrossOrigin(origins = "*")
public class VersionController {

	private static final Logger logger = LoggerFactory.getLogger(VersionController.class);

	private static final String VERSION_PROPERTIES = "/version.properties";

	private static final String DEFAULT_VERSION = "unknown";

	private static final String DEFAULT_BUILD_TIME = "unknown";

	private String cachedVersion = null;

	private String cachedBuildTime = null;

	/**
	 * Get version information
	 * @return Version information including version number and build time
	 */
	@GetMapping
	public ResponseEntity<Map<String, Object>> getVersion() {
		Map<String, Object> versionInfo = new HashMap<>();

		// Load version information (with caching)
		if (cachedVersion == null || cachedBuildTime == null) {
			loadVersionInfo();
		}

		versionInfo.put("version", cachedVersion != null ? cachedVersion : DEFAULT_VERSION);
		// Format build time to readable format (convert from ISO format if needed)
		String formattedBuildTime = formatBuildTime(cachedBuildTime);
		versionInfo.put("buildTime", formattedBuildTime);
		versionInfo.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));

		return ResponseEntity.ok(versionInfo);
	}

	/**
	 * Load version information from version.properties file The file is automatically
	 * generated during Maven build with version from pom.xml
	 */
	private void loadVersionInfo() {
		try (InputStream inputStream = getClass().getResourceAsStream(VERSION_PROPERTIES)) {
			if (inputStream == null) {
				logger.warn("Version properties file not found: {}. Using default values.", VERSION_PROPERTIES);
				cachedVersion = DEFAULT_VERSION;
				cachedBuildTime = DEFAULT_BUILD_TIME;
				return;
			}

			Properties properties = new Properties();
			properties.load(inputStream);

			cachedVersion = properties.getProperty("version", DEFAULT_VERSION);
			cachedBuildTime = properties.getProperty("build.time", DEFAULT_BUILD_TIME);

			logger.info("Loaded version information - Version: {}, Build Time: {}", cachedVersion, cachedBuildTime);
		}
		catch (IOException e) {
			logger.error("Failed to load version information from {}", VERSION_PROPERTIES, e);
			cachedVersion = DEFAULT_VERSION;
			cachedBuildTime = DEFAULT_BUILD_TIME;
		}
	}

	/**
	 * Format build time to readable format
	 * Converts ISO format (2025-12-07T15:14:10Z) to readable format (2025-12-07 15:14:10)
	 * @param buildTime Build time string (may be in ISO format)
	 * @return Formatted build time string
	 */
	private String formatBuildTime(String buildTime) {
		if (buildTime == null || buildTime.equals(DEFAULT_BUILD_TIME)) {
			return DEFAULT_BUILD_TIME;
		}

		try {
			// Try to parse ISO format (e.g., 2025-12-07T15:14:10Z)
			if (buildTime.contains("T") && buildTime.contains("Z")) {
				Instant instant = Instant.parse(buildTime);
				LocalDateTime dateTime = LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
				return dateTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
			}
			// If already in readable format, return as is
			return buildTime;
		}
		catch (Exception e) {
			logger.debug("Failed to format build time '{}', using original value", buildTime);
			return buildTime;
		}
	}

}
