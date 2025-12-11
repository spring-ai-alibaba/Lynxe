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
  <div class="right-panel">
    <div class="preview-header">
      <div class="preview-tabs">
        <!-- Func-Agent Config tab -->
        <div
          class="tab-item"
          :class="{ active: activeTab === 'config' }"
          @click="activeTab = 'config'"
        >
          <Icon icon="carbon:settings" />
          <span>{{ t('sidebar.configuration') }}</span>
        </div>
        <!-- Step Execution Details tab -->
        <div
          class="tab-item"
          :class="{ active: activeTab === 'details' }"
          @click="activeTab = 'details'"
        >
          <Icon icon="carbon:events" />
          <span>{{ t('rightPanel.stepExecutionDetails') }}</span>
        </div>
        <!-- File Browser tab -->
        <div
          class="tab-item"
          :class="{ active: activeTab === 'files' }"
          @click="activeTab = 'files'"
        >
          <Icon icon="carbon:folder" />
          <span>{{ t('fileBrowser.title') }}</span>
        </div>
      </div>
    </div>

    <div class="preview-content">
      <!-- Func-Agent Config -->
      <div v-if="activeTab === 'config'" class="config-tab-content">
        <div v-if="templateConfig.selectedTemplate.value" class="config-container">
          <!-- Template Info Header -->
          <div class="template-info-header">
            <div class="template-info">
              <h3>
                {{ templateConfig.selectedTemplate.value.title || t('sidebar.unnamedPlan') }}
              </h3>
              <span class="template-id"
                >ID: {{ templateConfig.selectedTemplate.value.planTemplateId }}</span
              >
            </div>
            <button class="back-to-list-btn" @click="sidebarStore.switchToTab('list')">
              <Icon icon="carbon:arrow-left" width="16" />
            </button>
          </div>

          <!-- JSON Editor -->
          <JsonEditorV2 />

          <!-- Execution Controller -->
          <ExecutionController />
        </div>
        <div v-else class="no-template-selected">
          <div class="action-buttons">
            <button class="new-task-btn" @click="handleCreateNewPlan">
              <Icon icon="carbon:add" width="16" />
              {{ t('rightPanel.newFuncAgentPlan') }}
            </button>
            <label class="new-task-btn" :title="t('rightPanel.importExistingPlan')">
              <Icon icon="carbon:import" width="16" />
              {{ t('rightPanel.importExistingPlan') }}
              <input
                type="file"
                accept=".json"
                @change="handleImportExistingPlan"
                style="display: none"
              />
            </label>
          </div>
          <p class="import-description">
            {{ t('rightPanel.importDescription') }}
            <a
              href="https://github.com/Lynxe-public/Lynxe-public-prompts"
              target="_blank"
              rel="noopener noreferrer"
              class="prompt-library-link"
            >
              {{ t('rightPanel.promptLibrary') }}
            </a>
            {{ t('rightPanel.importDescriptionSuffix') }}
          </p>
        </div>
      </div>

      <!-- Step Execution Details -->
      <div v-if="activeTab === 'details'" class="step-details">
        <!-- Step basic information -->
        <div v-if="selectedStep" class="step-info">
          <h3>
            {{
              selectedStep.title ||
              selectedStep.description ||
              t('rightPanel.defaultStepTitle', { number: 1 })
            }}
          </h3>

          <div class="agent-info" v-if="selectedStep.agentExecution">
            <div class="info-item">
              <span class="label">{{ t('rightPanel.callingModel') }}:</span>
              <span class="value">{{ selectedStep.agentExecution.modelName }}</span>
            </div>
          </div>

          <div class="execution-status">
            <div class="status-item">
              <Icon
                icon="carbon:checkmark-filled"
                v-if="selectedStep.completed"
                class="status-icon success"
              />
              <Icon
                icon="carbon:in-progress"
                v-else-if="selectedStep.current"
                class="status-icon progress"
              />
              <Icon icon="carbon:time" v-else class="status-icon pending" />
              <span class="status-text">
                {{ stepStatusText }}
              </span>
            </div>
          </div>
        </div>

        <!-- Scrollable detailed content area -->
        <div ref="scrollContainer" class="step-details-scroll-container" @scroll="checkScrollState">
          <div v-if="selectedStep">
            <!-- Think and action steps -->
            <div
              class="think-act-steps"
              v-if="
                selectedStep.agentExecution?.thinkActSteps &&
                selectedStep.agentExecution.thinkActSteps.length > 0
              "
            >
              <h4>{{ t('rightPanel.thinkAndActionSteps') }}</h4>
              <div class="steps-container">
                <div
                  v-for="(tas, index) in selectedStep.agentExecution.thinkActSteps"
                  :key="index"
                  class="think-act-step"
                >
                  <div class="step-header">
                    <span class="step-number">#{{ index + 1 }}</span>
                  </div>

                  <!-- Think section - strictly follow right-sidebar.js logic -->
                  <div class="think-section">
                    <h5>
                      <Icon icon="carbon:thinking" />
                      {{ t('rightPanel.thinking') }}
                    </h5>
                    <div class="think-content">
                      <div class="input">
                        <div class="label-row">
                          <span class="label">{{ t('rightPanel.input') }}:</span>
                          <div class="label-actions">
                            <button
                              class="copy-btn"
                              @click="copyToClipboard(tas.thinkInput)"
                              :title="t('rightPanel.copyToClipboard')"
                            >
                              <Icon icon="carbon:copy" />
                            </button>
                            <span class="char-count-badge"
                              >{{ tas.inputCharCount ?? 0 }} chars</span
                            >
                          </div>
                        </div>
                        <div class="pre-container">
                          <pre>{{ formatJson(tas.thinkInput) }}</pre>
                        </div>
                      </div>
                      <div class="output">
                        <div class="label-row">
                          <span class="label">{{ t('rightPanel.output') }}:</span>
                          <div class="label-actions">
                            <button
                              class="copy-btn"
                              @click="copyToClipboard(tas.thinkOutput)"
                              :title="t('rightPanel.copyToClipboard')"
                            >
                              <Icon icon="carbon:copy" />
                            </button>
                            <span class="char-count-badge"
                              >{{ tas.outputCharCount ?? 0 }} chars</span
                            >
                          </div>
                        </div>
                        <div class="pre-container">
                          <pre>{{ formatJson(tas.thinkOutput) }}</pre>
                        </div>
                      </div>
                    </div>
                  </div>

                  <!-- Action section - strictly follow right-sidebar.js logic -->
                  <div v-if="tas.actionNeeded" class="action-section">
                    <h5>
                      <Icon icon="carbon:play" />
                      {{ t('rightPanel.action') }}
                    </h5>
                    <div class="action-content">
                      <div
                        v-for="(actToolInfo, index) in tas.actToolInfoList || []"
                        :key="`tool-${index}-${actToolInfo?.id || actToolInfo?.name || index}`"
                        class="tool-execution-item"
                      >
                        <div class="tool-info">
                          <span class="label">{{ t('rightPanel.tool') }}:</span>
                          <span class="value">{{ actToolInfo?.name || 'N/A' }}</span>
                        </div>
                        <div class="input">
                          <span class="label">{{ t('rightPanel.toolParameters') }}:</span>
                          <pre>{{ formatJson(actToolInfo?.parameters) }}</pre>
                        </div>
                        <div class="output">
                          <span class="label">{{ t('rightPanel.executionResult') }}:</span>
                          <pre>{{ formatJson(actToolInfo?.result) }}</pre>
                        </div>
                      </div>
                      <div
                        v-if="!tas.actToolInfoList || tas.actToolInfoList.length === 0"
                        class="no-tools"
                      >
                        <p>{{ t('rightPanel.noToolsExecuted') }}</p>
                      </div>
                    </div>

                    <!-- Sub execution plan section - new feature -->
                    <div v-if="tas.subPlanExecutionRecord" class="sub-plan-section">
                      <h5>
                        <Icon icon="carbon:tree-view" />
                        {{ t('rightPanel.subPlan') }}
                      </h5>
                      <div class="sub-plan-content">
                        <div class="sub-plan-header">
                          <div class="sub-plan-info">
                            <span class="label">{{ $t('rightPanel.subPlanId') }}:</span>
                            <span class="value">{{
                              tas.subPlanExecutionRecord.currentPlanId
                            }}</span>
                          </div>
                          <div class="sub-plan-info" v-if="tas.subPlanExecutionRecord.title">
                            <span class="label">{{ $t('rightPanel.title') }}:</span>
                            <span class="value">{{ tas.subPlanExecutionRecord.title }}</span>
                          </div>
                          <div class="sub-plan-status">
                            <Icon
                              icon="carbon:checkmark-filled"
                              v-if="tas.subPlanExecutionRecord.completed"
                              class="status-icon success"
                            />
                            <Icon icon="carbon:in-progress" v-else class="status-icon progress" />
                            <span class="status-text">
                              {{
                                tas.subPlanExecutionRecord.completed
                                  ? $t('rightPanel.status.completed')
                                  : $t('rightPanel.status.executing')
                              }}
                            </span>
                          </div>
                        </div>
                      </div>
                    </div>
                  </div>
                </div>
              </div>

              <div
                v-if="
                  selectedStep.agentExecution && !selectedStep.agentExecution.thinkActSteps?.length
                "
                class="no-steps-message"
              >
                <p>{{ t('rightPanel.noStepDetails') }}</p>
              </div>

              <!-- Handle no agentExecution case -->
              <div v-else-if="!selectedStep.agentExecution" class="no-execution-message">
                <Icon icon="carbon:information" class="info-icon" />
                <h4>{{ t('rightPanel.stepInfo') }}</h4>
                <div class="step-basic-info">
                  <div class="info-item">
                    <span class="label">{{ t('rightPanel.stepName') }}:</span>
                    <span class="value">{{
                      selectedStep.title || selectedStep.description || selectedStep.stepId
                    }}</span>
                  </div>
                  <div class="info-item" v-if="selectedStep.description">
                    <span class="label">{{ $t('rightPanel.description') }}:</span>
                    <span class="value">{{ selectedStep.description }}</span>
                  </div>
                  <div class="info-item">
                    <span class="label">{{ $t('rightPanel.status.label') }}:</span>
                    <span
                      class="value"
                      :class="{
                        'status-completed': selectedStep.completed,
                        'status-current': selectedStep.current,
                        'status-pending': !selectedStep.completed && !selectedStep.current,
                      }"
                    >
                      {{
                        selectedStep.completed
                          ? $t('rightPanel.status.completed')
                          : selectedStep.current
                            ? $t('rightPanel.status.executing')
                            : $t('rightPanel.status.pending')
                      }}
                    </span>
                  </div>
                </div>
                <p class="no-execution-hint">{{ t('rightPanel.noExecutionInfo') }}</p>
              </div>

              <!-- Dynamic effect during execution -->
              <div
                v-if="selectedStep.current && !selectedStep.completed"
                class="execution-indicator"
              >
                <div class="execution-waves">
                  <div class="wave wave-1"></div>
                  <div class="wave wave-2"></div>
                  <div class="wave wave-3"></div>
                </div>
                <p class="execution-text">
                  <Icon icon="carbon:in-progress" class="rotating-icon" />
                  {{ t('rightPanel.stepExecuting') }}
                </p>
              </div>
            </div>

            <div v-else class="no-selection">
              <Icon icon="carbon:events" class="empty-icon" />
              <h3>{{ t('rightPanel.noStepSelected') }}</h3>
              <p>{{ t('rightPanel.selectStepHint') }}</p>
            </div>
          </div>

          <!-- Scroll to bottom button -->
          <Transition name="scroll-button">
            <button
              v-if="showScrollToBottomButton"
              @click="scrollToBottom"
              class="scroll-to-bottom-btn"
              :title="t('rightPanel.scrollToBottom')"
            >
              <Icon icon="carbon:chevron-down" />
            </button>
          </Transition>
        </div>
      </div>

      <!-- File Browser -->
      <div v-if="activeTab === 'files'" class="file-browser-container">
        <FileBrowser v-if="fileBrowserPlanId" :plan-id="fileBrowserPlanId" />
        <div v-else-if="shouldShowNoTaskMessage" class="no-plan-message">
          <Icon icon="carbon:folder-off" />
          <div class="message-content">
            <h3>{{ t('fileBrowser.noFilesYet') }}</h3>
            <p>{{ t('fileBrowser.noPlanExecuting') }}</p>
            <div class="tips">
              <Icon icon="carbon:information" />
              <span>{{ t('fileBrowser.startTaskTip') }}</span>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import FileBrowser from '@/components/file-browser/index.vue'
