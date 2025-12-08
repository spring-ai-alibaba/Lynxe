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
  <div class="template-list-container">
    <div class="organization-section">
      <div class="organization-row">
        <div class="organization-selector">
          <label class="organization-label">{{ $t('sidebar.organizationLabel') }}</label>
          <select
            :value="templateStore.organizationMethod"
            @change="handleOrganizationChange"
            class="organization-select"
          >
            <option value="by_time">{{ $t('sidebar.organizationByTime') }}</option>
            <option value="by_abc">{{ $t('sidebar.organizationByAbc') }}</option>
            <option value="by_group_time">{{ $t('sidebar.organizationByGroupTime') }}</option>
            <option value="by_group_abc">{{ $t('sidebar.organizationByGroupAbc') }}</option>
          </select>
        </div>
        <div class="search-input-wrapper">
          <label class="search-label">{{ $t('sidebar.searchLabel') }}</label>
          <div class="search-input-container">
            <Icon icon="carbon:search" width="16" class="search-icon" />
            <input
              v-model="searchKeyword"
              type="text"
              class="search-input"
              :placeholder="$t('sidebar.searchPlaceholder') || 'Search...'"
            />
            <button
              v-if="searchKeyword"
              class="search-clear-btn"
              @click="searchKeyword = ''"
              :title="$t('sidebar.clearSearch') || 'Clear search'"
            >
              <Icon icon="carbon:close" width="14" />
            </button>
          </div>
        </div>
      </div>
    </div>

    <div class="sidebar-content-list">
      <!-- Loading state -->
      <div v-if="templateStore.isLoading" class="loading-state">
        <Icon icon="carbon:circle-dash" width="20" class="spinning" />
        <span>{{ $t('sidebar.loading') }}</span>
      </div>

      <!-- Error state -->
      <div v-else-if="templateStore.errorMessage" class="error-state">
        <Icon icon="carbon:warning" width="20" />
        <span>{{ templateStore.errorMessage }}</span>
        <button @click="templateStore.loadPlanTemplateList" class="retry-btn">
          {{ $t('sidebar.retry') }}
        </button>
      </div>

      <!-- Empty state -->
      <div v-else-if="templateConfig.planTemplateList.value.length === 0" class="empty-state">
        <Icon icon="carbon:document" width="32" />
        <span>{{ $t('sidebar.noTemplates') }}</span>
      </div>

      <!-- Plan template list (grouped or ungrouped) -->
      <template v-else>
        <template
          v-for="[groupName, templates] in filteredGroupedTemplates"
          :key="groupName || 'ungrouped'"
        >
          <!-- Group header (only show for grouped methods) -->
          <div
            v-if="
              (templateStore.organizationMethod === 'by_group_time' ||
                templateStore.organizationMethod === 'by_group_abc') &&
              templates.length > 0
            "
            class="group-header"
            @click.prevent="handleToggleGroupCollapse(groupName)"
            :title="
              getGroupCollapseState(groupName)
                ? $t('sidebar.expandGroup')
                : $t('sidebar.collapseGroup')
            "
          >
            <button
              type="button"
              class="group-toggle-btn"
              @click.stop.prevent="handleToggleGroupCollapse(groupName)"
              :title="
                getGroupCollapseState(groupName)
                  ? $t('sidebar.expandGroup')
                  : $t('sidebar.collapseGroup')
              "
            >
              <Icon
                :icon="
                  getGroupCollapseState(groupName) ? 'carbon:chevron-right' : 'carbon:chevron-down'
                "
                width="14"
              />
            </button>
            <Icon icon="carbon:folder" width="16" />
            <span class="group-name">
              {{
                groupName === null || groupName === '' ? $t('sidebar.ungroupedMethods') : groupName
              }}
            </span>
            <span class="group-count">({{ templates.length }})</span>
          </div>
          <!-- Template items (hidden when group is collapsed) -->
          <template
            v-if="
              !(
                (templateStore.organizationMethod === 'by_group_time' ||
                  templateStore.organizationMethod === 'by_group_abc') &&
                getGroupCollapseState(groupName)
              )
            "
          >
            <div
              v-for="template in templates"
              :key="template.planTemplateId || 'unknown'"
              class="sidebar-content-list-item"
              :class="{
                'sidebar-content-list-item-active':
                  template.planTemplateId === templateConfig.currentPlanTemplateId.value,
                'grouped-item':
                  templateStore.organizationMethod === 'by_group_time' ||
                  templateStore.organizationMethod === 'by_group_abc',
              }"
              @click="handleSelectTemplate(template)"
            >
              <div class="task-icon">
                <Icon icon="carbon:document" width="20" />
              </div>
              <div class="task-details">
                <div class="task-title">{{ template.title || $t('sidebar.unnamedPlan') }}</div>
              </div>
              <div class="task-time">
                {{
                  getRelativeTimeString(
                    templateConfig.parseDateTime(template.updateTime || template.createTime)
                  )
                }}
              </div>
              <div class="task-actions">
                <button
                  class="delete-task-btn"
                  :title="$t('sidebar.deleteTemplate')"
                  @click.stop="showDeleteConfirm(template)"
                >
                  <Icon icon="carbon:close" width="16" />
                </button>
              </div>
            </div>
          </template>
        </template>
      </template>
    </div>
  </div>

  <!-- Delete Confirmation Modal -->
  <Teleport to="body">
    <Transition name="modal">
      <div v-if="showDeleteConfirmModal" class="modal-overlay" @click="cancelDelete">
        <div class="confirm-modal" @click.stop>
          <div class="confirm-header">
            <Icon icon="carbon:warning" class="warning-icon" />
            <h3>{{ $t('sidebar.deleteConfirm') }}</h3>
          </div>
          <div class="confirm-content">
            <p>
              {{
                $t('sidebar.deleteConfirmMessage', { templateName: templateToDelete?.title || '' })
              }}
            </p>
          </div>
          <div class="confirm-actions">
            <button class="confirm-btn cancel-btn" @click="cancelDelete">
              {{ $t('common.cancel') }}
            </button>
            <button
              class="confirm-btn delete-btn"
              @click="handleDeleteTemplate"
              :disabled="deleting"
            >
              <Icon :icon="deleting ? 'carbon:loading' : 'carbon:trash-can'" />
              {{ $t('common.delete') }}
            </button>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<script setup lang="ts">
