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
  <div class="config-section">
    <div class="section-header">
      <Icon icon="carbon:code" width="16" />
      <span>{{ $t('sidebar.dynamicAgentPlan') }}</span>
    </div>
    <!-- Visual JSON Editor -->
    <div class="visual-editor">
      <!-- Plan Basic Info -->
      <div class="plan-basic-info">
        <div class="form-row">
          <label class="form-label">{{ $t('sidebar.title') }}</label>
          <input
            v-model="displayData.title"
            type="text"
            class="form-input"
            :class="{ error: titleError }"
            :placeholder="$t('sidebar.titlePlaceholder')"
            @input="handleTitleInput"
          />
          <!-- Inline validation message for title -->
          <div v-if="titleError" class="field-error-message">
            <Icon icon="carbon:warning" width="12" />
            {{ titleError }}
          </div>
        </div>

        <!-- Service Group -->
        <div class="form-row">
          <label class="form-label">{{ $t('mcpService.serviceGroup') }}</label>
          <div class="service-group-autocomplete">
            <input
              type="text"
              v-model="serviceGroup"
              @input="handleServiceGroupInputWithEditing"
              @focus="showGroupSuggestions = true"
              @blur="handleServiceGroupBlur"
              :placeholder="$t('mcpService.serviceGroupPlaceholder')"
              class="form-input"
            />
            <!-- Filtered group suggestions dropdown -->
            <div
              v-if="showGroupSuggestions && filteredServiceGroups.length > 0"
              class="service-group-dropdown"
            >
              <div
                v-for="group in filteredServiceGroups"
                :key="group"
                class="service-group-option"
                @mousedown="selectServiceGroup(group)"
              >
                {{ group }}
              </div>
            </div>
          </div>
          <div class="field-description">{{ $t('mcpService.serviceGroupDescription') }}</div>
        </div>
      </div>

      <!-- Steps Editor -->
      <div class="steps-section">
        <div class="steps-container">
          <div v-for="(step, index) in displayData.steps" :key="index" class="step-item">
            <div class="step-content">
              <!-- Step Requirement -->
              <div class="form-row">
                <label class="form-label">{{ $t('sidebar.stepRequirement') }}</label>
                <textarea
                  :value="step.stepRequirement || ''"
                  @input="e => handleStepRequirementInput(e, index)"
                  class="form-textarea auto-resize"
                  :placeholder="$t('sidebar.stepRequirementPlaceholder')"
                  rows="8"
                ></textarea>
              </div>

              <!-- Terminate Columns -->
              <div class="form-row">
                <label class="form-label">{{ $t('sidebar.terminateColumns') }}</label>

                <textarea
                  :value="step.terminateColumns || ''"
                  @input="e => handleTerminateColumnsInput(e, index)"
                  class="form-textarea auto-resize"
                  :placeholder="$t('sidebar.terminateColumnsPlaceholder')"
                  rows="1"
                ></textarea>

                <!-- Preview Section -->
                <div
                  v-if="step.terminateColumns && step.terminateColumns.trim()"
                  class="preview-section"
                >
                  <div class="preview-label">{{ $t('sidebar.preview') }}:</div>
                  <div class="preview-content">
                    <div class="preview-text">
                      {{ $t('sidebar.systemWillReturnListWithTableHeaderFormat') }}:
                      <span class="preview-table-header">{{
                        formatTableHeader(step.terminateColumns)
                      }}</span>
                    </div>
                  </div>
                </div>
              </div>

              <!-- Model Name -->
              <div class="form-row">
                <label class="form-label">{{ $t('sidebar.modelName') }}</label>
                <div class="model-selector-wrapper">
                  <div
                    class="model-selector"
                    :class="{
                      'is-open': isModelDropdownOpenForStep(index),
                      'is-disabled': isLoadingModels,
                    }"
                  >
                    <!-- Input field with dropdown arrow -->
                    <div class="model-input-wrapper">
                      <input
                        :value="getModelDisplayValue(index)"
                        type="text"
                        class="form-input model-search-input"
                        :placeholder="getModelPlaceholder(index)"
                        :disabled="isLoadingModels"
                        @click.stop="openModelDropdown(index)"
                        @focus="openModelDropdown(index)"
                        @input="handleModelSearchInput($event, index)"
                        @keydown.escape="closeModelDropdown(index)"
                        @keydown.enter.prevent="selectFirstFilteredModel(index)"
                        @keydown.down.prevent="navigateModelDown(index)"
                        @keydown.up.prevent="navigateModelUp(index)"
                      />
                      <Icon
                        icon="carbon:chevron-down"
                        width="14"
                        class="dropdown-arrow"
                        :class="{ 'is-open': isModelDropdownOpenForStep(index) }"
                        @click.stop="toggleModelDropdown(index)"
                      />
                    </div>

                    <!-- Dropdown list -->
                    <div
                      v-if="isModelDropdownOpenForStep(index)"
                      class="model-dropdown"
                      @click.stop
                    >
                      <!-- Loading state -->
                      <div v-if="isLoadingModels" class="dropdown-item disabled">
                        {{ $t('sidebar.loading') }}
                      </div>

                      <!-- Error state -->
                      <div v-else-if="modelsLoadError" class="dropdown-item disabled error">
                        {{ $t('sidebar.modelLoadError') }}
                      </div>

                      <!-- No models found -->
                      <div
                        v-else-if="getFilteredModelsForStep(index).length === 0"
                        class="dropdown-item disabled"
                      >
                        {{ $t('sidebar.noModelsFound') }}
                      </div>

                      <!-- Model options -->
                      <div
                        v-for="(model, idx) in getFilteredModelsForStep(index)"
                        :key="model.value"
                        class="dropdown-item"
                        :class="{
                          'is-selected': step.modelName === model.value,
                          'is-highlighted': getHighlightedIndex(index) === idx,
                        }"
                        @click="selectModelForStep(model.value, index)"
                        @mouseenter="setHighlightedIndex(index, idx)"
                      >
                        {{ model.value }}
                        <Icon
                          v-if="step.modelName === model.value"
                          icon="carbon:checkmark"
                          width="12"
                          class="check-icon"
                        />
                      </div>

                      <!-- Default empty option -->
                      <div
                        class="dropdown-item"
                        :class="{
                          'is-selected': !step.modelName,
                          'is-highlighted': getHighlightedIndex(index) === -1,
                        }"
                        @click="selectModelForStep('', index)"
                        @mouseenter="setHighlightedIndex(index, -1)"
                      >
                        {{ $t('sidebar.noModelSelected') }}
                        <Icon
                          v-if="!step.modelName"
                          icon="carbon:checkmark"
                          width="12"
                          class="check-icon"
                        />
                      </div>
                    </div>
                  </div>

                  <!-- Error refresh button -->
                  <button
                    v-if="modelsLoadError"
                    @click="loadAvailableModels"
                    class="btn btn-sm btn-danger"
                    :title="$t('sidebar.retryLoadModels')"
                  >
                    <Icon icon="carbon:warning" width="14" />
                    {{ $t('sidebar.retry') }}
                  </button>
                </div>

                <!-- Error message -->
                <div v-if="modelsLoadError" class="error-message">
                  <Icon icon="carbon:warning" width="12" />
                  {{ modelsLoadError }}
                </div>
              </div>

              <!-- Tool Selection -->
              <div class="form-row">
                <AssignedTools
                  :title="$t('sidebar.selectedTools')"
                  :selected-tool-ids="(step as StepConfigWithTools).selectedToolKeys || []"
                  :add-button-text="$t('sidebar.addRemoveTools')"
                  :empty-text="$t('sidebar.noTools')"
                  :use-grid-layout="true"
                  @add-tools="showToolSelectionModal(index)"
                  @tools-filtered="
                    (filteredTools: string[]) => handleToolsFiltered(index, filteredTools)
                  "
                />
              </div>
            </div>
          </div>

          <!-- Empty State -->
          <div v-if="displayData.steps.length === 0" class="empty-steps">
            <Icon icon="carbon:add-alt" width="48" class="empty-icon" />
            <p class="empty-text">{{ $t('sidebar.noSteps') }}</p>
            <button class="btn btn-primary btn-add-step" @click="handleAddStep">
              <Icon icon="carbon:add" width="16" />
              {{ $t('sidebar.addFirstStep') }}
            </button>
          </div>
        </div>
      </div>

      <!-- JSON Preview (Optional) -->
      <div class="json-preview" v-if="showJsonPreview">
        <div class="preview-header">
          <label class="form-label">{{ $t('sidebar.jsonPreview') }}</label>
          <button @click="closeJsonPreview" class="btn btn-xs">
            <Icon icon="carbon:close" width="12" />
          </button>
        </div>
        <pre class="json-code">{{ generatedJsonOutput }}</pre>
      </div>

      <!-- Toggle JSON Preview -->
      <div class="editor-footer">
        <button @click="toggleJsonPreview" class="btn btn-sm btn-secondary">
          <Icon icon="carbon:code" width="14" />
          {{ showJsonPreview ? $t('sidebar.hideJson') : $t('sidebar.showJson') }}
        </button>
        <div class="section-actions">
          <button
            class="btn btn-sm"
            @click="handleCopyPlan"
            :disabled="isGenerating || isExecuting"
            :title="$t('sidebar.copyPlan')"
          >
            <Icon icon="carbon:copy" width="14" />
            {{ $t('sidebar.copyPlan') }}
          </button>
          <button
            class="btn btn-sm"
            @click="handleRollback"
            :disabled="!templateConfig.canRollback.value"
            :title="$t('sidebar.rollback')"
          >
            <Icon icon="carbon:undo" width="14" />
          </button>
          <button
            class="btn btn-sm"
            @click="handleRestore"
            :disabled="!templateConfig.canRestore.value"
            :title="$t('sidebar.restore')"
          >
            <Icon icon="carbon:redo" width="14" />
          </button>
          <button
            class="btn btn-primary"
            @click="handleSave"
            :disabled="isGenerating || isExecuting"
          >
            <Icon icon="carbon:save" width="14" />
            Save
          </button>
        </div>
      </div>
    </div>

    <!-- Tool Selection Modal -->
    <ToolSelectionModal
      v-model="showToolModal"
      :selected-tool-ids="
        currentStepIndex >= 0
          ? (displayData.steps[currentStepIndex] as StepConfigWithTools)?.selectedToolKeys || []
          : []
      "
      @confirm="handleToolSelectionConfirm"
    />

    <!-- Copy Plan Modal -->
    <div v-if="showCopyPlanModal" class="modal-overlay" @mousedown="handleModalOverlayClick">
      <div class="modal-content" @mousedown.stop>
        <div class="modal-header">
          <h3>{{ $t('sidebar.copyPlan') }}</h3>
          <button class="close-btn" @click="closeCopyPlanModal">
            <Icon icon="carbon:close" width="16" />
          </button>
        </div>
        <div class="modal-body">
          <div class="form-row">
            <label class="form-label">{{ $t('sidebar.newPlanTitle') }}</label>
            <input
              v-model="newPlanTitle"
              type="text"
              class="form-input"
              :placeholder="$t('sidebar.enterNewPlanTitle')"
              @keyup.enter="confirmCopyPlan"
            />
          </div>
        </div>
        <div class="modal-footer">
          <button class="btn btn-secondary" @click="closeCopyPlanModal">
            {{ $t('common.cancel') }}
          </button>
          <button
            class="btn btn-primary"
            @click="confirmCopyPlan"
            :disabled="!newPlanTitle.trim() || isCopyingPlan"
          >
            <Icon v-if="isCopyingPlan" icon="carbon:loading" width="16" class="spinning" />
            {{ isCopyingPlan ? $t('sidebar.copying') : $t('sidebar.copyPlan') }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ConfigApiService, type ModelOption } from '@/api/config-api-service'