import ExecutionController from '@/components/sidebar/ExecutionController.vue'
import JsonEditorV2 from '@/components/sidebar/JsonEditorV2.vue'
import { useAvailableToolsSingleton } from '@/composables/useAvailableTools'
import { usePlanTemplateConfigSingleton } from '@/composables/usePlanTemplateConfig'
import { usePlanTemplateImport } from '@/composables/usePlanTemplateImport'
import { useRightPanelSingleton } from '@/composables/useRightPanel'
import { useToast } from '@/plugins/useToast'
import { sidebarStore } from '@/stores/sidebar'
import { templateStore } from '@/stores/templateStore'
import { Icon } from '@iconify/vue'
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'

// Define props interface
interface Props {
  currentRootPlanId?: string | null
  selectedStepId?: string | null
}

const props = defineProps<Props>()

const { t } = useI18n()
const toast = useToast()

// Copy to clipboard function
const copyToClipboard = async (text: string | null | undefined) => {
  if (!text) {
    toast.error(t('rightPanel.copyFailed') || 'Failed to copy')
    return
  }
  try {
    await navigator.clipboard.writeText(text)
    toast.success(t('rightPanel.copySuccess') || 'Copied to clipboard')
  } catch (error) {
    console.error('Failed to copy to clipboard:', error)
    toast.error(t('rightPanel.copyFailed') || 'Failed to copy')
  }
}

