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
package com.alibaba.cloud.ai.lynxe.mcp.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * MCP service configuration properties
 */
@Component
@ConfigurationProperties("mcp")
public class McpProperties {

	/**
	 * Maximum retry count
	 */
	private int maxRetries = 3;

	/**
	 * Connection timeout duration
	 */
	private Duration timeout = Duration.ofSeconds(60);

	/**
	 * Initialization timeout duration (separate from request timeout) Used for MCP client
	 * initialization, which may take longer than regular requests
	 */
	private Duration initializationTimeout = Duration.ofSeconds(120);

	/**
	 * Cache expiration time after access
	 */
	private Duration cacheExpireAfterAccess = Duration.ofMinutes(10);

	/**
	 * Retry wait time multiplier (seconds)
	 */
	private int retryWaitMultiplier = 1;

	/**
	 * SSE URL path suffix
	 */
	private String ssePathSuffix = "/sse";

	/**
	 * User agent
	 */
	private String userAgent = "MCP-Client/1.0.0";

	/**
	 * SSE read timeout in seconds. Set to 0 to disable read timeout (recommended for SSE
	 * long connections). Default: 0 (disabled)
	 */
	private long sseReadTimeoutSeconds = 0;

	/**
	 * SSE write timeout in seconds. Default: 30 seconds
	 */
	private long sseWriteTimeoutSeconds = 30;

	/**
	 * SSE connection timeout in milliseconds. Default: 30000 (30 seconds)
	 */
	private int sseConnectTimeoutMillis = 30000;

	/**
	 * Request retry count for automatic retry on connection failure. Default: 3
	 */
	private int requestRetryCount = 3;

	/**
	 * Connection rebuild delay in milliseconds before rebuilding connection after
	 * timeout. Default: 100ms
	 */
	private long connectionRebuildDelayMillis = 100;

	// Getters and Setters
	public int getMaxRetries() {
		return maxRetries;
	}

	public void setMaxRetries(int maxRetries) {
		this.maxRetries = maxRetries;
	}

	public Duration getTimeout() {
		return timeout;
	}

	public void setTimeout(Duration timeout) {
		this.timeout = timeout;
	}

	public Duration getInitializationTimeout() {
		return initializationTimeout;
	}

	public void setInitializationTimeout(Duration initializationTimeout) {
		this.initializationTimeout = initializationTimeout;
	}

	public Duration getCacheExpireAfterAccess() {
		return cacheExpireAfterAccess;
	}

	public void setCacheExpireAfterAccess(Duration cacheExpireAfterAccess) {
		this.cacheExpireAfterAccess = cacheExpireAfterAccess;
	}

	public int getRetryWaitMultiplier() {
		return retryWaitMultiplier;
	}

	public void setRetryWaitMultiplier(int retryWaitMultiplier) {
		this.retryWaitMultiplier = retryWaitMultiplier;
	}

	public String getSsePathSuffix() {
		return ssePathSuffix;
	}

	public void setSsePathSuffix(String ssePathSuffix) {
		this.ssePathSuffix = ssePathSuffix;
	}

	public String getUserAgent() {
		return userAgent;
	}

	public void setUserAgent(String userAgent) {
		this.userAgent = userAgent;
	}

	public long getSseReadTimeoutSeconds() {
		return sseReadTimeoutSeconds;
	}

	public void setSseReadTimeoutSeconds(long sseReadTimeoutSeconds) {
		this.sseReadTimeoutSeconds = sseReadTimeoutSeconds;
	}

	public long getSseWriteTimeoutSeconds() {
		return sseWriteTimeoutSeconds;
	}

	public void setSseWriteTimeoutSeconds(long sseWriteTimeoutSeconds) {
		this.sseWriteTimeoutSeconds = sseWriteTimeoutSeconds;
	}

	public int getSseConnectTimeoutMillis() {
		return sseConnectTimeoutMillis;
	}

	public void setSseConnectTimeoutMillis(int sseConnectTimeoutMillis) {
		this.sseConnectTimeoutMillis = sseConnectTimeoutMillis;
	}

	public int getRequestRetryCount() {
		return requestRetryCount;
	}

	public void setRequestRetryCount(int requestRetryCount) {
		this.requestRetryCount = requestRetryCount;
	}

	public long getConnectionRebuildDelayMillis() {
		return connectionRebuildDelayMillis;
	}

	public void setConnectionRebuildDelayMillis(long connectionRebuildDelayMillis) {
		this.connectionRebuildDelayMillis = connectionRebuildDelayMillis;
	}

}
