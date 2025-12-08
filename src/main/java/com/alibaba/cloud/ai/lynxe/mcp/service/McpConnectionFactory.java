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
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.AsyncMcpToolCallbackProvider;
import org.springframework.stereotype.Component;

import com.alibaba.cloud.ai.lynxe.mcp.config.McpProperties;
import com.alibaba.cloud.ai.lynxe.mcp.model.po.McpConfigEntity;
import com.alibaba.cloud.ai.lynxe.mcp.model.vo.McpServerConfig;
import com.alibaba.cloud.ai.lynxe.mcp.model.vo.McpServiceEntity;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * MCP connection factory
 */
@Component
public class McpConnectionFactory {

	private static final Logger logger = LoggerFactory.getLogger(McpConnectionFactory.class);

	private final McpTransportBuilder transportBuilder;

	private final McpConfigValidator configValidator;

	private final McpProperties mcpProperties;

	private final ObjectMapper objectMapper;

	public McpConnectionFactory(McpTransportBuilder transportBuilder, McpConfigValidator configValidator,
			McpProperties mcpProperties, ObjectMapper objectMapper) {
		this.transportBuilder = transportBuilder;
		this.configValidator = configValidator;
		this.mcpProperties = mcpProperties;
		this.objectMapper = objectMapper;
	}

	/**
	 * Create MCP connection
	 * @param mcpConfigEntity MCP configuration entity
	 * @return MCP service entity
	 * @throws IOException Thrown when creation fails
	 */
	public McpServiceEntity createConnection(McpConfigEntity mcpConfigEntity) throws IOException {
		String serverName = mcpConfigEntity.getMcpServerName();

		// Validate configuration entity
		configValidator.validateMcpConfigEntity(mcpConfigEntity);

		// Check if enabled
		if (!configValidator.isEnabled(mcpConfigEntity)) {
			logger.info("Skipping disabled MCP server: {}", serverName);
			return null;
		}

		// Parse server configuration
		McpServerConfig serverConfig = parseServerConfig(mcpConfigEntity.getConnectionConfig(), serverName);

		// Configure MCP transport with retry mechanism
		return configureMcpTransportWithRetry(serverName, mcpConfigEntity.getConnectionType(), serverConfig);
	}

	/**
	 * Parse server configuration
	 * @param connectionConfig Connection configuration JSON
	 * @param serverName Server name
	 * @return Server configuration object
	 * @throws IOException Thrown when parsing fails
	 */
	private McpServerConfig parseServerConfig(String connectionConfig, String serverName) throws IOException {
		try (JsonParser jsonParser = objectMapper.createParser(connectionConfig)) {
			return jsonParser.readValueAs(McpServerConfig.class);
		}
		catch (Exception e) {
			logger.error("Failed to parse server config for server: {}", serverName, e);
			throw new IOException("Failed to parse server config for server: " + serverName, e);
		}
	}