// Use singleton composable for right panel state
const rightPanel = useRightPanelSingleton()

// Template config for Func-Agent Config tab
const templateConfig = usePlanTemplateConfigSingleton()

// Available tools management
const availableToolsStore = useAvailableToolsSingleton()

// Plan template import composable
const { handleImport: handleImportPlanTemplate } = usePlanTemplateImport()

// DOM element reference
const scrollContainer = ref<HTMLElement>()

// Scroll-related state (component-specific, not in composable)
const showScrollToBottomButton = ref(false)
const isNearBottom = ref(true)
const shouldAutoScrollToBottom = ref(true)

// Computed properties using composable state
const selectedStep = computed(() => rightPanel.selectedStep.value)
const activeTab = computed({
  get: () => rightPanel.activeTab.value,
  set: (value: 'config' | 'details' | 'files') => rightPanel.setActiveTab(value),
})
const fileBrowserPlanId = computed(() => rightPanel.fileBrowserPlanId.value)
const shouldShowNoTaskMessage = computed(() => rightPanel.shouldShowNoTaskMessage.value)

const stepStatusText = computed(() => {
  if (!selectedStep.value) return ''
  if (selectedStep.value.completed) return t('rightPanel.status.completed')
  if (selectedStep.value.current) return t('rightPanel.status.executing')
  return t('rightPanel.status.waiting')
})

