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
      <Icon icon="carbon:play" width="16" />
      <span>{{ t('sidebar.executionController') }}</span>
    </div>
    <div class="execution-content">
      <!-- Parameter Requirements Display -->
      <div class="params-requirements-group">
        <label>{{ t('sidebar.parameterRequirements') }}</label>
        <div class="params-help-text">
          {{ t('sidebar.parameterRequirementsHelp') }}
        </div>

        <!-- Show parameter fields only if there are parameters -->
        <div v-if="parameterRequirements.hasParameters" class="parameter-fields">
          <div
            v-for="(param, index) in parameterRequirements.parameters"
            :key="param"
            class="parameter-field"
          >
            <div class="parameter-label-row">
              <label class="parameter-label">
                {{ param }}
                <span class="required">*</span>
              </label>
              <!-- Unified parameter history navigation - only show on first parameter -->
              <div v-if="index === 0 && hasParameterHistory()" class="parameter-history-navigation">
                <div class="history-navigation-controls">
                  <button
                    type="button"
                    class="history-btn history-btn-up"
                    :title="t('sidebar.historyUp')"
                    :disabled="getToolHistoryIndex() >= getHistoryCount() - 1"
                    @click="navigateParameterSetHistory('up')"
                  >
                    <Icon icon="carbon:chevron-up" width="12" />
                  </button>
                  <button
                    type="button"
                    class="history-btn history-btn-down"
                    :title="t('sidebar.historyDown')"
                    @click="navigateParameterSetHistory('down')"
                  >
                    <Icon icon="carbon:chevron-down" width="12" />
                  </button>
                </div>
              </div>
            </div>
            <input
              v-model="parameterValues[param]"
              class="parameter-input"
              :class="{
                error: parameterErrors[param],
                'viewing-history': getToolHistoryIndex() >= 0,
              }"
              :placeholder="t('sidebar.enterValueFor', { param })"
              @input="updateParameterValue(param, ($event.target as HTMLInputElement).value)"
              required
            />
            <div v-if="parameterErrors[param]" class="parameter-error">
              {{ parameterErrors[param] }}
            </div>
          </div>
        </div>

        <!-- Validation status message - only show after user attempts to execute -->
        <div
          v-if="
            hasAttemptedExecute &&
            parameterRequirements.hasParameters &&
            !canExecute &&
            !props.isExecuting
          "
          class="validation-message"
        >
          <Icon icon="carbon:warning" width="14" />
          {{ t('sidebar.fillAllRequiredParameters') }}
        </div>
      </div>

      <!-- File Upload Component -->
      <FileUploadComponent
        ref="fileUploadRef"
        :disabled="props.isExecuting"
        @files-uploaded="handleFilesUploaded"
        @files-removed="handleFilesRemoved"
        @upload-key-changed="handleUploadKeyChanged"
        @upload-started="handleUploadStarted"
        @upload-completed="handleUploadCompleted"
        @upload-error="handleUploadError"
      />

      <!-- Toggle button: Execute Plan / Stop Task -->
      <button
        class="btn btn-primary execute-btn"
        :class="{ 'stop-button': isPlanRunning }"
        @click="isPlanRunning ? handleStop() : handleExecutePlan()"
        :disabled="isPlanRunning ? isStopping : !canExecute"
        :title="isPlanRunning ? t('sidebar.stopTask') : t('sidebar.executePlan')"
      >
        <Icon v-if="isPlanRunning" icon="carbon:stop-filled" width="16" />
        <Icon
          v-else
          :icon="props.isExecuting ? 'carbon:circle-dash' : 'carbon:play'"
          width="16"
          :class="{ spinning: props.isExecuting }"
        />
        {{
          isPlanRunning
            ? t('sidebar.stopTask')
            : props.isExecuting
              ? t('sidebar.executing')
              : t('sidebar.executePlan')
        }}
      </button>
      <button
        class="btn publish-mcp-btn"
        @click="handlePublishMcpService"
        :disabled="!templateConfig.currentPlanTemplateId.value"
        v-if="showPublishButton"
      >
        <Icon icon="carbon:application" width="16" />
        {{ buttonText }}
      </button>

      <!-- Internal Call wrapper - only show when enableInternalToolcall is true -->
      <div
        v-if="templateConfig.selectedTemplate.value?.toolConfig?.enableInternalToolcall"
        class="call-example-wrapper"
      >
        <div class="call-example-header">
          <h4 class="call-example-title">{{ t('sidebar.internalCall') }}</h4>
          <p class="call-example-description">{{ t('sidebar.internalCallDescription') }}</p>
        </div>
        <div class="internal-call-wrapper">
          <div class="call-info">
            <div class="call-method">{{ t('sidebar.internalMethodCall') }}</div>
            <div class="call-endpoint">
              {{ t('sidebar.toolName') }}:
              {{
                templateConfig.selectedTemplate.value?.title ||
                templateConfig.currentPlanTemplateId.value ||
                ''
              }}
            </div>
            <div v-if="templateConfig.selectedTemplate.value?.serviceGroup" class="call-endpoint">
              {{ t('sidebar.serviceGroup') }}:
              {{ templateConfig.selectedTemplate.value.serviceGroup }}
            </div>
            <div class="call-description">{{ t('sidebar.internalCallUsage') }}</div>
            <div class="call-example">
              <strong>{{ t('sidebar.usage') }}:</strong>
              <pre class="example-code">{{ t('sidebar.internalCallExample') }}</pre>
            </div>
          </div>
        </div>
      </div>

      <!-- HTTP API URLs wrapper with tabs - only show when enableHttpService is true -->
      <div
        v-if="templateConfig.selectedTemplate.value?.toolConfig?.enableHttpService"
        class="call-example-wrapper"
      >
        <div class="call-example-header">
          <h4 class="call-example-title">{{ t('sidebar.httpCallExample') }}</h4>
          <p class="call-example-description">{{ t('sidebar.httpCallDescription') }}</p>
        </div>
        <div class="http-api-urls-wrapper">
          <div class="tab-container">
            <div class="tab-header">
              <button
                v-for="tab in apiTabs"
                :key="tab.id"
                :class="['tab-button', { active: activeTab === tab.id }]"
                @click="activeTab = tab.id"
              >
                {{ tab.label }}
              </button>
            </div>
            <div class="tab-content">
              <div
                v-for="tab in apiTabs"
                :key="tab.id"
                v-show="activeTab === tab.id"
                class="tab-panel"
              >
                <div class="http-api-url-display">
                  <div class="api-method">{{ tab.method }}</div>
                  <div class="api-endpoint">{{ tab.endpoint }}</div>
                  <div class="api-description">{{ tab.description }}</div>
                  <div v-if="tab.example" class="api-example">
                    <strong>{{ t('sidebar.example') }}:</strong>
                    <pre class="example-code">{{ tab.example }}</pre>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>

  <!-- Save Confirmation Dialog -->
  <SaveConfirmationDialog
    v-model="showSaveDialog"
    @save="handleSaveAndExecute"
    @continue="handleContinueExecution"
  />

  <!-- Publish Service Modal -->
  <PublishServiceModal v-model="showPublishMcpModal" />
