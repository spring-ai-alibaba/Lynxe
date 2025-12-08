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
import java.net.URI;
import java.net.URL;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import com.alibaba.cloud.ai.lynxe.mcp.config.McpProperties;
import com.alibaba.cloud.ai.lynxe.mcp.model.po.McpConfigType;
import com.alibaba.cloud.ai.lynxe.mcp.model.vo.McpServerConfig;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.client.transport.WebClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.WebFluxSseClientTransport;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import io.netty.resolver.DefaultAddressResolverGroup;
import jakarta.annotation.PreDestroy;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

/**
 * MCP transport builder with shared HttpClient instance for better resource management
 */
@Component
public class McpTransportBuilder {

	private static final Logger logger = LoggerFactory.getLogger(McpTransportBuilder.class);

	private final McpConfigValidator configValidator;

	private final McpProperties mcpProperties;

	private final ObjectMapper objectMapper;

	/**
	 * Shared connection provider for MCP transports
	 */
	private final ConnectionProvider connectionProvider;

	/**
	 * Shared HttpClient instance for all MCP transports to reduce resource consumption
	 * and prevent HttpClientImpl$SelectorManager thread accumulation
	 */
	private final HttpClient sharedHttpClient;

	/**
	 * Shared ReactorClientHttpConnector using the shared HttpClient
	 */
	private final ReactorClientHttpConnector sharedConnector;

	public McpTransportBuilder(McpConfigValidator configValidator, McpProperties mcpProperties,
			ObjectMapper objectMapper) {
		this.configValidator = configValidator;
		this.mcpProperties = mcpProperties;
		this.objectMapper = objectMapper;

		// Create shared connection provider for MCP transports
		this.connectionProvider = ConnectionProvider.builder("mcp-shared-pool")
			.maxConnections(200) // Increased for multiple MCP servers
			.maxIdleTime(Duration.ofMinutes(5))
			.maxLifeTime(Duration.ofMinutes(10))
			.pendingAcquireTimeout(Duration.ofSeconds(30))
			.evictInBackground(Duration.ofSeconds(120))
			.build();

		// Create shared HttpClient instance with optimized configuration
		this.sharedHttpClient = HttpClient.create(connectionProvider)
			.resolver(DefaultAddressResolverGroup.INSTANCE)
			.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 30000) // 30 seconds
			.doOnConnected(conn -> conn.addHandlerLast(new ReadTimeoutHandler(30, TimeUnit.SECONDS))
				.addHandlerLast(new WriteTimeoutHandler(30, TimeUnit.SECONDS)))
			.option(ChannelOption.SO_KEEPALIVE, true)
			.option(ChannelOption.TCP_NODELAY, true);

		// Create shared connector
		this.sharedConnector = new ReactorClientHttpConnector(sharedHttpClient);