	/**
	 * Configure MCP transport with retry mechanism that creates fresh transport for each
	 * attempt
	 * @param mcpServerName MCP server name
	 * @param connectionType Connection type
	 * @param serverConfig Server configuration
	 * @return MCP service entity
	 * @throws IOException Thrown when configuration fails
	 */
	private McpServiceEntity configureMcpTransportWithRetry(String mcpServerName,
			com.alibaba.cloud.ai.lynxe.mcp.model.po.McpConfigType connectionType, McpServerConfig serverConfig)
			throws IOException {

		int maxRetries = mcpProperties.getMaxRetries();
		Exception lastException = null;

		for (int attempt = 1; attempt <= maxRetries; attempt++) {
			McpAsyncClient mcpAsyncClient = null;
			McpClientTransport transport = null;
			try {
				// Create fresh transport for each attempt to avoid unicast sink reuse
				transport = transportBuilder.buildTransport(connectionType, serverConfig, mcpServerName);
				if (transport == null) {
					throw new IOException("Failed to build transport for server: " + mcpServerName);
				}

				// Create new client with fresh transport
				// Use separate initialization timeout which may be longer than request
				// timeout
				mcpAsyncClient = McpClient.async(transport)
					.requestTimeout(mcpProperties.getTimeout())
					.initializationTimeout(mcpProperties.getInitializationTimeout())
					.clientInfo(new McpSchema.Implementation(mcpServerName, "1.0.0"))
					.build();

				logger.info("Attempting to initialize MCP transport for: {} (attempt {}/{}) with timeout: {}s",
						mcpServerName, attempt, maxRetries, mcpProperties.getInitializationTimeout().getSeconds());

				long initStartTime = System.currentTimeMillis();
				mcpAsyncClient.initialize().timeout(mcpProperties.getInitializationTimeout()).doOnSuccess(result -> {
					long initDuration = System.currentTimeMillis() - initStartTime;
					logger.info("MCP client initialized successfully for {} in {}ms", mcpServerName, initDuration);
				}).doOnError(error -> {
					long initDuration = System.currentTimeMillis() - initStartTime;
					logger.error("Failed to initialize MCP client for {} after {}ms: {}", mcpServerName, initDuration,
							error.getMessage(), error);
				}).block();

				logger.info("MCP transport configured successfully for: {} (attempt {})", mcpServerName, attempt);

				AsyncMcpToolCallbackProvider callbackProvider = new AsyncMcpToolCallbackProvider(mcpAsyncClient);
				return new McpServiceEntity(mcpAsyncClient, callbackProvider, mcpServerName);
			}
			catch (Exception e) {
				lastException = e;

				// Enhanced error diagnosis and logging
				String errorDiagnosis = diagnoseInitializationError(e, mcpServerName);
				logger.error(
						"Failed to initialize MCP transport for {} on attempt {}/{}. Error type: {}, Diagnosis: {}, Message: {}",
						mcpServerName, attempt, maxRetries, e.getClass().getSimpleName(), errorDiagnosis,
						e.getMessage(), e);

				// Check if this is a DNS-related error that shouldn't be retried
				if (isDnsRelatedError(e)) {
					logger.error("DNS resolution failed for MCP server '{}'. Skipping retries. Error: {}",
							mcpServerName, e.getMessage());
					cleanupClient(mcpAsyncClient, mcpServerName);
					cleanupTransport(transport, mcpServerName);
					throw new IOException(
							"DNS resolution failed for MCP server '" + mcpServerName + "': " + e.getMessage(), e);
				}

				// Check if this is a timeout error
				if (isTimeoutError(e)) {
					logger.warn(
							"Initialization timeout for MCP server '{}' after {}s. This may indicate the server is slow to start or unresponsive.",
							mcpServerName, mcpProperties.getInitializationTimeout().getSeconds());
				}

				// Check if this is a process-related error (for STDIO transport)
				if (isProcessRelatedError(e)) {
					logger.error(
							"Process-related error for MCP server '{}'. This may indicate the command failed to start or the process exited immediately. Check server stderr logs above.",
							mcpServerName);
				}

				// Clean up the failed client and transport
				cleanupClient(mcpAsyncClient, mcpServerName);
				cleanupTransport(transport, mcpServerName);

				if (attempt < maxRetries) {
					try {
						// Incremental wait time
						Thread.sleep(1000L * mcpProperties.getRetryWaitMultiplier() * attempt);
					}
					catch (InterruptedException ie) {
						Thread.currentThread().interrupt();
						logger.warn("Retry wait interrupted for server: {}", mcpServerName);
						break;
					}
				}
			}
		}

		// Final error logging with comprehensive diagnosis
		String finalDiagnosis = diagnoseInitializationError(lastException, mcpServerName);
		logger.error(
				"Failed to initialize MCP transport for {} after {} attempts. Final error type: {}, Diagnosis: {}, Message: {}",
				mcpServerName, maxRetries, lastException != null ? lastException.getClass().getSimpleName() : "null",
				finalDiagnosis, lastException != null ? lastException.getMessage() : "null", lastException);
		return null;
	}

	/**
	 * Diagnose initialization error and provide detailed information
	 * @param e Exception to diagnose
	 * @param serverName Server name for context
	 * @return Diagnosis string
	 */
	private String diagnoseInitializationError(Exception e, String serverName) {
		if (e == null) {
			return "Unknown error (exception is null)";
		}

		Throwable rootCause = getRootCause(e);
		String rootCauseType = rootCause.getClass().getSimpleName();
		String rootCauseMessage = rootCause.getMessage();

		if (isTimeoutError(rootCause)) {
			return String.format(
					"Initialization timeout after %ds. Server may be slow to start, unresponsive, or the timeout is too short. Consider increasing mcp.initialization-timeout.",
					mcpProperties.getInitializationTimeout().getSeconds());
		}

		if (isDnsRelatedError(rootCause)) {
			return "DNS resolution failed. Check network connectivity and DNS configuration.";
		}

		if (isProcessRelatedError(rootCause)) {
			return String.format(
					"Process-related error. The MCP server process may have failed to start or exited immediately. Check: 1) Command exists and is executable, 2) Server stderr logs above for error details, 3) Process permissions.");
		}

		if (rootCause instanceof IOException) {
			return String.format(
					"IO error: %s. Check process startup, stdin/stdout communication, or file permissions.",
					rootCauseMessage);
		}

		// Check for MCP protocol errors
		if (rootCause.getClass().getName().contains("McpError")) {
			return String.format("MCP protocol error: %s. Check server protocol version compatibility.",
					rootCauseMessage);
		}

		// Check for JSON parsing errors
		if (rootCause.getClass().getName().contains("Json")
				|| rootCauseMessage != null && rootCauseMessage.contains("JSON")) {
			return String.format("JSON parsing error: %s. Server response may be malformed.", rootCauseMessage);
		}

		return String.format("Error type: %s, Message: %s. Check full stack trace for details.", rootCauseType,
				rootCauseMessage);
	}

