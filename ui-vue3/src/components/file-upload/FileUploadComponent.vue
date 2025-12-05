<!--
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
-->
<template>
  <div class="file-upload-component">
    <!-- Hidden file input -->
    <input
      ref="fileInputRef"
      type="file"
      multiple
      style="display: none"
      @change="handleFileChange"
      :accept="acceptedFileTypes || ''"
    />

    <!-- Uploaded files display with integrated upload button -->
    <div class="uploaded-files" :class="{ 'has-files': uploadedFiles.length > 0 }">
      <div class="files-header" @click="handleFileUpload">
        <Icon icon="carbon:attachment" />
        <span v-if="uploadedFiles.length === 0">{{ t('input.attachFile') }}</span>
        <span v-else>{{ t('input.attachedFiles') }} ({{ uploadedFiles.length }})</span>
      </div>
      <div v-if="uploadedFiles.length > 0" class="files-list">
        <div v-for="file in uploadedFiles" :key="file.originalName" class="file-item">
          <Icon icon="carbon:document" class="file-icon" />
          <span class="file-name">{{ file.originalName }}</span>
          <span class="file-size">({{ formatFileSize(file.size) }})</span>
          <button @click="removeFile(file)" class="remove-btn" :title="t('input.removeFile')">
            <Icon icon="carbon:close" />
          </button>
        </div>
      </div>
    </div>

    <!-- Upload progress indicator -->
    <div v-if="isUploading" class="upload-progress">
      <Icon icon="carbon:rotate--clockwise" class="loading-icon" />
      <span>{{ t('input.uploading') }}</span>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onUnmounted, watch, computed } from 'vue'
import { Icon } from '@iconify/vue'
import { useI18n } from 'vue-i18n'
import {
  FileInfo,
  FileUploadApiService,
  type DeleteFileResponse,
  type FileUploadResult,
} from '@/api/file-upload-api-service'
import { useFileUploadSingleton } from '@/composables/useFileUpload'

const { t } = useI18n()

type FileUploadProps = {
  acceptedFileTypes?: string
  disabled?: boolean
}

interface Emits {
  (e: 'files-uploaded', files: FileInfo[], uploadKey: string | null): void
  (e: 'files-removed', files: FileInfo[]): void
  (e: 'upload-key-changed', uploadKey: string | null): void
  (e: 'upload-started'): void
  (e: 'upload-completed'): void
  (e: 'upload-error', error: unknown): void
}

const props = withDefaults(defineProps<FileUploadProps>(), {
  acceptedFileTypes:
    '.pdf,.txt,.md,.doc,.docx,.csv,.xlsx,.xls,.json,.xml,.html,.htm,.mhtml,.log,.java,.py,.js,.ts,.sql,.sh,.bat,.yaml,.yml,.properties,.conf,.ini,.jpg,.jpeg,.png,.gif',
  disabled: false,
})

const emit = defineEmits<Emits>()

// Shared file upload state
const fileUpload = useFileUploadSingleton()

// Reactive state
const fileInputRef = ref<HTMLInputElement>()
const isUploading = ref(false)

// Use shared state for uploadedFiles and uploadKey
// uploadedFiles is a reactive array (not a ref), so access directly
const uploadedFiles = computed(() => Array.from(fileUpload.uploadedFiles))

// Function to reset session when starting a new conversation session
const resetSession = () => {
  console.log('[FileUpload] Resetting session and clearing uploadKey')
  fileUpload.clearFiles()
  emit('upload-key-changed', null)
}

// Auto-reset session when component is unmounted to prevent memory leaks
onUnmounted(() => {
  resetSession()
})

// Watch for file changes to emit events
watch(
  () => fileUpload.uploadedFiles,
  newFiles => {
    emit('files-removed', Array.from(newFiles))
  },
  { deep: true }
)

// File upload handlers
const handleFileUpload = () => {
  if (props.disabled) return
  if (fileInputRef.value) {
    fileInputRef.value.click()
  }
}

const handleFileChange = async (event: Event) => {
  const target = event.target as HTMLInputElement
  const files = target.files

  if (!files || files.length === 0) return

  // Convert FileList to Array and add to pending files (for batch upload)
  const fileArray = Array.from(files)
  console.log(
    '[FileUpload] Selected files for upload:',
    fileArray.map(f => f.name)
  )

  // Immediately upload all selected files
  await uploadFiles(fileArray)

  // Reset file input
  target.value = ''
}

