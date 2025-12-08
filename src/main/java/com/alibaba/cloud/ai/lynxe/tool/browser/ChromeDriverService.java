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

import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import com.alibaba.cloud.ai.lynxe.config.LynxeProperties;
import com.alibaba.cloud.ai.lynxe.tool.filesystem.UnifiedDirectoryManager;
import com.alibaba.cloud.ai.lynxe.tool.innerStorage.SmartContentSavingService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.TimeoutError;
import com.microsoft.playwright.options.ServiceWorkerPolicy;

import jakarta.annotation.PreDestroy;

@Service
@Primary
public class ChromeDriverService implements IChromeDriverService {

	private static final Logger log = LoggerFactory.getLogger(ChromeDriverService.class);

	private final ConcurrentHashMap<String, DriverWrapper> drivers = new ConcurrentHashMap<>();

	private final ConcurrentHashMap<String, Boolean> initialCleanupDone = new ConcurrentHashMap<>();

	private final Lock driverLock = new ReentrantLock();

	private LynxeProperties lynxeProperties;

	private SmartContentSavingService innerStorageService;

	private UnifiedDirectoryManager unifiedDirectoryManager;

	@Autowired(required = false)
	private SpringBootPlaywrightInitializer playwrightInitializer;

	/**
	 * Shared directory for storing cookies
	 */
	/**
	 * Shared directory for storing cookies
	 */
	private String sharedDir;

	/**
	 * Get current shared directory
	 */
	public String getSharedDir() {
		return sharedDir;
	}

