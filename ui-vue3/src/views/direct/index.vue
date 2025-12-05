<!--
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
-->

<template>
  <div class="direct-page">
    <!-- Branding Header -->
    <header class="branding-header">
      <div class="branding-content">
        <div class="branding-logo">
          <img src="/Java-AI.svg" alt="Lynxe" class="java-logo" />
          <h1>Lynxe</h1>
        </div>
        <div class="branding-actions">
          <LanguageSwitcher />
          <button class="back-button" @click="goBack">
            <Icon icon="carbon:arrow-left" />
          </button>
          <button class="config-button" @click="handleConfig" :title="$t('direct.configuration')">
            <Icon icon="carbon:settings-adjust" width="20" />
          </button>
        </div>
      </div>
    </header>
    <div class="direct-chat">
      <Sidebar ref="sidebarRef" :width="sidebarWidth" />
      <!-- Sidebar Resizer -->
      <div
        class="panel-resizer"
        @mousedown="startSidebarResize"
        @dblclick="resetSidebarWidth"
        :title="$t('sidebar.resizeHint')"
      >
        <div class="resizer-line"></div>
      </div>
      <!-- Left Panel - Config/Preview (RightPanel component) -->
      <RightPanel
        ref="rightPanelRef"
        :style="{ width: 100 - sidebarWidth - leftPanelWidth + '%' }"
        :current-root-plan-id="currentRootPlanId"
      />

      <!-- Resizer -->
      <div
        class="panel-resizer"
        @mousedown="startResize"
        @dblclick="resetPanelSize"
        :title="$t('direct.panelResizeHint')"
      >
        <div class="resizer-line"></div>
      </div>

      <!-- Right Panel - Chat -->
      <div class="left-panel" :style="{ width: leftPanelWidth + '%' }">
        <div class="chat-header">
          <h2>{{ $t('conversation') }}</h2>
          <div class="header-actions">
            <button class="config-button" @click="newChat" :title="$t('memory.newChat')">
              <Icon icon="carbon:add" width="20" />
            </button>
            <button
              class="cron-task-btn"
              @click="memoryStore.toggleSidebar()"
              :title="$t('memory.selectMemory')"
            >
              <Icon icon="carbon:calendar" width="20" />
            </button>
          </div>
        </div>

        <!-- Chat Container -->
        <div class="chat-content">
          <ChatContainer @step-selected="handleStepSelected" />
        </div>

        <!-- Input Area -->
        <InputArea :key="$i18n.locale" :initial-value="prompt" />
      </div>
    </div>

    <!-- Memory Modal -->
    <Memory @memory-selected="memorySelected" />

    <!-- Toast notification component -->
    <div v-if="toast.show" class="message-toast" :class="toast.type">
      <div class="message-content">
        <span>{{ toast.text }}</span>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import ChatContainer from '@/components/chat/ChatContainer.vue'