const uploadFiles = async (files: File[]) => {
  if (files.length === 0) return

  isUploading.value = true
  emit('upload-started')

  try {
    // Upload files using the new API service
    const result: FileUploadResult = await FileUploadApiService.uploadFiles(files)

    if (result.success) {
      // Update shared state
      const currentKey = fileUpload.uploadKey.value
      if (!currentKey && result.uploadKey) {
        // New upload session - replace entire array
        console.log('[FileUpload] New upload session, setting uploadKey:', result.uploadKey)
        fileUpload.setUploadedFiles(result.uploadedFiles, result.uploadKey)
      } else if (currentKey) {
        // Existing upload session - append to existing files
        console.log('[FileUpload] Using existing uploadKey:', currentKey)
        fileUpload.addUploadedFiles(result.uploadedFiles)
        // Update uploadKey if it changed
        if (result.uploadKey && result.uploadKey !== currentKey) {
          fileUpload.setUploadedFiles(Array.from(fileUpload.uploadedFiles), result.uploadKey)
        }
      } else {
        console.warn('[FileUpload] No uploadKey returned from upload')
        // Fallback - replace entire array
        fileUpload.setUploadedFiles(result.uploadedFiles, null)
      }

      // Emit events for parent components
      const finalFiles = Array.from(fileUpload.uploadedFiles)
      const finalKey = fileUpload.uploadKey.value
      console.log('[FileUpload] Updated shared state - files:', finalFiles.length, 'key:', finalKey)

      if (finalKey) {
        emit('upload-key-changed', finalKey)
      }
      emit('files-uploaded', finalFiles, finalKey)
    }

    // Show success message or update UI as needed
    console.log('Files uploaded successfully:', result)
    emit('upload-completed')
  } catch (error) {
    console.error('File upload error:', error)
    emit('upload-error', error)
  } finally {
    isUploading.value = false
  }
}

// File management functions
const removeFile = async (fileToRemove: FileInfo) => {
  try {
    const currentKey = fileUpload.uploadKey.value
    console.log('🗑️ Removing file:', fileToRemove.originalName, 'from uploadKey:', currentKey)

    // Call backend API to delete the file from the server
    if (currentKey) {
      const result: DeleteFileResponse = await FileUploadApiService.deleteFile(
        currentKey,
        fileToRemove.originalName
      )
      if (result.success) {
        console.log('✅ File deleted from server successfully')
      } else {
        console.error('❌ Failed to delete file from server:', result.error)
      }
    }

    // Update shared state
    fileUpload.removeFile(fileToRemove.originalName)

    // Emit events
    const remainingFiles = Array.from(fileUpload.uploadedFiles)

    if (remainingFiles.length === 0) {
      console.log('[FileUpload] 🧹 All files removed, clearing uploadKey')
      emit('upload-key-changed', null)
    }

    emit('files-removed', remainingFiles)
    console.log('🎉 File removal completed, remaining files:', remainingFiles.length)
  } catch (error) {
    console.error('❌ Error removing file:', error)
    emit('upload-error', error)
  }
}

const formatFileSize = (bytes: number): string => {
  if (bytes === 0) return '0 Bytes'
  const k = 1024
  const sizes = ['Bytes', 'KB', 'MB', 'GB']
  const i = Math.floor(Math.log(bytes) / Math.log(k))
  return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i]
}

// Expose methods and state to parent component
defineExpose({
  get uploadedFiles() {
    return Array.from(fileUpload.uploadedFiles)
  },
  get uploadKey() {
    return fileUpload.uploadKey.value
  },
  resetSession,
  uploadFiles,
  removeFile,
  isUploading,
})
</script>

<style lang="less" scoped>
.file-upload-component {
  display: flex;
  flex-direction: column;
  gap: 8px;
  width: 100%;
}

/* File upload styles */
.uploaded-files {
  padding: 8px;
  border-radius: 8px;
  transition: all 0.2s ease;
  width: 100%;
  box-sizing: border-box;

  &:hover {
    background: rgba(255, 255, 255, 0.08);
    border-color: rgba(255, 255, 255, 0.2);
  }

  &.has-files {
    background: rgba(255, 255, 255, 0.08);
    border-color: rgba(255, 255, 255, 0.15);
  }
}

.files-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 4px;
  font-size: 12px;
  color: rgba(255, 255, 255, 0.7);
  font-weight: 500;
  cursor: pointer;
  padding: 4px 2px;
  transition: all 0.2s ease;
  user-select: none;

  &:hover {
    background: rgba(255, 255, 255, 0.1);
    color: #007acc;
  }

  &:active {
    transform: translateY(1px);
  }
}

.files-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.file-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 8px;
  background: rgba(255, 255, 255, 0.05);
  border-radius: 6px;
  border: 1px solid rgba(255, 255, 255, 0.1);
  transition: all 0.2s ease;

  &:hover {
    background: rgba(255, 255, 255, 0.08);
    border-color: rgba(255, 255, 255, 0.2);
  }
}

.file-icon {
  font-size: 14px;
  color: #007acc;
  flex-shrink: 0;
}

.file-name {
  flex: 1;
  font-size: 13px;
  color: #ffffff;
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.file-size {
  font-size: 11px;
  color: rgba(255, 255, 255, 0.6);
  flex-shrink: 0;
}

.remove-btn {
  background: none;
  border: none;
  padding: 2px;
  cursor: pointer;
  color: rgba(255, 255, 255, 0.5);
  transition: all 0.2s ease;
  border-radius: 3px;
  flex-shrink: 0;

  &:hover {
    color: #ff6b6b;
    background: rgba(255, 107, 107, 0.1);
  }
}

.upload-progress {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  background: rgba(0, 122, 204, 0.1);
  border-radius: 6px;
  border: 1px solid rgba(0, 122, 204, 0.2);
  font-size: 12px;
  color: #007acc;
}

.loading-icon {
  font-size: 14px;
  animation: spin 1s linear infinite;
}

@keyframes spin {
  from {
    transform: rotate(0deg);
  }
  to {
    transform: rotate(360deg);
  }
}
</style>