import { PlanTemplateApiService } from '@/api/plan-template-with-tool-api-service'
import { ToolApiService } from '@/api/tool-api-service'
import AssignedTools from '@/components/shared/AssignedTools.vue'
import ToolSelectionModal from '@/components/tool-selection-modal/ToolSelectionModal.vue'
import { usePlanTemplateConfigSingleton } from '@/composables/usePlanTemplateConfig'
import { useToast } from '@/plugins/useToast'
import { templateStore } from '@/stores/templateStore'
import type { PlanTemplateConfigVO, StepConfig } from '@/types/plan-template'
import { Icon } from '@iconify/vue'
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'

// Extended StepConfig with selectedToolKeys for UI state
interface StepConfigWithTools extends StepConfig {
  selectedToolKeys?: string[]
}

const { t } = useI18n()
const toast = useToast()

// Define props interface specific to JsonEditorV2
interface JsonEditorV2Props {
  isGenerating?: boolean
  isExecuting?: boolean
}

// Props
const { isGenerating = false, isExecuting = false } = defineProps<JsonEditorV2Props>()

// Get template config singleton
const templateConfig = usePlanTemplateConfigSingleton()

// Display data - sync with templateConfig
const displayData = reactive<{
  title: string
  steps: StepConfigWithTools[]
}>({
  title: '',
  steps: [],
})