import InputArea from '@/components/input/InputArea.vue'
import LanguageSwitcher from '@/components/language-switcher/LanguageSwitcher.vue'
import Memory from '@/components/memory/Memory.vue'
import RightPanel from '@/components/right-panel/RightPanel.vue'
import Sidebar from '@/components/sidebar/Sidebar.vue'
import { useConversationHistorySingleton } from '@/composables/useConversationHistory'
import { useMessageDialogSingleton } from '@/composables/useMessageDialog'
import { usePlanExecutionSingleton } from '@/composables/usePlanExecution'
import { useToast } from '@/composables/useToast'
import { memoryStore } from '@/stores/memory'
import { useTaskStore } from '@/stores/task'
import { templateStore } from '@/stores/templateStore'
import type { PlanExecutionRecord } from '@/types/plan-execution-record'
import { Icon } from '@iconify/vue'
import { nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

// Define component name for Vue linting rules
defineOptions({
  name: 'DirectIndex',
})

const route = useRoute()
const router = useRouter()
const taskStore = useTaskStore()
const { toast } = useToast()
const messageDialog = useMessageDialogSingleton()
const planExecution = usePlanExecutionSingleton()
const conversationHistory = useConversationHistorySingleton()

const prompt = ref<string>('')
const rightPanelRef = ref()
const sidebarRef = ref()
const currentRootPlanId = ref<string | null>(null)

// Related to panel width
// Note: leftPanelWidth variable name is kept for backward compatibility
// It actually controls the chat panel width (which is now on the right side)
const leftPanelWidth = ref(50) // Chat panel width percentage
const sidebarWidth = ref(80) // Sidebar width percentage
const isResizing = ref(false)
const startX = ref(0)
const startLeftWidth = ref(0)
// Sidebar resize state
const isSidebarResizing = ref(false)
const startSidebarX = ref(0)
const startSidebarWidth = ref(0)

onMounted(() => {
  console.log('[Direct] onMounted called')
  console.log('[Direct] taskStore.currentTask:', taskStore.currentTask)
  console.log('[Direct] taskStore.hasUnprocessedTask():', taskStore.hasUnprocessedTask())

  // Watch for plan execution record changes (reactive approach)
  watch(
    () => planExecution.planExecutionRecords,
    records => {
      // Process all records in the map
      for (const [planId, planDetails] of records.entries()) {
        // Skip completed plans early to avoid unnecessary processing
        if (planDetails?.completed) {
          continue
        }

        // Only process if this is the current root plan
        // Cast planDetails to mutable type for function parameter
        if (!shouldProcessEventForCurrentPlan(planId, false, planDetails as PlanExecutionRecord)) {
          continue
        }

        // Update config/preview panel progress
        if (
          rightPanelRef.value &&
          typeof rightPanelRef.value.updateDisplayedPlanProgress === 'function'
        ) {
          rightPanelRef.value.updateDisplayedPlanProgress(planId)
        }

        // Handle completion - only clear currentRootPlanId if this is the current plan
        // and ALL plans are completed
        if (planDetails.completed && planId === currentRootPlanId.value) {
          // Check if there are any other running plans
          const recordsArray = Array.from(records.entries())
          const hasOtherRunningPlans = recordsArray.some(
            ([otherPlanId, otherPlanDetails]) =>
              otherPlanId !== planId &&
              otherPlanDetails &&
              !otherPlanDetails.completed &&
              otherPlanDetails.status !== 'failed'
          )

          // Only clear currentRootPlanId if no other plans are running
          if (!hasOtherRunningPlans) {
            console.log('[Direct] All plans completed, clearing currentRootPlanId')
            currentRootPlanId.value = null
          } else {
            console.log(
              '[Direct] Current plan completed but other plans are still running, keeping currentRootPlanId'
            )
          }
        }
      }
    },
    { deep: true }
  )

  // Watch for new tracked plans to set currentRootPlanId
  watch(
    () => planExecution.trackedPlanIds,
    trackedIds => {
      // When a new plan is tracked, set it as currentRootPlanId if we don't have one
      if (!currentRootPlanId.value && trackedIds.value.size > 0) {
        const firstTrackedId = Array.from(trackedIds.value)[0]
        currentRootPlanId.value = firstTrackedId
        console.log('[Direct] Set currentRootPlanId to:', firstTrackedId)
      }
    },
    { deep: true }
  )

  // Restore conversation history if conversationId exists in localStorage
  const savedConversationId = memoryStore.getConversationId()
  if (savedConversationId) {
    console.log('[Direct] Found saved conversationId, restoring conversation:', savedConversationId)
    nextTick(async () => {
      try {
        await conversationHistory.restoreConversationHistory(savedConversationId)
      } catch (error) {
        console.error('[DirectView] Failed to restore conversation history:', error)
        // Don't show error message to user on page load, just log it
      }
    })
  }

  // Initialize sidebar data
  templateStore.loadPlanTemplateList()

  // Check if there is a task in the store
  if (taskStore.hasUnprocessedTask() && taskStore.currentTask) {
    const taskContent = taskStore.currentTask.prompt
    console.log('[Direct] Found unprocessed task from store:', taskContent)

    // Check if task content is not empty before processing
    if (taskContent.trim()) {
      // Mark the task as processed to prevent duplicate responses
      taskStore.markTaskAsProcessed()

      // Execute task directly without showing content in input box
      nextTick(async () => {
        try {
          console.log('[Direct] Calling messageDialog.sendMessage with taskContent:', taskContent)
          await messageDialog.sendMessage({
            input: taskContent,
          })
        } catch (error) {
          console.warn('[Direct] messageDialog.sendMessage failed, falling back to prompt:', error)
          prompt.value = taskContent
        }
      })
    } else {
      // Task has empty content, just mark it as processed and don't execute
      console.log('[Direct] Task has empty content, marking as processed without execution')
      taskStore.markTaskAsProcessed()
    }
  } else {
    // Check if there is a task to input (for pre-filling input without executing)
    // Note: InputArea automatically handles taskToInput via watch, so we don't need to clear it here
    if (taskStore.taskToInput) {
      console.log('[Direct] taskToInput available, InputArea will handle it automatically')
    } else {
      // Degrade to URL parameters (backward compatibility)
      prompt.value = (route.query.prompt as string) || ''
      console.log('[Direct] Received task from URL:', prompt.value)
      console.log('[Direct] No unprocessed task in store')
    }
  }

  // Restore panel width from localStorage
  const savedWidth = localStorage.getItem('directPanelWidth')
  if (savedWidth) {
    leftPanelWidth.value = parseFloat(savedWidth)
  }

  // Restore sidebar width from localStorage
  const savedSidebarWidth = localStorage.getItem('sidebarWidth')
  if (savedSidebarWidth) {
    sidebarWidth.value = parseFloat(savedSidebarWidth)
  }

  console.log('[Direct] Final prompt value:', prompt.value)
  // Note: InputArea automatically handles taskToInput via watch
  // Note: Plan execution is now handled directly by Sidebar.vue using messageDialog.executePlan()
})

// Listen for changes in the store's task (only handle unprocessed tasks)
watch(
  () => taskStore.currentTask,
  newTask => {
    console.log('[Direct] Watch taskStore.currentTask triggered, newTask:', newTask)
    if (newTask && !newTask.processed && newTask.prompt.trim()) {
      const taskContent = newTask.prompt
      taskStore.markTaskAsProcessed()
      console.log('[Direct] Received new task from store:', taskContent)

      // Execute task directly without showing content in input box
      nextTick(async () => {
        try {
          console.log(
            '[Direct] Directly executing new task via messageDialog.sendMessage:',
            taskContent
          )
          await messageDialog.sendMessage({ input: taskContent })
        } catch (error) {
          console.warn('[Direct] messageDialog.sendMessage failed for new task:', error)
        }
      })
    } else {
      console.log('[Direct] Task is null, already processed, or has empty prompt - ignoring')
    }
  },
  { immediate: false }
)

// Listen for changes in prompt value, only for logging purposes
watch(
  () => prompt.value,
  (newPrompt, oldPrompt) => {
    console.log('[Direct] prompt value changed from:', oldPrompt, 'to:', newPrompt)
    // Prompt is now only used for input field initial value, no automatic execution
  },
  { immediate: false }
)

// Note: taskToInput is now handled automatically by InputArea.vue

onUnmounted(() => {
  console.log('[Direct] onUnmounted called, cleaning up resources')

  // Clear current root plan ID
  currentRootPlanId.value = null

  // Clean up plan execution resources
  planExecution.cleanup()

  // Remove event listeners
  document.removeEventListener('mousemove', handleMouseMove)
  document.removeEventListener('mouseup', handleMouseUp)
  document.removeEventListener('mousemove', handleSidebarMouseMove)
  document.removeEventListener('mouseup', handleSidebarMouseUp)
})

// Methods related to panel size adjustment
const startResize = (e: MouseEvent) => {
  isResizing.value = true
  startX.value = e.clientX
  startLeftWidth.value = leftPanelWidth.value

  document.addEventListener('mousemove', handleMouseMove)
  document.addEventListener('mouseup', handleMouseUp)
  document.body.style.cursor = 'col-resize'
  document.body.style.userSelect = 'none'

  e.preventDefault()
}

const handleMouseMove = (e: MouseEvent) => {
  if (!isResizing.value) return

  const containerWidth = window.innerWidth
  const deltaX = e.clientX - startX.value
  const deltaPercent = (-deltaX / containerWidth) * 100

  let newWidth = startLeftWidth.value + deltaPercent

  // Limit panel width between 20% and 80%
  newWidth = Math.max(20, Math.min(80, newWidth))

  leftPanelWidth.value = newWidth
}

const handleMouseUp = () => {
  isResizing.value = false
  document.removeEventListener('mousemove', handleMouseMove)
  document.removeEventListener('mouseup', handleMouseUp)
  document.body.style.cursor = ''
  document.body.style.userSelect = ''

  // Save to localStorage
  localStorage.setItem('directPanelWidth', leftPanelWidth.value.toString())
}

const resetPanelSize = () => {
  leftPanelWidth.value = 50
  localStorage.setItem('directPanelWidth', '50')
}

// Sidebar resize methods
const startSidebarResize = (e: MouseEvent) => {
  isSidebarResizing.value = true
  startSidebarX.value = e.clientX
  startSidebarWidth.value = sidebarWidth.value

  document.addEventListener('mousemove', handleSidebarMouseMove)
  document.addEventListener('mouseup', handleSidebarMouseUp)
  document.body.style.cursor = 'col-resize'
  document.body.style.userSelect = 'none'

  e.preventDefault()
}

const handleSidebarMouseMove = (e: MouseEvent) => {
  if (!isSidebarResizing.value) return

  const containerWidth = window.innerWidth
  const deltaX = e.clientX - startSidebarX.value
  const deltaPercent = (deltaX / containerWidth) * 100

  let newWidth = startSidebarWidth.value + deltaPercent

  // Limit sidebar width between 15% and 100%
  newWidth = Math.max(15, Math.min(100, newWidth))

  sidebarWidth.value = newWidth
}

const handleSidebarMouseUp = () => {
  isSidebarResizing.value = false
  document.removeEventListener('mousemove', handleSidebarMouseMove)
  document.removeEventListener('mouseup', handleSidebarMouseUp)
  document.body.style.cursor = ''
  document.body.style.userSelect = ''

  // Save to localStorage
  localStorage.setItem('sidebarWidth', sidebarWidth.value.toString())
}

const resetSidebarWidth = () => {
  sidebarWidth.value = 80
  localStorage.setItem('sidebarWidth', '80')
}

// Helper function to check if the event should be processed for the current plan
const shouldProcessEventForCurrentPlan = (
  rootPlanId: string,
  allowSpecialIds: boolean = false,
  planDetails?: PlanExecutionRecord
): boolean => {
  // If no current plan is set, allow all events (initial state)
  if (!currentRootPlanId.value) {
    return true
  }

  // Check if this event is for the current active plan
  if (rootPlanId === currentRootPlanId.value) {
    return true
  }

  // Allow special IDs for UI state updates (error handling, etc.)
  if (allowSpecialIds && (rootPlanId === 'ui-state' || rootPlanId === 'error')) {
    return true
  }

  // If plan is completed, silently ignore without logging
  if (planDetails?.completed) {
    return false
  }

  // Otherwise, ignore the event (only log for active non-current plans)
  console.log(
    '[Direct] Ignoring event for non-current rootPlanId:',
    rootPlanId,
    'current:',
    currentRootPlanId.value
  )
  return false
}

const handleStepSelected = (stepId: string) => {
  console.log('[DirectView] Step selected:', stepId)

  // Forward step selection to config/preview panel
  if (rightPanelRef.value && typeof rightPanelRef.value.handleStepSelected === 'function') {
    console.log('[DirectView] Forwarding step selection to config/preview panel:', stepId)
    rightPanelRef.value.handleStepSelected(stepId)
  } else {
    console.warn('[DirectView] rightPanelRef.handleStepSelected method not available')
  }
}

const goBack = () => {
  router.push('/home')
}

const handleConfig = () => {
  router.push('/configs')
}

const memorySelected = async () => {
  // Memory sidebar is already closed by selectConversation() calling toggleSidebar()
  // Load conversation history if a conversation is selected
  if (memoryStore.conversationId) {
    console.log('[DirectView] Conversation selected:', memoryStore.conversationId)
    try {
      await conversationHistory.loadConversationHistory(memoryStore.conversationId, true, true)
    } catch (error) {
      console.error('[DirectView] Failed to load conversation history:', error)
      // Error toast is already shown by loadConversationHistory
    }
  }
}

const newChat = () => {
  memoryStore.clearSelectedConversation()
  // Reset all dialog state including conversationId to start a fresh conversation
  messageDialog.reset()
}
</script>

<style lang="less" scoped>
.direct-page {
  width: 100%;
  height: 100vh;
  display: flex;
  flex-direction: column;
  position: relative;
  overflow: hidden;
}

.branding-header {
  width: 100%;
  padding: 4px 12px;
  background: rgba(255, 255, 255, 0.02);
  border-bottom: 1px solid #1a1a1a;
  flex-shrink: 0;
  z-index: 200;
}

.branding-content {
  max-width: 100%;
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.branding-logo {
  display: flex;
  align-items: center;
  gap: 12px;

  .java-logo {
    width: 32px;
    height: 32px;
    object-fit: contain;
  }

  h1 {
    margin: 0;
    font-size: 20px;
    font-weight: 600;
    background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
    -webkit-background-clip: text;
    -webkit-text-fill-color: transparent;
    background-clip: text;
    letter-spacing: 0.5px;
  }
}

.branding-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}

.direct-chat {
  height: calc(100vh - 65px); /* Subtract branding header height */
  width: 100%;
  background: #0a0a0a;
  display: flex;
  flex: 1;
}

.left-panel {
  position: relative;
  border-right: none; /* Remove the original border, which will be provided by the resizer */
  display: flex;
  flex-direction: column;
  height: 100%; /* Fit within parent container */
  overflow: hidden; /* Prevent panel itself overflow */
  transition: width 0.1s ease; /* Smooth transition */
}

.panel-resizer {
  width: 6px;
  height: 100%; /* Fit within parent container */
  background: #1a1a1a;
  cursor: col-resize;
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: background-color 0.2s ease;
  flex-shrink: 0;

  &:hover {
    background: #2a2a2a;

    .resizer-line {
      background: #4a90e2;
      width: 2px;
    }
  }

  &:active {
    background: #3a3a3a;
  }
}

.resizer-line {
  width: 1px;
  height: 40px;
  background: #3a3a3a;
  border-radius: 1px;
  transition: all 0.2s ease;
}

/* Adjust config/preview panel styles */
:deep(.right-panel) {
  transition: width 0.1s ease;
}

.chat-header {
  padding: 12px 10px;
  border-bottom: 1px solid #1a1a1a;
  display: flex;
  align-items: center;
  gap: 16px;
  background: rgba(255, 255, 255, 0.02);
  flex-shrink: 0; /* Ensure the header will not be compressed */
  position: sticky; /* Fix the header at the top */
  top: 0;
  z-index: 100;

  h2 {
    flex: 1;
    margin: 0;
    font-size: 18px;
    font-weight: 600;
    color: #ffffff;
  }
}

.chat-content {
  flex: 1; /* Occupy remaining space */
  display: flex;
  flex-direction: column;
  min-height: 0; /* Allow shrink */
  overflow: hidden; /* Prevent overflow */
}

.header-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}