</template>

<script setup lang="ts">
import { FileInfo } from '@/api/file-upload-api-service'
import {
  PlanParameterApiService,
  type ParameterRequirements,
} from '@/api/plan-parameter-api-service'
import FileUploadComponent from '@/components/file-upload/FileUploadComponent.vue'
import PublishServiceModal from '@/components/publish-service-modal/PublishServiceModal.vue'
import SaveConfirmationDialog from '@/components/sidebar/SaveConfirmationDialog.vue'
import { useFileUploadSingleton } from '@/composables/useFileUpload'
import { useMessageDialogSingleton } from '@/composables/useMessageDialog'
import { usePlanExecutionSingleton } from '@/composables/usePlanExecution'
import { usePlanTemplateConfigSingleton } from '@/composables/usePlanTemplateConfig'
import { useTaskStop } from '@/composables/useTaskStop'
import { useToast } from '@/plugins/useToast'
import { parameterHistoryStore } from '@/stores/parameterHistory'
import { templateStore } from '@/stores/templateStore'
import { useTaskStore } from '@/stores/task'
import type { PlanData, PlanExecutionRequestPayload } from '@/types/plan-execution'
import { Icon } from '@iconify/vue'
import { computed, onMounted, ref, watch, watchEffect } from 'vue'
import { useI18n } from 'vue-i18n'

const { t } = useI18n()
const toast = useToast()

// Template config singleton
const templateConfig = usePlanTemplateConfigSingleton()

// Message dialog singleton for executing plans
const messageDialog = useMessageDialogSingleton()

// Plan execution singleton to track execution state
const planExecution = usePlanExecutionSingleton()

// Task store and stop functionality
const taskStore = useTaskStore()
const { stopTask, isStopping } = useTaskStop()

// Shared file upload state
const fileUpload = useFileUploadSingleton()

// Props
interface Props {
  isExecuting?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  isExecuting: false,
})

// No emits needed - we handle execution directly

// Local state
const executionParams = ref('')
const showPublishMcpModal = ref(false)
const parameterRequirements = ref<ParameterRequirements>({
  parameters: [],
  hasParameters: false,
  requirements: '',
})
const parameterValues = ref<Record<string, string>>({})
const isLoadingParameters = ref(false)
const activeTab = ref('post-async')
const parameterErrors = ref<Record<string, string>>({})
const isValidationError = ref(false)
const isExecutingPlan = ref(false) // Flag to prevent parameter reload during execution
const lastPlanId = ref<string | null>(null) // Track last returned plan ID
const lastRefreshTimestamp = ref<number>(0) // Track last refresh time for debouncing
const REFRESH_DEBOUNCE_MS = 500 // Debounce time for parameter refresh
const hasAttemptedExecute = ref(false) // Track if user has attempted to execute (for validation message)

// Computed property: whether to show publish MCP service button
const showPublishButton = computed(() => {
  return templateConfig.getCoordinatorToolConfig()
})

// File upload state - use shared state
const fileUploadRef = ref<InstanceType<typeof FileUploadComponent>>()
// Use shared state from composable
const uploadedFiles = computed(() => fileUpload.getUploadedFileNames())
const uploadKey = computed(() => fileUpload.uploadKey.value)

// Save confirmation dialog state
const showSaveDialog = ref(false)
const pendingExecutionPayload = ref<PlanExecutionRequestPayload | null>(null)

// API tabs configuration - dynamically generated from template and parameters
const apiTabs = computed(() => {
  // Get actual values from selected template
  const toolName = templateConfig.selectedTemplate.value?.title || 'my-tool'
  const serviceGroup = templateConfig.selectedTemplate.value?.serviceGroup || 'research'
  const planTemplateId = templateConfig.selectedTemplate.value?.planTemplateId || 'template-456'

  // Generate replacementParams from actual parameter requirements
  const replacementParams: Record<string, string> = {}
  if (
    parameterRequirements.value.hasParameters &&
    parameterRequirements.value.parameters.length > 0
  ) {
    parameterRequirements.value.parameters.forEach(param => {
      // Use actual value if available, otherwise use placeholder
      replacementParams[param] = parameterValues.value[param] || 'test'
    })
  } else {
    // Default example when no parameters
    replacementParams['rawParam'] = 'test'
  }

  // Use actual planId or i18n placeholder
  const planId = lastPlanId.value || t('sidebar.defaultPlanId')
  const detailsPlanId = lastPlanId.value || t('sidebar.defaultPlanIdDetails')

  return [
    {
      id: 'post-async',
      label: 'POST + Async',
      method: 'POST',
      endpoint: '/api/executor/executeByToolNameAsync',
      description: 'Asynchronous POST request - returns task ID, check status separately',
      example: `POST /api/executor/executeByToolNameAsync
Content-Type: application/json

{
  "toolName": "${toolName}",
  "serviceGroup": "${serviceGroup}",
  "replacementParams": ${JSON.stringify(replacementParams, null, 2)},
  "uploadedFiles": []
}

Response: {
  "planId": "${planId}",
  "status": "processing",
  "message": "Task submitted, processing",
  "toolName": "${toolName}",
  "planTemplateId": "${planTemplateId}"
}

# Check execution status and get detailed results:
GET /api/executor/details/${detailsPlanId}
Response: {
  "currentPlanId": "${planId}",
  "title": "Plan Title",
  "status": "completed",
  "summary": "Execution completed successfully",
  "agentExecutionSequence": [...],
  "userInputWaitState": null,
  "structureResult": {
    "message": [
      {
        "name": "Product A",
        "price": "100",
        "quantity": "5"
      }
    ],
    "fileList": [
      {
        "fileName": "report.md",
        "fileDescription": "Generated report with product information"
      }
    ]
  }
}`,
    },
    {
      id: 'post-sync',
      label: 'POST + Sync',
      method: 'POST',
      endpoint: '/api/executor/executeByToolNameSync',
      description: 'Synchronous POST request - returns execution result immediately',
      example: `POST /api/executor/executeByToolNameSync
Content-Type: application/json

{
  "toolName": "${toolName}",
  "serviceGroup": "${serviceGroup}",
  "replacementParams": ${JSON.stringify(replacementParams, null, 2)},
  "uploadedFiles": []
}

Response: {
  "status": "completed",
  "result": "Execution result here"
}`,
    },
  ]
})