// JSON preview state
const showJsonPreview = ref(false)

// Service group autocomplete state
const showGroupSuggestions = ref(false)
const availableServiceGroups = ref<string[]>([])
const isLoadingGroups = ref(false)
const serviceGroup = ref('')

// Dynamically generate JSON output from templateConfig (not cached, regenerated each time)
const generatedJsonOutput = computed(() => {
  return templateConfig.generateJsonString()
})

// Flag to track if we're syncing from config (to avoid setting modification flag during load)
const isSyncingFromConfig = ref(false)

// Timeout for resetting editing flag (debounce)
let editingTimeout: ReturnType<typeof setTimeout> | null = null

// Helper to set editing flag with debounce (uses templateConfig.isUserUpdating)
const setEditingFlag = () => {
  templateConfig.isUserUpdating.value = true
  if (editingTimeout) {
    clearTimeout(editingTimeout)
  }
  editingTimeout = setTimeout(() => {
    templateConfig.isUserUpdating.value = false
    editingTimeout = null
  }, 500)
}

// Sync displayData with templateConfig
const syncDisplayDataFromConfig = () => {
  // Don't sync if user is actively editing to prevent losing unsaved changes
  if (templateConfig.isUserUpdating.value) {
    console.log('[JsonEditorV2] syncDisplayDataFromConfig skipped: isUserUpdating is true')
    return
  }

  console.log('[JsonEditorV2] syncDisplayDataFromConfig called')
  isSyncingFromConfig.value = true
  try {
    const config = templateConfig.getConfig()
    console.log('[JsonEditorV2] Syncing displayData with config:', {
      title: config.title,
      stepsCount: config.steps?.length || 0,
      serviceGroup: config.serviceGroup,
    })
    // Only update title if:
    // 1. config.title has a value (not empty), OR
    // 2. displayData.title is empty (user hasn't started typing)
    // This prevents resetting the title input when user is typing
    if (config.title?.trim() || !displayData.title?.trim()) {
      displayData.title = config.title || ''
    }
    // Deep copy steps to avoid reference issues
    displayData.steps = (config.steps || []).map(step => ({ ...step }))
    // Sync service group
    serviceGroup.value = config.serviceGroup || ''
    console.log('[JsonEditorV2] displayData synced:', {
      title: displayData.title,
      stepsCount: displayData.steps.length,
      serviceGroup: serviceGroup.value,
    })
  } finally {
    // Use nextTick to ensure the watch doesn't trigger during sync
    setTimeout(() => {
      isSyncingFromConfig.value = false
    }, 0)
  }
}

// Sync displayData changes back to templateConfig
// DISABLED: Only sync on save to prevent flickering during input
// watch(
//   () => displayData,
//   () => {
//     // Skip if we're syncing from config (initial load)
//     if (isSyncingFromConfig.value) {
//       return
//     }

//     // Update templateConfig when displayData changes
//     templateConfig.setTitle(displayData.title)
//     templateConfig.setSteps(displayData.steps)

//     // Mark task requirements as modified if there's a selected template
//     // Note: This is a fallback - the @input handlers should handle most cases
//     if (templateConfig.currentPlanTemplateId.value) {
//       templateStore.hasTaskRequirementModified = true
//       console.log(
//         '[JsonEditorV2] Task requirements modified (via watch), hasTaskRequirementModified set to true'
//       )
//     }
//   },
//   { deep: true }
// )

// Manual sync function to be called on save
const syncDisplayDataToTemplateConfig = () => {
  // Set flag to prevent watcher from syncing back during this update
  isSyncingFromConfig.value = true
  try {
    templateConfig.setTitle(displayData.title)
    templateConfig.setSteps(displayData.steps)
    if (templateConfig.currentPlanTemplateId.value) {
      templateStore.hasTaskRequirementModified = true
    }
  } finally {
    setTimeout(() => {
      isSyncingFromConfig.value = false
    }, 0)
  }
}

// JSON preview functions
const toggleJsonPreview = () => {
  showJsonPreview.value = !showJsonPreview.value
}

const closeJsonPreview = () => {
  showJsonPreview.value = false
}

// Action handlers (moved from json-editor-logic.ts to usePlanTemplateConfig)
const handleRollback = () => {
  try {
    // Clear editing flag and timeout to allow sync
    if (editingTimeout) {
      clearTimeout(editingTimeout)
      editingTimeout = null
    }
    templateConfig.isUserUpdating.value = false
    templateConfig.rollbackVersion()
  } catch (error) {
    console.error('Error during rollback operation:', error)
    toast.error(t('sidebar.rollbackFailed') || 'Rollback failed')
  }
}

const handleRestore = () => {
  try {
    // Clear editing flag and timeout to allow sync
    if (editingTimeout) {
      clearTimeout(editingTimeout)
      editingTimeout = null
    }
    templateConfig.isUserUpdating.value = false
    templateConfig.restoreVersion()
  } catch (error) {
    console.error('Error during restore operation:', error)
    toast.error(t('sidebar.restoreFailed') || 'Restore failed')
  }
}

const handleSave = async () => {
  try {
    if (!templateConfig.selectedTemplate.value) {
      toast.error(t('sidebar.selectPlanFirst'))
      return
    }

    // Clear editing flag and timeout before saving to ensure proper sync
    if (editingTimeout) {
      clearTimeout(editingTimeout)
      editingTimeout = null
    }
    templateConfig.isUserUpdating.value = false

    // Sync displayData to templateConfig before validation and save
    // This ensures all user input is synchronized before saving
    syncDisplayDataToTemplateConfig()

    // Validate config
    const validation = templateConfig.validate()
    if (!validation.isValid) {
      toast.error(
        'Invalid format, please correct and save.\nErrors: ' + validation.errors.join(', ')
      )
      return
    }

    const planTemplateId = templateConfig.selectedTemplate.value.planTemplateId
    if (!planTemplateId) {
      toast.error('Plan template ID is required')
      return
    }

    // Save using templateConfig (this already calls PlanTemplateApiService.createOrUpdatePlanTemplateWithTool)
    // The save() method already calls load() which reloads versions from backend
    const success = await templateConfig.save()
    if (!success) {
      toast.error('Failed to save plan template')
      return
    }

    // Update versions after save (adds current content to local version history)
    const content = templateConfig.generateJsonString().trim()
    templateConfig.updateVersionsAfterSave(content)

    // Get actual version count after update (save() already reloaded versions from backend, then updateVersionsAfterSave adds one more)
    const versionCount = templateConfig.planVersions.value.length

    // Reset modification flag after successful save
    templateStore.hasTaskRequirementModified = false

    // Refresh sidebar template list to reflect the saved changes
    await templateStore.loadPlanTemplateList()

    toast.success(t('sidebar.saveSuccess', { message: 'Plan saved successfully', versionCount }))
  } catch (error: unknown) {
    console.error('Failed to save plan modifications:', error)
    const message = error instanceof Error ? error.message : t('sidebar.saveFailed')
    toast.error(message)
    throw error // Re-throw to allow caller to handle
  }
}

