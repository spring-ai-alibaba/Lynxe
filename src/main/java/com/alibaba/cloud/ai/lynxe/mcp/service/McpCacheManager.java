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
package com.alibaba.cloud.ai.lynxe.mcp.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.alibaba.cloud.ai.lynxe.mcp.config.McpProperties;
import com.alibaba.cloud.ai.lynxe.mcp.model.po.McpConfigEntity;
import com.alibaba.cloud.ai.lynxe.mcp.model.po.McpConfigStatus;
import com.alibaba.cloud.ai.lynxe.mcp.model.vo.McpServiceEntity;
import com.alibaba.cloud.ai.lynxe.mcp.repository.McpConfigRepository;

import io.modelcontextprotocol.client.McpAsyncClient;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

/**
 * MCP Cache Manager with fail-fast design
 *
 * Key features: - Single connection per server - Fail-fast: main thread returns
 * immediately, background tasks handle connection operations - Automatic health check and
 * connection rebuild - No blocking operations on main/EventLoop threads
 */
@Component
public class McpCacheManager {

	private static final Logger logger = LoggerFactory.getLogger(McpCacheManager.class);

	/**
	 * Connection state enum
	 */
	private enum ConnectionState {

		CONNECTED, CLOSING, CLOSED, RECONNECTING

	}

	/**
	 * Connection wrapper with state management
	 */
	private static class ConnectionWrapper {

		private final AtomicReference<ConnectionState> state;

		private volatile McpServiceEntity serviceEntity;

		private final ReentrantLock rebuildLock;

		private final AtomicInteger pendingRequests;

		private final String serverName;

		public ConnectionWrapper(String serverName, McpServiceEntity serviceEntity) {
			this.serverName = serverName;
			this.serviceEntity = serviceEntity;
			this.state = new AtomicReference<>(
					serviceEntity != null ? ConnectionState.CONNECTED : ConnectionState.RECONNECTING);
			this.rebuildLock = new ReentrantLock();
			this.pendingRequests = new AtomicInteger(0);
		}

		public ConnectionState getState() {
			return state.get();
		}

		public boolean setState(ConnectionState expected, ConnectionState update) {
			return state.compareAndSet(expected, update);
		}

		public McpServiceEntity getServiceEntity() {
			return serviceEntity;
		}

		public void setServiceEntity(McpServiceEntity serviceEntity) {
			this.serviceEntity = serviceEntity;
		}

		public ReentrantLock getRebuildLock() {
			return rebuildLock;
		}

		public AtomicInteger getPendingRequests() {
			return pendingRequests;
		}

		public String getServerName() {
			return serverName;
		}

	}

	private final McpConnectionFactory connectionFactory;

	private final McpConfigRepository mcpConfigRepository;

	private final McpProperties mcpProperties;

	/**
	 * Single connection per server (serverName -> ConnectionWrapper)
	 */
	private final Map<String, ConnectionWrapper> connections = new ConcurrentHashMap<>();

	/**
	 * Configuration cache (serverName -> McpConfigEntity)
	 */
	private final Map<String, McpConfigEntity> configCache = new ConcurrentHashMap<>();

	/**
	 * Thread pool for async connection rebuild operations
	 */
	private final ExecutorService rebuildExecutor = Executors.newCachedThreadPool(r -> {
		Thread t = new Thread(r, "MCP-Rebuild");
		t.setDaemon(true);
		return t;
	});

	/**
	 * Thread pool for blocking connection operations to avoid blocking Netty EventLoop
	 * threads
	 */
	private final ExecutorService connectionExecutor = Executors.newCachedThreadPool(r -> {
		Thread t = new Thread(r, "MCP-Connection");
		t.setDaemon(true);
		return t;
	});

	/**
	 * Scheduled executor for background connection health check and rebuild
	 */
	private final ScheduledExecutorService healthCheckExecutor = Executors.newScheduledThreadPool(2, r -> {
		Thread t = new Thread(r, "MCP-HealthCheck");
		t.setDaemon(true);
		return t;
	});

	/**
	 * Track scheduled health check tasks for each server
	 */
	private final Map<String, ScheduledFuture<?>> healthCheckTasks = new ConcurrentHashMap<>();