import { usePlanTemplateConfigSingleton } from '@/composables/usePlanTemplateConfig'
import { useRightPanelSingleton } from '@/composables/useRightPanel'
import { useToast } from '@/plugins/useToast'
import { templateStore, type TemplateStoreType } from '@/stores/templateStore'
import type { PlanTemplateConfigVO } from '@/types/plan-template'
import { Icon } from '@iconify/vue'
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'

const { t } = useI18n()

// Template config management
const templateConfig = usePlanTemplateConfigSingleton()

// Right panel management
const rightPanel = useRightPanelSingleton()

// Toast for notifications
const toast = useToast()

// Search functionality
const searchKeyword = ref('')

// Delete confirmation state
const showDeleteConfirmModal = ref(false)
const templateToDelete = ref<PlanTemplateConfigVO | null>(null)
const deleting = ref(false)

// Emits
const emit = defineEmits<{
  templateSelected: [template: PlanTemplateConfigVO]
}>()

// Handle organization method change
const handleOrganizationChange = (event: Event) => {
  const target = event.target as HTMLSelectElement
  templateStore.setOrganizationMethod(
    target.value as 'by_time' | 'by_abc' | 'by_group_time' | 'by_group_abc'
  )
}

// Utility functions
const getRelativeTimeString = (date: Date): string => {
  // Check if date is valid
  if (isNaN(date.getTime())) {
    console.warn('Invalid date received:', date)
    return t('time.unknown')
  }

  const now = new Date()
  const diffMs = now.getTime() - date.getTime()
  const diffMinutes = Math.floor(diffMs / 60000)
  const diffHours = Math.floor(diffMs / 3600000)
  const diffDays = Math.floor(diffMs / 86400000)

  if (diffMinutes < 1) return t('time.now')
  if (diffMinutes < 60) return t('time.minuteAgo', { count: diffMinutes })
  if (diffHours < 24) return t('time.hourAgo', { count: diffHours })
  if (diffDays < 30) return t('time.dayAgo', { count: diffDays })

  return date.toLocaleDateString('zh-CN')
}