// Computed properties
const isAnyServiceEnabled = computed(() => {
  const toolConfig = templateConfig.selectedTemplate.value?.toolConfig
  return (
    toolConfig?.enableInternalToolcall ??
    toolConfig?.enableHttpService ??
    toolConfig?.enableInConversation ??
    false
  )
})

const buttonText = computed(() => {
  return isAnyServiceEnabled.value
    ? t('sidebar.updateServiceStatus')
    : t('sidebar.publishMcpService')
})

// Computed property for disabled state - same as InputArea.vue
const isDisabled = computed(() => messageDialog.isLoading.value)

// Check if a plan is currently running
const isPlanRunning = computed(() => {
  return (
    taskStore.hasRunningTask() ||
    planExecution.trackedPlanIds.value.size > 0 ||
    isExecutingPlan.value
  )
})

const canExecute = computed(() => {
  // Disable if messageDialog is loading (same validation as InputArea)
  if (isDisabled.value) {
    return false
  }

  if (props.isExecuting) {
    return false
  }

  // Disable if there's a plan execution in progress (prevents duplicate submissions)
  if (isExecutingPlan.value) {
    return false
  }

  // Also check if there are any tracked plans or running plans
  const hasTrackedPlans = planExecution.trackedPlanIds.value.size > 0
  const recordsArray = Array.from(planExecution.planExecutionRecords.entries())
  const hasRunningPlansInRecords = recordsArray.some(
    ([, record]) => record && !record.completed && record.status !== 'failed'
  )
  if (hasTrackedPlans || hasRunningPlansInRecords) {
    return false
  }

  if (parameterRequirements.value.hasParameters) {
    // Check if all required parameters are filled
    return parameterRequirements.value.parameters.every(param => {
      const value = parameterValues.value[param]
      return typeof value === 'string' && value.trim() !== ''
    })
  }

  return true
})

// File upload event handlers - state is already updated in shared composable
const handleFilesUploaded = (files: FileInfo[], key: string | null) => {
  console.log(
    '[ExecutionController] Files uploaded event received:',
    files.length,
    'uploadKey:',
    key
  )
  // State is already updated in shared composable
}

const handleFilesRemoved = (files: FileInfo[]) => {
  console.log('[ExecutionController] Files removed event received, remaining:', files.length)
  // State is already updated in shared composable
}

const handleUploadKeyChanged = (key: string | null) => {
  console.log('[ExecutionController] Upload key changed:', key)
  // State is already updated in shared composable
}

const handleUploadStarted = () => {
  console.log('[ExecutionController] Upload started')
}

const handleUploadCompleted = () => {
  console.log('[ExecutionController] Upload completed')
}

const handleUploadError = (error: unknown) => {
  console.error('[ExecutionController] Upload error:', error)
}

// Methods
const handleExecutePlan = async () => {
  console.log('[ExecutionController] 🚀 Execute button clicked')

  // Mark that user has attempted to execute (for validation message)
  hasAttemptedExecute.value = true

  // Check if there's already an execution in progress
  if (props.isExecuting || messageDialog.isLoading.value || isExecutingPlan.value) {
    console.log(
      '[ExecutionController] ⏸️ Execution already in progress. isExecuting: {}, messageDialog.isLoading: {}, isExecutingPlan: {}',
      props.isExecuting,
      messageDialog.isLoading.value,
      isExecutingPlan.value
    )
    toast.error(t('sidebar.executionInProgress'))
    return
  }

  // Check if task requirements have been modified
  if (templateStore.hasTaskRequirementModified) {
    console.log(
      '[ExecutionController] ⚠️ Task requirements modified, showing save confirmation dialog'
    )
    // Prepare payload but don't execute yet
    if (!validateParameters()) {
      console.log('[ExecutionController] ❌ Parameter validation failed:', parameterErrors.value)
      // Keep hasAttemptedExecute as true to show validation message
      return
    }

    const replacementParams =
      parameterRequirements.value.hasParameters && Object.keys(parameterValues.value).length > 0
        ? parameterValues.value
        : undefined

    pendingExecutionPayload.value = {
      title: '', // Will be set by the parent component
      planData: {
        title: '',
        steps: [],
      }, // Will be set by the parent component
      params: undefined, // Will be set by the parent component
      replacementParams,
      uploadedFiles: uploadedFiles.value,
      uploadKey: uploadKey.value,
    }

    showSaveDialog.value = true
    return
  }

  // Continue with normal execution if no modifications
  await proceedWithExecution()
}