// Actions - Template creation and import
/**
 * Handle creating a new Func-Agent plan
 */
const handleCreateNewPlan = async () => {
  try {
    // Use default plan type or get from templateConfig
    const planType = templateConfig.getPlanType() || 'dynamic_agent'
    await templateStore.createNewTemplate(planType)

    // Load template config for new template
    const newTemplate = templateConfig.selectedTemplate.value
    if (newTemplate) {
      templateConfig.reset()
      templateConfig.setPlanType(newTemplate.planType || 'dynamic_agent')
      if (newTemplate.planTemplateId) {
        templateConfig.setPlanTemplateId(newTemplate.planTemplateId)
      }
      templateConfig.setTitle(newTemplate.title || '')
    }

    // Reload available tools to ensure fresh tool list
    console.log('[RightPanel] 🔄 Reloading available tools for new template')
    await availableToolsStore.loadAvailableTools()
  } catch (error) {
    console.error('[RightPanel] Failed to create new plan:', error)
    const message = error instanceof Error ? error.message : t('rightPanel.createPlanFailed')
    toast.error(message)
  }
}

/**
 * Handle importing an existing plan
 */
const handleImportExistingPlan = async (event: Event) => {
  await handleImportPlanTemplate(event, {
    onSuccess: async result => {
      const successMsg = t('rightPanel.importSuccess', {
        total: result.total,
        success: result.successCount,
        failed: result.failureCount,
      })
      toast.success(successMsg)
    },
    onError: error => {
      const errorMessage = error instanceof Error ? error.message : t('rightPanel.importFailed')
      toast.error(errorMessage)
    },
    onReload: async () => {
      // Reload template list
      await templateStore.loadPlanTemplateList()
      // Reload available tools to show newly imported tools and dependencies
      await availableToolsStore.loadAvailableTools()
    },
    onSingleTemplateImported: async template => {
      // If only one template was imported, select it
      if (template.planTemplateId) {
        templateConfig.setPlanTemplateId(template.planTemplateId)
        await templateConfig.load(template.planTemplateId)
      }
    },
  })
}

// Actions - Step selection and refresh control

/**
 * Handle step selection by stepId
 * Wrapper around composable method with scroll management
 * @param stepId - The step ID to display
 */
const handleStepSelected = async (stepId: string): Promise<void> => {
  await rightPanel.handleStepSelected(stepId)

  // Delay scroll state check to ensure DOM is updated
  setTimeout(() => {
    checkScrollState()
  }, 100)

  // Auto-scroll to latest content if previously at bottom
  autoScrollToBottomIfNeeded()
}

/**
 * Refresh the currently selected step
 * Wrapper around composable method with scroll management
 */
const refreshCurrentStep = async (): Promise<void> => {
  await rightPanel.refreshCurrentStep()

  // Auto-scroll to latest content if previously at bottom
  autoScrollToBottomIfNeeded()
}

// Watch for selectedStepId prop changes
watch(
  () => props.selectedStepId,
  async newStepId => {
    if (newStepId) {
      await handleStepSelected(newStepId)
    } else {
      rightPanel.clearSelectedStep()
    }
  },
  { immediate: true }
)

// Actions - Scroll management
const setScrollContainer = (element: HTMLElement | null) => {
  scrollContainer.value = element ?? undefined
}

const checkScrollState = () => {
  if (!scrollContainer.value) return

  const { scrollTop, scrollHeight, clientHeight } = scrollContainer.value
  const isAtBottom = scrollHeight - scrollTop - clientHeight < 50
  const hasScrollableContent = scrollHeight > clientHeight

  isNearBottom.value = isAtBottom
  showScrollToBottomButton.value = hasScrollableContent && !isAtBottom

  // Update auto-scroll flag: should auto-scroll if user scrolls to bottom
  // If user actively scrolls up away from bottom, stop auto-scrolling
  if (isAtBottom) {
    shouldAutoScrollToBottom.value = true
  } else if (scrollHeight - scrollTop - clientHeight > 100) {
    // If user clearly scrolled up (more than 100px from bottom), stop auto-scrolling
    shouldAutoScrollToBottom.value = false
  }
}