	/**
	 * Get root cause of exception
	 * @param e Exception
	 * @return Root cause
	 */
	private Throwable getRootCause(Throwable e) {
		Throwable cause = e;
		while (cause.getCause() != null && cause.getCause() != cause) {
			cause = cause.getCause();
		}
		return cause;
	}

	/**
	 * Check if the exception is DNS-related and shouldn't be retried
	 * @param e Exception to check
	 * @return true if DNS-related, false otherwise
	 */
	private boolean isDnsRelatedError(Throwable e) {
		if (e == null)
			return false;

		String message = e.getMessage();
		if (message == null)
			return false;

		return message.contains("Failed to resolve") || message.contains("DnsNameResolverTimeoutException")
				|| message.contains("SearchDomainUnknownHostException") || message.contains("UnknownHostException")
				|| message.toLowerCase().contains("dns");
	}

	/**
	 * Check if the exception is timeout-related
	 * @param e Exception to check
	 * @return true if timeout-related, false otherwise
	 */
	private boolean isTimeoutError(Throwable e) {
		if (e == null)
			return false;

		// Check exception type
		if (e instanceof TimeoutException || e instanceof java.util.concurrent.TimeoutException) {
			return true;
		}

		// Check exception class name
		String className = e.getClass().getName();
		if (className.contains("Timeout")) {
			return true;
		}

		// Check message
		String message = e.getMessage();
		if (message != null) {
			String lowerMessage = message.toLowerCase();
			return lowerMessage.contains("timeout") || lowerMessage.contains("timed out");
		}

		return false;
	}

	/**
	 * Check if the exception is process-related (for STDIO transport)
	 * @param e Exception to check
	 * @return true if process-related, false otherwise
	 */
	private boolean isProcessRelatedError(Throwable e) {
		if (e == null)
			return false;

		// Check exception type
		if (e instanceof IOException) {
			String message = e.getMessage();
			if (message != null) {
				String lowerMessage = message.toLowerCase();
				return lowerMessage.contains("cannot run program") || lowerMessage.contains("process")
						|| lowerMessage.contains("command") || lowerMessage.contains("exec")
						|| lowerMessage.contains("spawn");
			}
		}

		// Check exception class name
		String className = e.getClass().getName();
		return className.contains("Process") || className.contains("Exec");
	}

	/**
	 * Clean up MCP client resources. Uses graceful shutdown first, then falls back to
	 * force close. This is especially important for STDIO transport to allow child
	 * processes to terminate cleanly.
	 * @param mcpAsyncClient Client to clean up
	 * @param mcpServerName Server name for logging
	 */
	private void cleanupClient(McpAsyncClient mcpAsyncClient, String mcpServerName) {
		if (mcpAsyncClient != null) {
			try {
				logger.debug("Cleaning up MCP client for server: {}", mcpServerName);
				// Try graceful shutdown first (important for STDIO processes)
				try {
					mcpAsyncClient.closeGracefully().timeout(java.time.Duration.ofSeconds(3)).block();
					// Wait a bit for process cleanup (especially for STDIO)
					Thread.sleep(100);
				}
				catch (Exception gracefulEx) {
					logger.debug("Graceful shutdown failed for server: {}, forcing close: {}", mcpServerName,
							gracefulEx.getMessage());
					mcpAsyncClient.close();
				}
			}
			catch (Exception closeEx) {
				logger.debug("Failed to close MCP client during cleanup for server: {}: {}", mcpServerName,
						closeEx.getMessage());
			}
		}
	}

	/**
	 * Clean up MCP transport resources
	 * @param transport Transport to clean up
	 * @param mcpServerName Server name for logging
	 */
	private void cleanupTransport(McpClientTransport transport, String mcpServerName) {
		if (transport != null) {
			try {
				logger.debug("Cleaning up MCP transport for server: {}", mcpServerName);
				transport.close();
			}
			catch (Exception closeEx) {
				logger.debug("Failed to close MCP transport during cleanup for server: {}: {}", mcpServerName,
						closeEx.getMessage());
			}
		}
	}

}