// Filter templates based on search keyword
const filteredGroupedTemplates = computed(() => {
  // Access planTemplateList directly to ensure reactivity
  // This ensures Vue tracks changes to the list
  void templateConfig.planTemplateList.value

  const keyword = searchKeyword.value.trim().toLowerCase()

  // Get grouped templates from templateStore
  const grouped = (templateStore as TemplateStoreType).groupedTemplates

  if (!keyword) {
    return grouped
  }

  const filtered = new Map<string | null, PlanTemplateConfigVO[]>()

  // Iterate through all groups
  for (const [groupName, templates] of grouped) {
    const matchingTemplates: PlanTemplateConfigVO[] = []

    for (const template of templates) {
      // Search in title
      const title = (template.title || '').toLowerCase()

      if (title.includes(keyword)) {
        matchingTemplates.push(template)
      }
    }

    // Only add groups that have matching templates
    if (matchingTemplates.length > 0) {
      filtered.set(groupName, matchingTemplates)
    }
  }

  return filtered
})

// Auto-expand groups that contain matching items when searching
watch(searchKeyword, newKeyword => {
  // Access planTemplateList to ensure reactivity
  void templateConfig.planTemplateList.value

  const keyword = newKeyword.trim().toLowerCase()
  if (!keyword) {
    return
  }

  // Only auto-expand for grouped organization methods
  if (
    templateStore.organizationMethod !== 'by_group_time' &&
    templateStore.organizationMethod !== 'by_group_abc'
  ) {
    return
  }

  // Expand groups that contain matches
  const grouped = (templateStore as TemplateStoreType).groupedTemplates
  for (const [groupName, templates] of grouped) {
    let hasMatch = false
    for (const template of templates) {
      const title = (template.title || '').toLowerCase()
      if (title.includes(keyword)) {
        hasMatch = true
        break
      }
    }

    if (hasMatch && templateStore.isGroupCollapsed(groupName)) {
      templateStore.toggleGroupCollapse(groupName)
    }
  }
})

// Get group collapse state - helper function to access reactive property directly
const getGroupCollapseState = (groupName: string | null): boolean => {
  const key = groupName ?? 'null'
  // Directly access reactive object property so Vue can track it
  return templateStore.groupCollapseState[key] ?? false
}

// Handle group collapse toggle
const handleToggleGroupCollapse = (groupName: string | null) => {
  templateStore.toggleGroupCollapse(groupName)
}

// Handle select template
const handleSelectTemplate = async (template: PlanTemplateConfigVO) => {
  // Switch to 'config' tab immediately when template is clicked
  // This ensures immediate feedback and prevents the need for double-click
  rightPanel.setActiveTab('config')

  await templateStore.selectTemplate(template)

  // Load template config using singleton
  if (template.planTemplateId) {
    await templateConfig.load(template.planTemplateId)
    // Reset modification flag when loading new template
    templateStore.hasTaskRequirementModified = false
  } else {
    // Reset config if no template ID
    templateConfig.reset()
    templateStore.hasTaskRequirementModified = false
  }

  // Note: No need to call setActiveTab again after nextTick
  // The initial call at the beginning is sufficient since activeTab is reactive
  // and will be maintained throughout the async operations

  emit('templateSelected', template)
}

/**
 * Show delete confirmation dialog
 * @param template Template to delete
 */
const showDeleteConfirm = (template: PlanTemplateConfigVO) => {
  templateToDelete.value = template
  showDeleteConfirmModal.value = true
}

/**
 * Handle template deletion
 */
const handleDeleteTemplate = async () => {
  if (!templateToDelete.value) return

  deleting.value = true
  try {
    await templateStore.deleteTemplate(templateToDelete.value)
    showDeleteConfirmModal.value = false
    templateToDelete.value = null
    toast.success(t('sidebar.deleteSuccess') || 'Template deleted successfully')
  } catch (error) {
    console.error('Failed to delete template:', error)
    toast.error(
      t('sidebar.deleteFailed') ||
        `Delete failed: ${error instanceof Error ? error.message : String(error)}`
    )
  } finally {
    deleting.value = false
  }
}

/**
 * Cancel delete operation
 */
const cancelDelete = () => {
  showDeleteConfirmModal.value = false
  templateToDelete.value = null
}
</script>

<style scoped>
.template-list-container {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
  overflow: hidden;
  padding: 10px 10px;
}