// Error state
const titleError = ref<string>('')

// Handle step requirement input
const handleStepRequirementInput = (e: Event, stepIndex: number) => {
  setEditingFlag()
  const step = displayData.steps[stepIndex]
  if (step) {
    step.stepRequirement = (e.target as HTMLTextAreaElement).value
  }
  autoResizeTextarea(e)
  // Only update displayData, don't sync to templateConfig or trigger any watchers
  // Sync will happen on save via syncDisplayDataToTemplateConfig()
}

// Handle terminate columns input
const handleTerminateColumnsInput = (e: Event, stepIndex: number) => {
  setEditingFlag()
  const step = displayData.steps[stepIndex]
  if (step) {
    step.terminateColumns = (e.target as HTMLTextAreaElement).value
  }
  autoResizeTextarea(e)
  // Only update displayData, don't sync to templateConfig or trigger any watchers
  // Sync will happen on save via syncDisplayDataToTemplateConfig()
}

// Add step handler
const handleAddStep = () => {
  const newStep: StepConfigWithTools = {
    stepRequirement: '',
    agentName: '',
    modelName: '',
    terminateColumns: '',
    selectedToolKeys: [],
  }
  displayData.steps.push(newStep)
  // Sync to templateConfig - no guard needed since setSteps() doesn't trigger watcher (needsFullRefresh is false)
  templateConfig.setSteps(displayData.steps)
  console.log('[JsonEditorV2] Added new step, total steps:', displayData.steps.length)
}

// Model selection state
const availableModels = ref<ModelOption[]>([])
const isLoadingModels = ref(false)
const modelsLoadError = ref<string>('')

// Per-step state for dropdown (stepIndex -> state)
const modelSearchFilters = ref<Map<number, string>>(new Map())
const openDropdownSteps = ref<Set<number>>(new Set())
const highlightedIndices = ref<Map<number, number>>(new Map())

// Get search filter for a specific step
const getSearchFilter = (stepIndex: number): string => {
  return modelSearchFilters.value.get(stepIndex) ?? ''
}

// Set search filter for a specific step
const setSearchFilter = (stepIndex: number, value: string) => {
  modelSearchFilters.value.set(stepIndex, value)
}

// Filtered models based on search for a specific step
const getFilteredModelsForStep = (stepIndex: number) => {
  const filter = getSearchFilter(stepIndex)
  if (!filter.trim()) {
    return availableModels.value
  }
  const searchTerm = filter.toLowerCase().trim()
  return availableModels.value.filter(
    model =>
      model.value.toLowerCase().includes(searchTerm) ||
      model.label.toLowerCase().includes(searchTerm)
  )
}

// Get display value for a specific step
const getModelDisplayValue = (stepIndex: number): string => {
  const step = displayData.steps[stepIndex]
  const filter = getSearchFilter(stepIndex)
  if (openDropdownSteps.value.has(stepIndex) && filter !== '') {
    return filter
  }
  return step.modelName ?? ''
}

// Get placeholder text based on selected model
const getModelPlaceholder = (stepIndex: number) => {
  if (isLoadingModels.value) {
    return ''
  }
  if (modelsLoadError.value) {
    return ''
  }
  const step = displayData.steps[stepIndex]
  if (step.modelName) {
    return ''
  }
  return (t('sidebar.modelNameDescription') as string) || ''
}

// Check if dropdown is open for a specific step
const isModelDropdownOpenForStep = (stepIndex: number): boolean => {
  return openDropdownSteps.value.has(stepIndex)
}

// Model dropdown functions
const openModelDropdown = (stepIndex: number) => {
  if (!isLoadingModels.value) {
    openDropdownSteps.value.add(stepIndex)
  }
}

const closeModelDropdown = (stepIndex: number) => {
  openDropdownSteps.value.delete(stepIndex)
  highlightedIndices.value.set(stepIndex, -1)
  // Reset search filter to selected model name
  const step = displayData.steps[stepIndex]
  setSearchFilter(stepIndex, step.modelName ?? '')
}

const toggleModelDropdown = (stepIndex: number) => {
  if (isModelDropdownOpenForStep(stepIndex)) {
    closeModelDropdown(stepIndex)
  } else {
    openModelDropdown(stepIndex)
  }
}

const selectModelForStep = (modelName: string, stepIndex: number) => {
  setEditingFlag()
  const step = displayData.steps[stepIndex]
  step.modelName = modelName
  setSearchFilter(stepIndex, modelName)
  closeModelDropdown(stepIndex)
}

// Handle search input
const handleModelSearchInput = (event: Event, stepIndex: number) => {
  setEditingFlag()
  const target = event.target as HTMLInputElement
  setSearchFilter(stepIndex, target.value)
  openModelDropdown(stepIndex)
}

// Get highlighted index for a step
const getHighlightedIndex = (stepIndex: number): number => {
  return highlightedIndices.value.get(stepIndex) ?? -1
}

// Set highlighted index for a step
const setHighlightedIndex = (stepIndex: number, index: number) => {
  highlightedIndices.value.set(stepIndex, index)
}

// Keyboard navigation
const selectFirstFilteredModel = (stepIndex: number) => {
  const highlightedIndex = getHighlightedIndex(stepIndex)
  const filtered = getFilteredModelsForStep(stepIndex)

  // If a specific item is highlighted, select it
  if (highlightedIndex >= 0 && highlightedIndex < filtered.length) {
    selectModelForStep(filtered[highlightedIndex].value, stepIndex)
  } else if (highlightedIndex === -1) {
    // Select empty option
    selectModelForStep('', stepIndex)
  } else if (filtered.length > 0) {
    // Fallback to first item
    selectModelForStep(filtered[0].value, stepIndex)
  } else {
    const step = displayData.steps[stepIndex]
    if (!step.modelName) {
      selectModelForStep('', stepIndex)
    }
  }
}