	/**
	 * Maximum pending requests threshold for health check
	 */
	private static final int MAX_PENDING_REQUESTS_THRESHOLD = 100;

	/**
	 * Health check interval in seconds (default: 5 seconds)
	 */
	private static final long HEALTH_CHECK_INTERVAL_SECONDS = 5;

	public McpCacheManager(McpConnectionFactory connectionFactory, McpConfigRepository mcpConfigRepository,
			McpProperties mcpProperties) {
		this.connectionFactory = connectionFactory;
		this.mcpConfigRepository = mcpConfigRepository;
		this.mcpProperties = mcpProperties;
	}

	/**
	 * Initialize cache on startup
	 */
	@PostConstruct
	public void initializeCache() {
		logger.info("Initializing MCP cache manager with fail-fast design");
		try {
			// Load all enabled configurations
			List<McpConfigEntity> configs = mcpConfigRepository.findByStatus(McpConfigStatus.ENABLE);
			for (McpConfigEntity config : configs) {
				configCache.put(config.getMcpServerName(), config);
			}
			logger.info("Loaded {} MCP server configurations", configs.size());
		}
		catch (Exception e) {
			logger.error("Failed to initialize cache", e);
		}
	}

	/**
	 * Get connection for a server (fail-fast, non-blocking) Main thread returns
	 * immediately, background task handles connection creation/rebuild
	 * @param serverName Server name
	 * @return Connection wrapper if connected, null otherwise (fail-fast)
	 */
	private ConnectionWrapper getConnection(String serverName) {
		ConnectionWrapper wrapper = connections.get(serverName);
		if (wrapper != null) {
			ConnectionState state = wrapper.getState();
			if (state == ConnectionState.CONNECTED) {
				return wrapper;
			}
			// If connection is closed or closing, trigger background rebuild
			// (non-blocking)
			if (state == ConnectionState.CLOSED || state == ConnectionState.CLOSING) {
				triggerBackgroundRebuild(serverName);
				return null; // Fail-fast: return immediately
			}
			// If reconnecting, fail-fast: return null immediately
			if (state == ConnectionState.RECONNECTING) {
				return null;
			}
		}

		// Connection doesn't exist, trigger background creation (non-blocking)
		McpConfigEntity config = configCache.get(serverName);
		if (config == null) {
			logger.warn("MCP server configuration not found: {}", serverName);
			return null;
		}

		// Fail-fast: trigger background creation and return immediately
		triggerBackgroundCreation(serverName);
		return null;
	}

	/**
	 * Trigger background connection creation (non-blocking)
	 * @param serverName Server name
	 */
	private void triggerBackgroundCreation(String serverName) {
		// Check if already creating
		ConnectionWrapper existing = connections.get(serverName);
		if (existing != null && existing.getState() == ConnectionState.RECONNECTING) {
			return; // Already being created
		}

		// Create placeholder wrapper with RECONNECTING state
		ConnectionWrapper placeholder = new ConnectionWrapper(serverName, null);
		placeholder.setState(ConnectionState.CONNECTED, ConnectionState.RECONNECTING);
		connections.putIfAbsent(serverName, placeholder);

		// Trigger background creation task
		connectionExecutor.execute(() -> {
			try {
				McpConfigEntity config = configCache.get(serverName);
				if (config == null) {
					logger.warn("MCP server configuration not found for background creation: {}", serverName);
					connections.remove(serverName);
					return;
				}

				McpServiceEntity serviceEntity = connectionFactory.createConnection(config);
				if (serviceEntity != null) {
					ConnectionWrapper wrapper = connections.get(serverName);
					if (wrapper != null) {
						wrapper.setServiceEntity(serviceEntity);
						wrapper.setState(ConnectionState.RECONNECTING, ConnectionState.CONNECTED);
						logger.info("Background connection created successfully for server: {}", serverName);
						// Start health check for this connection
						scheduleHealthCheck(serverName);
					}
				}
				else {
					logger.error("Failed to create connection in background for server: {}", serverName);
					ConnectionWrapper wrapper = connections.get(serverName);
					if (wrapper != null) {
						wrapper.setState(ConnectionState.RECONNECTING, ConnectionState.CLOSED);
					}
				}
			}
			catch (Exception e) {
				logger.error("Exception during background connection creation for server: {}", serverName, e);
				ConnectionWrapper wrapper = connections.get(serverName);
				if (wrapper != null) {
					wrapper.setState(ConnectionState.RECONNECTING, ConnectionState.CLOSED);
				}
			}
		});
	}