.organization-section {
  margin-bottom: 16px;
  padding-right: 12px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.organization-row {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.organization-selector {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  gap: 8px;
}

.organization-label {
  font-size: 12px;
  color: rgba(255, 255, 255, 0.7);
  white-space: nowrap;
  margin: 0;
  padding: 0;
  line-height: 1;
}

.organization-select {
  width: 20ch;
  max-width: 20ch;
  padding: 6px 10px;
  background: rgba(255, 255, 255, 0.05);
  border: 1px solid rgba(255, 255, 255, 0.1);
  border-radius: 6px;
  color: white;
  font-size: 12px;
  cursor: pointer;
  transition: all 0.2s ease;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.organization-select:hover {
  background: rgba(255, 255, 255, 0.1);
  border-color: rgba(255, 255, 255, 0.2);
}

.organization-select:focus {
  outline: none;
  border-color: #667eea;
  box-shadow: 0 0 0 2px rgba(102, 126, 234, 0.2);
}

.organization-select option {
  background: #1a1a1a;
  color: white;
  white-space: normal;
}

.search-input-wrapper {
  flex: 1;
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.search-label {
  font-size: 12px;
  color: rgba(255, 255, 255, 0.7);
  white-space: nowrap;
  margin: 0;
  padding: 0;
  line-height: 1;
}

.search-input-container {
  flex: 1;
  position: relative;
  display: flex;
  align-items: center;
  min-width: 0;
}

.search-icon {
  position: absolute;
  left: 10px;
  color: rgba(255, 255, 255, 0.5);
  pointer-events: none;
  z-index: 1;
}

.search-input {
  width: 100%;
  padding: 6px 10px 6px 32px;
  background: rgba(255, 255, 255, 0.05);
  border: 1px solid rgba(255, 255, 255, 0.1);
  border-radius: 6px;
  color: white;
  font-size: 12px;
  transition: all 0.2s ease;
}

.search-input::placeholder {
  color: rgba(255, 255, 255, 0.4);
}

.search-input:hover {
  background: rgba(255, 255, 255, 0.1);
  border-color: rgba(255, 255, 255, 0.2);
}

.search-input:focus {
  outline: none;
  border-color: #667eea;
  box-shadow: 0 0 0 2px rgba(102, 126, 234, 0.2);
}

.search-clear-btn {
  position: absolute;
  right: 6px;
  width: 20px;
  height: 20px;
  background: transparent;
  border: none;
  border-radius: 4px;
  color: rgba(255, 255, 255, 0.5);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all 0.2s ease;
  z-index: 1;
}

.search-clear-btn:hover {
  background: rgba(255, 255, 255, 0.1);
  color: white;
}

.sidebar-content-list {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding-right: 12px;
  padding-bottom: 40px;
}

.loading-state,
.error-state,
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 32px 16px;
  color: rgba(255, 255, 255, 0.6);
  font-size: 14px;
  text-align: center;
  gap: 12px;
}

.spinning {
  animation: spin 1s linear infinite;
}

.retry-btn {
  padding: 8px 16px;
  background: rgba(255, 255, 255, 0.1);
  border: 1px solid rgba(255, 255, 255, 0.2);
  border-radius: 4px;
  color: white;
  cursor: pointer;
  font-size: 12px;
  transition: background-color 0.2s ease;
}

.retry-btn:hover {
  background: rgba(255, 255, 255, 0.2);
}

.group-header {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  margin-top: 12px;
  margin-bottom: 6px;
  color: rgba(255, 255, 255, 0.8);
  font-size: 13px;
  font-weight: 600;
  border-bottom: 1px solid rgba(255, 255, 255, 0.1);
  cursor: pointer;
  user-select: none;
  transition: background-color 0.2s ease;
  position: relative;
  z-index: 1;
}

.group-header:hover {
  background: rgba(255, 255, 255, 0.05);
}

.group-header:first-child {
  margin-top: 0;
}

.group-toggle-btn {
  width: 20px;
  height: 20px;
  background: transparent;
  border: none;
  border-radius: 4px;
  color: rgba(255, 255, 255, 0.7);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all 0.2s ease;
  flex-shrink: 0;
  pointer-events: auto;
  position: relative;
  z-index: 2;
}

.group-toggle-btn:hover {
  background: rgba(255, 255, 255, 0.1);
  color: white;
}

.group-toggle-btn:active {
  transform: scale(0.95);
}

.group-name {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.group-count {
  font-size: 11px;
  color: rgba(255, 255, 255, 0.5);
  flex-shrink: 0;
}

.sidebar-content-list-item {
  display: flex;
  align-items: flex-start;
  padding: 8px;
  margin-bottom: 8px;
  background: rgba(255, 255, 255, 0.05);
  border: 1px solid rgba(255, 255, 255, 0.1);
  border-radius: 8px;
  cursor: pointer;
  transition: all 0.2s ease;
  position: relative;
}

.sidebar-content-list-item.grouped-item {
  margin-left: 16px;
  border-left: 2px solid rgba(102, 126, 234, 0.3);
}

.sidebar-content-list-item:hover {
  background: rgba(255, 255, 255, 0.1);
  border-color: rgba(255, 255, 255, 0.2);
  transform: translateY(-1px);
}

.sidebar-content-list-item.sidebar-content-list-item-active {
  border: 2px solid #667eea;
  background: rgba(102, 126, 234, 0.1);
}

.task-icon {
  margin-right: 12px;
  color: #667eea;
  flex-shrink: 0;
  margin-top: 2px;
}

.task-details {
  flex: 1;
  min-width: 0;
}

.task-title {
  font-size: 14px;
  font-weight: 600;
  color: white;
  margin-bottom: 4px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.task-time {
  font-size: 11px;
  color: rgba(255, 255, 255, 0.5);
  margin-left: 8px;
  flex-shrink: 0;
  position: absolute;
  top: 12px;
  right: 40px;
}

.task-actions {
  display: flex;
  align-items: center;
  margin-left: 8px;
  flex-shrink: 0;
}

.delete-task-btn {
  width: 24px;
  height: 24px;
  background: transparent;
  border: none;
  border-radius: 4px;
  color: rgba(255, 255, 255, 0.5);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all 0.2s ease;
  position: absolute;
  top: 12px;
  right: 12px;
}

.delete-task-btn:hover {
  background: rgba(255, 0, 0, 0.2);
  color: #ff6b6b;
}

@keyframes spin {
  from {
    transform: rotate(0deg);
  }
  to {
    transform: rotate(360deg);
  }
}

/* Delete confirmation modal styles */
.modal-overlay {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0, 0, 0, 0.7);
  backdrop-filter: blur(4px);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
}

.confirm-modal {
  background: linear-gradient(135deg, rgba(102, 126, 234, 0.1), rgba(118, 75, 162, 0.15));
  border: 1px solid rgba(255, 255, 255, 0.1);
  border-radius: 16px;
  width: 90%;
  max-width: 480px;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.4);
  overflow: hidden;
}

.confirm-header {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 24px 24px 16px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.1);
}

.warning-icon {
  font-size: 24px;
  color: #f59e0b;
}

.confirm-header h3 {
  margin: 0;
  font-size: 18px;
  font-weight: 600;
  color: rgba(255, 255, 255, 0.9);
}

.confirm-content {
  padding: 20px 24px;
}

.confirm-content p {
  margin: 0;
  color: rgba(255, 255, 255, 0.8);
  line-height: 1.6;
  font-size: 14px;
}

.confirm-actions {
  display: flex;
  gap: 12px;
  padding: 16px 24px 24px;
  justify-content: flex-end;
}

.confirm-btn {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 10px 16px;
  border-radius: 8px;
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.3s;
  border: 1px solid transparent;
  min-width: 80px;
  justify-content: center;
}

.confirm-btn.cancel-btn {
  background: rgba(156, 163, 175, 0.1);
  border-color: rgba(156, 163, 175, 0.2);
  color: #9ca3af;
}

.confirm-btn.cancel-btn:hover {
  background: rgba(156, 163, 175, 0.2);
  border-color: rgba(156, 163, 175, 0.3);
}

.confirm-btn.delete-btn {
  background: rgba(239, 68, 68, 0.1);
  border-color: rgba(239, 68, 68, 0.2);
  color: #ef4444;
}

.confirm-btn.delete-btn:hover:not(:disabled) {
  background: rgba(239, 68, 68, 0.2);
  border-color: rgba(239, 68, 68, 0.3);
}

.confirm-btn.delete-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

/* Modal transition animations */
.modal-enter-active,
.modal-leave-active {
  transition: all 0.3s ease;
}

.modal-enter-from,
.modal-leave-to {
  opacity: 0;
}

.modal-enter-from .confirm-modal,
.modal-leave-to .confirm-modal {
  transform: scale(0.9) translateY(-20px);
}

.modal-enter-to .confirm-modal,
.modal-leave-from .confirm-modal {
  transform: scale(1) translateY(0);
}
</style>