const navigateModelDown = (stepIndex: number) => {
  if (!isModelDropdownOpenForStep(stepIndex)) {
    openModelDropdown(stepIndex)
    setHighlightedIndex(stepIndex, 0)
    return
  }
  const filtered = getFilteredModelsForStep(stepIndex)
  const totalItems = filtered.length + 1 // +1 for "no model selected" option
  const currentIndex = getHighlightedIndex(stepIndex)
  const newIndex = Math.min(currentIndex + 1, totalItems - 1)
  setHighlightedIndex(stepIndex, newIndex)
}

const navigateModelUp = (stepIndex: number) => {
  if (!isModelDropdownOpenForStep(stepIndex)) {
    openModelDropdown(stepIndex)
    const filtered = getFilteredModelsForStep(stepIndex)
    setHighlightedIndex(stepIndex, filtered.length)
    return
  }
  const currentIndex = getHighlightedIndex(stepIndex)
  const newIndex = Math.max(currentIndex - 1, -1)
  setHighlightedIndex(stepIndex, newIndex)
}

// Click outside to close dropdown
const handleClickOutside = (event: MouseEvent) => {
  const target = event.target as HTMLElement
  // Check if click is inside any model selector
  const modelSelector = target.closest('.model-selector')
  if (!modelSelector) {
    // Close all open dropdowns
    openDropdownSteps.value.clear()
  }
}

// Initialize search filters when steps change
watch(
  () => displayData.steps,
  newSteps => {
    newSteps.forEach((step, index) => {
      if (!modelSearchFilters.value.has(index)) {
        setSearchFilter(index, step.modelName ?? '')
      }
    })
  },
  { deep: true, immediate: true }
)

// Tool selection state
const showToolModal = ref(false)
const currentStepIndex = ref<number>(-1)

// Load available models
const loadAvailableModels = async () => {
  if (isLoadingModels.value) return

  isLoadingModels.value = true
  modelsLoadError.value = ''

  try {
    const response = await ConfigApiService.getAvailableModels()
    availableModels.value = response.options
  } catch (error) {
    console.error('Failed to load models:', error)
    modelsLoadError.value = error instanceof Error ? error.message : 'Failed to load models'
    availableModels.value = []
  } finally {
    isLoadingModels.value = false
  }
}

// Tool selection functions
const showToolSelectionModal = (stepIndex: number) => {
  currentStepIndex.value = stepIndex
  showToolModal.value = true
}

const handleToolSelectionConfirm = (selectedToolIds: string[]) => {
  setEditingFlag()
  if (currentStepIndex.value >= 0 && currentStepIndex.value < displayData.steps.length) {
    // Update the specific step's selected tool keys
    displayData.steps[currentStepIndex.value].selectedToolKeys = [...selectedToolIds]
  }
  showToolModal.value = false
  currentStepIndex.value = -1
}

const handleToolsFiltered = (stepIndex: number, filteredTools: string[]) => {
  setEditingFlag()
  if (stepIndex >= 0 && stepIndex < displayData.steps.length) {
    // Update the step's selected tool keys with filtered tools
    displayData.steps[stepIndex].selectedToolKeys = [...filteredTools]
  }
}

// Copy plan state
const showCopyPlanModal = ref(false)
const newPlanTitle = ref('')
const isCopyingPlan = ref(false)

// Copy plan function
const handleCopyPlan = () => {
  console.log('[JsonEditorV2] Copy plan clicked')

  if (!templateConfig.selectedTemplate.value) {
    console.log('[JsonEditorV2] No template selected, cannot copy')
    toast.error(t('sidebar.selectPlanFirst'))
    return
  }

  newPlanTitle.value =
    (templateConfig.selectedTemplate.value.title ?? t('sidebar.unnamedPlan')) + ' (copy)'
  console.log('[JsonEditorV2] Opening copy plan modal')
  showCopyPlanModal.value = true
}

const closeCopyPlanModal = () => {
  showCopyPlanModal.value = false
  newPlanTitle.value = ''
  isCopyingPlan.value = false
}

// Handle modal overlay click - only close if clicking directly on overlay
const handleModalOverlayClick = (event: MouseEvent) => {
  // Only close if the click target is the overlay itself, not its children
  if (event.target === event.currentTarget) {
    closeCopyPlanModal()
  }
}

const confirmCopyPlan = async () => {
  if (!newPlanTitle.value.trim()) {
    toast.error(t('sidebar.titleRequired'))
    return
  }

  if (!templateConfig.selectedTemplate.value) {
    toast.error(t('sidebar.noPlanToCopy'))
    return
  }

  isCopyingPlan.value = true

  try {
    // Get the current plan config
    const currentConfig = templateConfig.getConfig()

    // Generate a new planTemplateId from backend
    const newPlanTemplateId = await PlanTemplateApiService.generatePlanTemplateId()
    console.log('[JsonEditorV2] Generated plan template ID from backend:', newPlanTemplateId)

    // Exclude toolConfig from the copy to avoid copying service configuration
    const { toolConfig: _toolConfig, ...configWithoutToolConfig } = currentConfig

    // Create a new plan config with all fields copied, only changing the title and using new ID
    const newPlanConfig: PlanTemplateConfigVO = {
      ...configWithoutToolConfig,
      title: newPlanTitle.value.trim(),
      planTemplateId: newPlanTemplateId,
    }

    console.log('[JsonEditorV2] Copying plan without toolConfig:', newPlanConfig)

    const result = await PlanTemplateApiService.createOrUpdatePlanTemplateWithTool(newPlanConfig)

    if (result.success) {
      toast.success(t('sidebar.copyPlanSuccess', { title: newPlanTitle.value.trim() }))
      await templateStore.loadPlanTemplateList()
      closeCopyPlanModal()
    } else {
      toast.error(t('sidebar.copyPlanFailed', { message: 'Failed to copy plan' }))
    }
  } catch (error: unknown) {
    console.error('[JsonEditorV2] Error copying plan:', error)
    // Check if it's a duplicate title error
    if (
      error instanceof Error &&
      (error as Error & { errorCode?: string }).errorCode === 'DUPLICATE_TITLE'
    ) {
      toast.error(t('sidebar.duplicatePlanTitle'))
    } else {
      const message = error instanceof Error ? error.message : 'Unknown error'
      toast.error(t('sidebar.copyPlanFailed', { message: message }))
    }
  } finally {
    isCopyingPlan.value = false
  }
}

// Watch for displayData changes to validate structure
watch(
  () => displayData.title,
  newTitle => {
    // Soft validation for title - show warning but don't block the form
    if (!newTitle?.trim()) {
      titleError.value = 'Title is required field'
    } else {
      titleError.value = ''
    }
  },
  { immediate: true }
)

// Load service group from template config
const loadServiceGroup = () => {
  const group = templateConfig.getServiceGroup() || ''
  serviceGroup.value = group
}