const proceedWithExecution = async () => {
  // Double-check execution state before proceeding (defense in depth)
  if (props.isExecuting || messageDialog.isLoading.value || isExecutingPlan.value) {
    console.log(
      '[ExecutionController] ⏸️ Execution already in progress in proceedWithExecution. Skipping.'
    )
    return
  }

  // Set execution flag to prevent parameter reload and concurrent execution
  isExecutingPlan.value = true
  console.log('[ExecutionController] 🔒 Set isExecutingPlan to true')

  // Validate parameters before execution
  if (!validateParameters()) {
    console.log('[ExecutionController] ❌ Parameter validation failed:', parameterErrors.value)
    isExecutingPlan.value = false // Reset flag on validation failure
    // Keep hasAttemptedExecute as true to show validation message
    return
  }

  // Reset validation attempt flag when validation passes and execution starts
  hasAttemptedExecute.value = false

  // Save current parameter set to history before execution
  saveParameterSetToHistory()

  try {
    // Get plan data from templateConfig
    if (!templateConfig.selectedTemplate.value) {
      console.log('[ExecutionController] ❌ No template selected, returning')
      toast.error(t('sidebar.selectPlanFirst'))
      isExecutingPlan.value = false
      return
    }

    const config = templateConfig.getConfig()

    // Convert PlanTemplateConfigVO to PlanData format
    const planTemplateId =
      templateConfig.selectedTemplate.value.planTemplateId || config.planTemplateId
    const planData: PlanData = {
      title: config.title || templateConfig.selectedTemplate.value.title || 'Execution Plan',
      steps: (config.steps || []).map(step => ({
        stepRequirement: step.stepRequirement || '',
        agentName: step.agentName || '',
        modelName: step.modelName || null,
        selectedToolKeys: [],
        terminateColumns: step.terminateColumns || '',
        stepContent: '',
      })),
      ...(planTemplateId && { planTemplateId }),
      ...(config.planType && { planType: config.planType }),
    }

    const title = templateConfig.selectedTemplate.value.title ?? config.title ?? 'Execution Plan'

    // Extract toolName and serviceGroup from template for API execution
    const toolName = templateConfig.selectedTemplate.value?.title || config.title || ''
    const serviceGroup =
      templateConfig.selectedTemplate.value?.serviceGroup || config.serviceGroup || undefined

    // Validate toolName is present
    if (!toolName || toolName.trim() === '') {
      console.error('[ExecutionController] ❌ Tool name is required but not found')
      toast.error(t('sidebar.toolNameRequired') || 'Tool name is required for execution')
      isExecutingPlan.value = false
      return
    }

    // Pass replacement parameters if available
    const replacementParams =
      parameterRequirements.value.hasParameters && Object.keys(parameterValues.value).length > 0
        ? parameterValues.value
        : undefined

    console.log('[ExecutionController] 🔄 Replacement params:', replacementParams)
    console.log('[ExecutionController] 📋 Prepared plan data:', JSON.stringify(planData, null, 2))
    console.log('[ExecutionController] 🔧 Tool name:', toolName, 'Service group:', serviceGroup)

    // Build final payload with plan data
    const finalPayload: PlanExecutionRequestPayload = {
      title,
      planData,
      params: undefined, // params are now handled via replacementParams
      replacementParams,
      uploadedFiles: uploadedFiles.value,
      uploadKey: uploadKey.value,
      toolName,
      serviceGroup,
    }

    console.log(
      '[ExecutionController] 📤 Executing plan with payload:',
      JSON.stringify(finalPayload, null, 2)
    )

    // Execute plan directly via messageDialog
    const result = await messageDialog.executePlan(finalPayload)

    if (result.success) {
      console.log('[ExecutionController] ✅ Plan execution started successfully:', result.planId)
      // Track the returned planId for API examples
      if (result.planId) {
        lastPlanId.value = result.planId
        console.log('[ExecutionController] 📝 Tracked planId for API examples:', lastPlanId.value)
      }
    } else {
      console.error('[ExecutionController] ❌ Plan execution failed:', result.error)
      toast.error(result.error || t('sidebar.executeFailed'))
    }
  } catch (error: unknown) {
    console.error('[ExecutionController] ❌ Error executing plan:', error)
    const message = error instanceof Error ? error.message : 'Unknown error'
    toast.error(t('sidebar.executeFailed') + ': ' + message)
    isExecutingPlan.value = false
  } finally {
    console.log('[ExecutionController] 🧹 Cleaning up after execution')
    // Clear parameters after execution
    clearExecutionParams()
    console.log('[ExecutionController] ✅ Cleanup completed')
  }
}

const handleSaveAndExecute = async () => {
  console.log('[ExecutionController] 💾 Save and execute requested')
  try {
    // Save using templateConfig directly
    if (!templateConfig.selectedTemplate.value) {
      toast.error(t('sidebar.selectPlanFirst'))
      return
    }

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
    const success = await templateConfig.save()
    if (!success) {
      toast.error('Failed to save plan template')
      return
    }

    // Update versions after save
    const content = templateConfig.generateJsonString().trim()
    templateConfig.updateVersionsAfterSave(content)

    // Get actual version count after update
    const versionCount = templateConfig.planVersions.value.length

    // Reset modification flag after successful save
    templateStore.hasTaskRequirementModified = false

    // Wait for templateConfig.save() to complete and selectedTemplate to be updated
    // The save() method already calls load() internally, so we need to wait a bit more
    await new Promise(resolve => setTimeout(resolve, 500))

    // Refresh parameter requirements after successful save
    // Increase delay to ensure backend has processed the save and parameters are updated
    await refreshParameterRequirements()

    // Refresh sidebar template list to reflect the saved changes
    await templateStore.loadPlanTemplateList()

    // Note: templateConfig.save() already handles the save, so we just show success
    toast.success(t('sidebar.saveSuccess', { message: 'Plan saved successfully', versionCount }))

    // Wait a bit for save to complete
    await new Promise(resolve => setTimeout(resolve, 500))
    // Now proceed with execution - rebuild payload with current template config
    if (pendingExecutionPayload.value) {
      // Rebuild payload with current template config
      await proceedWithExecution()
      pendingExecutionPayload.value = null
    }
  } catch (error: unknown) {
    console.error('[ExecutionController] ❌ Failed to save before execute:', error)
    const message = error instanceof Error ? error.message : t('sidebar.saveFailed')
    toast.error(message)
    throw error
  }
}

const handleContinueExecution = async () => {
  console.log('[ExecutionController] ⏩ Continue without save requested')
  if (pendingExecutionPayload.value) {
    // Rebuild payload with current template config
    await proceedWithExecution()
    pendingExecutionPayload.value = null
  }
}

const handlePublishMcpService = () => {
  console.log('[ExecutionController] Publish MCP service button clicked')
  console.log(
    '[ExecutionController] currentPlanTemplateId:',
    templateConfig.currentPlanTemplateId.value
  )

  if (!templateConfig.currentPlanTemplateId.value) {
    console.log('[ExecutionController] No plan template selected, showing warning')
    toast.error(t('mcpService.selectPlanTemplateFirst'))
    return
  }

  showPublishMcpModal.value = true
}

const handleStop = async () => {
  console.log('[ExecutionController] Stop button clicked')
  const success = await stopTask()
  if (success) {
    console.log('[ExecutionController] Task stopped successfully')
    toast.success(t('input.stop') || 'Stopped')
  } else {
    console.error('[ExecutionController] Failed to stop task')
    toast.error(t('sidebar.executeFailed') || 'Failed to stop task')
  }
}

const clearExecutionParams = () => {
  console.log('[ExecutionController] 🧹 clearExecutionParams called')
  executionParams.value = ''
  // Clear parameter values as well
  parameterValues.value = {}

  // Note: isExecutingPlan is NOT reset here - it will be reset when the plan execution completes
  // This prevents concurrent executions while a plan is still running

  console.log('[ExecutionController] ✅ After clear - parameterValues cleared')
  // Execution params are now managed internally, no need to emit
}

// Helper function to compare parameter lists
const areParameterListsEqual = (params1: string[], params2: string[]): boolean => {
  if (params1.length !== params2.length) {
    return false
  }
  const sorted1 = [...params1].sort()
  const sorted2 = [...params2].sort()
  return sorted1.every((param, index) => param === sorted2[index])
}

