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

/**
 * Version component structure
 */
export interface VersionComponents {
  major: number
  minor: number
  patch: number
}

/**
 * Parse version string into components
 * Supports format: "major.minor.patch" (e.g., "4.8.6")
 * @param version Version string to parse
 * @returns Parsed version components or null if invalid
 */
export function parseVersion(version: string): VersionComponents | null {
  if (!version || typeof version !== 'string') {
    return null
  }

  // Handle "unknown" or empty strings
  const trimmed = version.trim().toLowerCase()
  if (trimmed === 'unknown' || trimmed === '' || trimmed === 'null' || trimmed === 'undefined') {
    return null
  }

  // Split by dot and parse numbers
  const parts = trimmed.split('.')
  if (parts.length < 2) {
    return null
  }

  const major = parseInt(parts[0], 10)
  const minor = parseInt(parts[1], 10)
  const patch = parts.length >= 3 ? parseInt(parts[2], 10) : 0

  // Validate parsed numbers
  if (isNaN(major) || isNaN(minor) || isNaN(patch)) {
    return null
  }

  return { major, minor, patch }
}

/**
 * Compare template version with system version
 * Returns true if template's major OR minor version is lower than system's
 * @param templateVersion Template version string (e.g., "4.7.6")
 * @param systemVersion System version string (e.g., "4.8.6")
 * @returns true if template version is outdated (major or minor is lower), false otherwise
 */
export function isVersionOutdated(templateVersion: string, systemVersion: string): boolean {
  // Handle null/undefined/empty versions
  if (!templateVersion || !systemVersion) {
    return false
  }

  const template = parseVersion(templateVersion)
  const system = parseVersion(systemVersion)

  // If either version is invalid, don't show warning
  if (!template || !system) {
    return false
  }

  // Compare major version first
  if (template.major < system.major) {
    return true
  }

  // If major versions are equal, compare minor version
  if (template.major === system.major && template.minor < system.minor) {
    return true
  }

  // Same major and minor version (or template is newer) - no warning
  return false
}