// Watch for templateConfig changes and sync to displayData
watch(
  () => templateConfig.config,
  () => {
    // Only sync when a full refresh is needed (load, setConfig, fromJsonString, reset, version control)
    // Skip sync for partial updates (setTitle, setSteps, etc.) to avoid unnecessary refreshes
    // Don't sync if we're already syncing (prevents circular updates)
    // Don't sync if user is actively editing or programmatic updates are in progress
    if (
      templateConfig.needsFullRefresh.value &&
      !isSyncingFromConfig.value &&
      !templateConfig.isUserUpdating.value
    ) {
      syncDisplayDataFromConfig()
    }
  },
  { deep: true, immediate: true }
)

// Watch for templateConfig changes (when template is loaded)
watch(
  () => templateConfig.currentPlanTemplateId.value,
  (newId, oldId) => {
    console.log('[JsonEditorV2] currentPlanTemplateId changed:', { oldId, newId })
    // Only reset if template actually changed (not initial load)
    if (oldId !== null && oldId !== undefined && newId !== oldId) {
      // Reset UI states when template changes
      modelSearchFilters.value.clear()
      openDropdownSteps.value.clear()
      highlightedIndices.value.clear()
      showToolModal.value = false
      showCopyPlanModal.value = false
      showJsonPreview.value = false
      currentStepIndex.value = -1
      newPlanTitle.value = ''
      isCopyingPlan.value = false
    }
    // Sync displayData when template changes (including when reloading same template)
    // This watch will trigger even when oldId === newId if we temporarily set to null
    console.log('[JsonEditorV2] Calling syncDisplayDataFromConfig from currentPlanTemplateId watch')
    syncDisplayDataFromConfig()
    // Load service group when template changes
    loadServiceGroup()
  },
  { immediate: true }
)

// Watch service group changes and sync to template config
watch(
  () => serviceGroup.value,
  newGroup => {
    // Set a flag to prevent syncDisplayDataFromConfig from running
    isSyncingFromConfig.value = true
    try {
      templateConfig.setServiceGroup(newGroup)
    } finally {
      // Reset the flag after a microtask to prevent the templateConfig watcher from syncing back
      setTimeout(() => {
        isSyncingFromConfig.value = false
      }, 0)
    }
  }
)

// Load available service groups
const loadAvailableServiceGroups = async () => {
  if (isLoadingGroups.value) {
    return
  }

  isLoadingGroups.value = true
  try {
    const groupsSet = new Set<string>()

    // Load service groups from tools
    try {
      const tools = await ToolApiService.getAvailableTools()
      tools.forEach(tool => {
        if (tool.serviceGroup) {
          groupsSet.add(tool.serviceGroup)
        }
      })
    } catch (error) {
      console.error('[JsonEditorV2] Failed to load service groups from tools:', error)
    }

    // Load service groups from plan templates
    try {
      const planTemplates = await PlanTemplateApiService.getAllPlanTemplateConfigVOs()
      planTemplates.forEach(template => {
        if (template.serviceGroup) {
          groupsSet.add(template.serviceGroup)
        }
      })
    } catch (error) {
      console.error('[JsonEditorV2] Failed to load service groups from plan templates:', error)
    }

    availableServiceGroups.value = Array.from(groupsSet).sort()
  } catch (error) {
    console.error('[JsonEditorV2] Failed to load service groups:', error)
    availableServiceGroups.value = []
  } finally {
    isLoadingGroups.value = false
  }
}

// Filtered service groups
const filteredServiceGroups = computed(() => {
  const trimmedGroup = serviceGroup.value.trim()
  if (!trimmedGroup) {
    return availableServiceGroups.value
  }

  const query = trimmedGroup.toLowerCase()
  return availableServiceGroups.value.filter(group => group.toLowerCase().includes(query))
})

// Handle title input
const handleTitleInput = () => {
  setEditingFlag()
}

// Handle service group input
const handleServiceGroupInput = () => {
  showGroupSuggestions.value = true
}

// Handle service group input with editing flag
const handleServiceGroupInputWithEditing = () => {
  setEditingFlag()
  handleServiceGroupInput()
}

// Handle service group blur
const handleServiceGroupBlur = () => {
  setTimeout(() => {
    showGroupSuggestions.value = false
  }, 200)
}

// Select a service group from dropdown
const selectServiceGroup = (group: string) => {
  serviceGroup.value = group
  showGroupSuggestions.value = false
}

// Initialize on mount
onMounted(() => {
  // Sync displayData from templateConfig
  syncDisplayDataFromConfig()

  // Load service group
  loadServiceGroup()

  // Load available service groups
  loadAvailableServiceGroups()

  loadAvailableModels()

  // Add click outside listener
  document.addEventListener('click', handleClickOutside)
})

onUnmounted(() => {
  // Remove click outside listener
  document.removeEventListener('click', handleClickOutside)

  // Clean up editing timeout
  if (editingTimeout) {
    clearTimeout(editingTimeout)
    editingTimeout = null
  }
})

// Expose save method for parent component to call
defineExpose({
  save: handleSave,
})

const autoResizeTextarea = (event: Event) => {
  const textarea = event.target as HTMLTextAreaElement

  textarea.style.height = 'auto'

  const lineHeight = 20
  const lines = Math.ceil(textarea.scrollHeight / lineHeight)

  const minRows = 4
  const maxRows = 12
  const targetRows = Math.max(minRows, Math.min(maxRows, lines))

  const newHeight = targetRows * lineHeight
  textarea.style.height = `${newHeight}px`
  textarea.rows = targetRows

  if (lines > maxRows) {
    textarea.style.overflowY = 'auto'
  } else {
    textarea.style.overflowY = 'hidden'
  }
}

// Format table header preview
const formatTableHeader = (terminateColumns: string): string => {
  if (!terminateColumns.trim()) {
    return ''
  }

  // Split by comma and clean up each column name
  const columns = terminateColumns
    .split(',')
    .map(col => col.trim())
    .filter(col => col.length > 0)

  if (columns.length === 0) {
    return ''
  }

  // Format as |col1|col2|col3|
  return `|${columns.join('|')}|`
}
</script>

<style scoped>
.config-section {
  margin-bottom: 16px;
  background: rgba(255, 255, 255, 0.05);
  border-radius: 8px;
  padding: 12px;
}

.section-header {
  display: flex;
  align-items: center;
  margin-bottom: 12px;
  color: #667eea;
  font-size: 13px;
  font-weight: 600;
  gap: 8px;
}

.section-actions {
  margin-left: auto;
  display: flex;
  gap: 6px;
}