// Refresh parameter requirements (called after save)
const refreshParameterRequirements = async () => {
  // Check if we should skip refresh due to debouncing
  const now = Date.now()
  if (now - lastRefreshTimestamp.value < REFRESH_DEBOUNCE_MS) {
    console.log(
      '[ExecutionController] ⏸️ Skipping refresh - too soon after last refresh (debounced)'
    )
    return
  }

  // Store current parameter list for comparison
  const currentParams = [...parameterRequirements.value.parameters]

  // Add a delay to ensure the backend has processed the new template and committed the transaction
  // Also ensure selectedTemplate has been updated by templateConfig.save()
  await new Promise(resolve => setTimeout(resolve, 1500))

  console.log(
    '[ExecutionController] 🔄 Refreshing parameter requirements for templateId:',
    templateConfig.currentPlanTemplateId.value
  )
  console.log(
    '[ExecutionController] 📋 Current selectedTemplate steps:',
    templateConfig.selectedTemplate.value?.steps?.map(s => s.stepRequirement).join(' ||| ')
  )

  // Use nextTick to ensure all reactive updates are complete
  await new Promise(resolve => setTimeout(resolve, 200))

  // Reload parameter requirements
  await loadParameterRequirements()

  // Update refresh timestamp
  lastRefreshTimestamp.value = now

  // Compare old and new parameter lists
  const newParams = [...parameterRequirements.value.parameters]
  if (areParameterListsEqual(currentParams, newParams)) {
    console.log(
      '[ExecutionController] ✅ Parameter list unchanged, values preserved:',
      JSON.stringify(parameterValues.value, null, 2)
    )
  } else {
    console.log(
      '[ExecutionController] 🔄 Parameter list changed:',
      JSON.stringify({ old: currentParams, new: newParams }, null, 2)
    )
  }
}

// Load parameter requirements when plan template changes
const loadParameterRequirements = async () => {
  const planTemplateId = templateConfig.currentPlanTemplateId.value
  console.log(
    '[ExecutionController] 🔄 loadParameterRequirements called for templateId:',
    planTemplateId
  )
  console.log(
    '[ExecutionController] 📊 Current parameterRequirements before load:',
    JSON.stringify(parameterRequirements.value, null, 2)
  )

  if (!planTemplateId) {
    console.log('[ExecutionController] ❌ No template ID, resetting parameters')
    parameterRequirements.value = {
      parameters: [],
      hasParameters: false,
      requirements: '',
    }
    parameterValues.value = {}
    return
  }

  // Preserve current parameter values before clearing to prevent data loss
  const preservedValues = { ...parameterValues.value }
  console.log(
    '[ExecutionController] 💾 Preserved parameter values before reload:',
    JSON.stringify(preservedValues, null, 2)
  )

  // Clear previous data immediately to prevent stale data display
  parameterRequirements.value = {
    parameters: [],
    hasParameters: false,
    requirements: '',
  }
  parameterValues.value = {}
  console.log('[ExecutionController] 🧹 Cleared previous data before loading new template')

  isLoadingParameters.value = true
  try {
    console.log('[ExecutionController] 🌐 Fetching parameter requirements from API...')
    const requirements = await PlanParameterApiService.getParameterRequirements(planTemplateId)
    console.log(
      '[ExecutionController] 📥 Received requirements from API:',
      JSON.stringify(requirements, null, 2)
    )

    parameterRequirements.value = requirements

    // Initialize parameter values, restoring preserved values for parameters that still exist
    const newValues: Record<string, string> = {}
    requirements.parameters.forEach(param => {
      // Restore preserved value if it exists, otherwise use empty string
      newValues[param] = preservedValues[param] || ''
    })
    parameterValues.value = newValues

    console.log(
      '[ExecutionController] ✅ Updated parameterRequirements:',
      JSON.stringify(parameterRequirements.value, null, 2)
    )
    console.log(
      '[ExecutionController] ✅ Updated parameterValues:',
      JSON.stringify(parameterValues.value, null, 2)
    )

    // Update execution params with current parameter values
    updateExecutionParamsFromParameters()
  } catch (error) {
    console.error('[ExecutionController] ❌ Failed to load parameter requirements:', error)
    // Don't show error for 404 - template might not be ready yet
    if (error instanceof Error && !error.message.includes('404')) {
      console.warn(
        '[ExecutionController] ⚠️ Parameter requirements not available yet, will retry later'
      )
    }
    parameterRequirements.value = {
      parameters: [],
      hasParameters: false,
      requirements: '',
    }
    // Restore preserved values on error to prevent data loss
    // Only restore if we have previous requirements with matching parameters
    const previousParams = Object.keys(preservedValues)
    if (previousParams.length > 0) {
      console.log(
        '[ExecutionController] 🔄 Restoring preserved values on error:',
        JSON.stringify(preservedValues, null, 2)
      )
      parameterValues.value = { ...preservedValues }
    } else {
      parameterValues.value = {}
    }
    console.log(
      '[ExecutionController] 🔄 Reset parameterRequirements due to error:',
      JSON.stringify(parameterRequirements.value, null, 2)
    )
    console.log(
      '[ExecutionController] 🔄 Restored parameterValues:',
      JSON.stringify(parameterValues.value, null, 2)
    )
  } finally {
    isLoadingParameters.value = false
    console.log('[ExecutionController] ✅ loadParameterRequirements completed')
  }
}

// Update parameter value and sync with execution params
const updateParameterValue = (paramName: string, value: string) => {
  parameterValues.value[paramName] = value
  // Clear error for this parameter when user starts typing
  if (parameterErrors.value[paramName]) {
    delete parameterErrors.value[paramName]
  }
  // Reset validation attempt flag when user starts filling parameters
  // This hides the validation message as user corrects the issue
  if (hasAttemptedExecute.value && canExecute.value) {
    hasAttemptedExecute.value = false
  }
  // Reset tool-level navigation index when user manually types (viewing current, not history)
  const planTemplateId = templateConfig.currentPlanTemplateId.value
  if (planTemplateId) {
    parameterHistoryStore.setToolHistoryIndex(planTemplateId, -1)
  }
  updateExecutionParamsFromParameters()
}

// Validate all parameters
const validateParameters = (): boolean => {
  parameterErrors.value = {}
  isValidationError.value = false

  if (!parameterRequirements.value.hasParameters) {
    return true
  }

  let hasErrors = false

  parameterRequirements.value.parameters.forEach(param => {
    const value = parameterValues.value[param] ? parameterValues.value[param].trim() : ''
    if (!value) {
      parameterErrors.value[param] = `${param} is required`
      hasErrors = true
    }
  })

  isValidationError.value = hasErrors
  return !hasErrors
}

