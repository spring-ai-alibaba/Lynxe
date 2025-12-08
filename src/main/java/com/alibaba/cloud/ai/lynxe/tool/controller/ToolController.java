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
package com.alibaba.cloud.ai.lynxe.tool.controller;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.alibaba.cloud.ai.lynxe.agent.model.Tool;
import com.alibaba.cloud.ai.lynxe.mcp.service.McpService;
import com.alibaba.cloud.ai.lynxe.planning.PlanningFactory;
import com.alibaba.cloud.ai.lynxe.planning.PlanningFactory.ToolCallBackContext;
import com.alibaba.cloud.ai.lynxe.tool.ToolCallBiFunctionDef;

/**
 * Tool Controller - Provides API endpoints for tool management
 */
@RestController
@RequestMapping("/api/tools")
@CrossOrigin(origins = "*")
public class ToolController {

	private static final Logger log = LoggerFactory.getLogger(ToolController.class);

	@Autowired
	private PlanningFactory planningFactory;

	@Autowired
	private McpService mcpService;

	/**
	 * Get all available tools
	 * @return List of available tools
	 */
	@GetMapping
	public ResponseEntity<List<Tool>> getAvailableTools() {
		String uuid = UUID.randomUUID().toString();
		String expectedReturnInfo = null;

		try {
			log.debug("Getting available tools using PlanningFactory");
			Map<String, ToolCallBackContext> toolCallbackContext = planningFactory.toolCallbackMap(uuid, uuid,
					expectedReturnInfo);

			List<Tool> tools = toolCallbackContext.entrySet().stream().map(entry -> {
				Tool tool = new Tool();
				ToolCallBiFunctionDef<?> functionInstance = entry.getValue().getFunctionInstance();
				String serviceGroup = functionInstance.getServiceGroup();
				String toolName = functionInstance.getName();

				// Construct key in serviceGroup.toolName format for frontend
				// Backend will convert this to serviceGroup_toolName format during
				// execution
				String toolKey;
				if (serviceGroup != null && !serviceGroup.isEmpty()) {
					toolKey = serviceGroup + "_" + toolName;
				}
				else {
					toolKey = toolName;
				}
				tool.setKey(toolKey);
				tool.setName(toolName); // Keep just the tool name for display
				tool.setDescription(functionInstance.getDescriptionWithServiceGroup());
				tool.setEnabled(true);
				tool.setServiceGroup(serviceGroup);
				tool.setSelectable(functionInstance.isSelectable());
				return tool;
			}).collect(Collectors.toList());

			log.info("Retrieved {} available tools", tools.size());
			return ResponseEntity.ok(tools);

		}
		catch (Exception e) {
			log.error("Error getting available tools: {}", e.getMessage(), e);
			return ResponseEntity.internalServerError().build();
		}
		finally {
			// Clean up MCP connections
			try {
				mcpService.close(uuid);
			}
			catch (Exception e) {
				log.warn("Error closing MCP service for UUID {}: {}", uuid, e.getMessage());
			}
		}
	}

}