/* Error Section Styles */
.error-section {
  margin-bottom: 16px;
  background: rgba(239, 68, 68, 0.1);
  border: 1px solid rgba(239, 68, 68, 0.3);
  border-radius: 8px;
  padding: 16px;
}

.error-message {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  color: #ef4444;
}

.error-content {
  flex: 1;
}

.error-title {
  font-weight: 600;
  font-size: 14px;
  margin-bottom: 4px;
}

.error-description {
  font-size: 12px;
  color: rgba(239, 68, 68, 0.8);
  line-height: 1.4;
}

/* Visual Editor Styles */
.visual-editor {
  background: rgba(0, 0, 0, 0.2);
  border-radius: 8px;
  padding: 16px;
  border: 1px solid rgba(255, 255, 255, 0.1);
}

.plan-basic-info {
  margin-bottom: 20px;
  padding-bottom: 16px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.1);
}

.form-row {
  margin-bottom: 12px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.form-label {
  font-size: 10px;
  font-weight: 600;
  color: rgba(255, 255, 255, 0.9);
}

.field-description {
  font-size: 9px;
  color: rgba(255, 255, 255, 0.5);
  line-height: 1.4;
  margin-top: 2px;
}

.form-input,
.form-textarea {
  padding: 8px 12px;
  border: 1px solid rgba(255, 255, 255, 0.2);
  border-radius: 6px;
  background: rgba(0, 0, 0, 0.3);
  color: white;
  font-size: 11px;
  font-family: inherit;
  transition: all 0.2s ease;
}

.form-input:focus,
.form-textarea:focus {
  outline: none;
  border-color: #667eea;
  box-shadow: 0 0 0 2px rgba(102, 126, 234, 0.2);
}

/* Error state for form inputs */
.form-input.error,
.form-textarea.error {
  border-color: #ef4444;
  box-shadow: 0 0 0 2px rgba(239, 68, 68, 0.2);
}

/* Field error message */
.field-error-message {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 10px;
  color: #ef4444;
  margin-top: 4px;
  padding: 4px 8px;
  background: rgba(239, 68, 68, 0.1);
  border-radius: 4px;
  border: 1px solid rgba(239, 68, 68, 0.2);
}

.readonly-input {
  background: rgba(255, 255, 255, 0.02);
  border: 1px solid rgba(255, 255, 255, 0.05);
  color: rgba(255, 255, 255, 0.6);
  cursor: not-allowed;
  font-family: 'Monaco', 'Menlo', 'Ubuntu Mono', monospace;
  font-size: 12px;
}

.readonly-input:focus {
  border-color: rgba(255, 255, 255, 0.05);
  box-shadow: none;
}

.form-textarea {
  resize: vertical;
  min-height: 20px;
  font-family: 'Monaco', 'Menlo', 'Ubuntu Mono', monospace;
  line-height: 1.4;
}

.form-textarea.auto-resize {
  resize: none;
  transition: height 0.2s ease;
  overflow-y: auto;
  max-height: 240px;
}

/* Model Selector Styles */
.model-selector-wrapper {
  display: flex;
  gap: 8px;
  align-items: flex-start;
}

.model-selector {
  position: relative;
  flex: 1;
}

.model-input-wrapper {
  position: relative;
  display: flex;
  align-items: center;
}

.model-search-input {
  flex: 1;
  padding-right: 32px;
}

.dropdown-arrow {
  position: absolute;
  right: 12px;
  color: rgba(255, 255, 255, 0.5);
  pointer-events: none;
  transition: transform 0.2s ease;
}

.dropdown-arrow.is-open {
  transform: rotate(180deg);
}

.model-dropdown {
  position: absolute;
  top: 100%;
  left: 0;
  right: 0;
  margin-top: 4px;
  background: rgba(0, 0, 0, 0.95);
  border: 1px solid rgba(255, 255, 255, 0.2);
  border-radius: 6px;
  max-height: 200px;
  overflow-y: auto;
  z-index: 1000;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.3);
}

.dropdown-item {
  padding: 8px 12px;
  font-size: 11px;
  color: rgba(255, 255, 255, 0.9);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: space-between;
  transition: all 0.2s ease;
  border-bottom: 1px solid rgba(255, 255, 255, 0.05);
}

.dropdown-item:last-child {
  border-bottom: none;
}

.dropdown-item:hover,
.dropdown-item.is-highlighted {
  background: rgba(102, 126, 234, 0.2);
  color: white;
}

.dropdown-item.is-selected {
  background: rgba(102, 126, 234, 0.15);
  color: #667eea;
  font-weight: 500;
}

.dropdown-item.disabled {
  color: rgba(255, 255, 255, 0.5);
  cursor: not-allowed;
  font-style: italic;
}

.dropdown-item.disabled.error {
  color: #ef4444;
}

.check-icon {
  color: #667eea;
  margin-left: 8px;
}

.model-selector.is-disabled .model-search-input {
  opacity: 0.5;
  cursor: not-allowed;
}

/* Steps Section */
.steps-section {
  margin-bottom: 20px;
}

.error-message {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 10px;
  color: #ef4444;
  margin-top: 4px;
  padding: 4px 8px;
  background: rgba(239, 68, 68, 0.1);
  border-radius: 4px;
  border: 1px solid rgba(239, 68, 68, 0.2);
}

.steps-container {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.step-item {
  background: rgba(0, 0, 0, 0.3);
  border: 1px solid rgba(255, 255, 255, 0.1);
  border-radius: 8px;
  overflow: hidden;
}

.step-content {
  padding: 16px;
}

/* Empty State */
.empty-steps {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 40px 20px;
  text-align: center;
  gap: 16px;
  min-height: 200px;
}

.empty-steps .empty-icon {
  color: rgba(255, 255, 255, 0.4);
  margin-bottom: 8px;
}

.empty-steps .empty-text {
  color: rgba(255, 255, 255, 0.6);
  font-size: 14px;
  margin: 0;
}

.empty-steps .btn-add-step {
  margin-top: 8px;
  padding: 12px 24px;
  font-size: 14px;
  font-weight: 600;
  min-width: 180px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  box-shadow: 0 4px 12px rgba(102, 126, 234, 0.3);
  transition: all 0.3s ease;
}

.empty-steps .btn-add-step:hover {
  transform: translateY(-2px);
  box-shadow: 0 6px 16px rgba(102, 126, 234, 0.4);
}

/* JSON Preview */
.json-preview {
  margin-bottom: 16px;
  background: rgba(0, 0, 0, 0.4);
  border-radius: 6px;
  overflow: hidden;
}

.preview-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 12px;
  background: rgba(255, 255, 255, 0.05);
  border-bottom: 1px solid rgba(255, 255, 255, 0.1);
}