	/**
	 * Trigger background connection rebuild (non-blocking)
	 * @param serverName Server name
	 */
	private void triggerBackgroundRebuild(String serverName) {
		ConnectionWrapper wrapper = connections.get(serverName);
		if (wrapper == null) {
			triggerBackgroundCreation(serverName);
			return;
		}

		// Only trigger if not already rebuilding
		if (wrapper.getState() == ConnectionState.RECONNECTING) {
			return; // Already rebuilding
		}

		// Mark as reconnecting and trigger background rebuild
		wrapper.setState(ConnectionState.CLOSED, ConnectionState.RECONNECTING);
		rebuildExecutor.execute(() -> rebuildConnection(serverName));
	}

	/**
	 * Get connection with automatic retry on connection errors (fail-fast) This method
	 * quickly checks connection status and returns, relying on background tasks for
	 * rebuild
	 * @param serverName Server name
	 * @return Connection wrapper if connected, null otherwise (fail-fast)
	 */
	public ConnectionWrapper getConnectionWithRetry(String serverName) {
		// Fail-fast: check once and return immediately
		ConnectionWrapper wrapper = getConnection(serverName);
		if (wrapper != null && wrapper.getState() == ConnectionState.CONNECTED) {
			return wrapper;
		}

		// Connection not available, trigger background rebuild if needed
		if (wrapper == null) {
			triggerBackgroundCreation(serverName);
		}
		else if (wrapper.getState() != ConnectionState.CONNECTED) {
			triggerBackgroundRebuild(serverName);
		}

		return null; // Fail-fast: return immediately
	}