.back-button {
  padding: 8px 12px;
  border: 1px solid rgba(255, 255, 255, 0.1);
  border-radius: 6px;
  background: rgba(255, 255, 255, 0.05);
  color: #ffffff;
  cursor: pointer;
  transition: all 0.2s ease;
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 14px;

  &:hover {
    background: rgba(255, 255, 255, 0.1);
    border-color: rgba(255, 255, 255, 0.2);
  }
}

.config-button {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border: 1px solid rgba(255, 255, 255, 0.1);
  border-radius: 6px;
  background: rgba(255, 255, 255, 0.05);
  color: #ffffff;
  cursor: pointer;
  transition: all 0.2s ease;

  &:hover {
    background: rgba(255, 255, 255, 0.1);
    border-color: rgba(255, 255, 255, 0.2);
  }
}

.cron-task-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border: 1px solid rgba(255, 255, 255, 0.1);
  border-radius: 6px;
  background: rgba(255, 255, 255, 0.05);
  color: #ffffff;
  cursor: pointer;
  transition: all 0.2s ease;

  &:hover {
    background: rgba(255, 255, 255, 0.1);
    border-color: rgba(255, 255, 255, 0.2);
  }
}

.loading-prompt {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #888888;
  font-size: 16px;
  padding: 50px;
}

/* Message toast styles */
.message-toast {
  position: fixed;
  top: 80px;
  right: 24px;
  z-index: 9999;
  min-width: 320px;
  max-width: 480px;
  padding: 16px 20px;
  border-radius: 8px;
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.15);
  display: flex;
  align-items: center;
  justify-content: space-between;
  animation: slideInRight 0.3s ease-out;
  font-size: 14px;
  font-weight: 500;
}

.message-toast.error {
  color: #fff2f0;
  background-color: #ff4d4f;
}

.message-content {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: 1;
  word-break: break-all;
}

.message-content i {
  font-size: 16px;
}
</style>
