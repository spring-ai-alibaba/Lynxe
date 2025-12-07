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

import { fetchAgentExecutionDetail, refreshAgentExecutionDetail } from '@/api/agent-execution'
import { useMessageDialogSingleton } from '@/composables/useMessageDialog'
import { usePlanExecutionSingleton } from '@/composables/usePlanExecution'
import type { AgentExecutionRecordDetail } from '@/types/agent-execution-detail'
import type { PlanExecutionRecord } from '@/types/plan-execution-record'
import { computed, nextTick, readonly, ref } from 'vue'

/**
 * Selected step interface
 */
export interface SelectedStep {
  stepId: string
  title: string
  description: string
  agentExecution?: AgentExecutionRecordDetail
  completed: boolean
  current: boolean
}

/**
 * Composable for managing right panel state and operations
 * Provides methods to manage step selection, file browser, and panel state
 */
export function useRightPanel() {
  // Get messageDialog singleton to access activeRootPlanId
  const messageDialog = useMessageDialogSingleton()
  const planExecution = usePlanExecutionSingleton()

  // Step selection state
  const selectedStep = ref<SelectedStep | null>(null)

  // Root plan ID for the selected step (used for file browser)
  // When a step is selected, this will be set to the step's rootPlanId
  const selectedRootPlanId = ref<string | null>(null)

  // Active tab state
  const activeTab = ref<'config' | 'details' | 'files'>('config')

  // Current root plan ID - reactively derived from useMessageDialog's activeRootPlanId
  // This gets the planId from the active dialog, ensuring each dialog maintains its own execution tracking
  const currentRootPlanId = computed(() => {
    return messageDialog.activeRootPlanId.value
  })

  /**
   * Find rootPlanId for a given stepId by searching through planExecutionRecords
   * @param stepId - The step ID to find rootPlanId for
   * @returns The rootPlanId if found, null otherwise
   */
  const findRootPlanIdForStep = (stepId: string): string | null => {
    if (!stepId) return null

    // Search through all plan execution records
    for (const [planId, record] of Object.entries(planExecution.planExecutionRecords.value)) {
      // Check if this record's rootPlanId or currentPlanId matches
      const recordKey = record.rootPlanId || record.currentPlanId
      if (!recordKey) continue

      // Search in agentExecutionSequence
      if (record.agentExecutionSequence) {
        for (const agentExecution of record.agentExecutionSequence) {
          if (agentExecution.stepId === stepId) {
            // Found the step, return the rootPlanId (or currentPlanId if rootPlanId is not available)
            const rootPlanId = record.rootPlanId || record.currentPlanId
            console.log('[useRightPanel] Found rootPlanId for stepId:', {
              stepId,
              rootPlanId,
              planId,
            })
            return rootPlanId || null
          }

          // Also check in subPlanExecutionRecords recursively
          if (agentExecution.subPlanExecutionRecords) {
            for (const subPlan of agentExecution.subPlanExecutionRecords) {
              // Use type assertion to handle readonly types from reactive Map
              const subRootPlanId = findRootPlanIdInSubPlan(
                subPlan as unknown as PlanExecutionRecord,
                stepId
              )
              if (subRootPlanId) {
                return subRootPlanId
              }
            }
          }
        }
      }
    }

    console.warn('[useRightPanel] Could not find rootPlanId for stepId:', stepId)
    return null
  }

  /**
   * Recursively search for stepId in sub-plan execution records
   * @param subPlan - The sub-plan record to search
   * @param stepId - The step ID to find
   * @returns The rootPlanId if found, null otherwise
   */
  const findRootPlanIdInSubPlan = (subPlan: PlanExecutionRecord, stepId: string): string | null => {
    if (!subPlan || !subPlan.agentExecutionSequence) return null

    for (const agentExecution of subPlan.agentExecutionSequence) {
      if (agentExecution.stepId === stepId) {
        return subPlan.rootPlanId || subPlan.currentPlanId || null
      }

      if (agentExecution.subPlanExecutionRecords) {
        for (const nestedSubPlan of agentExecution.subPlanExecutionRecords) {
          const result = findRootPlanIdInSubPlan(nestedSubPlan, stepId)
          if (result) return result
        }
      }
    }

    return null
  }

  /**
   * Computed property to determine which planId to show in file browser
   * Priority: selectedRootPlanId (when a step is selected) > currentRootPlanId (current active plan)
   */
  const fileBrowserPlanId = computed(() => {
    return selectedRootPlanId.value || currentRootPlanId.value
  })

  /**
   * Computed property to determine if we should show the "no task" message
   */
  const shouldShowNoTaskMessage = computed(() => {
    return !fileBrowserPlanId.value
  })

  /**
   * Handle step selection by stepId
   * @param stepId - The step ID to display
   */
  const handleStepSelected = async (stepId: string): Promise<void> => {
    console.log('[useRightPanel] Step selected:', { stepId })

    if (!stepId) {
      console.warn('[useRightPanel] No stepId provided')
      selectedStep.value = null
      selectedRootPlanId.value = null
      return
    }

    // Switch to 'details' tab when a step is selected
    setActiveTab('details')
    console.log('[useRightPanel] Switched to details tab')

    try {
      // Find the rootPlanId for this step first
      const rootPlanId = findRootPlanIdForStep(stepId)
      if (rootPlanId) {
        selectedRootPlanId.value = rootPlanId
        console.log('[useRightPanel] Set selectedRootPlanId to:', rootPlanId)
      } else {
        // If not found, clear it (will fall back to currentRootPlanId)
        selectedRootPlanId.value = null
        console.warn(
          '[useRightPanel] Could not find rootPlanId for step, will use currentRootPlanId'
        )
      }

      // Fetch agent execution detail from API
      const agentExecutionDetail = await fetchAgentExecutionDetail(stepId)

      if (!agentExecutionDetail) {
        console.warn('[useRightPanel] Agent execution detail not found for stepId:', stepId)
        selectedStep.value = null
        selectedRootPlanId.value = null
        return
      }

      // Create step data object
      const stepData: SelectedStep = {
        stepId: stepId,
        title: agentExecutionDetail.agentName ?? `Step ${stepId}`,
        description: agentExecutionDetail.agentDescription ?? '',
        agentExecution: agentExecutionDetail,
        completed: agentExecutionDetail.status === 'FINISHED',
        current: agentExecutionDetail.status === 'RUNNING',
      }

      selectedStep.value = stepData
      console.log('[useRightPanel] Step details updated:', stepData)

      // Force reactivity update
      await nextTick()
      console.log('[useRightPanel] After nextTick - selectedStep:', selectedStep.value)
    } catch (error) {
      console.error('[useRightPanel] Error fetching step details:', error)
      selectedStep.value = null
      selectedRootPlanId.value = null
    }
  }

  /**
   * Refresh the currently selected step
   */
  const refreshCurrentStep = async (): Promise<void> => {
    if (!selectedStep.value?.stepId) {
      console.warn('[useRightPanel] No step selected for refresh')
      return
    }

    console.log('[useRightPanel] Refreshing current step:', selectedStep.value.stepId)

    try {
      const agentExecutionDetail = await refreshAgentExecutionDetail(selectedStep.value.stepId)

      if (agentExecutionDetail) {
        // Update the existing step data
        selectedStep.value.agentExecution = agentExecutionDetail
        selectedStep.value.completed = agentExecutionDetail.status === 'FINISHED'
        selectedStep.value.current = agentExecutionDetail.status === 'RUNNING'

        console.log('[useRightPanel] Step refreshed successfully')
      }
    } catch (error) {
      console.error('[useRightPanel] Error refreshing step:', error)
    }
  }

  /**
   * Set active tab
   * @param tab - The tab to activate ('config', 'details', or 'files')
   */
  const setActiveTab = (tab: 'config' | 'details' | 'files'): void => {
    activeTab.value = tab
    console.log('[useRightPanel] Active tab set to:', tab)
  }

  /**
   * Update displayed plan progress
   * This method is kept for backward compatibility but no longer needed
   * since currentRootPlanId is now reactively derived from useMessageDialog
   * @param rootPlanId - The root plan ID to update (deprecated, kept for compatibility)
   */
  const updateDisplayedPlanProgress = (rootPlanId: string): void => {
    console.log('[useRightPanel] updateDisplayedPlanProgress called with rootPlanId:', rootPlanId)
    // No-op: currentRootPlanId is now reactively derived from useMessageDialog
  }

  /**
   * Clear selected step
   */
  const clearSelectedStep = (): void => {
    selectedStep.value = null
    selectedRootPlanId.value = null
    console.log('[useRightPanel] Selected step cleared')
  }

  /**
   * Format JSON data for display
   * @param jsonData - The data to format
   * @returns Formatted JSON string or 'N/A' if invalid
   */
  const formatJson = (jsonData: unknown): string => {
    // Handle null, undefined, or empty string
    if (jsonData === null || typeof jsonData === 'undefined' || jsonData === '') {
      return 'N/A'
    }

    // If it's already an object, stringify it directly
    if (typeof jsonData === 'object' && jsonData !== null) {
      try {
        return JSON.stringify(jsonData, null, 2)
      } catch {
        return String(jsonData)
      }
    }

    // If it's a string, try to parse it as JSON
    if (typeof jsonData === 'string') {
      const trimmed = jsonData.trim()
      if (trimmed === '') {
        return 'N/A'
      }

      try {
        // Try to parse as JSON
        const parsed = JSON.parse(trimmed)
        // Re-stringify with formatting
        return JSON.stringify(parsed, null, 2)
      } catch {
        // If parsing fails, return the string as-is (might be plain text)
        return trimmed
      }
    }

    // For other types (number, boolean, etc.), convert to string
    return String(jsonData)
  }

  /**
   * Reset state
   */
  const reset = (): void => {
    selectedStep.value = null
    selectedRootPlanId.value = null
    activeTab.value = 'config'
    // Note: currentRootPlanId is computed from useMessageDialog, so no need to reset it
    console.log('[useRightPanel] State reset')
  }

  return {
    // State
    selectedStep: readonly(selectedStep),
    activeTab,

    // Computed
    currentRootPlanId,
    fileBrowserPlanId,
    shouldShowNoTaskMessage,

    // Methods
    handleStepSelected,
    refreshCurrentStep,
    setActiveTab,
    updateDisplayedPlanProgress,
    clearSelectedStep,
    formatJson,
    reset,
  }
}

// Singleton instance for global use
let singletonInstance: ReturnType<typeof useRightPanel> | null = null

/**
 * Get or create singleton instance of useRightPanel
 */
export function useRightPanelSingleton() {
  if (!singletonInstance) {
    singletonInstance = useRightPanel()
  }
  return singletonInstance
}