		logger.info("Initialized shared HttpClient for MCP transports with connection pool size: 200");
	}

	/**
	 * Build MCP transport
	 * @param configType Configuration type
	 * @param serverConfig Server configuration
	 * @param serverName Server name
	 * @return MCP client transport
	 * @throws IOException Thrown when build fails
	 */
	public McpClientTransport buildTransport(McpConfigType configType, McpServerConfig serverConfig, String serverName)
			throws IOException {
		// Validate server configuration
		configValidator.validateServerConfig(serverConfig, serverName);

		switch (configType) {
			case SSE -> {
				return buildSseTransport(serverConfig, serverName);
			}
			case STUDIO -> {
				return buildStudioTransport(serverConfig, serverName);
			}
			case STREAMING -> {
				return buildStreamingTransport(serverConfig, serverName);
			}
			default -> {
				throw new IOException("Unsupported connection type: " + configType + " for server: " + serverName);
			}
		}
	}

	/**
	 * Build SSE transport
	 * @param serverConfig Server configuration
	 * @param serverName Server name
	 * @return SSE transport
	 * @throws IOException Thrown when build fails
	 */
	private McpClientTransport buildSseTransport(McpServerConfig serverConfig, String serverName) throws IOException {
		String url = serverConfig.getUrl().trim();
		configValidator.validateSseUrl(url, serverName);

		URL parsedUrl = URI.create(url).toURL();
		String baseUrl = parsedUrl.getProtocol() + "://" + parsedUrl.getHost()
				+ (parsedUrl.getPort() == -1 ? "" : ":" + parsedUrl.getPort());

		String file = parsedUrl.getFile();
		String sseEndpoint = file;

		// Remove leading slash
		if (sseEndpoint.startsWith("/")) {
			sseEndpoint = sseEndpoint.substring(1);
		}

		// Set to null if empty
		if (sseEndpoint.isEmpty()) {
			sseEndpoint = null;
		}

		logger.info("Building SSE transport for server: {} with baseUrl: {}, endpoint: {}", serverName, baseUrl,
				sseEndpoint);

		WebClient.Builder webClientBuilder = createWebClientBuilder(baseUrl, serverConfig);

		JacksonMcpJsonMapper jsonMapper = new JacksonMcpJsonMapper(objectMapper);

		if (sseEndpoint != null && !sseEndpoint.isEmpty()) {
			return new WebFluxSseClientTransport(webClientBuilder, jsonMapper, sseEndpoint);
		}
		else {
			return new WebFluxSseClientTransport(webClientBuilder, jsonMapper);
		}
	}

	/**
	 * Build STUDIO transport (STDIO). Sets up error handler for server stderr output to
	 * improve debugging and monitoring.
	 * @param serverConfig Server configuration
	 * @param serverName Server name
	 * @return STUDIO transport
	 * @throws IOException Thrown when build fails
	 */
	private McpClientTransport buildStudioTransport(McpServerConfig serverConfig, String serverName)
			throws IOException {
		String command = serverConfig.getCommand().trim();
		List<String> args = serverConfig.getArgs();
		Map<String, String> env = serverConfig.getEnv();

		logger.debug("Building STUDIO transport for server: {} with command: {}", serverName, command);

		ServerParameters.Builder builder = ServerParameters.builder(command);

		// Add parameters
		if (args != null && !args.isEmpty()) {
			builder.args(args);
			logger.debug("Added {} arguments for server: {}", args.size(), serverName);
		}

		// Add environment variables
		if (env != null && !env.isEmpty()) {
			builder.env(env);
			logger.debug("Added {} environment variables for server: {}", env.size(), serverName);
		}

		ServerParameters serverParameters = builder.build();
		JacksonMcpJsonMapper jsonMapper = new JacksonMcpJsonMapper(objectMapper);
		StdioClientTransport transport = new StdioClientTransport(serverParameters, jsonMapper);

		// Set up error handler for server stderr output
		// This captures all console.log/console.error output from MCP server and logs to
		// info level
		transport.setStdErrorHandler(error -> {
			if (error != null && !error.trim().isEmpty()) {
				// Log all MCP server stderr output (including console.log) to info log
				// This ensures all MCP server output is captured in logs for debugging
				logger.info("[MCP Server] [{}]: {}", serverName, error);
			}
		});

		return transport;
	}

	/**
	 * Build STREAMING transport
	 * @param serverConfig Server configuration
	 * @param serverName Server name
	 * @return STREAMING transport
	 * @throws IOException Thrown when build fails
	 */
	private McpClientTransport buildStreamingTransport(McpServerConfig serverConfig, String serverName)
			throws IOException {
		String url = serverConfig.getUrl().trim();
		configValidator.validateUrl(url, serverName);

		URL parsedUrl = URI.create(url).toURL();
		String baseUrl = parsedUrl.getProtocol() + "://" + parsedUrl.getHost()
				+ (parsedUrl.getPort() == -1 ? "" : ":" + parsedUrl.getPort());

		String streamEndpoint = parsedUrl.getPath();

		// Remove leading slash
		if (streamEndpoint.startsWith("/")) {
			streamEndpoint = streamEndpoint.substring(1);
		}

		// Set to null if empty
		if (streamEndpoint.isEmpty()) {
			streamEndpoint = null;
		}

		logger.info("Building Streamable HTTP transport for server: {} with Url: {} and Endpoint: {}", serverName,
				baseUrl, streamEndpoint);

		WebClient.Builder webClientBuilder = createWebClientBuilder(baseUrl, serverConfig);

		logger.debug("Using WebClientStreamableHttpTransport with endpoint: {} for STREAMING mode", streamEndpoint);
		JacksonMcpJsonMapper jsonMapper = new JacksonMcpJsonMapper(objectMapper);
		return WebClientStreamableHttpTransport.builder(webClientBuilder)
			.jsonMapper(jsonMapper)
			.endpoint(streamEndpoint)
			.resumableStreams(true)
			.openConnectionOnStartup(false)
			.build();

	}

	/**
	 * Create WebClient builder (with baseUrl) using shared HttpClient instance
	 * @param baseUrl Base URL
	 * @param serverConfig Server configuration (may contain custom headers)
	 * @return WebClient builder
	 */
	private WebClient.Builder createWebClientBuilder(String baseUrl, McpServerConfig serverConfig) {
		WebClient.Builder builder = WebClient.builder()
			.clientConnector(sharedConnector) // Use shared HttpClient connector
			.baseUrl(baseUrl)
			.defaultHeader("Accept", "text/event-stream")
			.defaultHeader("Content-Type", "application/json")
			.defaultHeader("User-Agent", mcpProperties.getUserAgent())
			.codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(1024 * 1024 * 10))
			// Add timeout to prevent hanging connections
			.filter((request,
					next) -> next.exchange(request).timeout(java.time.Duration.ofSeconds(30)).onErrorMap(ex -> {
						if (ex.getMessage() != null && ex.getMessage().contains("Failed to resolve")) {
							return new IOException("DNS resolution failed for URL: " + baseUrl + ". "
									+ "Please verify the hostname is correct and accessible.", ex);
						}
						return ex;
					}));

		// Apply custom headers from server configuration
		if (serverConfig != null && serverConfig.getHeaders() != null && !serverConfig.getHeaders().isEmpty()) {
			Map<String, String> headers = serverConfig.getHeaders();
			for (Map.Entry<String, String> header : headers.entrySet()) {
				builder.defaultHeader(header.getKey(), header.getValue());
				logger.debug("Added custom header: {} = {}", header.getKey(),
						header.getKey().toLowerCase().contains("token") ? "***" : header.getValue());
			}
			logger.info("Applied {} custom headers from server configuration", headers.size());
		}

		return builder;
	}

	/**
	 * Cleanup shared HttpClient resources on application shutdown
	 */
	@PreDestroy
	public void shutdown() {
		logger.info("Shutting down shared HttpClient for MCP transports");
		try {
			// Dispose the connection provider, which will close all connections
			connectionProvider.dispose();
			logger.info("Shared HttpClient connection provider disposed successfully");
		}
		catch (Exception e) {
			logger.warn("Error disposing shared HttpClient connection provider", e);
		}
	}

}
