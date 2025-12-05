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

import { CommonApiService } from '@/api/common-api-service'
import { useFileUploadSingleton } from '@/composables/useFileUpload'
import { useTaskStore } from '@/stores/task'
import type { PlanExecutionRecord } from '@/types/plan-execution-record'
import { reactive, readonly, ref } from 'vue'

/**
 * Composable for managing plan execution with reactive state
 * Optimized for real-time status updates with progressive polling intervals
 */
export function usePlanExecution() {
  // Polling configuration
  const POLL_INTERVAL = 1000 // 1 second polling interval for responsive updates
  const MAX_RETRY_ATTEMPTS = 10 // Maximum retry attempts for plan not found
  const POST_COMPLETION_POLL_COUNT = 10 // Continue polling after completion to ensure summary is fetched

  // Tracked plan IDs (plans being polled)
  const trackedPlanIds = ref<Set<string>>(new Set())

  // Track completed plans that still need polling for summary
  const completedPlansPollCount = reactive(new Map<string, number>())

  // Track polling attempts per plan (for progressive intervals)
  const planPollAttempts = reactive(new Map<string, number>())

  // Track retry attempts for plans not found
  const planRetryAttempts = reactive(new Map<string, number>())

  // Reactive map of PlanExecutionRecord by planId (rootPlanId or currentPlanId)
  // This is the main reactive state that components watch
  const planExecutionRecords = reactive(new Map<string, PlanExecutionRecord>())

  // Polling state
  const isPolling = ref(false)
  const pollTimer = ref<number | null>(null)

  /**
   * Get PlanExecutionRecord by planId
   */
  const getPlanExecutionRecord = (planId: string): PlanExecutionRecord | undefined => {
    return planExecutionRecords.get(planId)
  }

  /**
   * Set a cached plan record in the reactive map
   * Useful for restoring conversation history
   */
  const setCachedPlanRecord = (planId: string, record: PlanExecutionRecord): void => {
    if (!planId) {
      console.warn('[usePlanExecution] Cannot cache plan record with empty planId')
      return
    }
    planExecutionRecords.set(planId, record)
    console.log('[usePlanExecution] Cached plan record:', planId)
  }

  /**
   * Get all tracked plan IDs
   */
  const getTrackedPlanIds = (): string[] => {
    return Array.from(trackedPlanIds.value)
  }

  /**
   * Add planId to tracking and start aggressive polling
   */
  const trackPlan = (planId: string): void => {
    if (!planId) {
      console.warn('[usePlanExecution] Cannot track empty planId')
      return
    }

    trackedPlanIds.value.add(planId)
    planPollAttempts.set(planId, 0)
    planRetryAttempts.set(planId, 0)
    console.log('[usePlanExecution] Tracking plan:', planId)

    // Start polling if not already started
    if (!pollTimer.value) {
      startPolling()
    }

    // Immediately poll the plan status (don't wait for first interval)
    pollPlanStatus(planId).catch(error => {
      console.error(`[usePlanExecution] Initial poll failed for ${planId}:`, error)
    })
  }

  /**
   * Remove planId from tracking
   */
  const untrackPlan = (planId: string): void => {
    trackedPlanIds.value.delete(planId)
    planPollAttempts.delete(planId)
    planRetryAttempts.delete(planId)
    console.log('[usePlanExecution] Untracking plan:', planId)

    // Stop polling if no more plans to track
    if (trackedPlanIds.value.size === 0 && completedPlansPollCount.size === 0) {
      stopPolling()
    }
  }

  /**
   * Poll plan execution status for a specific planId
   * Implements retry logic for plans not found and progressive polling intervals
   */
  const pollPlanStatus = async (planId: string): Promise<void> => {
    if (!planId) return

    try {
      console.log('[usePlanExecution] Polling plan status for:', planId)
      const details = await CommonApiService.getDetails(planId)
      console.log('[usePlanExecution] Received plan details:', details ? 'YES' : 'NO', details)

      // Reset retry attempts on successful fetch
      planRetryAttempts.delete(planId)

      if (!details) {
        // Plan not found - might be temporary (plan not created yet)
        const retryCount = planRetryAttempts.get(planId) || 0

        if (retryCount < MAX_RETRY_ATTEMPTS) {
          planRetryAttempts.set(planId, retryCount + 1)
          console.log(
            `[usePlanExecution] Plan ${planId} not found yet, retrying (${retryCount + 1}/${MAX_RETRY_ATTEMPTS})`
          )
          // Retry after a short delay
          setTimeout(() => {
            if (trackedPlanIds.value.has(planId)) {
              pollPlanStatus(planId).catch(error => {
                console.error(`[usePlanExecution] Retry poll failed for ${planId}:`, error)
              })
            }
          }, POLL_INTERVAL)
        } else {
          console.warn(
            `[usePlanExecution] Plan ${planId} not found after ${MAX_RETRY_ATTEMPTS} attempts, giving up`
          )
          // Remove from tracking if plan doesn't exist after max retries
          untrackPlan(planId)
        }
        return
      }

      // Increment poll attempts
      const currentAttempts = planPollAttempts.get(planId) || 0
      planPollAttempts.set(planId, currentAttempts + 1)

      // Determine the key to use (rootPlanId or currentPlanId)
      // Priority: use rootPlanId if available, otherwise use currentPlanId
      const recordKey = details.rootPlanId || details.currentPlanId

      if (!recordKey) {
        console.warn('[usePlanExecution] Plan record has no rootPlanId or currentPlanId:', details)
        return
      }

      // Update reactive map - this will trigger watchers in components
      // Always use recordKey (rootPlanId or currentPlanId) as the map key
      planExecutionRecords.set(recordKey, details)

      // If the passed planId is different from recordKey, also store it with the passed planId
      // This handles cases where the API returns a different planId than what was requested
      if (planId !== recordKey) {
        planExecutionRecords.set(planId, details)
        console.log('[usePlanExecution] Stored record with both keys:', { planId, recordKey })
      }

      console.log('[usePlanExecution] Updated plan execution record:', {
        planId,
        recordKey,
        rootPlanId: details.rootPlanId,
        currentPlanId: details.currentPlanId,
        completed: details.completed,
        status: details.status,
        attempt: currentAttempts + 1,
      })

      // Handle completion - continue polling to ensure summary is fetched
      if (details.completed) {
        console.log(`[usePlanExecution] Plan ${recordKey} completed, checking for summary...`)

        // Mark task as no longer running
        const taskStore = useTaskStore()
        if (taskStore.currentTask && taskStore.currentTask.isRunning) {
          taskStore.currentTask.isRunning = false
        }

        // Check if we have summary or if we need to continue polling
        const hasSummary = details.summary || details.result || details.message
        const currentPollCount = completedPlansPollCount.get(recordKey) || 0

        if (!hasSummary && currentPollCount < POST_COMPLETION_POLL_COUNT) {
          // Continue polling to fetch summary
          completedPlansPollCount.set(recordKey, currentPollCount + 1)
          console.log(
            `[usePlanExecution] Plan ${recordKey} completed but no summary yet, continuing to poll (${currentPollCount + 1}/${POST_COMPLETION_POLL_COUNT})`
          )
          // Don't untrack yet - keep polling
        } else {
          // We have summary or reached max poll count, proceed with cleanup
          console.log(`[usePlanExecution] Plan ${recordKey} completed, cleaning up...`, {
            hasSummary: !!hasSummary,
            pollCount: currentPollCount,
          })

          // Clear uploaded files after successful execution (similar to execution state management)
          const fileUpload = useFileUploadSingleton()
          fileUpload.clearFiles()
          console.log(`[usePlanExecution] Cleared uploaded files after execution completion`)

          // Delete execution details from backend
          try {
            await CommonApiService.deleteExecutionDetails(recordKey)
            console.log(`[usePlanExecution] Deleted execution details for plan: ${recordKey}`)
          } catch (error: unknown) {
            const message = error instanceof Error ? error.message : 'Unknown error'
            console.error(`[usePlanExecution] Failed to delete execution details: ${message}`)
          }

          // Remove from tracking
          untrackPlan(planId)

          // Clean up poll count tracking
          completedPlansPollCount.delete(recordKey)

          // Remove from reactive map after a delay
          setTimeout(() => {
            planExecutionRecords.delete(recordKey)
            if (planId !== recordKey) {
              planExecutionRecords.delete(planId)
            }
          }, 5000)
        }
      }

      // Handle errors
      if (
        details.status === 'failed' &&
        details.message &&
        !details.message.includes('Failed to get detailed information')
      ) {
        console.error(`[usePlanExecution] Plan ${recordKey} failed:`, details.message)
        // Keep the record in the map so components can handle the error
      }
    } catch (error: unknown) {
      const errorMessage = error instanceof Error ? error.message : 'Unknown error'
      console.error(`[usePlanExecution] Failed to poll plan status for ${planId}:`, errorMessage)

      // Handle network errors with retry
      const retryCount = planRetryAttempts.get(planId) || 0
      if (retryCount < MAX_RETRY_ATTEMPTS) {
        planRetryAttempts.set(planId, retryCount + 1)
        console.log(
          `[usePlanExecution] Network error for ${planId}, retrying (${retryCount + 1}/${MAX_RETRY_ATTEMPTS})`
        )
        // Retry after a delay with exponential backoff
        setTimeout(
          () => {
            if (trackedPlanIds.value.has(planId)) {
              pollPlanStatus(planId).catch(err => {
                console.error(`[usePlanExecution] Retry poll failed for ${planId}:`, err)
              })
            }
          },
          POLL_INTERVAL * (retryCount + 1)
        )
      }
    }
  }

  /**
   * Poll all tracked plans with adaptive intervals
   */
  const pollAllTrackedPlans = async (): Promise<void> => {
    if (isPolling.value) {
      console.log('[usePlanExecution] Previous polling still in progress, skipping')
      return
    }

    // Poll both tracked plans and completed plans that still need polling
    const plansToPoll = new Set(trackedPlanIds.value)
    for (const [planId] of completedPlansPollCount.entries()) {
      plansToPoll.add(planId)
    }

    if (plansToPoll.size === 0) {
      return
    }

    try {
      isPolling.value = true

      // Poll all plans in parallel
      const pollPromises = Array.from(plansToPoll).map(planId => pollPlanStatus(planId))
      await Promise.all(pollPromises)
    } catch (error: unknown) {
      console.error('[usePlanExecution] Failed to poll tracked plans:', error)
    } finally {
      isPolling.value = false
    }
  }

  /**
   * Start adaptive polling with dynamic intervals
   * Uses shorter intervals for new plans, longer intervals for established plans
   */
  const startPolling = (): void => {
    if (pollTimer.value) {
      clearInterval(pollTimer.value)
    }

    // Use 1 second interval for responsive updates
    pollTimer.value = window.setInterval(() => {
      pollAllTrackedPlans()
    }, POLL_INTERVAL)

    console.log('[usePlanExecution] Started adaptive polling')
  }

  /**
   * Stop polling
   * Only stops if there are no tracked plans AND no completed plans waiting for summary
   */
  const stopPolling = (): void => {
    // Check if there are any completed plans still being polled
    if (completedPlansPollCount.size > 0) {
      console.log(
        `[usePlanExecution] Not stopping polling - ${completedPlansPollCount.size} completed plans still waiting for summary`
      )
      return
    }

    if (pollTimer.value) {
      clearInterval(pollTimer.value)
      pollTimer.value = null
    }
    console.log('[usePlanExecution] Stopped polling')
  }

  /**
   * Immediately poll a specific plan
   */
  const pollPlanStatusImmediately = async (planId: string): Promise<void> => {
    console.log(`[usePlanExecution] Polling plan status immediately for: ${planId}`)
    await pollPlanStatus(planId)
  }

  /**
   * Handle plan execution request - track the planId and start aggressive polling
   * Called by useMessageDialog when a new plan execution starts
   * This method is the entry point for tracking plan execution
   */
  const handlePlanExecutionRequested = (planId: string): void => {
    console.log('[usePlanExecution] Received plan execution request:', { planId })

    if (!planId) {
      console.error('[usePlanExecution] Invalid plan execution request: missing planId')
      return
    }

    // Mark task as running
    const taskStore = useTaskStore()
    taskStore.setTaskRunning(planId)

    // Track the plan (this will start immediate polling)
    trackPlan(planId)
  }

  /**
   * Clean up resources
   */
  const cleanup = (): void => {
    stopPolling()
    trackedPlanIds.value.clear()
    completedPlansPollCount.clear()
    planPollAttempts.clear()
    planRetryAttempts.clear()
    planExecutionRecords.clear()
    isPolling.value = false
  }

  return {
    // Reactive state - components can watch this
    planExecutionRecords: readonly(planExecutionRecords),
    trackedPlanIds: readonly(trackedPlanIds),
    isPolling: readonly(isPolling),

    // Methods
    getPlanExecutionRecord,
    setCachedPlanRecord,
    getTrackedPlanIds,
    trackPlan,
    untrackPlan,
    handlePlanExecutionRequested,
    pollPlanStatusImmediately,
    startPolling,
    stopPolling,
    cleanup,
  }
}

// Singleton instance for global use
let singletonInstance: ReturnType<typeof usePlanExecution> | null = null

/**
 * Get or create singleton instance of usePlanExecution
 */
export function usePlanExecutionSingleton() {
  if (!singletonInstance) {
    singletonInstance = usePlanExecution()
  }
  return singletonInstance
}