	public ChromeDriverService(LynxeProperties lynxeProperties, SmartContentSavingService innerStorageService,
			UnifiedDirectoryManager unifiedDirectoryManager) {
		this.lynxeProperties = lynxeProperties;
		this.innerStorageService = innerStorageService;
		this.unifiedDirectoryManager = unifiedDirectoryManager;
		// Use UnifiedDirectoryManager to get the shared directory for playwright
		try {
			java.nio.file.Path playwrightDir = unifiedDirectoryManager.getWorkingDirectory().resolve("playwright");
			unifiedDirectoryManager.ensureDirectoryExists(playwrightDir);
			this.sharedDir = playwrightDir.toString();
		}
		catch (java.io.IOException e) {
			log.error("Failed to create playwright directory", e);
			this.sharedDir = unifiedDirectoryManager.getWorkingDirectory().resolve("playwright").toString();
		}
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			log.info("JVM shutting down - cleaning up Playwright processes");
			cleanupAllPlaywrightProcesses();
		}));
	}

	public DriverWrapper getDriver(String planId) {
		if (planId == null) {
			throw new IllegalArgumentException("planId cannot be null");
		}

		DriverWrapper currentDriver = drivers.get(planId);
		if (currentDriver != null) {
			// Check if the existing driver is still healthy
			if (isDriverHealthy(currentDriver)) {
				return currentDriver;
			}
			else {
				log.warn("Existing driver for planId {} is unhealthy, recreating", planId);
				closeDriverForPlan(planId);
				currentDriver = null;
			}
		}

		try {
			if (!driverLock.tryLock(30, TimeUnit.SECONDS)) {
				throw new RuntimeException("Failed to acquire driver lock within 30 seconds for planId: " + planId);
			}
			try {
				currentDriver = drivers.get(planId);
				if (currentDriver != null && isDriverHealthy(currentDriver)) {
					return currentDriver;
				}
				log.info("Creating new Playwright Browser instance for planId: {}", planId);
				currentDriver = createNewDriverWithRetry(planId);
				if (currentDriver != null) {
					drivers.put(planId, currentDriver);
					log.info("Successfully created and cached new driver for planId: {}", planId);
				}
				else {
					log.error("Failed to create new driver for planId: {}. All retry attempts failed.", planId);
					throw new RuntimeException("Failed to create new driver for planId: " + planId);
				}
			}
			finally {
				driverLock.unlock();
			}
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new RuntimeException("Interrupted while waiting for driver lock for planId: " + planId, e);
		}
		catch (Exception e) {
			log.error("Unexpected error while getting driver for planId: {}", planId, e);
			throw new RuntimeException("Failed to get driver for planId: " + planId, e);
		}

		return currentDriver;
	}

	private void cleanupAllPlaywrightProcesses() {
		log.info("Starting cleanup of all Playwright processes and drivers");
		try {
			// Close all drivers first before clearing the map
			// This ensures browser processes and I/O threads are properly terminated
			// DriverWrapper.close() will handle closing pages, browsers, contexts, and
			// Playwright instances
			for (String planId : drivers.keySet()) {
				DriverWrapper driver = drivers.get(planId);
				if (driver != null) {
					try {
						log.info("Closing driver for planId: {}", planId);
						driver.close();
					}
					catch (Exception e) {
						log.warn("Error closing driver for planId {}: {}", planId, e.getMessage());
					}
				}
			}

			// Now clear the map after all resources are closed
			drivers.clear();
			initialCleanupDone.clear();
			log.info("Successfully cleaned up all Playwright processes and drivers");
		}
		catch (Exception e) {
			log.error("Error cleaning up Browser processes", e);
		}
	}

	public void closeDriverForPlan(String planId) {
		DriverWrapper driver = drivers.remove(planId);
		if (driver != null) {
			driver.close();
		}
		// Remove cleanup flag when driver is closed, so next time it will do cleanup
		// again
		initialCleanupDone.remove(planId);

		// Clean up planId-specific userDataDir after browser is closed
		// This prevents accumulation of browser profile directories
		if (planId != null && sharedDir != null) {
			try {
				java.nio.file.Path planIdUserDataDir = java.nio.file.Paths.get(sharedDir, "planId-" + planId);
				if (java.nio.file.Files.exists(planIdUserDataDir)) {
					// Delete the planId-specific directory
					// Use a recursive delete utility if available, or delete files
					// manually
					try {
						java.nio.file.Files.walk(planIdUserDataDir)
							.sorted(java.util.Comparator.reverseOrder())
							.forEach(path -> {
								try {
									java.nio.file.Files.delete(path);
								}
								catch (java.io.IOException e) {
									log.warn("Failed to delete file/directory during cleanup: {}", path, e);
								}
							});
						log.info("Successfully cleaned up planId-specific userDataDir: {}", planIdUserDataDir);
					}
					catch (java.io.IOException e) {
						log.warn("Failed to clean up planId-specific userDataDir for planId {}: {}", planId,
								e.getMessage());
					}
				}
			}
			catch (Exception e) {
				log.warn("Error during cleanup of planId-specific userDataDir for planId {}: {}", planId,
						e.getMessage());
				// Don't fail the close operation if cleanup fails
			}
		}
	}

	/**
	 * Create new driver with retry mechanism 1
	 */
	private DriverWrapper createNewDriverWithRetry(String planId) {
		int maxRetries = 3;
		int retryDelay = 2000; // 2 seconds

		for (int attempt = 1; attempt <= maxRetries; attempt++) {
			try {
				log.info("Creating new browser driver for planId: {} (attempt {}/{})", planId, attempt, maxRetries);
				DriverWrapper driver = createDriverInstance(planId);
				if (driver != null && isDriverHealthy(driver)) {
					log.info("Successfully created healthy driver for planId: {} on attempt {}", planId, attempt);
					return driver;
				}
				else {
					log.warn("Created driver for planId: {} is not healthy on attempt {}", planId, attempt);
					if (driver != null) {
						try {
							driver.close();
						}
						catch (Exception e) {
							log.warn("Error closing unhealthy driver: {}", e.getMessage());
						}
					}
				}
			}
			catch (PlaywrightException e) {
				log.error("Playwright error on attempt {} for planId: {}: {}", attempt, planId, e.getMessage());
			}
			catch (Exception e) {
				log.error("Unexpected error on attempt {} for planId: {}: {}", attempt, planId, e.getMessage(), e);
			}

			if (attempt < maxRetries) {
				try {
					log.info("Waiting {} ms before retry attempt {} for planId: {}", retryDelay, attempt + 1, planId);
					Thread.sleep(retryDelay);
					retryDelay *= 2; // Exponential backoff
				}
				catch (InterruptedException ie) {
					Thread.currentThread().interrupt();
					log.error("Interrupted during retry delay for planId: {}", planId);
					break;
				}
			}
		}

		log.error("Failed to create driver for planId: {} after {} attempts", planId, maxRetries);
		return null;
	}

	/**
	 * Find planId for a given browser instance
	 */
	private String findPlanIdForBrowser(Browser browser) {
		if (browser == null) {
			return null;
		}
		for (java.util.Map.Entry<String, DriverWrapper> entry : drivers.entrySet()) {
			DriverWrapper wrapper = entry.getValue();
			if (wrapper != null && wrapper.getBrowser() == browser) {
				return entry.getKey();
			}
		}
		return null;
	}

	/**
	 * Check if driver is healthy and responsive
	 */
	private boolean isDriverHealthy(DriverWrapper driver) {
		if (driver == null) {
			return false;
		}

		try {
			// Check if browser is connected
			Browser browser = driver.getBrowser();
			if (browser == null || !browser.isConnected()) {
				log.debug("Driver health check failed: browser not connected");
				return false;
			}

			// Check if browser context is still valid
			BrowserContext context = driver.getBrowserContext();
			if (context == null) {
				log.debug("Driver health check failed: browser context is null");
				return false;
			}

			// Check if current page is accessible
			Page page = driver.getCurrentPage();
			if (page == null || page.isClosed()) {
				log.debug("Driver health check failed: page is null or closed");
				return false;
			}

			// Try a simple operation with timeout
			try {
				page.evaluate("() => document.readyState");
				return true;
			}
			catch (TimeoutError e) {
				log.debug("Driver health check failed: page evaluation timeout");
				return false;
			}
			catch (PlaywrightException e) {
				log.debug("Driver health check failed: playwright exception: {}", e.getMessage());
				return false;
			}

		}
		catch (Exception e) {
			log.debug("Driver health check failed with exception: {}", e.getMessage());
			return false;
		}
	}

	/**
	 * Create browser driver instance with comprehensive error handling Uses
	 * browser.newContext() for better isolation and resource management
	 * @param planId Plan ID to track initial cleanup per plan
	 */
	private DriverWrapper createDriverInstance(String planId) {
		Playwright playwright = null;
		Browser browser = null;
		BrowserContext browserContext = null;
		Page page = null;
		String userDataDir = null;

		try {
			// Set system properties for Playwright configuration
			System.setProperty("playwright.browsers.path", System.getProperty("user.home") + "/.cache/ms-playwright");
			System.setProperty("playwright.driver.tmpdir", System.getProperty("java.io.tmpdir"));
			System.setProperty("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1");

			// Create Playwright instance with error handling
			try {
				if (playwrightInitializer != null && playwrightInitializer.canInitialize()) {
					log.info("Using SpringBootPlaywrightInitializer");
					playwright = playwrightInitializer.createPlaywright();
				}
				else {
					log.info("Using standard Playwright initialization");
					playwright = Playwright.create();
				}
				log.info("Successfully created Playwright instance");
			}
			catch (PlaywrightException e) {
				log.error("Playwright initialization failed: {}", e.getMessage(), e);
				throw new RuntimeException("Failed to initialize Playwright: " + e.getMessage(), e);
			}
			catch (Exception e) {
				log.error("Unexpected error during Playwright initialization: {}", e.getMessage(), e);
				throw new RuntimeException("Unexpected error during Playwright initialization", e);
			}

			// Get browser type with error handling
			BrowserType browserType;
			try {
				browserType = getBrowserTypeFromEnv(playwright);
				log.info("Using browser type: {}", browserType.name());
			}
			catch (Exception e) {
				log.error("Failed to get browser type: {}", e.getMessage(), e);
				throw new RuntimeException("Failed to get browser type", e);
			}

			// Validate browser binaries exist before launching (helps prevent crashes)
			try {
				String browserPath = System.getProperty("playwright.browsers.path");
				if (browserPath != null) {
					java.nio.file.Path browsersDir = java.nio.file.Paths.get(browserPath);
					if (!java.nio.file.Files.exists(browsersDir)) {
						log.warn(
								"Browser binaries directory does not exist: {}. Playwright will download browsers on first use.",
								browserPath);
					}
					else {
						log.debug("Browser binaries directory exists: {}", browserPath);
					}
				}
			}
			catch (Exception e) {
				log.warn("Could not validate browser binaries path: {}", e.getMessage());
				// Continue anyway - Playwright will handle missing binaries
			}

			// Use planId-specific userDataDir to allow multiple browser processes
			// Each planId gets its own isolated userDataDir:
			// extensions/playwright/planId-{planId}
			// This prevents Chromium from locking the directory and allows multiple
			// browsers
			// Using launchPersistentContext() is the recommended Playwright way to handle
			// persistent profiles
			java.nio.file.Path userDataDirPath = java.nio.file.Paths.get(sharedDir, "planId-" + planId);
			try {
				// Ensure the planId-specific directory exists
				unifiedDirectoryManager.ensureDirectoryExists(userDataDirPath);
				userDataDir = userDataDirPath.toString();
				log.info("Configured planId-specific persistent userDataDir: {}", userDataDir);
			}
			catch (Exception e) {
				log.warn("Failed to setup userDataDir, browser will use temporary profile: {}", e.getMessage());
				// Continue without persistent userDataDir - history cleaning won't work
				// but browser will still function
			}

			// Configure launch persistent context options with optimizations for faster
			// startup
			// Store configuration values for use in both persistent and fallback modes
			BrowserType.LaunchPersistentContextOptions launchOptions = new BrowserType.LaunchPersistentContextOptions();
			List<String> args = null;
			String userAgent = null;
			boolean headlessMode = false;
			java.nio.file.Path storageStatePath = null;
			boolean hasStorageState = false;

			try {
				// Basic configuration with error handling for user agent
				try {
					userAgent = getRandomUserAgent();
				}
				catch (Exception e) {
					log.warn("Failed to get random user agent, using default: {}", e.getMessage());
					userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
				}

				// Build optimized arguments list for faster startup
				// Critical: disable background networking to prevent unnecessary network
				// requests
				// Note: Browser runs in normal mode (not incognito) to preserve cookies
				// and history
				args = new java.util.ArrayList<>(Arrays.asList(
						// Essential arguments
						"--remote-allow-origins=*", "--disable-blink-features=AutomationControlled",
						"--disable-infobars", "--disable-notifications", "--disable-dev-shm-usage", "--no-sandbox",
						"--disable-gpu", "--lang=zh-CN,zh,en-US,en", "--user-agent=" + userAgent,
						"--window-size=1440,900",
						// Ensure normal mode (not incognito) - do not add --incognito
						// flag
						// Performance optimizations - disable background network requests
						"--disable-background-networking", // Critical: prevents
															// background network requests
						"--disable-background-timer-throttling", "--disable-backgrounding-occluded-windows",
						"--disable-breakpad", "--disable-client-side-phishing-detection",
						"--disable-component-extensions-with-background-pages", "--disable-component-update", // Disables
																												// component
																												// updates
						"--disable-default-apps", // Disables default apps
						"--disable-domain-reliability", // Disables domain reliability
														// service
						"--disable-extensions", // Disables extensions
						"--disable-features=TranslateUI", // Disables translate UI
						"--disable-hang-monitor", "--disable-ipc-flooding-protection", "--disable-popup-blocking",
						"--disable-prompt-on-repost", "--disable-renderer-backgrounding", "--disable-sync", // Disables
																											// sync
																											// service
						"--disable-translate", "--metrics-recording-only", "--no-first-run", // Skips
																								// first
																								// run
																								// tasks
						"--safebrowsing-disable-auto-update", // Disables safe browsing
																// updates
						"--enable-automation", "--password-store=basic", "--use-mock-keychain",
						// macOS-specific crash prevention flags
						"--disable-software-rasterizer", // Prevents GPU-related crashes
															// on macOS
						"--disable-accelerated-2d-canvas", // Prevents canvas rendering
															// crashes
						"--disable-accelerated-video-decode", // Prevents video decode
																// crashes
						"--disable-features=UseChromeOSDirectVideoDecoder", // Prevents
																			// video
																			// decoder
																			// crashes
						"--disable-features=MediaFoundationRenderer", // Prevents media
																		// foundation
																		// crashes
						"--js-flags=--max-old-space-size=4096")); // Limits JS memory to
																	// prevent OOM crashes

				launchOptions.setArgs(args);

				// Set viewport size
				launchOptions.setViewportSize(1440, 900);

				// Set user agent
				launchOptions.setUserAgent(userAgent);

				// Set locale
				launchOptions.setLocale("zh-CN");

				// Disable service workers to prevent network requests during startup
				try {
					launchOptions.setServiceWorkers(ServiceWorkerPolicy.BLOCK);
					log.debug("Service workers disabled for faster startup");
				}
				catch (Exception e) {
					log.warn("Failed to disable service workers: {}", e.getMessage());
				}

				// Set headless mode based on configuration
				headlessMode = lynxeProperties.getBrowserHeadless();
				if (headlessMode) {
					log.info("Enable Playwright headless mode");
					launchOptions.setHeadless(true);
				}
				else {
					log.info("Enable Playwright non-headless mode");
					launchOptions.setHeadless(false);
				}

				// Set timeout for browser launch
				launchOptions.setTimeout(60000); // 60 seconds timeout for browser launch

				// Check for storage state (cookies, localStorage, etc.) for persistence
				storageStatePath = java.nio.file.Paths.get(sharedDir, "storage-state.json");
				hasStorageState = java.nio.file.Files.exists(storageStatePath);
				if (hasStorageState) {
					log.info("Storage state file found: {}", storageStatePath);
				}
				else {
					log.debug("Storage state file not found, creating new context without storage state: {}",
							storageStatePath);
				}

			}
			catch (Exception e) {
				log.error("Failed to configure browser launch options: {}", e.getMessage(), e);
				throw new RuntimeException("Failed to configure browser launch options", e);
			}

			// Launch persistent context with error handling
			// Using launchPersistentContext() is the recommended Playwright way to handle
			// persistent profiles with userDataDir
			try {
				if (userDataDir != null && !userDataDir.isEmpty()) {
					// Use persistent context with userDataDir for history cleaning
					browserContext = browserType.launchPersistentContext(userDataDirPath, launchOptions);
					log.info("Successfully launched Playwright persistent context with userDataDir: {}", userDataDir);
					// Get browser instance from context (browser is automatically managed
					// by Playwright)
					browser = browserContext.browser();
					if (browser == null) {
						throw new RuntimeException("Browser context created but browser is null");
					}

					// Load shared cookies from storage-state.json if it exists
					// This allows multiple browser instances to share cookies
					if (hasStorageState && storageStatePath != null) {
						try {
							// Read and parse storage state JSON file
							ObjectMapper objectMapper = new ObjectMapper();
							JsonNode storageStateJson = objectMapper.readTree(storageStatePath.toFile());
							JsonNode cookiesNode = storageStateJson.get("cookies");
							if (cookiesNode != null && cookiesNode.isArray() && cookiesNode.size() > 0) {
								// Convert JSON cookies to Playwright Cookie objects
								List<com.microsoft.playwright.options.Cookie> cookies = new java.util.ArrayList<>();
								for (JsonNode cookieNode : cookiesNode) {
									com.microsoft.playwright.options.Cookie cookie = new com.microsoft.playwright.options.Cookie(
											cookieNode.get("name").asText(), cookieNode.get("value").asText());
									if (cookieNode.has("domain")) {
										cookie.setDomain(cookieNode.get("domain").asText());
									}
									if (cookieNode.has("path")) {
										cookie.setPath(cookieNode.get("path").asText());
									}
									if (cookieNode.has("expires")) {
										cookie.setExpires(cookieNode.get("expires").asDouble());
									}
									if (cookieNode.has("httpOnly")) {
										cookie.setHttpOnly(cookieNode.get("httpOnly").asBoolean());
									}
									if (cookieNode.has("secure")) {
										cookie.setSecure(cookieNode.get("secure").asBoolean());
									}
									// Note: sameSite is typically handled automatically
									// by Playwright
									// when loading from storage state, so we skip manual
									// setting
									cookies.add(cookie);
								}
								if (!cookies.isEmpty()) {
									browserContext.addCookies(cookies);
									log.info("Loaded {} cookies from shared storage state: {}", cookies.size(),
											storageStatePath);
								}
							}
						}
						catch (Exception e) {
							log.warn(
									"Failed to load shared cookies from storage state: {}. Continuing without shared cookies.",
									e.getMessage());
							// Continue without shared cookies - browser will still
							// function
						}
					}
				}
				else {
					// Fallback: use regular launch if userDataDir creation failed
					log.warn("userDataDir not available, falling back to regular launch");
					BrowserType.LaunchOptions regularLaunchOptions = new BrowserType.LaunchOptions();
					if (args != null) {
						regularLaunchOptions.setArgs(args);
					}
					regularLaunchOptions.setHeadless(headlessMode);
					regularLaunchOptions.setTimeout(60000); // 60 seconds timeout
					browser = browserType.launch(regularLaunchOptions);
					log.info("Successfully launched Playwright Browser instance (fallback mode)");

					// Create context with the same options
					Browser.NewContextOptions contextOptions = new Browser.NewContextOptions();
					contextOptions.setViewportSize(1440, 900);
					if (userAgent != null) {
						contextOptions.setUserAgent(userAgent);
					}
					contextOptions.setLocale("zh-CN");
					contextOptions.setServiceWorkers(ServiceWorkerPolicy.BLOCK);
					if (hasStorageState && storageStatePath != null) {
						contextOptions.setStorageStatePath(storageStatePath);
						log.info("Loading browser storage state from: {}", storageStatePath);
					}
					browserContext = browser.newContext(contextOptions);
					log.info("Successfully created browser context (fallback mode)");
				}

				// Verify browser connection
				if (browser != null && !browser.isConnected()) {
					throw new RuntimeException("Browser launched but is not connected");
				}

				// Verify context is valid
				if (browserContext == null) {
					throw new RuntimeException("Browser context was created but is null");
				}

				// Set up browser crash listener for better error handling
				if (browser != null) {
					browser.onDisconnected((Browser disconnectedBrowser) -> {
						log.error("Browser disconnected unexpectedly - possible crash detected");
						// Mark driver as unhealthy for this planId
						String disconnectedPlanId = findPlanIdForBrowser(disconnectedBrowser);
						if (disconnectedPlanId != null) {
							log.warn("Removing crashed browser driver for planId: {}", disconnectedPlanId);
							drivers.remove(disconnectedPlanId);
						}
					});
					log.debug("Browser crash listener registered");
				}

			}
			catch (PlaywrightException e) {
				log.error("Failed to launch browser context: {}", e.getMessage(), e);
				throw new RuntimeException("Failed to launch browser context: " + e.getMessage(), e);
			}
			catch (Exception e) {
				log.error("Unexpected error during browser context launch: {}", e.getMessage(), e);
				throw new RuntimeException("Unexpected error during browser context launch", e);
			}

			// Create new page from context with error handling
			// According to Playwright call tree: context.newPage() ->
			// sendMessage("newPage")
			// -> [driver process] creates new page and initializes page environment
			try {
				// browserContext is guaranteed to be non-null here due to previous
				// validation

				// Check if there are existing pages from storage state
				// When storage state is loaded, context.newContext() may restore existing
				// pages
				// We preserve them instead of closing, as they may contain important
				// state
				// Use cached hasStorageState flag to avoid duplicate file system check
				if (hasStorageState) {
					try {
						// Check for existing pages restored from storage state
						List<Page> existingPages = browserContext.pages();
						if (!existingPages.isEmpty()) {
							log.info("Found {} existing page(s) from storage state for planId {}, preserving them",
									existingPages.size(), planId);
							// Use the first existing page if available
							page = existingPages.get(0);
							log.info("Using existing page from storage state: {}", page.url());
						}
						else {
							// No existing pages restored, create a new one
							page = browserContext.newPage();
							log.info("Successfully created new page from context");
						}
					}
					catch (Exception e) {
						log.warn("Failed to check existing pages from storage state, creating new page: {}",
								e.getMessage());
						// Fallback: create new page if we can't check existing pages
						page = browserContext.newPage();
						log.info("Successfully created new page from context (fallback)");
					}
				}
				else {
					// No storage state, directly create new page without checking
					// existing pages
					// This avoids unnecessary browserContext.pages() call when we know
					// there are no pages
					page = browserContext.newPage();
					log.info("Successfully created new page from context (no storage state)");
				}

				// Verify page is not closed
				if (page.isClosed()) {
					throw new RuntimeException("Page was created but is already closed");
				}

			}
			catch (PlaywrightException e) {
				log.error("Failed to create new page from context: {}", e.getMessage(), e);
				// Check if context was closed
				if (e.getMessage() != null && (e.getMessage().contains("Target closed")
						|| e.getMessage().contains("Context has been closed"))) {
					throw new RuntimeException("Browser context was closed while creating page: " + e.getMessage(), e);
				}
				throw new RuntimeException("Failed to create new page: " + e.getMessage(), e);
			}
			catch (Exception e) {
				log.error("Unexpected error during page creation: {}", e.getMessage(), e);
				throw new RuntimeException("Unexpected error during page creation", e);
			}

			// Configure page timeouts with error handling
			try {
				Integer timeout = lynxeProperties.getBrowserRequestTimeout();
				if (timeout != null && timeout > 0) {
					log.info("Setting browser page timeout to {} seconds", timeout);
					page.setDefaultTimeout(timeout * 1000); // Convert to milliseconds
					page.setDefaultNavigationTimeout(timeout * 1000);
					// Also set context-level timeout
					browserContext.setDefaultTimeout(timeout * 1000);
					browserContext.setDefaultNavigationTimeout(timeout * 1000);
				}
				else {
					// Set reasonable default timeouts
					log.info("Setting default browser timeouts (30 seconds)");
					page.setDefaultTimeout(30000);
					page.setDefaultNavigationTimeout(30000);
					browserContext.setDefaultTimeout(30000);
					browserContext.setDefaultNavigationTimeout(30000);
				}
			}
			catch (Exception e) {
				log.warn("Failed to set page/context timeouts, continuing with defaults: {}", e.getMessage());
			}

			// Create and return DriverWrapper with error handling
			// Following best practices: Browser -> BrowserContext -> Page
			try {
				DriverWrapper wrapper = new DriverWrapper(playwright, browser, browserContext, page, this.sharedDir,
						userDataDir);
				log.info("Successfully created DriverWrapper instance with browser context");
				return wrapper;
			}
			catch (Exception e) {
				log.error("Failed to create DriverWrapper: {}", e.getMessage(), e);
				throw new RuntimeException("Failed to create DriverWrapper", e);
			}

		}
		catch (Exception e) {
			// Comprehensive cleanup on any error
			// Follow Playwright best practices: Context -> Browser -> Playwright
			// According to call tree: context.close() automatically closes all pages
			log.error("Driver creation failed, performing cleanup: {}", e.getMessage(), e);

			// Step 1: Close browser context first (best practice)
			// This will automatically close all pages in the context
			// According to call tree: context.close() -> sendMessage("close")
			// -> [driver process] closes all pages and cleans up all resources
			if (browserContext != null) {
				try {
					// Check if browser is still connected before closing context
					if (browser != null && browser.isConnected()) {
						browserContext.close();
						log.debug("Cleaned up browser context after error (pages closed automatically)");
					}
					else {
						log.debug("Browser disconnected, skipping context close");
					}
				}
				catch (Exception ex) {
					log.warn("Failed to close browser context during cleanup: {}", ex.getMessage());
				}
			}
			// Note: No need to close page separately - context.close() handles it

			// Step 2: Close browser (after contexts are closed)
			if (browser != null && browser.isConnected()) {
				try {
					browser.close();
					log.debug("Cleaned up browser after error");
				}
				catch (Exception ex) {
					log.warn("Failed to close browser during cleanup: {}", ex.getMessage());
				}
			}

			// Step 3: Close Playwright instance (terminates I/O threads)
			if (playwright != null) {
				try {
					playwright.close();
					log.debug("Cleaned up playwright after error");
				}
				catch (Exception ex) {
					log.warn("Failed to close playwright during cleanup: {}", ex.getMessage());
				}
			}

			if (e instanceof RuntimeException) {
				throw (RuntimeException) e;
			}
			else {
				throw new RuntimeException("Failed to initialize Playwright Browser", e);
			}
		}
	}

	/**
	 * Get browser type, supports environment variable configuration
	 */
	private BrowserType getBrowserTypeFromEnv(Playwright playwright) {
		String browserName = System.getenv("BROWSER");
		if (browserName == null) {
			browserName = "chromium";
		}

		switch (browserName.toLowerCase()) {
			case "webkit":
				return playwright.webkit();
			case "firefox":
				return playwright.firefox();
			case "chromium":
			default:
				return playwright.chromium();
		}
	}

	private String getRandomUserAgent() {
		List<String> userAgents = Arrays.asList(
				"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
				"Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
				"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36 Edg/119.0.0.0");
		return userAgents.get(new Random().nextInt(userAgents.size()));
	}

	@PreDestroy
	public void cleanup() {
		log.info("Spring container shutting down - cleaning up Browser resources");
		cleanupAllPlaywrightProcesses();
	}

	public void setLynxeProperties(LynxeProperties lynxeProperties) {
		this.lynxeProperties = lynxeProperties;
	}

	public LynxeProperties getLynxeProperties() {
		return lynxeProperties;
	}

	public SmartContentSavingService getInnerStorageService() {
		return innerStorageService;
	}

	public UnifiedDirectoryManager getUnifiedDirectoryManager() {
		return unifiedDirectoryManager;
	}

}