// Update execution params from parameter values
const updateExecutionParamsFromParameters = () => {
  if (parameterRequirements.value.hasParameters) {
    // Convert parameter values to JSON string for execution
    executionParams.value = JSON.stringify(parameterValues.value, null, 2)
  } else {
    executionParams.value = ''
  }
  // Execution params are now managed internally, no need to emit
}

// Save current parameter set to history
const saveParameterSetToHistory = () => {
  const planTemplateId = templateConfig.currentPlanTemplateId.value
  if (!planTemplateId) {
    console.log('[ExecutionController] ⚠️ No planTemplateId, skipping history save')
    return
  }

  // Only save if there are parameters
  if (
    !parameterRequirements.value.hasParameters ||
    Object.keys(parameterValues.value).length === 0
  ) {
    console.log('[ExecutionController] ⚠️ No parameters to save to history')
    return
  }

  // Create a copy of current parameter values
  const currentSet = { ...parameterValues.value }

  // Save to persistent store (store handles deduplication)
  parameterHistoryStore.saveParameterSet(planTemplateId, currentSet)

  console.log(
    '[ExecutionController] 💾 Saved parameter set to history:',
    JSON.stringify(currentSet, null, 2)
  )

  // Reset tool-level navigation index to -1 (viewing current, not history)
  parameterHistoryStore.resetParamHistoryNavigation(planTemplateId)
}

// Reset tool navigation index
const resetParamHistoryNavigation = () => {
  const planTemplateId = templateConfig.currentPlanTemplateId.value
  if (planTemplateId) {
    parameterHistoryStore.resetParamHistoryNavigation(planTemplateId)
  }
}

// Navigate through parameter history for all parameters together
const navigateParameterSetHistory = (direction: 'up' | 'down') => {
  const planTemplateId = templateConfig.currentPlanTemplateId.value
  if (!planTemplateId) {
    console.log('[ExecutionController] ⚠️ No planTemplateId, cannot navigate history')
    return
  }

  const history = parameterHistoryStore.getHistory(planTemplateId)
  if (!history || history.length === 0) {
    console.log('[ExecutionController] ⚠️ No history available for navigation')
    return
  }

  // Get current navigation index for this tool (-1 means viewing current)
  const currentIndex = parameterHistoryStore.getToolHistoryIndex(planTemplateId)

  // Calculate new index based on direction
  let newIndex: number
  if (direction === 'up') {
    // Up means going to older history (higher index in array, since most recent is at index 0)
    if (currentIndex === -1) {
      // Currently viewing current value, go to most recent history (index 0)
      newIndex = 0
    } else if (currentIndex < history.length - 1) {
      // Go to next older entry
      newIndex = currentIndex + 1
    } else {
      // Already at oldest, stay there
      newIndex = currentIndex
    }
  } else {
    // Down means going to newer history (lower index in array)
    if (currentIndex === -1) {
      // Currently viewing current value, cannot go down
      return
    } else if (currentIndex > 0) {
      // Go to next newer entry
      newIndex = currentIndex - 1
    } else {
      // At most recent history (index 0), go to empty args
      newIndex = -1
      // Clear all parameter values
      Object.keys(parameterValues.value).forEach(param => {
        parameterValues.value[param] = ''
      })
      parameterHistoryStore.setToolHistoryIndex(planTemplateId, -1)
      updateExecutionParamsFromParameters()
      console.log('[ExecutionController] 📜 Cleared all parameter values')
      return
    }
  }

  // Update all parameter values from history if not viewing current
  if (newIndex >= 0 && newIndex < history.length) {
    const historySet = parameterHistoryStore.getParameterSetFromHistory(planTemplateId, newIndex)
    if (historySet) {
      // Update all parameter values from the history set
      Object.keys(historySet).forEach(param => {
        parameterValues.value[param] = historySet[param]
      })
      parameterHistoryStore.setToolHistoryIndex(planTemplateId, newIndex)
      updateExecutionParamsFromParameters()
      console.log(
        `[ExecutionController] 📜 Navigated to history index ${newIndex}:`,
        JSON.stringify(historySet, null, 2)
      )
    }
  } else if (newIndex === -1) {
    // Reset to current value (just reset index, values remain as user typed)
    parameterHistoryStore.setToolHistoryIndex(planTemplateId, -1)
    console.log('[ExecutionController] 📜 Reset to current values')
  }
}

// Check if tool has history available
const hasParameterHistory = (): boolean => {
  const planTemplateId = templateConfig.currentPlanTemplateId.value
  if (!planTemplateId) {
    return false
  }

  return parameterHistoryStore.hasParameterHistory(planTemplateId)
}

// Get current history index for the tool
const getToolHistoryIndex = (): number => {
  const planTemplateId = templateConfig.currentPlanTemplateId.value
  if (!planTemplateId) {
    return -1
  }
  return parameterHistoryStore.getToolHistoryIndex(planTemplateId)
}

// Get history count for current tool
const getHistoryCount = (): number => {
  const planTemplateId = templateConfig.currentPlanTemplateId.value
  if (!planTemplateId) {
    return 0
  }
  const history = parameterHistoryStore.getHistory(planTemplateId)
  return history ? history.length : 0
}

// Watch for changes in plan template ID
watch(
  () => templateConfig.currentPlanTemplateId.value,
  (newId, oldId) => {
    if (newId && newId !== oldId) {
      // Skip parameter reload if we're currently executing a plan
      if (isExecutingPlan.value) {
        console.log('[ExecutionController] ⏸️ Skipping parameter reload - plan is executing')
        return
      }

      // Reset parameter history navigation when template changes
      resetParamHistoryNavigation()

      console.log('[ExecutionController] 🔄 Template ID changed, will reload parameters')
      // If this is a new template ID (not from initial load), retry loading parameters
      if (oldId && newId.startsWith('planTemplate-')) {
        console.log('[ExecutionController] ⏰ New template detected, retrying with delay...')
        // Retry loading parameters with a delay for new templates
        setTimeout(() => {
          console.log('[ExecutionController] ⏰ Delay timeout, calling loadParameterRequirements')
          loadParameterRequirements()
        }, 1000)
      } else {
        console.log('[ExecutionController] 🚀 Immediate reload of parameters')
        loadParameterRequirements()
      }
    }
  }
)

