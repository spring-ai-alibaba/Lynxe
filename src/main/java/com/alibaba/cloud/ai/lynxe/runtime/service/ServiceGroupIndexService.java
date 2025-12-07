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

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Service for managing serviceGroup to index mapping with thread-safe caching. Provides
 * application-wide consistent indexing for service groups.
 *
 * @author yuluo
 */
@Service
public class ServiceGroupIndexService {

	private static final Logger log = LoggerFactory.getLogger(ServiceGroupIndexService.class);

	/**
	 * Thread-safe cache mapping serviceGroup names to their assigned indices
	 */
	private final ConcurrentHashMap<String, Integer> serviceGroupIndexMap = new ConcurrentHashMap<>();

	/**
	 * Thread-safe counter for generating unique indices, starting from 1
	 */
	private final AtomicInteger nextIndex = new AtomicInteger(1);

	/**
	 * Get or assign a unique index for the given serviceGroup. If the serviceGroup
	 * already exists in the cache, returns its existing index. Otherwise, assigns a new
	 * unique index and caches it.
	 * @param serviceGroup The service group name (can be null or empty)
	 * @return The index assigned to the serviceGroup, or null if serviceGroup is null or
	 * empty
	 */
	public Integer getOrAssignIndex(String serviceGroup) {
		if (serviceGroup == null || serviceGroup.isEmpty()) {
			return null;
		}

		// Use computeIfAbsent to atomically get existing index or assign new one
		Integer index = serviceGroupIndexMap.computeIfAbsent(serviceGroup, k -> {
			int newIndex = nextIndex.getAndIncrement();
			log.debug("Assigned new index {} to serviceGroup: {}", newIndex, serviceGroup);
			return newIndex;
		});

		log.debug("Retrieved index {} for serviceGroup: {}", index, serviceGroup);
		return index;
	}

	/**
	 * Clear the cache and reset the counter to 1. Useful for testing or resetting the
	 * service state.
	 */
	public void clearCache() {
		serviceGroupIndexMap.clear();
		nextIndex.set(1);
		log.info("ServiceGroupIndexService cache cleared and counter reset");
	}

	/**
	 * Get the current size of the cache.
	 * @return The number of serviceGroups currently cached
	 */
	public int getCacheSize() {
		return serviceGroupIndexMap.size();
	}

	/**
	 * Check if a serviceGroup exists in the cache.
	 * @param serviceGroup The service group name to check
	 * @return true if the serviceGroup exists in the cache, false otherwise
	 */
	public boolean containsServiceGroup(String serviceGroup) {
		return serviceGroup != null && serviceGroupIndexMap.containsKey(serviceGroup);
	}

	/**
	 * Convert serviceGroup.toolName format to serviceGroup_toolName format This method
	 * handles the conversion from frontend format (serviceGroup.toolName) to backend
	 * execution format (serviceGroup_toolName)
	 * @param toolKey The tool key in serviceGroup.toolName format or other formats
	 * @return The converted key in serviceGroup_toolName format, or the original key if
	 * conversion is not needed or failed
	 */
	public String constructFrontendToolKey(String toolKey) {
		if (toolKey == null || toolKey.isEmpty()) {
			return toolKey;
		}

		// Check if key is in serviceGroup.toolName format (contains a dot)
		// Use lastIndexOf to handle serviceGroups that may contain dots
		int dotIndex = toolKey.lastIndexOf('.');
		if (dotIndex > 0 && dotIndex < toolKey.length() - 1) {
			// Convert to serviceGroup_toolName format
			String qualifiedKey = toolKey.substring(0, dotIndex) + "_" + toolKey.substring(dotIndex + 1);
			log.debug("Converted tool key from '{}' to '{}'", toolKey, qualifiedKey);
			return qualifiedKey;
		}

		// Return original key if conversion is not needed (key is already in correct
		// format or doesn't contain a dot)
		return toolKey;
	}

}
