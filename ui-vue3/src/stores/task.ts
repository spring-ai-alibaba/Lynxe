/*
 * Copyright 2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import { defineStore } from 'pinia'
import { ref } from 'vue'
import { useTaskStop } from '@/composables/useTaskStop'

export interface TaskPayload {
  prompt: string
  timestamp: number
  processed?: boolean
  planId?: string
  isRunning?: boolean
}

export const useTaskStore = defineStore('task', () => {
  const currentTask = ref<TaskPayload | null>(null)
  const taskToInput = ref<string>('')
  const hasVisitedHome = ref(false)

  // Set new task
  const setTask = (prompt: string) => {
    console.log('[TaskStore] setTask called with prompt:', prompt)

    // Don't create tasks with empty prompts
    if (!prompt.trim()) {
      console.warn('[TaskStore] Empty prompt provided, not creating task')
      return
    }

    const newTask = {
      prompt,
      timestamp: Date.now(),
      processed: false,
    }
    currentTask.value = newTask
    console.log('[TaskStore] Task set, currentTask.value:', currentTask.value)
  }

  // Set task to input (for pre-filling input without executing)
  const setTaskToInput = (prompt: string) => {
    console.log('[TaskStore] setTaskToInput called with prompt:', prompt)
    taskToInput.value = prompt
    console.log('[TaskStore] Task to input set:', taskToInput.value)
  }

  // Get and clear task to input
  const getAndClearTaskToInput = () => {
    const task = taskToInput.value
    taskToInput.value = ''
    console.log('[TaskStore] getAndClearTaskToInput returning:', task)
    return task
  }

  // Mark task as processed
  const markTaskAsProcessed = () => {
    console.log('[TaskStore] markTaskAsProcessed called, current task:', currentTask.value)
    if (currentTask.value) {
      currentTask.value.processed = true
      console.log('[TaskStore] Task marked as processed:', currentTask.value)
    } else {
      console.log('[TaskStore] No current task to mark as processed')
    }
  }

  // Clear task
  const clearTask = () => {
    currentTask.value = null
  }

  // Check if there are unprocessed tasks
  const hasUnprocessedTask = () => {
    const result = currentTask.value && !currentTask.value.processed
    console.log(
      '[TaskStore] hasUnprocessedTask check - currentTask:',
      currentTask.value,
      'result:',
      result
    )
    return result
  }

  // Set that home page has been visited
  const markHomeVisited = () => {
    hasVisitedHome.value = true
    // Save to localStorage
    localStorage.setItem('hasVisitedHome', 'true')
  }

  // Check if home page has been visited
  const checkHomeVisited = () => {
    const stored = localStorage.getItem('hasVisitedHome')
    hasVisitedHome.value = stored === 'true'
    return hasVisitedHome.value
  }

  // Reset visit status (for debugging or reset)
  const resetHomeVisited = () => {
    hasVisitedHome.value = false
    localStorage.removeItem('hasVisitedHome')
  }

  // Emit plan execution requested event
  const emitPlanExecutionRequested = (payload: {
    title: string
    planData: unknown
    params?: string
  }) => {
    console.log('[TaskStore] emitPlanExecutionRequested called with payload:', payload)

    // User is on direct page, send event directly
    window.dispatchEvent(new CustomEvent('plan-execution-requested', { detail: payload }))
  }

  // Set task as running with plan ID
  const setTaskRunning = (planId: string) => {
    console.log('[TaskStore] setTaskRunning called with planId:', planId)
    // Create a task if none exists, or update existing one
    if (!currentTask.value) {
      // Create a new task for running state
      currentTask.value = {
        prompt: '', // Empty prompt since this is just for tracking running state
        timestamp: Date.now(),
        processed: false,
        planId: planId,
        isRunning: true,
      }
      console.log('[TaskStore] Created new task for running state:', currentTask.value)
    } else {
      currentTask.value.planId = planId
      currentTask.value.isRunning = true
      console.log('[TaskStore] Updated existing task:', currentTask.value)
    }
  }

  // Stop current running task
  // Uses shared stop logic from useTaskStop composable
  const stopCurrentTask = async () => {
    console.log('[TaskStore] stopCurrentTask called')
    if (currentTask.value && currentTask.value.isRunning && currentTask.value.planId) {
      // Use shared stop logic from composable
      const { stopTask } = useTaskStop()
      return await stopTask(currentTask.value.planId, true)
    }
    return false
  }

  // Check if there's a running task
  const hasRunningTask = () => {
    const result = currentTask.value && currentTask.value.isRunning
    console.log('[TaskStore] hasRunningTask check - result:', result)
    return result
  }

  return {
    currentTask,
    taskToInput,
    hasVisitedHome,
    setTask,
    setTaskToInput,
    getAndClearTaskToInput,
    markTaskAsProcessed,
    clearTask,
    hasUnprocessedTask,
    markHomeVisited,
    checkHomeVisited,
    resetHomeVisited,
    emitPlanExecutionRequested,
    setTaskRunning,
    stopCurrentTask,
    hasRunningTask,
  }
})