const scrollToBottom = () => {
  if (!scrollContainer.value) return

  scrollContainer.value.scrollTo({
    top: scrollContainer.value.scrollHeight,
    behavior: 'smooth',
  })

  // Reset state after scrolling
  nextTick(() => {
    shouldAutoScrollToBottom.value = true
    checkScrollState()
  })
}

const autoScrollToBottomIfNeeded = () => {
  if (!shouldAutoScrollToBottom.value || !scrollContainer.value) return

  nextTick(() => {
    if (scrollContainer.value) {
      scrollContainer.value.scrollTop = scrollContainer.value.scrollHeight
      console.log('[RightPanel] Auto scroll to bottom')
    }
  })
}

// Actions - Utility functions
// Use formatJson from composable
const formatJson = rightPanel.formatJson

// Actions - Resource cleanup
const cleanup = () => {
  shouldAutoScrollToBottom.value = true

  if (scrollContainer.value) {
    scrollContainer.value.removeEventListener('scroll', checkScrollState)
  }
}

// Initialize scroll listener
const initScrollListener = () => {
  const setupScrollListener = () => {
    const element = scrollContainer.value
    if (!element) {
      console.log('[RightPanel] Scroll container not found, retrying...')
      return false
    }

    // Set scroll container
    setScrollContainer(element)

    element.addEventListener('scroll', checkScrollState)
    // Initial state check
    shouldAutoScrollToBottom.value = true // Reset to auto scroll state
    checkScrollState()
    console.log('[RightPanel] Scroll listener initialized successfully')
    return true
  }

  // Use nextTick to ensure DOM is updated
  nextTick(() => {
    if (!setupScrollListener()) {
      // If first attempt fails, try again
      setTimeout(() => {
        setupScrollListener()
      }, 100)
    }
  })
}

// Note: currentRootPlanId is now reactively derived from useMessageDialog
// No need to watch props.currentRootPlanId anymore

// Lifecycle - initialization on mount
onMounted(() => {
  console.log('[RightPanel] Component mounted')
  // Use nextTick to ensure DOM is rendered
  nextTick(() => {
    initScrollListener()
  })
})

// Lifecycle - cleanup on unmount
onUnmounted(() => {
  console.log('[RightPanel] Component unmounting, cleaning up...')
  cleanup()
})

/**
 * Update displayed plan progress
 * This method is called when a plan is updated to refresh the display
 * Wrapper around composable method
 * @param rootPlanId - The root plan ID to update
 */
const updateDisplayedPlanProgress = (rootPlanId: string): void => {
  rightPanel.updateDisplayedPlanProgress(rootPlanId)
}

// Expose methods to parent component - only keep necessary interfaces
defineExpose({
  handleStepSelected,
  refreshCurrentStep,
  updateDisplayedPlanProgress,
})
</script>

<style lang="less" scoped>
.right-panel {
  width: 50%;
  display: flex;
  flex-direction: column;
  height: 100%; /* Ensure it takes full height */
  overflow: hidden; /* Prevent content from overflowing */
}

.preview-header {
  padding: 8px 12px;
  border-bottom: 1px solid #1a1a1a;
  background: rgba(255, 255, 255, 0.02);

  .tab-button {
    padding: 8px 16px;
    border: 1px solid rgba(255, 255, 255, 0.1);
    border-radius: 6px;
    background: linear-gradient(135deg, rgba(102, 126, 234, 0.2) 0%, rgba(118, 75, 162, 0.2) 100%);
    border-color: #667eea;
    color: #667eea;
    cursor: default;
    display: flex;
    align-items: center;
    gap: 6px;
    font-size: 14px;
  }
}

.preview-content {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-height: 0; /* Ensure flex items can shrink */
  height: 100%; /* Ensure it takes full height */
  overflow: hidden; /* Prevent content from overflowing */
}

/* Step details styles */
.step-details {
  flex: 1;
  position: relative;
  display: flex;
  flex-direction: column;
  min-height: 0; /* Ensure flex items can shrink */
  height: 100%; /* Ensure it takes full height */
}

/* Step basic information */
.step-info {
  padding: 12px 16px;
  margin: 0 20px;
  background: rgba(41, 42, 45, 0.8);
  border-radius: 8px;
  margin-bottom: 12px;
  min-height: 60px; /* Ensure minimum height */

  h3 {
    color: #ffffff;
    margin: 0 0 12px 0;
    font-size: 14px;
    font-weight: 600;
    padding-bottom: 6px;
    border-bottom: 2px solid #667eea;
  }
}

