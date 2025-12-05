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

import { PlanActApiService } from '@/api/plan-act-api-service'
import { PlanTemplateApiService } from '@/api/plan-template-with-tool-api-service'
import type {
  InputSchemaParam,
  PlanTemplateConfigVO,
  StepConfig,
  ToolConfigVO,
} from '@/types/plan-template'
import { computed, reactive, ref } from 'vue'

/**
 * Composable for managing PlanTemplateConfigVO
 * Provides getter and setter methods for all properties
 */
export function usePlanTemplateConfig() {
  // Reactive state for PlanTemplateConfigVO
  const config = reactive<PlanTemplateConfigVO>({
    title: '',
    steps: [],
    planType: 'dynamic_agent',
    planTemplateId: '',
    accessLevel: 'editable',
    serviceGroup: '',
  })

  // Loading state
  const isLoading = ref(false)
  const error = ref<string | null>(null)

  // Flag to prevent watchers from syncing when user is updating individual properties
  const isUserUpdating = ref(false)

  // Flag to indicate that a full refresh is needed (for load, setConfig, fromJsonString, reset, version control)
  const needsFullRefresh = ref(false)

  // Version control state
  const planVersions = ref<string[]>([])
  const currentVersionIndex = ref(-1)

  // Template list and selection state
  const planTemplateList = ref<PlanTemplateConfigVO[]>([])
  const selectedTemplate = ref<PlanTemplateConfigVO | null>(null)
  const currentPlanTemplateId = ref<string | null>(null)

  // Getters
  const getTitle = () => config.title
  const getPlanType = () => config.planType || 'dynamic_agent'
  const getServiceGroup = () => config.serviceGroup || ''

  // Setters
  const setTitle = (title: string) => {
    config.title = title
  }

  const setSteps = (steps: StepConfig[]) => {
    config.steps = steps || []
  }

  const setPlanType = (planType: string) => {
    config.planType = planType
  }

  const setPlanTemplateId = (planTemplateId: string) => {
    config.planTemplateId = planTemplateId
  }

  const setServiceGroup = (serviceGroup: string) => {
    config.serviceGroup = serviceGroup
  }

  const setToolConfig = (toolConfig: ToolConfigVO | undefined) => {
    if (toolConfig === undefined) {
      delete config.toolConfig
    } else {
      config.toolConfig = toolConfig
    }
  }

  // ToolConfig getters (no getters are used, only setters)

  // ToolConfig setters
  const setToolDescription = (toolDescription: string) => {
    if (!config.toolConfig) {
      config.toolConfig = {}
    }
    config.toolConfig.toolDescription = toolDescription
  }

  const setEnableInternalToolcall = (enable: boolean) => {
    if (!config.toolConfig) {
      config.toolConfig = {}
    }
    config.toolConfig.enableInternalToolcall = enable
  }

  const setEnableHttpService = (enable: boolean) => {
    if (!config.toolConfig) {
      config.toolConfig = {}
    }
    config.toolConfig.enableHttpService = enable
  }

  const setEnableInConversation = (enable: boolean) => {
    if (!config.toolConfig) {
      config.toolConfig = {}
    }
    config.toolConfig.enableInConversation = enable
  }

  const setInputSchema = (inputSchema: InputSchemaParam[]) => {
    if (!config.toolConfig) {
      config.toolConfig = {}
    }
    config.toolConfig.inputSchema = inputSchema || []
  }

  // Get full config
  const getConfig = (): PlanTemplateConfigVO => {
    return { ...config }
  }

  // Set full config
  const setConfig = (newConfig: PlanTemplateConfigVO) => {
    needsFullRefresh.value = true
    // Get accessLevel with backward compatibility for readOnly
    const accessLevel = newConfig.accessLevel || (newConfig.readOnly ? 'readOnly' : 'editable')
    const updatedConfig: PlanTemplateConfigVO = {
      title: newConfig.title || '',
      // Deep copy steps to preserve all properties including selectedToolKeys
      // Convert null selectedToolKeys to empty array for consistency
      steps: (newConfig.steps || []).map(step => ({
        ...step,
        selectedToolKeys: step.selectedToolKeys ?? [],
      })),
      planType: newConfig.planType || 'dynamic_agent',
      planTemplateId: newConfig.planTemplateId || '',
      accessLevel: accessLevel,
      serviceGroup: newConfig.serviceGroup || '',
    }
    if (newConfig.toolConfig) {
      updatedConfig.toolConfig = { ...newConfig.toolConfig }
    }
    Object.assign(config, updatedConfig)
    // Reset flag after reactivity update
    // Use a longer delay to ensure watchers have time to process the change
    setTimeout(() => {
      needsFullRefresh.value = false
    }, 50)
  }

  // Reset to default
  const reset = () => {
    needsFullRefresh.value = true
    Object.assign(config, {
      title: '',
      steps: [],
      planType: 'dynamic_agent',
      planTemplateId: '',
      accessLevel: 'editable',
      serviceGroup: '',
    })
    // Remove toolConfig if it exists
    if ('toolConfig' in config) {
      delete config.toolConfig
    }
    planVersions.value = []
    currentVersionIndex.value = -1
    error.value = null
    // Reset flag after reactivity update
    setTimeout(() => {
      needsFullRefresh.value = false
    }, 0)
  }

  // Dynamically generate JSON from current config state (not cached, regenerated each time)
  const generateJsonString = (): string => {
    // Generate fresh JSON from current config state
    // Ensure selectedToolKeys is always an array (not null) in the output
    const accessLevel = config.accessLevel || (config.readOnly ? 'readOnly' : 'editable')
    const jsonConfig: PlanTemplateConfigVO = {
      title: config.title || '',
      steps: (config.steps || []).map(step => ({
        ...step,
        // Ensure selectedToolKeys is always an array, not null
        selectedToolKeys: step.selectedToolKeys ?? [],
      })),
      planType: config.planType || 'dynamic_agent',
      planTemplateId: config.planTemplateId || '',
      accessLevel: accessLevel,
      serviceGroup: config.serviceGroup || '',
    }
    // Include toolConfig if it exists
    if (config.toolConfig) {
      jsonConfig.toolConfig = { ...config.toolConfig }
    }
    return JSON.stringify(jsonConfig, null, 2)
  }

  // Load config from JSON string
  const fromJsonString = (jsonString: string) => {
    try {
      const parsed = JSON.parse(jsonString)
      setConfig(parsed)
      return true
    } catch (err) {
      error.value = err instanceof Error ? err.message : 'Invalid JSON format'
      return false
    }
  }

  // Version control methods
  const canRollback = computed(() => {
    return planVersions.value.length > 1 && currentVersionIndex.value > 0
  })

  const canRestore = computed(() => {
    return (
      planVersions.value.length > 1 && currentVersionIndex.value < planVersions.value.length - 1
    )
  })

  const rollbackVersion = () => {
    if (canRollback.value && currentVersionIndex.value > 0) {
      currentVersionIndex.value--
      const versionContent = planVersions.value[currentVersionIndex.value] || ''
      fromJsonString(versionContent)
    }
  }

  const restoreVersion = () => {
    if (canRestore.value && currentVersionIndex.value < planVersions.value.length - 1) {
      currentVersionIndex.value++
      const versionContent = planVersions.value[currentVersionIndex.value] || ''
      fromJsonString(versionContent)
    }
  }

  // Update versions after save
  const updateVersionsAfterSave = (content: string) => {
    if (currentVersionIndex.value < planVersions.value.length - 1) {
      planVersions.value = planVersions.value.slice(0, currentVersionIndex.value + 1)
    }
    planVersions.value.push(content)
    currentVersionIndex.value = planVersions.value.length - 1
  }

  // Load from API
  const load = async (planTemplateId: string) => {
    if (!planTemplateId) {
      error.value = 'Plan template ID is required'
      return false
    }

    try {
      isLoading.value = true
      error.value = null

      // Load config from API
      const loadedConfig = await PlanTemplateApiService.getPlanTemplateConfigVO(planTemplateId)

      // Check if this is the same template being reloaded
      const isSameTemplate = currentPlanTemplateId.value === planTemplateId

      // Update currentPlanTemplateId BEFORE setConfig to ensure watchers can detect the change
      // If it's the same template, temporarily set to null first to trigger watch
      if (isSameTemplate) {
        currentPlanTemplateId.value = null
        // Wait for the null assignment to be processed by watchers
        await new Promise(resolve => setTimeout(resolve, 0))
      }

      // Ensure isUserUpdating is false before loading to allow sync
      isUserUpdating.value = false

      setConfig(loadedConfig)

      // Update currentPlanTemplateId to ensure watchers can detect the change
      currentPlanTemplateId.value = planTemplateId

      // Force a small delay to ensure watchers have processed the changes
      // This is especially important when reloading the same template
      await new Promise(resolve => setTimeout(resolve, 10))

      // Update selectedTemplate.value to keep it in sync with the loaded config
      // This ensures RightPanel.vue uses the latest data from API, not stale data from template list
      if (selectedTemplate.value?.planTemplateId === planTemplateId) {
        selectedTemplate.value = {
          ...selectedTemplate.value,
          ...loadedConfig,
        }
      }

      // Load versions for version control
      try {
        const versionsResponse = await PlanActApiService.getPlanVersions(planTemplateId)
        planVersions.value = (versionsResponse as { versions?: string[] }).versions || []
        if (planVersions.value.length > 0) {
          currentVersionIndex.value = planVersions.value.length - 1
        } else {
          currentVersionIndex.value = -1
        }
      } catch (versionError) {
        console.warn('Failed to load plan versions:', versionError)
        planVersions.value = []
        currentVersionIndex.value = -1
      }

      return true
    } catch (err) {
      error.value = err instanceof Error ? err.message : 'Failed to load plan template config'
      console.error('Failed to load plan template config:', err)
      return false
    } finally {
      isLoading.value = false
    }
  }

  // Save to API
  const save = async (): Promise<boolean> => {
    if (!config.planTemplateId) {
      error.value = 'Plan template ID is required'
      return false
    }

    try {
      isLoading.value = true
      error.value = null

      const result = await PlanTemplateApiService.createOrUpdatePlanTemplateWithTool(getConfig())

      if (result.success) {
        // Use the planTemplateId from backend response (may be different if frontend sent "new-xxx")
        const actualPlanTemplateId = result.planTemplateId || config.planTemplateId

        // Update local config with the actual planTemplateId from backend
        if (actualPlanTemplateId && actualPlanTemplateId !== config.planTemplateId) {
          console.log(
            '[usePlanTemplateConfig] PlanTemplateId replaced by backend:',
            config.planTemplateId,
            '->',
            actualPlanTemplateId
          )
          config.planTemplateId = actualPlanTemplateId
        }

        // Reload the template to get updated data from backend
        if (actualPlanTemplateId) {
          await load(actualPlanTemplateId)

          // Update selectedTemplate if it matches the saved template
          // Check both old and new planTemplateId to handle ID replacement
          const oldPlanTemplateId = selectedTemplate.value?.planTemplateId
          if (
            oldPlanTemplateId === actualPlanTemplateId ||
            oldPlanTemplateId === config.planTemplateId
          ) {
            const loadedConfig = getConfig()
            selectedTemplate.value = {
              ...selectedTemplate.value,
              ...loadedConfig,
              planTemplateId: actualPlanTemplateId, // Ensure using the actual ID
            }
          }

          // Update planTemplateList if the template exists in the list
          // Check both old and new planTemplateId
          const templateIndex = planTemplateList.value.findIndex(
            t => t.planTemplateId === actualPlanTemplateId || t.planTemplateId === oldPlanTemplateId
          )
          if (templateIndex >= 0) {
            const loadedConfig = getConfig()
            planTemplateList.value[templateIndex] = {
              ...planTemplateList.value[templateIndex],
              ...loadedConfig,
              planTemplateId: actualPlanTemplateId, // Ensure using the actual ID
            }
          } else if (oldPlanTemplateId) {
            // If template was not found in list but we have old ID, try to find and update it
            const oldTemplateIndex = planTemplateList.value.findIndex(
              t => t.planTemplateId === oldPlanTemplateId
            )
            if (oldTemplateIndex >= 0) {
              const loadedConfig = getConfig()
              planTemplateList.value[oldTemplateIndex] = {
                ...planTemplateList.value[oldTemplateIndex],
                ...loadedConfig,
                planTemplateId: actualPlanTemplateId, // Update to new ID
              }
            }
          }
        }
      }

      return result.success
    } catch (err) {
      error.value = err instanceof Error ? err.message : 'Failed to save plan template config'
      console.error('Failed to save plan template config:', err)
      return false
    } finally {
      isLoading.value = false
    }
  }

  // Validation
  const validate = (): { isValid: boolean; errors: string[] } => {
    const errors: string[] = []

    if (!config.planTemplateId?.trim()) {
      errors.push('Plan template ID is required')
    }

    if (!config.title?.trim()) {
      errors.push('Title is required')
    }

    if (!config.steps || config.steps.length === 0) {
      errors.push('At least one step is required')
    }

    return {
      isValid: errors.length === 0,
      errors,
    }
  }

  // Computed properties (isValid and hasToolConfig are not used)

  // Utility function to parse date from different formats
  const parseDateTime = (dateValue: unknown): Date => {
    if (!dateValue) {
      return new Date()
    }

    // If array format [year, month, day, hour, minute, second, nanosecond]
    if (Array.isArray(dateValue) && dateValue.length >= 6) {
      // JavaScript Date constructor months start from 0, so subtract 1
      return new Date(
        dateValue[0],
        dateValue[1] - 1,
        dateValue[2],
        dateValue[3],
        dateValue[4],
        dateValue[5],
        Math.floor(dateValue[6] / 1000000)
      )
    }

    // If string format, parse directly
    if (typeof dateValue === 'string') {
      return new Date(dateValue)
    }

    // Return current time for other cases
    return new Date()
  }

  // Action handlers (handleRollback, handleRestore, handleSave are not used)

  /**
   * Get all coordinator tools from planTemplateList
   * Filters templates that have toolConfig with enableInternalToolcall enabled
   * @returns Array of coordinator tools with tool configuration data
   */
  const getAllCoordinatorToolsFromTemplates = () => {
    const tools: Array<{
      toolName: string
      toolDescription: string
      planTemplateId: string
      inputSchema: string
      enableInternalToolcall: boolean
      enableHttpService: boolean
      enableInConversation: boolean
      serviceGroup?: string
    }> = []

    for (const template of planTemplateList.value) {
      // Only include templates with toolConfig
      if (!template.toolConfig) {
        continue
      }

      const toolConfig = template.toolConfig

      // Only include tools with enableInternalToolcall enabled
      if (!toolConfig.enableInternalToolcall) {
        continue
      }

      // Convert inputSchema array to JSON string
      let inputSchemaJson = '[]'
      if (toolConfig.inputSchema && Array.isArray(toolConfig.inputSchema)) {
        inputSchemaJson = JSON.stringify(toolConfig.inputSchema)
      }

      const tool: {
        toolName: string
        toolDescription: string
        planTemplateId: string
        inputSchema: string
        enableInternalToolcall: boolean
        enableHttpService: boolean
        enableInConversation: boolean
        serviceGroup?: string
      } = {
        toolName: template.title || '',
        toolDescription: toolConfig.toolDescription || '',
        planTemplateId: template.planTemplateId || '',
        inputSchema: inputSchemaJson,
        enableInternalToolcall: toolConfig.enableInternalToolcall ?? true,
        enableHttpService: toolConfig.enableHttpService ?? false,
        enableInConversation: toolConfig.enableInConversation ?? false,
      }

      if (template.serviceGroup) {
        tool.serviceGroup = template.serviceGroup
      }

      tools.push(tool)
    }

    return tools
  }

  /**
   * Get coordinator tool configuration status
   * Determines if coordinator tools are enabled based on planTemplateList
   * @returns boolean indicating if coordinator tools are enabled
   */
  const getCoordinatorToolConfig = (): boolean => {
    // Check if there are any templates with toolConfig
    return planTemplateList.value.some(template => template.toolConfig !== undefined)
  }

  // Helper method to wrap updates with guard flag
  const withUpdateGuard = <T>(callback: () => T): T => {
    isUserUpdating.value = true
    try {
      return callback()
    } finally {
      // Use setTimeout to ensure the flag is reset after reactivity updates
      setTimeout(() => {
        isUserUpdating.value = false
      }, 0)
    }
  }

  // Wrapped setters that prevent watcher syncing
  const setStepsWithGuard = (steps: StepConfig[]) => {
    return withUpdateGuard(() => {
      setSteps(steps)
    })
  }

  const setToolConfigWithGuard = (toolConfig: ToolConfigVO | undefined) => {
    return withUpdateGuard(() => {
      setToolConfig(toolConfig)
    })
  }

  const setToolDescriptionWithGuard = (toolDescription: string) => {
    return withUpdateGuard(() => {
      setToolDescription(toolDescription)
    })
  }

  const setEnableInternalToolcallWithGuard = (enable: boolean) => {
    return withUpdateGuard(() => {
      setEnableInternalToolcall(enable)
    })
  }

  const setEnableHttpServiceWithGuard = (enable: boolean) => {
    return withUpdateGuard(() => {
      setEnableHttpService(enable)
    })
  }

  const setEnableInConversationWithGuard = (enable: boolean) => {
    return withUpdateGuard(() => {
      setEnableInConversation(enable)
    })
  }

  const setInputSchemaWithGuard = (inputSchema: InputSchemaParam[]) => {
    return withUpdateGuard(() => {
      setInputSchema(inputSchema)
    })
  }

  return {
    // State
    config,
    isLoading,
    error,
    isUserUpdating,
    needsFullRefresh,
    planVersions,
    currentVersionIndex,
    planTemplateList,
    selectedTemplate,
    currentPlanTemplateId,

    // Getters
    getTitle,
    getPlanType,
    getServiceGroup,
    getConfig,

    // Setters
    setTitle,
    setSteps,
    setPlanType,
    setPlanTemplateId,
    setServiceGroup,
    setToolConfig,
    setToolDescription,
    setEnableInternalToolcall,
    setEnableHttpService,
    setEnableInConversation,
    setInputSchema,
    setConfig,

    // Guarded setters (prevent watcher syncing)
    setStepsWithGuard,
    setToolConfigWithGuard,
    setToolDescriptionWithGuard,
    setEnableInternalToolcallWithGuard,
    setEnableHttpServiceWithGuard,
    setEnableInConversationWithGuard,
    setInputSchemaWithGuard,
    withUpdateGuard,

    // Actions
    reset,
    load,
    save,
    validate,
    generateJsonString,
    fromJsonString,
    rollbackVersion,
    restoreVersion,
    updateVersionsAfterSave,

    // Computed
    canRollback,
    canRestore,

    // Utility functions
    parseDateTime,

    // Coordinator tools methods
    getAllCoordinatorToolsFromTemplates,
    getCoordinatorToolConfig,
  }
}

// Singleton instance for global use
let singletonInstance: ReturnType<typeof usePlanTemplateConfig> | null = null

/**
 * Get or create singleton instance of usePlanTemplateConfig
 */
export function usePlanTemplateConfigSingleton() {
  if (!singletonInstance) {
    singletonInstance = usePlanTemplateConfig()
  }
  return singletonInstance
}