// Watch for hasTaskRequirementModified flag change from true to false (indicates save completed)
// This is the only watch that triggers parameter refresh - only on save, not on input
watch(
  () => templateStore.hasTaskRequirementModified,
  async (newValue, oldValue) => {
    // When modification flag changes from true to false, it means save was completed
    if (oldValue === true && newValue === false && templateConfig.currentPlanTemplateId.value) {
      // Skip if currently executing
      if (isExecutingPlan.value) {
        console.log('[ExecutionController] ⏸️ Skipping parameter reload - plan is executing')
        return
      }

      // Check debounce to prevent rapid successive refreshes
      const now = Date.now()
      if (now - lastRefreshTimestamp.value < REFRESH_DEBOUNCE_MS) {
        console.log(
          '[ExecutionController] ⏸️ Skipping parameter refresh - debounced (too soon after last refresh)'
        )
        return
      }

      console.log(
        '[ExecutionController] 💾 Save completed (hasTaskRequirementModified: true -> false), refreshing parameters'
      )
      // Add a delay to ensure backend has processed the save and parameters are updated
      // Also ensure selectedTemplate has been updated by templateConfig.save()
      await new Promise(resolve => setTimeout(resolve, 2000))
      console.log('[ExecutionController] ⏰ Refreshing parameters after save')
      await refreshParameterRequirements()
    }
  }
)

// Watch for plan execution completion to reset isExecutingPlan
watchEffect(() => {
  const records = planExecution.planExecutionRecords
  const recordsArray = Array.from(records.entries())

  // Check both trackedPlanIds and planExecutionRecords to handle the case where
  // a plan has just started but hasn't been polled yet (no record in planExecutionRecords)
  const hasTrackedPlans = planExecution.trackedPlanIds.value.size > 0
  const hasRunningPlansInRecords = recordsArray.some(
    ([, record]) => record && !record.completed && record.status !== 'failed'
  )

  // If there are tracked plans but no records yet, consider it as running
  // This handles the race condition where a plan just started but hasn't been polled
  const hasRunningPlans = hasTrackedPlans || hasRunningPlansInRecords

  // Reset isExecutingPlan when all plans are completed
  if (!hasRunningPlans && isExecutingPlan.value) {
    console.log('[ExecutionController] All plans completed, resetting isExecutingPlan', {
      hasTrackedPlans,
      hasRunningPlansInRecords,
      trackedPlanIds: Array.from(planExecution.trackedPlanIds.value),
      recordsCount: recordsArray.length,
    })
    isExecutingPlan.value = false
  }
})

// Execution params are now managed internally, no need to emit updates

// Load parameters on mount
onMounted(() => {
  loadParameterRequirements()
})

// Expose methods for parent component
defineExpose({
  executionParams,
  clearExecutionParams,
  loadParameterRequirements,
  refreshParameterRequirements,
  fileUploadRef,
  get uploadedFiles() {
    return fileUpload.getUploadedFileNames()
  },
  get uploadKey() {
    return fileUpload.uploadKey.value
  },
})
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