.step-details-scroll-container {
  flex: 1;
  overflow-y: auto;
  overflow-x: hidden;
  padding: 0 20px 20px;
  margin: 0 20px 20px;
  background: rgba(255, 255, 255, 0.01);
  border-radius: 8px;
  min-height: 200px; /* Ensure minimum height */

  /* Custom scrollbar styles */
  &::-webkit-scrollbar {
    width: 6px;
  }

  &::-webkit-scrollbar-track {
    background: rgba(255, 255, 255, 0.1);
    border-radius: 3px;
  }

  &::-webkit-scrollbar-thumb {
    background: rgba(255, 255, 255, 0.3);
    border-radius: 3px;

    &:hover {
      background: rgba(255, 255, 255, 0.5);
    }
  }
}

/* Step information styles - for fixed top */
.agent-info {
  margin-bottom: 12px;

  .info-item {
    display: flex;
    margin-bottom: 6px;
    font-size: 12px;
    line-height: 1.4;

    .label {
      min-width: 100px;
      font-weight: 600;
      color: #888888;
      flex-shrink: 0;
      font-size: 12px;
    }

    .value {
      flex: 1;
      color: #cccccc;
      word-break: break-word;
      font-size: 12px;

      &.success {
        color: #27ae60;
      }
    }
  }
}

.execution-status {
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px solid rgba(255, 255, 255, 0.1);

  .status-item {
    display: flex;
    align-items: center;
    gap: 6px;

    .status-icon {
      font-size: 14px;

      &.success {
        color: #27ae60;
      }

      &.progress {
        color: #3498db;
      }

      &.pending {
        color: #f39c12;
      }
    }

    .status-text {
      color: #cccccc;
      font-weight: 500;
      font-size: 12px;
    }
  }
}

.no-steps-message {
  text-align: center;
  color: #666666;
  font-style: italic;
  margin-top: 16px;

  p {
    margin: 0;
  }
}

.no-execution-message {
  padding: 20px;
  background: #f8f9fa;
  border: 1px solid #e9ecef;
  border-radius: 8px;
  margin-top: 16px;

  .info-icon {
    color: #6c757d;
    font-size: 20px;
    margin-bottom: 8px;
  }

  h4 {
    margin: 0 0 16px 0;
    color: #495057;
    font-size: 16px;
    font-weight: 500;
  }

  .step-basic-info {
    .info-item {
      display: flex;
      margin-bottom: 8px;
      font-size: 14px;

      .label {
        font-weight: 500;
        color: #6c757d;
        min-width: 80px;
        margin-right: 8px;
      }

      .value {
        color: #333;
        flex: 1;

        &.status-completed {
          color: #28a745;
          font-weight: 500;
        }

        &.status-current {
          color: #007bff;
          font-weight: 500;
        }

        &.status-pending {
          color: #6c757d;
        }
      }
    }
  }

  .no-execution-hint {
    margin: 16px 0 0 0;
    color: #6c757d;
    font-style: italic;
    font-size: 13px;
    text-align: center;
  }
}

.execution-indicator {
  margin-top: 20px;
  padding: 20px;
  background: rgba(74, 144, 226, 0.1);
  border: 1px solid rgba(74, 144, 226, 0.3);
  border-radius: 8px;
  text-align: center;
  position: relative;
  overflow: hidden;
}

.execution-waves {
  position: relative;
  height: 4px;
  margin-bottom: 16px;
  background: rgba(74, 144, 226, 0.2);
  border-radius: 2px;
  overflow: hidden;
}

.wave {
  position: absolute;
  top: 0;
  left: -100%;
  width: 100%;
  height: 100%;
  background: linear-gradient(90deg, transparent, rgba(74, 144, 226, 0.6), transparent);
  border-radius: 2px;
}

.wave-1 {
  animation: wave-animation 2s ease-in-out infinite;
}

.wave-2 {
  animation: wave-animation 2s ease-in-out infinite 0.6s;
}

.wave-3 {
  animation: wave-animation 2s ease-in-out infinite 1.2s;
}

@keyframes wave-animation {
  0% {
    left: -100%;
  }
  50% {
    left: 100%;
  }
  100% {
    left: 100%;
  }
}

.execution-text {
  color: #4a90e2;
  font-size: 14px;
  margin: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
}

.rotating-icon {
  animation: rotate-animation 1s linear infinite;
}

@keyframes rotate-animation {
  from {
    transform: rotate(0deg);
  }
  to {
    transform: rotate(360deg);
  }
}

.step-info {
  h3 {
    color: #ffffff;
    margin: 0 0 20px 0;
    font-size: 18px;
    font-weight: 600;
  }
}

.think-act-steps {
  margin-top: 20px; /* Increase top spacing since there's no fixed header step info now */

  h4 {
    color: #ffffff;
    margin: 0 0 16px 0;
    font-size: 16px;
    font-weight: 600;
    padding-bottom: 8px;
    border-bottom: 1px solid rgba(255, 255, 255, 0.2);
  }
}