.json-code {
  padding: 12px;
  margin: 0;
  font-family: 'Monaco', 'Menlo', 'Ubuntu Mono', monospace;
  font-size: 10px;
  color: rgba(255, 255, 255, 0.8);
  background: transparent;
  overflow-x: auto;
  white-space: pre-wrap;
  word-break: break-word;
}

.editor-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding-top: 12px;
  border-top: 1px solid rgba(255, 255, 255, 0.1);
}

/* Button Styles */
.btn {
  padding: 6px 12px;
  border: none;
  border-radius: 4px;
  font-size: 12px;
  font-weight: 500;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  transition: all 0.2s ease;
  background: rgba(255, 255, 255, 0.1);
  color: rgba(255, 255, 255, 0.8);
}

.btn:hover:not(:disabled) {
  background: rgba(255, 255, 255, 0.2);
  color: white;
  transform: translateY(-1px);
}

.btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
  transform: none !important;
  box-shadow: none !important;
}

.btn-sm {
  padding: 4px 8px;
  font-size: 11px;
}

.btn-xs {
  padding: 2px 4px;
  font-size: 10px;
  min-width: auto;
}

.btn-primary {
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  color: white;
}

.btn-primary:hover:not(:disabled) {
  background: linear-gradient(135deg, #5a6fd8 0%, #6a4190 100%);
  box-shadow: 0 2px 8px rgba(102, 126, 234, 0.3);
}

.btn-secondary {
  background: rgba(255, 255, 255, 0.05);
  color: rgba(255, 255, 255, 0.7);
}

.btn-danger {
  background: linear-gradient(135deg, #ef4444 0%, #dc2626 100%);
  color: white;
}

.btn-danger:hover:not(:disabled) {
  background: linear-gradient(135deg, #dc2626 0%, #b91c1c 100%);
  box-shadow: 0 2px 8px rgba(239, 68, 68, 0.3);
}

/* Preview Section Styles */
.preview-section {
  margin-top: 8px;
  padding: 8px 12px;
  background: rgba(102, 126, 234, 0.1);
  border: 1px solid rgba(102, 126, 234, 0.2);
  border-radius: 6px;
  font-size: 10px;
}

.preview-label {
  font-weight: 600;
  color: #667eea;
  margin-bottom: 4px;
  font-size: 9px;
}

.preview-content {
  color: white;
}

.preview-text {
  line-height: 1.4;
  color: white;
}

.preview-table-header {
  font-family: 'Monaco', 'Menlo', 'Ubuntu Mono', monospace;
  background: rgba(0, 0, 0, 0.3);
  padding: 2px 6px;
  border-radius: 3px;
  color: #ef4444;
  font-weight: 600;
  border: 1px solid rgba(239, 68, 68, 0.3);
}

/* Copy Plan Modal Styles */
.modal-overlay {
  position: fixed;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
}

.modal-content {
  background: #1a1a1a;
  border-radius: 8px;
  padding: 0;
  min-width: 400px;
  max-width: 500px;
  border: 1px solid rgba(255, 255, 255, 0.1);
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.3);
}

.modal-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 20px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.1);
}

.modal-header h3 {
  margin: 0;
  color: white;
  font-size: 16px;
  font-weight: 600;
}

.close-btn {
  background: transparent;
  border: none;
  color: rgba(255, 255, 255, 0.7);
  cursor: pointer;
  padding: 4px;
  border-radius: 4px;
  transition: all 0.2s ease;
}

.close-btn:hover {
  background: rgba(255, 255, 255, 0.1);
  color: white;
}

.modal-body {
  padding: 20px;
}

.modal-footer {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  padding: 16px 20px;
  border-top: 1px solid rgba(255, 255, 255, 0.1);
}

.modal-footer .form-row {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.modal-footer .form-label {
  font-size: 14px;
  font-weight: 500;
  color: rgba(255, 255, 255, 0.9);
}

.modal-footer .form-input {
  padding: 10px 12px;
  border: 1px solid rgba(255, 255, 255, 0.2);
  border-radius: 6px;
  background: rgba(0, 0, 0, 0.3);
  color: white;
  font-size: 13px;
  transition: all 0.2s ease;
}

.modal-footer .form-input:focus {
  outline: none;
  border-color: #667eea;
  box-shadow: 0 0 0 2px rgba(102, 126, 234, 0.2);
}

.modal-footer .btn-secondary {
  background: rgba(255, 255, 255, 0.1);
  color: white;
}

.modal-footer .btn-secondary:hover:not(:disabled) {
  background: rgba(255, 255, 255, 0.2);
}

.modal-footer .btn-primary {
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  color: white;
}

.modal-footer .btn-primary:hover:not(:disabled) {
  background: linear-gradient(135deg, #5566dd 0%, #653b91 100%);
  box-shadow: 0 4px 12px rgba(102, 126, 234, 0.3);
}

.modal-footer .btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.modal-footer .spinning {
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

/* Service Group Autocomplete Styles */
.service-group-autocomplete {
  position: relative;
  width: 100%;
}

.service-group-autocomplete .form-input {
  width: 100%;
  padding: 8px 12px;
  border: 1px solid rgba(255, 255, 255, 0.2);
  border-radius: 6px;
  background: rgba(0, 0, 0, 0.3);
  color: white;
  font-size: 11px;
  font-family: inherit;
  transition: all 0.2s ease;
}

.service-group-autocomplete .form-input:focus {
  outline: none;
  border-color: #667eea;
  box-shadow: 0 0 0 2px rgba(102, 126, 234, 0.2);
  background: rgba(0, 0, 0, 0.4);
}

.service-group-dropdown {
  position: absolute;
  top: 100%;
  left: 0;
  right: 0;
  margin-top: 4px;
  background: rgba(0, 0, 0, 0.95);
  backdrop-filter: blur(10px);
  border: 1px solid rgba(255, 255, 255, 0.2);
  border-radius: 6px;
  max-height: 200px;
  overflow-y: auto;
  z-index: 1000;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.3);
}

.service-group-option {
  padding: 8px 12px;
  font-size: 11px;
  color: rgba(255, 255, 255, 0.9);
  cursor: pointer;
  transition: all 0.2s ease;
  border-bottom: 1px solid rgba(255, 255, 255, 0.05);
}

.service-group-option:last-child {
  border-bottom: none;
}

.service-group-option:hover {
  background: rgba(102, 126, 234, 0.2);
  color: white;
}

.service-group-option:active {
  background: rgba(102, 126, 234, 0.3);
}
</style>