.execution-content {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.params-requirements-group {
  label {
    display: block;
    margin-bottom: 6px;
    font-size: 12px;
    color: rgba(255, 255, 255, 0.8);
    font-weight: 500;
  }

  .params-help-text {
    margin-bottom: 12px;
    font-size: 11px;
    color: rgba(255, 255, 255, 0.6);
    line-height: 1.4;
    padding: 6px 8px;
    background: rgba(102, 126, 234, 0.1);
    border: 1px solid rgba(102, 126, 234, 0.2);
    border-radius: 4px;
  }

  .parameter-fields {
    display: flex;
    flex-direction: column;
    gap: 12px;
    margin-bottom: 12px;
  }

  .parameter-field {
    display: flex;
    flex-direction: column;
    gap: 6px;

    .parameter-label-row {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 8px;
    }

    .parameter-label {
      font-size: 11px;
      color: rgba(255, 255, 255, 0.8);
      font-weight: 500;
      display: flex;
      align-items: center;
      gap: 4px;

      .required {
        color: #ff6b6b;
        font-weight: bold;
      }
    }

    .parameter-input {
      width: 100%;
      background: rgba(0, 0, 0, 0.3);
      border: 1px solid rgba(255, 255, 255, 0.2);
      border-radius: 6px;
      color: white;
      font-size: 12px;
      font-family: 'Monaco', 'Menlo', 'Ubuntu Mono', monospace;
      padding: 8px 12px;
      min-height: 36px;

      &:focus {
        outline: none;
        border-color: #667eea;
        box-shadow: 0 0 0 2px rgba(102, 126, 234, 0.2);
      }

      &::placeholder {
        color: rgba(255, 255, 255, 0.4);
      }

      &.error {
        border-color: #ff6b6b;
        box-shadow: 0 0 0 2px rgba(255, 107, 107, 0.2);
      }

      &.viewing-history {
        background: rgba(102, 126, 234, 0.15);
        border-color: rgba(102, 126, 234, 0.4);
      }
    }

    .parameter-history-navigation {
      display: flex;
      align-items: center;
      margin-left: auto;
    }

    .history-navigation-controls {
      display: flex;
      gap: 6px;
      align-items: center;

      .history-position {
        font-size: 10px;
        color: rgba(255, 255, 255, 0.6);
        min-width: 32px;
        text-align: center;
        flex-shrink: 0;
      }

      .history-btn {
        display: flex;
        align-items: center;
        justify-content: center;
        width: 24px;
        height: 24px;
        padding: 0;
        background: rgba(102, 126, 234, 0.2);
        border: 1px solid rgba(102, 126, 234, 0.3);
        border-radius: 4px;
        color: rgba(255, 255, 255, 0.8);
        cursor: pointer;
        transition: all 0.2s ease;
        flex-shrink: 0;

        &:hover:not(:disabled) {
          background: rgba(102, 126, 234, 0.3);
          border-color: rgba(102, 126, 234, 0.5);
          color: rgba(255, 255, 255, 1);
        }

        &:disabled {
          opacity: 0.4;
          cursor: not-allowed;
        }
      }
    }

    .parameter-history-controls {
      display: flex;
      gap: 4px;
      align-items: center;

      .history-btn {
        display: flex;
        align-items: center;
        justify-content: center;
        width: 24px;
        height: 24px;
        padding: 0;
        background: rgba(102, 126, 234, 0.2);
        border: 1px solid rgba(102, 126, 234, 0.3);
        border-radius: 4px;
        color: rgba(255, 255, 255, 0.8);
        cursor: pointer;
        transition: all 0.2s ease;

        &:hover:not(:disabled) {
          background: rgba(102, 126, 234, 0.3);
          border-color: rgba(102, 126, 234, 0.5);
          color: white;
          transform: translateY(-1px);
        }

        &:active:not(:disabled) {
          transform: translateY(0);
        }

        &:disabled {
          opacity: 0.4;
          cursor: not-allowed;
        }
      }
    }

    .parameter-error {
      color: #ff6b6b;
      font-size: 11px;
      margin-top: 4px;
      display: block;
      font-family: 'Monaco', 'Menlo', 'Ubuntu Mono', monospace;
    }
  }

  .validation-message {
    display: flex;
    align-items: center;
    gap: 6px;
    color: #ffa726;
    font-size: 14px;
    margin-top: 8px;
    padding: 8px 12px;
    background: rgba(255, 167, 38, 0.1);
    border: 1px solid rgba(255, 167, 38, 0.3);
    border-radius: 4px;
    font-family: 'Monaco', 'Menlo', 'Ubuntu Mono', monospace;
  }
}

.call-example-wrapper {
  margin-top: 12px;
}

.call-example-header {
  margin-bottom: 12px;
}

.call-example-title {
  color: #667eea;
  font-size: 14px;
  font-weight: 600;
  margin: 0 0 6px 0;
}

.call-example-description {
  color: rgba(255, 255, 255, 0.8);
  font-size: 12px;
  line-height: 1.4;
  margin: 0;
  padding: 8px 12px;
  background: rgba(102, 126, 234, 0.1);
  border: 1px solid rgba(102, 126, 234, 0.2);
  border-radius: 6px;
}

.http-api-urls-wrapper {
  margin-top: 0;
}

.internal-call-wrapper,
.mcp-call-wrapper {
  background: rgba(0, 0, 0, 0.2);
  border: 1px solid rgba(255, 255, 255, 0.1);
  border-radius: 8px;
  padding: 12px;
}

.call-info {
  font-size: 11px;

  .call-method {
    display: inline-block;
    padding: 2px 6px;
    background: #667eea;
    color: white;
    border-radius: 3px;
    font-size: 10px;
    font-weight: 600;
    margin-bottom: 8px;
  }

  .call-endpoint {
    color: #64b5f6;
    font-family: 'Monaco', 'Menlo', 'Ubuntu Mono', monospace;
    font-size: 12px;
    margin-bottom: 8px;
    word-break: break-all;
    background: rgba(0, 0, 0, 0.3);
    padding: 6px 8px;
    border-radius: 4px;
    border: 1px solid rgba(100, 181, 246, 0.2);
  }

  .call-description {
    color: rgba(255, 255, 255, 0.8);
    margin-bottom: 8px;
    line-height: 1.4;
  }

  .call-example {
    margin-top: 8px;

    strong {
      color: rgba(255, 255, 255, 0.9);
      font-size: 10px;
      display: block;
      margin-bottom: 4px;
    }

    .example-code {
      background: rgba(0, 0, 0, 0.4);
      border: 1px solid rgba(255, 255, 255, 0.1);
      border-radius: 4px;
      padding: 8px;
      color: #e0e0e0;
      font-family: 'Monaco', 'Menlo', 'Ubuntu Mono', monospace;
      font-size: 10px;
      line-height: 1.3;
      white-space: pre-wrap;
      word-break: break-all;
      overflow-x: auto;
    }
  }
}

.tab-container {
  background: rgba(0, 0, 0, 0.2);
  border: 1px solid rgba(255, 255, 255, 0.1);
  border-radius: 8px;
  overflow: hidden;
}

.tab-header {
  display: flex;
  background: rgba(0, 0, 0, 0.3);
  border-bottom: 1px solid rgba(255, 255, 255, 0.1);
}

.tab-button {
  flex: 1;
  padding: 8px 12px;
  background: transparent;
  border: none;
  color: rgba(255, 255, 255, 0.6);
  font-size: 11px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.2s ease;
  border-right: 1px solid rgba(255, 255, 255, 0.1);

  &:last-child {
    border-right: none;
  }

  &:hover {
    background: rgba(102, 126, 234, 0.1);
    color: rgba(255, 255, 255, 0.8);
  }

  &.active {
    background: rgba(102, 126, 234, 0.2);
    color: #667eea;
    font-weight: 600;
  }
}

.tab-content {
  padding: 0;
}

.tab-panel {
  padding: 0;
}

.http-api-url-display {
  padding: 12px;
  font-size: 11px;

  .api-method {
    display: inline-block;
    padding: 2px 6px;
    background: #667eea;
    color: white;
    border-radius: 3px;
    font-size: 10px;
    font-weight: 600;
    margin-bottom: 8px;
  }

  .api-endpoint {
    color: #64b5f6;
    font-family: 'Monaco', 'Menlo', 'Ubuntu Mono', monospace;
    font-size: 12px;
    margin-bottom: 8px;
    word-break: break-all;
    background: rgba(0, 0, 0, 0.3);
    padding: 6px 8px;
    border-radius: 4px;
    border: 1px solid rgba(100, 181, 246, 0.2);
  }

  .api-description {
    color: rgba(255, 255, 255, 0.8);
    margin-bottom: 8px;
    line-height: 1.4;
  }

  .api-example {
    margin-top: 8px;

    strong {
      color: rgba(255, 255, 255, 0.9);
      font-size: 10px;
      display: block;
      margin-bottom: 4px;
    }

    .example-code {
      background: rgba(0, 0, 0, 0.4);
      border: 1px solid rgba(255, 255, 255, 0.1);
      border-radius: 4px;
      padding: 8px;
      color: #e0e0e0;
      font-family: 'Monaco', 'Menlo', 'Ubuntu Mono', monospace;
      font-size: 10px;
      line-height: 1.3;
      white-space: pre-wrap;
      word-break: break-all;
      overflow-x: auto;
    }
  }
}

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

  &.btn-primary {
    background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
    color: white;

    &:hover:not(:disabled) {
      transform: translateY(-1px);
      box-shadow: 0 2px 8px rgba(102, 126, 234, 0.3);
    }
  }

  &.stop-button {
    background: linear-gradient(135deg, #ff6b6b 0%, #ee5a6f 100%);
    color: white;

    &:hover:not(:disabled) {
      transform: translateY(-1px);
      box-shadow: 0 2px 8px rgba(255, 107, 107, 0.3);
    }
  }

  &.publish-mcp-btn {
    background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
    color: #ffffff;
    border: none;

    &:hover:not(:disabled) {
      transform: translateY(-2px);
      box-shadow: 0 8px 25px rgba(102, 126, 234, 0.4);
    }
  }

  &:disabled {
    opacity: 0.5;
    cursor: not-allowed;
    transform: none !important;
    box-shadow: none !important;
  }

  .spinning {
    animation: spin 1s linear infinite;
  }
}

.execute-btn,
.publish-mcp-btn {
  padding: 10px 16px;
  font-size: 13px;
  font-weight: 500;
  width: 100%;
  margin-bottom: 8px;
}

.publish-mcp-btn {
  margin-bottom: 0;
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