	/**
	 * Rebuild connection for a server (executed in background thread)
	 * @param serverName Server name
	 */
	private void rebuildConnection(String serverName) {
		ConnectionWrapper wrapper = connections.get(serverName);
		if (wrapper == null) {
			// No existing connection, just create new one
			triggerBackgroundCreation(serverName);
			return;
		}

		ReentrantLock lock = wrapper.getRebuildLock();
		if (!lock.tryLock()) {
			// Another thread is already rebuilding
			logger.debug("Connection rebuild already in progress for server: {}", serverName);
			return;
		}

		try {
			// Double-check state after acquiring lock
			if (wrapper.getState() == ConnectionState.CONNECTED) {
				logger.debug("Connection already rebuilt by another thread for server: {}", serverName);
				return;
			}

			// Mark as reconnecting
			wrapper.setState(ConnectionState.CLOSED, ConnectionState.RECONNECTING);

			logger.info("Rebuilding connection for server: {}", serverName);

			// Close old connection gracefully
			McpServiceEntity oldEntity = wrapper.getServiceEntity();
			if (oldEntity != null && oldEntity.getMcpAsyncClient() != null) {
				closeClientSafely(oldEntity, serverName);
			}

			// Wait a bit before rebuilding (configurable delay, but in background thread)
			long rebuildDelay = mcpProperties.getConnectionRebuildDelayMillis();
			if (rebuildDelay > 0) {
				try {
					Thread.sleep(rebuildDelay);
				}
				catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					wrapper.setState(ConnectionState.RECONNECTING, ConnectionState.CLOSED);
					return;
				}
			}

			// Create new connection
			McpConfigEntity config = configCache.get(serverName);
			if (config == null) {
				logger.error("MCP server configuration not found for rebuild: {}", serverName);
				wrapper.setState(ConnectionState.RECONNECTING, ConnectionState.CLOSED);
				return;
			}

			try {
				McpServiceEntity newEntity = connectionFactory.createConnection(config);

				if (newEntity != null) {
					wrapper.setServiceEntity(newEntity);
					wrapper.setState(ConnectionState.RECONNECTING, ConnectionState.CONNECTED);
					logger.info("Successfully rebuilt connection for server: {}", serverName);
					// Start health check for this connection
					scheduleHealthCheck(serverName);
				}
				else {
					logger.error("Failed to create new connection for server: {}", serverName);
					wrapper.setState(ConnectionState.RECONNECTING, ConnectionState.CLOSED);
				}
			}
			catch (Exception e) {
				logger.error("Failed to rebuild connection for server: {}", serverName, e);
				wrapper.setState(ConnectionState.RECONNECTING, ConnectionState.CLOSED);
			}
		}
		finally {
			lock.unlock();
		}
	}

	/**
	 * Check if exception is connection-related and should trigger rebuild
	 * @param e Exception to check
	 * @return true if connection-related
	 */
	private boolean isConnectionError(Exception e) {
		if (e == null) {
			return false;
		}

		// Check for timeout exceptions
		if (e instanceof TimeoutException || e instanceof java.util.concurrent.TimeoutException) {
			return true;
		}

		// Check for ReadTimeoutException (SSE specific)
		String className = e.getClass().getName();
		if (className.contains("ReadTimeoutException") || className.contains("ReadTimeout")) {
			return true;
		}

		// Check for WebClientResponseException with ReadTimeoutException cause
		if (className.contains("WebClientResponseException") || className.contains("WebClientException")) {
			Throwable cause = e.getCause();
			if (cause != null) {
				String causeClassName = cause.getClass().getName();
				if (causeClassName.contains("ReadTimeoutException") || causeClassName.contains("ReadTimeout")) {
					return true;
				}
			}
		}

		// Check for IOException (connection closed, network errors)
		if (e instanceof IOException) {
			String message = e.getMessage();
			if (message != null) {
				String lowerMessage = message.toLowerCase();
				return lowerMessage.contains("connection") || lowerMessage.contains("closed")
						|| lowerMessage.contains("reset") || lowerMessage.contains("broken")
						|| lowerMessage.contains("read timeout");
			}
			return true;
		}

		// Check exception class name
		if (className.contains("Timeout") || className.contains("Connection") || className.contains("Closed")
				|| className.contains("ReadTimeout")) {
			return true;
		}

		// Check message
		String message = e.getMessage();
		if (message != null) {
			String lowerMessage = message.toLowerCase();
			return lowerMessage.contains("timeout") || lowerMessage.contains("timed out")
					|| lowerMessage.contains("connection") || lowerMessage.contains("closed")
					|| lowerMessage.contains("read timeout");
		}

		return false;
	}

	/**
	 * Handle connection error by marking connection as closed and triggering background
	 * rebuild (fail-fast) This method should be called when a connection error is
	 * detected during request execution
	 * @param serverName Server name
	 */
	public void handleConnectionError(String serverName) {
		ConnectionWrapper wrapper = connections.get(serverName);
		if (wrapper != null) {
			ConnectionState currentState = wrapper.getState();
			// Only mark as closed if currently connected (avoid race conditions)
			if (currentState == ConnectionState.CONNECTED) {
				logger.warn(
						"Connection error detected for server: {}, marking as closed and triggering background rebuild",
						serverName);
				// Fail-fast: mark as closed and trigger background rebuild, don't wait
				wrapper.setState(ConnectionState.CONNECTED, ConnectionState.CLOSED);
				triggerBackgroundRebuild(serverName);
			}
		}
		else {
			// Connection doesn't exist, trigger background creation (fail-fast)
			triggerBackgroundCreation(serverName);
		}
	}

	/**
	 * Schedule periodic health check for a connection
	 * @param serverName Server name
	 */
	private void scheduleHealthCheck(String serverName) {
		// Cancel existing health check task if any
		ScheduledFuture<?> existingTask = healthCheckTasks.get(serverName);
		if (existingTask != null && !existingTask.isCancelled()) {
			existingTask.cancel(false);
		}

		// Schedule new health check task
		ScheduledFuture<?> task = healthCheckExecutor.scheduleWithFixedDelay(() -> {
			try {
				performHealthCheck(serverName);
			}
			catch (Exception e) {
				logger.error("Error during health check for server: {}", serverName, e);
			}
		}, HEALTH_CHECK_INTERVAL_SECONDS, HEALTH_CHECK_INTERVAL_SECONDS, TimeUnit.SECONDS);

		healthCheckTasks.put(serverName, task);
		logger.debug("Scheduled health check for server: {} (interval: {}s)", serverName,
				HEALTH_CHECK_INTERVAL_SECONDS);
	}

	/**
	 * Perform health check on a connection and rebuild if needed
	 * @param serverName Server name
	 */
	private void performHealthCheck(String serverName) {
		ConnectionWrapper wrapper = connections.get(serverName);
		if (wrapper == null) {
			// Connection doesn't exist, cancel health check
			cancelHealthCheck(serverName);
			return;
		}

		ConnectionState state = wrapper.getState();
		if (state == ConnectionState.CONNECTED) {
			// Connection is connected, check if it's actually healthy
			McpServiceEntity entity = wrapper.getServiceEntity();
			if (entity == null || entity.getMcpAsyncClient() == null) {
				logger.warn("Health check: Connection for server {} has null entity, marking as closed", serverName);
				wrapper.setState(ConnectionState.CONNECTED, ConnectionState.CLOSED);
				triggerBackgroundRebuild(serverName);
				return;
			}

			// Check pending requests threshold
			int pendingRequests = wrapper.getPendingRequests().get();
			if (pendingRequests > MAX_PENDING_REQUESTS_THRESHOLD) {
				logger.warn("Health check: Too many pending requests ({}) for server {}, marking as closed",
						pendingRequests, serverName);
				wrapper.setState(ConnectionState.CONNECTED, ConnectionState.CLOSED);
				triggerBackgroundRebuild(serverName);
				return;
			}

			// Connection appears healthy
			logger.debug("Health check: Connection for server {} is healthy (pending requests: {})", serverName,
					pendingRequests);
		}
		else if (state == ConnectionState.CLOSED || state == ConnectionState.CLOSING) {
			// Connection is closed, trigger rebuild
			logger.info("Health check: Connection for server {} is closed, triggering rebuild", serverName);
			triggerBackgroundRebuild(serverName);
		}
		// RECONNECTING state: do nothing, wait for rebuild to complete
	}

	/**
	 * Cancel health check for a server
	 * @param serverName Server name
	 */
	private void cancelHealthCheck(String serverName) {
		ScheduledFuture<?> task = healthCheckTasks.remove(serverName);
		if (task != null && !task.isCancelled()) {
			task.cancel(false);
			logger.debug("Cancelled health check for server: {}", serverName);
		}
	}

	/**
	 * Execute a function with automatic retry on connection errors This is a helper
	 * method that can be used to wrap tool execution with retry logic
	 * @param serverName Server name
	 * @param function Function to execute
	 * @return Result of function execution
	 * @param <T> Return type
	 * @throws Exception If execution fails after all retries
	 */
	public <T> T executeWithRetry(String serverName, java.util.function.Function<McpServiceEntity, T> function)
			throws Exception {
		int retryCount = 0;
		int maxRetries = mcpProperties.getRequestRetryCount();
		Exception lastException = null;

		while (retryCount <= maxRetries) {
			ConnectionWrapper wrapper = getConnectionWithRetry(serverName);
			if (wrapper == null || wrapper.getState() != ConnectionState.CONNECTED) {
				throw new IOException("Failed to get valid connection for server: " + serverName);
			}

			McpServiceEntity entity = wrapper.getServiceEntity();
			if (entity == null) {
				throw new IOException("Service entity is null for server: " + serverName);
			}

			try {
				wrapper.getPendingRequests().incrementAndGet();
				T result = function.apply(entity);
				wrapper.getPendingRequests().decrementAndGet();
				return result;
			}
			catch (Exception e) {
				wrapper.getPendingRequests().decrementAndGet();
				lastException = e;

				if (isConnectionError(e)) {
					logger.warn("Connection error during execution for server: {} (attempt {}/{}): {}", serverName,
							retryCount + 1, maxRetries + 1, e.getMessage());
					// Fail-fast: mark connection as closed and trigger background rebuild
					handleConnectionError(serverName);

					if (retryCount < maxRetries) {
						// Fail-fast: don't wait, let background task handle rebuild
						// Retry immediately (background task will rebuild connection)
						retryCount++;
						continue;
					}
				}

				// Not a connection error or retries exhausted
				throw e;
			}
		}

		// All retries exhausted
		throw new IOException("Failed to execute after " + (maxRetries + 1) + " attempts for server: " + serverName,
				lastException);
	}

	/**
	 * Get MCP services (maintains interface compatibility)
	 * @param planId Plan ID (not used, maintained for compatibility)
	 * @return MCP service entity mapping
	 */
	public Map<String, McpServiceEntity> getOrLoadServices(String planId) {
		Map<String, McpServiceEntity> result = new ConcurrentHashMap<>();
		for (String serverName : configCache.keySet()) {
			ConnectionWrapper wrapper = getConnectionWithRetry(serverName);
			if (wrapper != null && wrapper.getState() == ConnectionState.CONNECTED) {
				McpServiceEntity entity = wrapper.getServiceEntity();
				if (entity != null) {
					result.put(serverName, entity);
				}
			}
		}
		return result;
	}

	/**
	 * Get MCP service entity list (maintains interface compatibility)
	 * @param planId Plan ID
	 * @return MCP service entity list
	 */
	public List<McpServiceEntity> getServiceEntities(String planId) {
		return new ArrayList<>(getOrLoadServices(planId).values());
	}

	/**
	 * Invalidate cache for a plan (triggers connection rebuild)
	 * @param planId Plan ID (not used, maintained for compatibility)
	 */
	public void invalidateCache(String planId) {
		logger.info("Cache invalidation requested, triggering connection rebuild for all servers");
		for (String serverName : connections.keySet()) {
			ConnectionWrapper wrapper = connections.get(serverName);
			if (wrapper != null) {
				wrapper.setState(ConnectionState.CONNECTED, ConnectionState.CLOSED);
				triggerBackgroundRebuild(serverName);
			}
		}
	}

	/**
	 * Invalidate all cache (triggers connection rebuild for all servers)
	 */
	public void invalidateAllCache() {
		logger.info("All cache invalidation requested, triggering connection rebuild for all servers");
		// Reload configurations
		try {
			List<McpConfigEntity> configs = mcpConfigRepository.findByStatus(McpConfigStatus.ENABLE);
			configCache.clear();
			for (McpConfigEntity config : configs) {
				configCache.put(config.getMcpServerName(), config);
			}
		}
		catch (Exception e) {
			logger.error("Failed to reload configurations", e);
		}

		// Rebuild all connections
		for (String serverName : connections.keySet()) {
			ConnectionWrapper wrapper = connections.get(serverName);
			if (wrapper != null) {
				wrapper.setState(ConnectionState.CONNECTED, ConnectionState.CLOSED);
				triggerBackgroundRebuild(serverName);
			}
		}
	}

	/**
	 * Trigger cache reload (rebuilds all connections)
	 */
	public void triggerCacheReload() {
		logger.info("Triggering cache reload");
		invalidateAllCache();
	}

	/**
	 * Safely close a single MCP client
	 * @param serviceEntity Service entity containing the client
	 * @param serverName Server name for logging
	 * @return true if client was closed successfully, false otherwise
	 */
	private boolean closeClientSafely(McpServiceEntity serviceEntity, String serverName) {
		if (serviceEntity == null) {
			return false;
		}

		McpAsyncClient client = serviceEntity.getMcpAsyncClient();
		if (client == null) {
			return false;
		}

		try {
			logger.debug("Closing MCP client for server: {}", serverName);
			try {
				client.closeGracefully()
					.timeout(java.time.Duration.ofSeconds(5))
					.doOnSuccess(v -> logger.debug("MCP client closed gracefully for server: {}", serverName))
					.doOnError(e -> logger.warn("Error during graceful close for server: {}, will force close",
							serverName, e))
					.block();
				Thread.sleep(200); // In background thread, safe to sleep
				logger.debug("Successfully closed MCP client for server: {}", serverName);
				return true;
			}
			catch (Exception gracefulEx) {
				logger.warn("Graceful shutdown failed for server: {}, forcing close", serverName, gracefulEx);
				client.close();
				Thread.sleep(100); // In background thread, safe to sleep
				return true;
			}
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			logger.warn("Interrupted during client shutdown for server: {}, forcing close", serverName);
			try {
				client.close();
			}
			catch (Exception ex) {
				logger.error("Error during force close after interruption for server: {}", serverName, ex);
			}
			return false;
		}
		catch (Exception e) {
			logger.warn("Error closing MCP client for server: {}", serverName, e);
			try {
				client.close();
			}
			catch (Exception ex) {
				logger.error("Error during final force close for server: {}", serverName, ex);
			}
			return false;
		}
	}

	/**
	 * Check connection health and rebuild if necessary
	 * @param serverName Server name
	 * @return true if connection is healthy
	 */
	public boolean checkConnectionHealth(String serverName) {
		ConnectionWrapper wrapper = connections.get(serverName);
		if (wrapper == null) {
			return false;
		}

		ConnectionState state = wrapper.getState();
		if (state != ConnectionState.CONNECTED) {
			return false;
		}

		McpServiceEntity entity = wrapper.getServiceEntity();
		if (entity == null || entity.getMcpAsyncClient() == null) {
			return false;
		}

		// Check pending requests count (may indicate connection is stuck)
		int pendingRequests = wrapper.getPendingRequests().get();
		if (pendingRequests > MAX_PENDING_REQUESTS_THRESHOLD) {
			logger.warn("Too many pending requests ({}) for server: {}, connection may be stuck", pendingRequests,
					serverName);
			return false;
		}

		return true;
	}

	/**
	 * Connection statistics for monitoring
	 */
	public static class ConnectionStats {

		private final String state;

		private final int pendingRequests;

		private final boolean hasEntity;

		public ConnectionStats(String state, int pendingRequests, boolean hasEntity) {
			this.state = state;
			this.pendingRequests = pendingRequests;
			this.hasEntity = hasEntity;
		}

		public String getState() {
			return state;
		}

		public int getPendingRequests() {
			return pendingRequests;
		}

		public boolean isHasEntity() {
			return hasEntity;
		}

	}

	/**
	 * Get connection statistics for monitoring
	 * @return Map of server name to connection stats
	 */
	public Map<String, ConnectionStats> getConnectionStats() {
		Map<String, ConnectionStats> stats = new ConcurrentHashMap<>();
		for (Map.Entry<String, ConnectionWrapper> entry : connections.entrySet()) {
			ConnectionWrapper wrapper = entry.getValue();
			ConnectionStats stat = new ConnectionStats(wrapper.getState().name(), wrapper.getPendingRequests().get(),
					wrapper.getServiceEntity() != null);
			stats.put(entry.getKey(), stat);
		}
		return stats;
	}

	/**
	 * Shutdown and close all connections
	 */
	@PreDestroy
	public void shutdown() {
		logger.info("Shutting down MCP cache manager");
		int closedCount = 0;
		for (Map.Entry<String, ConnectionWrapper> entry : connections.entrySet()) {
			ConnectionWrapper wrapper = entry.getValue();
			if (wrapper != null && wrapper.getServiceEntity() != null) {
				if (closeClientSafely(wrapper.getServiceEntity(), entry.getKey())) {
					closedCount++;
				}
			}
		}
		connections.clear();
		configCache.clear();

		// Cancel all health check tasks
		for (String serverName : new ArrayList<>(healthCheckTasks.keySet())) {
			cancelHealthCheck(serverName);
		}

		// Shutdown executors
		healthCheckExecutor.shutdown();
		rebuildExecutor.shutdown();
		connectionExecutor.shutdown();
		try {
			if (!healthCheckExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
				healthCheckExecutor.shutdownNow();
			}
			if (!rebuildExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
				rebuildExecutor.shutdownNow();
			}
			if (!connectionExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
				connectionExecutor.shutdownNow();
			}
		}
		catch (InterruptedException e) {
			healthCheckExecutor.shutdownNow();
			rebuildExecutor.shutdownNow();
			connectionExecutor.shutdownNow();
			Thread.currentThread().interrupt();
		}

		logger.info("MCP cache manager shutdown completed. Closed {} MCP clients.", closedCount);
	}

}