.steps-container {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.think-act-step {
  background: rgba(255, 255, 255, 0.05);
  border: 1px solid rgba(255, 255, 255, 0.1);
  border-radius: 8px;
  padding: 16px;

  .step-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 16px;

    .step-number {
      font-weight: 600;
      color: #667eea;
      font-size: 14px;
    }
  }

  .think-section,
  .action-section,
  .sub-plan-section {
    margin-bottom: 16px;

    &:last-child {
      margin-bottom: 0;
    }

    h5 {
      display: flex;
      align-items: center;
      gap: 6px;
      margin: 0 0 12px 0;
      font-size: 14px;
      font-weight: 600;
      color: #ffffff;
    }
  }

  .think-content,
  .action-content {
    .tool-execution-item {
      margin-bottom: 20px;
      padding: 12px;
      background: rgba(0, 0, 0, 0.2);
      border-radius: 6px;
      border: 1px solid rgba(255, 255, 255, 0.05);

      &:last-child {
        margin-bottom: 0;
      }
    }

    .no-tools {
      padding: 12px;
      text-align: center;
      color: rgba(255, 255, 255, 0.5);
      font-size: 13px;
    }

    .input,
    .output,
    .tool-info {
      margin-bottom: 12px;

      &:last-child {
        margin-bottom: 0;
      }

      .label-row {
        display: flex;
        align-items: center;
        justify-content: space-between;
        margin-bottom: 4px;
      }

      .label {
        font-weight: 600;
        color: #888888;
        font-size: 12px;
      }

      .label-actions {
        display: flex;
        align-items: center;
        gap: 6px;
      }

      .copy-btn {
        display: flex;
        align-items: center;
        justify-content: center;
        background: rgba(0, 0, 0, 0.3);
        border: 1px solid rgba(255, 255, 255, 0.1);
        border-radius: 4px;
        padding: 4px 6px;
        color: rgba(255, 255, 255, 0.7);
        cursor: pointer;
        transition: all 0.2s;
        font-size: 12px;

        &:hover {
          background: rgba(0, 0, 0, 0.5);
          border-color: rgba(255, 255, 255, 0.2);
          color: rgba(255, 255, 255, 0.9);
        }

        &:active {
          transform: scale(0.95);
        }
      }

      .value {
        color: #cccccc;
        font-size: 14px;
      }

      .pre-container {
        display: block;
      }

      pre {
        background: rgba(0, 0, 0, 0.3);
        border: 1px solid rgba(255, 255, 255, 0.1);
        border-radius: 4px;
        padding: 12px;
        color: #cccccc;
        font-size: 12px;
        overflow-x: auto;
        white-space: pre-wrap;
        margin: 0;
        line-height: 1.4;
        max-height: 200px;
        overflow-y: auto;
      }

      .char-count-badge {
        background: rgba(0, 0, 0, 0.6);
        border: 1px solid rgba(255, 255, 255, 0.2);
        border-radius: 4px;
        padding: 2px 6px;
        font-size: 10px;
        color: rgba(255, 255, 255, 0.7);
        font-weight: 500;
      }
    }
  }

  /* Sub plan styles */
  .sub-plan-content {
    .sub-plan-header {
      background: rgba(102, 126, 234, 0.1);
      border: 1px solid rgba(102, 126, 234, 0.3);
      border-radius: 6px;
      padding: 12px;
      margin-bottom: 12px;

      .sub-plan-info {
        display: flex;
        margin-bottom: 8px;
        font-size: 12px;

        &:last-child {
          margin-bottom: 0;
        }

        .label {
          min-width: 80px;
          font-weight: 600;
          color: #888888;
          flex-shrink: 0;
        }

        .value {
          flex: 1;
          color: #cccccc;
          word-break: break-word;
        }
      }

      .sub-plan-status {
        display: flex;
        align-items: center;
        gap: 6px;
        padding-top: 8px;
        border-top: 1px solid rgba(255, 255, 255, 0.1);

        .status-icon {
          font-size: 14px;

          &.success {
            color: #27ae60;
          }

          &.progress {
            color: #3498db;
          }
        }

        .status-text {
          color: #cccccc;
          font-size: 12px;
          font-weight: 500;
        }
      }
    }
  }
}

.no-selection {
  height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  color: #666666;

  .empty-icon {
    font-size: 48px;
    margin-bottom: 16px;
    color: #444444;
  }

  h3 {
    margin: 0 0 8px 0;
    font-size: 18px;
    color: #888888;
  }

  p {
    margin: 0;
    font-size: 14px;
    text-align: center;
    max-width: 300px;
    line-height: 1.5;
  }
}

