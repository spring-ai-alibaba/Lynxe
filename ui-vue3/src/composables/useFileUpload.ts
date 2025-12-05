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

import type { FileInfo } from '@/api/file-upload-api-service'
import { reactive, readonly, ref } from 'vue'

/**
 * Composable for managing shared file upload state across components
 * Provides synchronized file upload state between InputArea and ExecutionController
 */
export function useFileUpload() {
  // Shared reactive state
  const uploadedFiles = reactive<FileInfo[]>([])
  const uploadKey = ref<string | null>(null)

  /**
   * Set uploaded files and upload key
   * @param files Array of uploaded file information
   * @param key Upload key for the file session
   */
  const setUploadedFiles = (files: FileInfo[], key: string | null) => {
    console.log('[useFileUpload] Setting uploaded files:', files.length, 'uploadKey:', key)
    // Clear existing files and set new ones
    uploadedFiles.length = 0
    uploadedFiles.push(...files)
    uploadKey.value = key
    console.log(
      '[useFileUpload] Updated state - files:',
      uploadedFiles.length,
      'key:',
      uploadKey.value
    )
  }

  /**
   * Add files to existing upload session
   * @param files Array of file information to add
   */
  const addUploadedFiles = (files: FileInfo[]) => {
    console.log('[useFileUpload] Adding files to existing session:', files.length)
    uploadedFiles.push(...files)
    console.log('[useFileUpload] Total files after add:', uploadedFiles.length)
  }

  /**
   * Remove a file from uploaded files
   * @param fileName Name of the file to remove
   */
  const removeFile = (fileName: string) => {
    console.log('[useFileUpload] Removing file:', fileName)
    const index = uploadedFiles.findIndex(file => file.originalName === fileName)
    if (index !== -1) {
      uploadedFiles.splice(index, 1)
      console.log('[useFileUpload] File removed, remaining:', uploadedFiles.length)

      // Clear uploadKey if no files remain
      if (uploadedFiles.length === 0) {
        uploadKey.value = null
        console.log('[useFileUpload] Cleared uploadKey - no files remaining')
      }
    }
  }

  /**
   * Clear all uploaded files and upload key
   */
  const clearFiles = () => {
    console.log('[useFileUpload] Clearing all files and uploadKey')
    uploadedFiles.length = 0
    uploadKey.value = null
    console.log('[useFileUpload] Files cleared')
  }

  /**
   * Get current uploaded files
   * @returns Array of uploaded file information
   */
  const getUploadedFiles = (): FileInfo[] => {
    return [...uploadedFiles]
  }

  /**
   * Get current upload key
   * @returns Upload key string or null
   */
  const getUploadKey = (): string | null => {
    return uploadKey.value
  }

  /**
   * Get file names as string array (for backward compatibility)
   * @returns Array of file names
   */
  const getUploadedFileNames = (): string[] => {
    return uploadedFiles.map(file => file.originalName)
  }

  return {
    // Reactive state (readonly to prevent direct mutation)
    uploadedFiles: readonly(uploadedFiles),
    uploadKey: readonly(uploadKey),

    // Methods
    setUploadedFiles,
    addUploadedFiles,
    removeFile,
    clearFiles,
    getUploadedFiles,
    getUploadKey,
    getUploadedFileNames,
  }
}

// Singleton instance for global use
let singletonInstance: ReturnType<typeof useFileUpload> | null = null

/**
 * Get or create singleton instance of useFileUpload
 */
export function useFileUploadSingleton() {
  if (!singletonInstance) {
    singletonInstance = useFileUpload()
  }
  return singletonInstance
}
