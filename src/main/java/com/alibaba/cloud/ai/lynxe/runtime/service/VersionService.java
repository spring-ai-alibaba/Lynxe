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
package com.alibaba.cloud.ai.lynxe.runtime.service;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Version service for managing application version information Provides version
 * information extracted from version.properties file
 */
@Service
public class VersionService {

	private static final Logger logger = LoggerFactory.getLogger(VersionService.class);

	private static final String VERSION_PROPERTIES = "/version.properties";

	private static final String DEFAULT_VERSION = "unknown";

	private String cachedVersion = null;

	/**
	 * Get current application version
	 * @return Current version string
	 */
	public String getCurrentVersion() {
		if (cachedVersion == null) {
			loadVersionInfo();
		}
		return cachedVersion != null ? cachedVersion : DEFAULT_VERSION;
	}

	/**
	 * Load version information from version.properties file The file is automatically
	 * generated during Maven build with version from pom.xml
	 */
	private void loadVersionInfo() {
		try (InputStream inputStream = getClass().getResourceAsStream(VERSION_PROPERTIES)) {
			if (inputStream == null) {
				logger.warn("Version properties file not found: {}. Using default version.", VERSION_PROPERTIES);
				cachedVersion = DEFAULT_VERSION;
				return;
			}

			Properties properties = new Properties();
			properties.load(inputStream);

			cachedVersion = properties.getProperty("version", DEFAULT_VERSION);

			logger.info("Loaded version information - Version: {}", cachedVersion);
		}
		catch (IOException e) {
			logger.error("Failed to load version information from {}", VERSION_PROPERTIES, e);
			cachedVersion = DEFAULT_VERSION;
		}
	}

}