/* Scroll to bottom button */
.scroll-to-bottom-btn {
  position: fixed;
  bottom: 40px;
  right: 40px;
  width: 40px;
  height: 40px;
  border-radius: 50%;
  background: rgba(74, 144, 226, 0.9);
  border: none;
  color: white;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 18px;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.3);
  transition: all 0.3s ease;
  z-index: 100;

  &:hover {
    background: rgba(74, 144, 226, 1);
    transform: translateY(-2px);
    box-shadow: 0 6px 16px rgba(0, 0, 0, 0.4);
  }

  &:active {
    transform: translateY(0);
  }
}

/* Scroll button transition animation */
.scroll-button-enter-active,
.scroll-button-leave-active {
  transition: all 0.3s ease;
}

.scroll-button-enter-from,
.scroll-button-leave-to {
  opacity: 0;
  transform: translateY(20px) scale(0.8);
}

/* File Browser Container */
.file-browser-container {
  height: 100%;
  padding: 0;
  overflow: hidden;
}

.no-plan-message {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 100%;
  color: rgba(255, 255, 255, 0.6);
  gap: 24px;
  padding: 40px 20px;
  text-align: center;
}

.no-plan-message > .iconify {
  font-size: 64px;
  color: rgba(255, 255, 255, 0.3);
}

.message-content {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 16px;
  max-width: 300px;
}

.message-content h3 {
  margin: 0;
  font-size: 18px;
  font-weight: 600;
  color: rgba(255, 255, 255, 0.9);
}

.message-content p {
  margin: 0;
  font-size: 14px;
  line-height: 1.5;
  color: rgba(255, 255, 255, 0.6);
}

.tips {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px 16px;
  background: rgba(103, 126, 234, 0.1);
  border: 1px solid rgba(103, 126, 234, 0.2);
  border-radius: 8px;
  font-size: 12px;
  color: rgba(103, 126, 234, 0.9);
}

.tips .iconify {
  font-size: 14px;
  flex-shrink: 0;
}

/* Tab styles */
.preview-tabs {
  display: flex;
  gap: 0;
  background: rgba(255, 255, 255, 0.05);
  border-radius: 8px;
  padding: 4px;
}

.tab-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 16px;
  border-radius: 6px;
  cursor: pointer;
  transition: all 0.2s ease;
  color: rgba(255, 255, 255, 0.7);
  font-size: 13px;
  font-weight: 500;
  min-width: 0;
  flex: 1;
  justify-content: center;
  position: relative;
}

.tab-item:hover {
  background: rgba(255, 255, 255, 0.08);
  color: rgba(255, 255, 255, 0.9);
}

.tab-item.active {
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  color: #ffffff;
  box-shadow: 0 2px 8px rgba(102, 126, 234, 0.3);
}

.tab-item .iconify {
  font-size: 16px;
  flex-shrink: 0;
}

.tab-item span {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* Config Tab Styles */
.config-tab-content {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-height: 0;
  height: 100%;
  overflow: hidden;
}

.config-container {
  display: flex;
  flex-direction: column;
  height: 100%;
  overflow-y: auto;
  padding: 20px;
  gap: 16px;
}

.template-info-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
  padding: 12px;
  background: rgba(255, 255, 255, 0.05);
  border-radius: 8px;

  .template-info {
    flex: 1;
    min-width: 0;

    h3 {
      margin: 0 0 4px 0;
      font-size: 14px;
      font-weight: 600;
      color: white;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .template-id {
      font-size: 11px;
      color: rgba(255, 255, 255, 0.5);
    }
  }

  .back-to-list-btn {
    width: 28px;
    height: 28px;
    background: transparent;
    border: none;
    border-radius: 4px;
    color: rgba(255, 255, 255, 0.7);
    cursor: pointer;
    display: flex;
    align-items: center;
    justify-content: center;
    transition: all 0.2s ease;

    &:hover {
      background: rgba(255, 255, 255, 0.1);
      color: white;
    }
  }
}

.no-template-selected {
  height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  color: #666666;
  padding: 40px 20px;

  .action-buttons {
    display: flex;
    flex-direction: column;
    gap: 16px;
    width: 100%;
    max-width: 400px;
  }

  .new-task-btn {
    width: 100%;
    padding: 10px 16px;
    background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
    border: none;
    border-radius: 6px;
    color: white;
    font-size: 13px;
    font-weight: 500;
    cursor: pointer;
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 8px;
    transition: all 0.2s ease;
    white-space: nowrap;

    &:hover {
      transform: translateY(-1px);
      box-shadow: 0 4px 12px rgba(102, 126, 234, 0.3);
    }
  }

  .import-description {
    margin-top: 16px;
    padding: 0 16px;
    font-size: 12px;
    line-height: 1.6;
    color: rgba(255, 255, 255, 0.7);
    text-align: center;
    max-width: 400px;

    .prompt-library-link {
      color: #667eea;
      text-decoration: none;
      transition: color 0.2s ease;

      &:hover {
        color: #764ba2;
        text-decoration: underline;
      }
    }
  }
}
</style>
