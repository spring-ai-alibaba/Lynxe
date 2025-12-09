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
package com.alibaba.cloud.ai.lynxe.tool.browser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.microsoft.playwright.Playwright;

/**
 * Spring Boot environment Playwright initializer Handles the special requirements for
 * running Playwright in Spring Boot fat jar
 */
@Component
public class SpringBootPlaywrightInitializer {

	private static final Logger log = LoggerFactory.getLogger(SpringBootPlaywrightInitializer.class);

	/**
	 * Initialize Playwright for Spring Boot environment
	 */
	public Playwright createPlaywright() {
		try {
			// Set up environment for Spring Boot
			setupSpringBootEnvironment();

			// Save current context class loader
			ClassLoader originalClassLoader = Thread.currentThread().getContextClassLoader();

			try {
				// Use this class's classloader (LaunchedClassLoader in Spring Boot)
				ClassLoader newCL = this.getClass().getClassLoader();
				log.info("Switching to ClassLoader: {} [{}]", newCL.getClass().getSimpleName(), newCL.toString());
				Thread.currentThread().setContextClassLoader(newCL);

				log.info("About to call Playwright.create()...");
				Playwright playwright = Playwright.create();
				log.info("Playwright.create() successful! Instance: {}", playwright.getClass().getName());

				// Skip directory traversal for performance - only log basic info
				// Detailed directory checks are only needed for debugging
				if (log.isDebugEnabled()) {
					log.debug("=== Post-Creation Directory Check (DEBUG only) ===");
					String browserPath = System.getProperty("playwright.browsers.path");
					String tempDir = System.getProperty("playwright.driver.tmpdir");
					log.debug("Browser path: {}, Temp dir: {}", browserPath, tempDir);
					log.debug("=====================================");
				}

				return playwright;
			}
			finally {
				// Always restore original class loader
				Thread.currentThread().setContextClassLoader(originalClassLoader);
			}
		}
		catch (Exception e) {
			log.error("Failed to create Playwright in Spring Boot environment", e);
			throw new RuntimeException("Failed to initialize Playwright", e);
		}
	}

	/**
	 * Set up environment properties for Spring Boot
	 */
	private void setupSpringBootEnvironment() {
		// Print detailed class loader information
		ClassLoader currentCL = Thread.currentThread().getContextClassLoader();
		ClassLoader thisCL = this.getClass().getClassLoader();

		log.info("=== Playwright Class Loader Analysis ===");
		log.info("Current thread context ClassLoader: {} [{}]", currentCL.getClass().getSimpleName(),
				currentCL.toString());
		log.info("This class ClassLoader: {} [{}]", thisCL.getClass().getSimpleName(), thisCL.toString());

		// Print classpath information
		String classPath = System.getProperty("java.class.path");
		log.info("Java classpath: {}", classPath);

		// Print loader path if exists
		String loaderPath = System.getProperty("loader.path");
		if (loaderPath != null) {
			log.info("Spring Boot loader.path: {}", loaderPath);
		}

		// Set browser path
		String browserPath = System.getProperty("user.home") + "/.cache/ms-playwright";
		System.setProperty("playwright.browsers.path", browserPath);

		// Set driver temp directory
		String tempDir = System.getProperty("java.io.tmpdir");
		System.setProperty("playwright.driver.tmpdir", tempDir);

		// Check if browsers are installed
		Path browsersPath = Paths.get(browserPath);
		if (Files.exists(browsersPath)) {
			log.info("Playwright browsers found at: {}", browserPath);
			// Verify the directory is actually readable and contains browser binaries
			try {
				if (Files.isDirectory(browsersPath) && Files.isReadable(browsersPath)) {
					// Check if chromium directory exists
					boolean hasChromium = Files.list(browsersPath)
						.anyMatch(p -> p.getFileName().toString().startsWith("chromium-"));
					if (hasChromium) {
						log.info("Chromium browser binaries found in: {}", browserPath);
						System.setProperty("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1");
					}
					else {
						log.warn(
								"Browser directory exists but Chromium not found. Browsers will be downloaded on first use.");
					}
				}
				else {
					log.warn("Browser path exists but is not a readable directory: {}", browserPath);
				}
			}
			catch (Exception e) {
				log.warn("Error checking browser binaries: {}. Browsers will be downloaded on first use.",
						e.getMessage());
			}
		}
		else {
			log.debug("Playwright browsers not found at: {}. They will be downloaded on first use.", browserPath);
		}

		log.info("Spring Boot Playwright environment configured:");
		log.info("  - Browser path: {}", browserPath);
		log.info("  - Temp directory: {}", tempDir);

		// Print all Playwright-related system properties
		log.info("=== Playwright Runtime Directories ===");
		log.info("  - playwright.browsers.path: {}", System.getProperty("playwright.browsers.path"));
		log.info("  - playwright.driver.tmpdir: {}", System.getProperty("playwright.driver.tmpdir"));
		log.info("  - PLAYWRIGHT_BROWSERS_PATH env: {}", System.getenv("PLAYWRIGHT_BROWSERS_PATH"));
		log.info("  - PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD: {}", System.getProperty("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD"));

		// Check actual directories - only in debug mode to avoid slow directory traversal
		if (log.isDebugEnabled()) {
			String[] checkPaths = { browserPath, browserPath + "/chromium-*", browserPath + "/firefox-*",
					browserPath + "/webkit-*", tempDir + "/playwright-java-*" };

			for (String path : checkPaths) {
				try {
					Path p = Paths.get(path.replace("*", ""));
					if (Files.exists(p)) {
						log.debug("  ✓ Directory exists: {}", path);
						if (Files.isDirectory(p)) {
							Files.list(p).forEach(subPath -> log.debug("    - {}", subPath.getFileName()));
						}
					}
					else {
						log.debug("  ✗ Directory not found: {}", path);
					}
				}
				catch (Exception e) {
					log.debug("  ? Could not check path {}: {}", path, e.getMessage());
				}
			}
		}

		log.info("==========================================");
	}

	/**
	 * Check if Playwright can be initialized
	 */
	public boolean canInitialize() {
		try {
			// Try to find the required classes
			Class.forName("com.microsoft.playwright.Playwright");
			return true;
		}
		catch (ClassNotFoundException e) {
			log.error("Playwright classes not found in classpath", e);
			return false;
		}
	}

}
