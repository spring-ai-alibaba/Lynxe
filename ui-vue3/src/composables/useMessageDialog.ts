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

import { DirectApiService } from '@/api/direct-api-service'
import { PlanActApiService } from '@/api/plan-act-api-service'
import { usePlanExecutionSingleton } from '@/composables/usePlanExecution'
import { memoryStore } from '@/stores/memory'
import type {
  ChatMessage,
  CompatiblePlanExecutionRecord,
  InputMessage,
  MessageDialog,
} from '@/types/message-dialog'
import type { PlanExecutionRequestPayload } from '@/types/plan-execution'
import type { AgentExecutionRecord, PlanExecutionRecord } from '@/types/plan-execution-record'
import { computed, readonly, ref, watchEffect } from 'vue'

/**
 * Composable for managing message dialogs
 * Provides methods to manage dialog list and send messages
 */
export function useMessageDialog() {
  // Plan execution manager
  const planExecution = usePlanExecutionSingleton()

  // Dialog list state
  const dialogList = ref<MessageDialog[]>([])
  const activeDialogId = ref<string | null>(null)

  // Maintain conversationId independently (persisted)
  // Relationship: conversationId 1:n rootPlanId 1:n dialogId
  // - conversationId: Persistent conversation identifier
  // - rootPlanId: Plan execution identifier (one conversation can have multiple plans)
  // - dialogId: Round identifier (one plan can have multiple dialog rounds)
  const conversationId = ref<string | null>(null)

  // Maintain rootPlanId independently (not persisted)
  // Relationship: rootPlanId 1:n dialogId (one plan can have multiple dialog rounds)
  const rootPlanId = ref<string | null>(null)

  // Computed active rootPlanId - now just returns the maintained rootPlanId
  const activeRootPlanId = computed(() => {
    return rootPlanId.value
  })

  // Loading state
  const isLoading = ref(false)
  const error = ref<string | null>(null)
  const streamingMessageId = ref<string | null>(null)
  const inputPlaceholder = ref<string | null>(null)

  // Computed properties
  const activeDialog = computed(() => {
    if (!activeDialogId.value) {
      return null
    }
    return dialogList.value.find(dialog => dialog.id === activeDialogId.value) || null
  })

  const hasDialogs = computed(() => {
    return dialogList.value.length > 0
  })

  const dialogCount = computed(() => {
    return dialogList.value.length
  })

  // Messages from all dialogs in the current conversation
  // Since each round has its own dialogId, we need to merge messages from all dialogs
  // that share the same conversationId
  const messages = computed(() => {
    if (!conversationId.value) {
      // If no conversationId, return messages from active dialog only
      return activeDialog.value?.messages || []
    }
    // Merge messages from all dialogs with the same conversationId
    const allMessages: ChatMessage[] = []
    for (const dialog of dialogList.value) {
      if (dialog.conversationId === conversationId.value) {
        allMessages.push(...dialog.messages)
      }
    }
    // Sort by timestamp to maintain chronological order
    return allMessages.sort((a, b) => a.timestamp.getTime() - b.timestamp.getTime())
  })

  /**
   * Create a new dialog for each conversation round
   * Each round (user message + assistant response) gets a new dialogId
   * Relationship: conversationId 1:n rootPlanId 1:n dialogId
   */
  const createDialog = (title?: string): MessageDialog => {
    const dialog: MessageDialog = {
      id: `dialog_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`,
      title: title || 'New Conversation',
      messages: [],
      ...(conversationId.value && { conversationId: conversationId.value }), // Link to conversation if exists
      ...(rootPlanId.value && { planId: rootPlanId.value }), // Link to plan if exists
      createdAt: new Date(),
      updatedAt: new Date(),
      isActive: true,
    }

    dialogList.value.push(dialog)
    activeDialogId.value = dialog.id
    console.log('[useMessageDialog] Created new dialog round:', dialog.id, {
      conversationId: conversationId.value,
      rootPlanId: rootPlanId.value,
    })
    return dialog
  }

  /**
   * Get dialog by ID
   */
  const getDialog = (dialogId: string): MessageDialog | null => {
    return dialogList.value.find(dialog => dialog.id === dialogId) || null
  }

  /**
   * Set active dialog
   */
  const setActiveDialog = (dialogId: string | null) => {
    // Deactivate all dialogs
    dialogList.value.forEach(dialog => {
      dialog.isActive = false
    })

    // Activate selected dialog
    if (dialogId) {
      const dialog = dialogList.value.find(d => d.id === dialogId)
      if (dialog) {
        dialog.isActive = true
        activeDialogId.value = dialogId
      }
    } else {
      activeDialogId.value = null
    }
  }

  /**
   * Add message to a dialog
   */
  const addMessageToDialog = (dialogId: string, message: ChatMessage) => {
    const dialog = dialogList.value.find(d => d.id === dialogId)
    if (dialog) {
      dialog.messages.push(message)
      dialog.updatedAt = new Date()
    }
  }

  /**
   * Update message in a dialog
   */
  const updateMessageInDialog = (
    dialogId: string,
    messageId: string,
    updates: Partial<ChatMessage>
  ) => {
    const dialog = dialogList.value.find(d => d.id === dialogId)
    if (dialog) {
      const messageIndex = dialog.messages.findIndex(m => m.id === messageId)
      if (messageIndex !== -1) {
        dialog.messages[messageIndex] = { ...dialog.messages[messageIndex], ...updates }
        dialog.updatedAt = new Date()
      }
    }
  }

  /**
   * Delete a dialog (one conversation round)
   * @param dialogId - The dialog ID to delete
   */
  const deleteDialog = (dialogId: string) => {
    const index = dialogList.value.findIndex(d => d.id === dialogId)
    if (index !== -1) {
      const deletedDialog = dialogList.value[index]
      dialogList.value.splice(index, 1)
      console.log('[useMessageDialog] Deleted dialog round:', dialogId)

      // If deleted dialog was active, set first dialog with same conversationId as active or null
      if (activeDialogId.value === dialogId) {
        const sameConversationDialogs = dialogList.value.filter(
          d => d.conversationId === deletedDialog.conversationId
        )
        activeDialogId.value =
          sameConversationDialogs.length > 0 ? sameConversationDialogs[0].id : null
        if (activeDialogId.value) {
          setActiveDialog(activeDialogId.value)
        }
      }
    }
  }

  /**
   * Delete a conversation round by rootPlanId
   * Deletes all dialogs associated with a specific rootPlanId
   * @param planId - The rootPlanId to delete
   */
  const deleteConversationRoundByPlanId = (planId: string): void => {
    const dialogsToDelete = dialogList.value.filter(d => d.planId === planId)
    dialogsToDelete.forEach(dialog => {
      deleteDialog(dialog.id)
    })
    console.log(
      `[useMessageDialog] Deleted ${dialogsToDelete.length} dialog round(s) for plan:`,
      planId
    )
  }

  /**
   * Delete all dialogs in a conversation
   * @param convId - The conversationId to delete
   */
  const deleteConversation = (convId: string): void => {
    const dialogsToDelete = dialogList.value.filter(d => d.conversationId === convId)
    dialogsToDelete.forEach(dialog => {
      deleteDialog(dialog.id)
    })
    console.log(
      `[useMessageDialog] Deleted ${dialogsToDelete.length} dialog round(s) for conversation:`,
      convId
    )
    // Clear conversationId if it was the active one
    if (conversationId.value === convId) {
      conversationId.value = null
    }
  }

  /**
   * Clear all dialogs
   */
  const clearAllDialogs = () => {
    dialogList.value = []
    activeDialogId.value = null
  }

  /**
   * Send message via send button (InputArea)
   * This method handles sending messages and updating the dialog list
   */
  const sendMessage = async (
    query: InputMessage
  ): Promise<{ success: boolean; planId?: string; conversationId?: string; error?: string }> => {
    let targetDialog: MessageDialog | null = null
    let assistantMessage: ChatMessage | null = null
    let response:
      | { planId?: string; conversationId?: string; message?: string; result?: string }
      | undefined

    try {
      // Check if there's an active running task based on our own state
      // Disable new requests if we're currently loading
      if (isLoading.value) {
        const errorMsg = 'Please wait for the current task to complete before starting a new one'
        error.value = errorMsg
        return {
          success: false,
          error: errorMsg,
        }
      }

      isLoading.value = true
      error.value = null

      // Always create a new dialog for each conversation round
      // Each round (user message + assistant response) gets a new dialogId
      targetDialog = createDialog()

      // Add user message to dialog
      const userMessage: ChatMessage = {
        id: `msg_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`,
        type: 'user',
        content: query.input,
        timestamp: new Date(),
        isStreaming: false,
        ...(query.uploadedFiles && {
          attachments: query.uploadedFiles.map(file => {
            // Convert string file names to File objects if needed
            return typeof file === 'string' ? new File([], file) : file
          }),
        }),
      }
      addMessageToDialog(targetDialog.id, userMessage)

      // Add assistant thinking message
      assistantMessage = {
        id: `msg_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`,
        type: 'assistant',
        content: '',
        timestamp: new Date(),
        thinking: 'Processing...',
        isStreaming: true,
      }
      addMessageToDialog(targetDialog.id, assistantMessage)

      // Import and call DirectApiService
      const extendedQuery = query as InputMessage & {
        toolName?: string
        replacementParams?: Record<string, string>
        serviceGroup?: string
      }

      if (extendedQuery.toolName && extendedQuery.replacementParams) {
        // Execute selected tool
        response = (await DirectApiService.executeByToolName(
          extendedQuery.toolName,
          extendedQuery.replacementParams as Record<string, string>,
          query.uploadedFiles,
          query.uploadKey,
          'VUE_DIALOG',
          extendedQuery.serviceGroup
        )) as typeof response

        // Update conversationId if present (persisted)
        if (response?.conversationId) {
          // Maintain conversationId independently (persisted)
          conversationId.value = response.conversationId
          // Also set on dialog for reference
          targetDialog.conversationId = response.conversationId
          memoryStore.setConversationId(response.conversationId)
          console.log('[useMessageDialog] Conversation ID set:', response.conversationId)
        }

        // Update assistant message with response
        if (response?.planId) {
          // Plan execution mode
          const newRootPlanId = response.planId
          // Maintain rootPlanId independently (not persisted)
          rootPlanId.value = newRootPlanId
          // Also set on dialog for reference
          targetDialog.planId = newRootPlanId

          updateMessageInDialog(targetDialog.id, assistantMessage.id, {
            thinking: 'Planning execution...',
            planExecution: {
              currentPlanId: newRootPlanId,
              rootPlanId: newRootPlanId,
              status: 'running',
            },
            isStreaming: false,
          })

          // Actively notify usePlanExecution to track this plan
          planExecution.handlePlanExecutionRequested(newRootPlanId)
          console.log('[useMessageDialog] Root plan ID set and tracking started:', newRootPlanId)
        } else {
          // Direct response mode
          const updates: Partial<ChatMessage> = {
            content: response?.message || response?.result || 'No response received',
            isStreaming: false,
            thinking: '',
          }
          updateMessageInDialog(targetDialog.id, assistantMessage.id, updates)
        }
      } else {
        // Use simple chat mode with streaming
        // Start streaming
        startStreaming(assistantMessage.id)

        // Initialize content
        let accumulatedContent = ''

        // Handle streaming chunks
        response = (await DirectApiService.sendChatMessage(query, 'VUE_DIALOG', chunk => {
          if (!targetDialog || !assistantMessage) return

          if (chunk.type === 'start' && chunk.conversationId) {
            // Update conversationId if present (persisted)
            conversationId.value = chunk.conversationId
            targetDialog.conversationId = chunk.conversationId
            memoryStore.setConversationId(chunk.conversationId)
            console.log('[useMessageDialog] Conversation ID set from stream:', chunk.conversationId)
          } else if (chunk.type === 'chunk' && chunk.content) {
            // Append chunk to accumulated content
            accumulatedContent += chunk.content
            // Update message content incrementally
            updateMessageInDialog(targetDialog.id, assistantMessage.id, {
              content: accumulatedContent,
              isStreaming: true,
              thinking: '',
            })
          } else if (chunk.type === 'done') {
            // Stop streaming
            stopStreaming(assistantMessage.id)
            // Final update
            updateMessageInDialog(targetDialog.id, assistantMessage.id, {
              content: accumulatedContent || 'No response received',
              isStreaming: false,
              thinking: '',
            })
          } else if (chunk.type === 'error') {
            // Handle error
            stopStreaming(assistantMessage.id)
            updateMessageInDialog(targetDialog.id, assistantMessage.id, {
              content: `Error: ${chunk.message || 'Streaming error occurred'}`,
              error: chunk.message || 'Streaming error occurred',
              isStreaming: false,
            })
          }
        })) as typeof response

        // Ensure streaming is stopped and final content is set
        stopStreaming(assistantMessage.id)
        if (response?.conversationId) {
          conversationId.value = response.conversationId
          targetDialog.conversationId = response.conversationId
          memoryStore.setConversationId(response.conversationId)
        }
        // Final update with complete message
        updateMessageInDialog(targetDialog.id, assistantMessage.id, {
          content: response?.message || accumulatedContent || 'No response received',
          isStreaming: false,
          thinking: '',
        })
      }

      return {
        success: true,
        ...(response?.planId && { planId: response.planId }),
        ...(response?.conversationId && { conversationId: response.conversationId }),
      }
    } catch (err) {
      const errorMessage = err instanceof Error ? err.message : 'Failed to send message'
      error.value = errorMessage
      console.error('[useMessageDialog] Send message failed:', err)

      // Update assistant message with error
      if (targetDialog && assistantMessage) {
        updateMessageInDialog(targetDialog.id, assistantMessage.id, {
          content: `Error: ${errorMessage}`,
          error: errorMessage,
          isStreaming: false,
        })
      }

      return {
        success: false,
        error: errorMessage,
      }
    } finally {
      // Only reset isLoading if no plan execution is in progress
      // If a planId was returned, isLoading will be reset by watchEffect when plan completes
      if (!response?.planId) {
        isLoading.value = false
      }
      // If planId exists, let watchEffect handle the reset when plan completes
    }
  }

  /**
   * Execute plan via execute plan button (ExecutionController)
   * This method handles executing plans and updating the dialog list
   */
  const executePlan = async (
    payload: PlanExecutionRequestPayload
  ): Promise<{ success: boolean; planId?: string; error?: string }> => {
    let targetDialog: MessageDialog | null = null
    let assistantMessage: ChatMessage | null = null

    try {
      // Check if there's an active running task based on our own state
      // Disable new requests if we're currently loading
      if (isLoading.value) {
        const errorMsg = 'Please wait for the current task to complete before starting a new one'
        error.value = errorMsg
        return {
          success: false,
          error: errorMsg,
        }
      }

      isLoading.value = true
      error.value = null

      // Check if there's an existing conversationId from memoryStore (persisted)
      // This allows appending new dialog rounds to existing conversations
      const existingConversationId = memoryStore.getConversationId()
      if (existingConversationId && !conversationId.value) {
        // Restore conversationId from memoryStore if we don't have one yet
        conversationId.value = existingConversationId
        console.log(
          '[useMessageDialog] Restored conversationId from memoryStore:',
          existingConversationId
        )
      }

      // Always create a new dialog for each conversation round
      // Each round (user message + assistant response) gets a new dialogId
      // If conversationId exists, it will be automatically linked to the new dialog
      targetDialog = createDialog(payload.title)

      // Update dialog title if provided
      if (payload.title) {
        targetDialog.title = payload.title
      }

      // Add user message to dialog
      const userMessage: ChatMessage = {
        id: `msg_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`,
        type: 'user',
        content: payload.title,
        timestamp: new Date(),
        isStreaming: false,
        ...(payload.uploadedFiles && {
          attachments: payload.uploadedFiles.map(file => {
            return typeof file === 'string' ? new File([], file) : file
          }),
        }),
      }
      addMessageToDialog(targetDialog.id, userMessage)

      // Add assistant thinking message
      assistantMessage = {
        id: `msg_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`,
        type: 'assistant',
        content: '',
        timestamp: new Date(),
        thinking: 'Planning execution...',
        isStreaming: true,
      }
      addMessageToDialog(targetDialog.id, assistantMessage)

      // Get toolName and serviceGroup from payload (required for API execution)
      const toolName = payload.toolName
      const serviceGroup = payload.serviceGroup

      if (!toolName || toolName.trim() === '') {
        throw new Error('Tool name is required for plan execution')
      }

      // Call PlanActApiService.executePlan
      // Note: DirectApiService.executeByToolName will automatically include conversationId from memoryStore
      const response = (await PlanActApiService.executePlan(
        toolName,
        serviceGroup,
        payload.params,
        payload.uploadedFiles,
        payload.replacementParams,
        payload.uploadKey ?? undefined,
        'VUE_SIDEBAR'
      )) as { planId?: string; conversationId?: string }

      // Update conversationId if present (persisted)
      // Only update if it's different from what we already have
      if (response.conversationId) {
        const newConversationId = response.conversationId
        const currentConversationId = conversationId.value || memoryStore.getConversationId()

        // Only update if the backend returned a different conversationId
        // This can happen if:
        // 1. We didn't send a conversationId (first request)
        // 2. Backend generated a new one (shouldn't happen if we sent one)
        if (newConversationId !== currentConversationId) {
          console.log(
            '[useMessageDialog] Conversation ID changed:',
            currentConversationId,
            '->',
            newConversationId
          )
          // Maintain conversationId independently (persisted)
          conversationId.value = newConversationId
          // Also set on dialog for reference
          targetDialog.conversationId = newConversationId
          memoryStore.setConversationId(newConversationId)
        } else {
          // Ensure dialog has the conversationId even if it didn't change
          targetDialog.conversationId = newConversationId
          console.log('[useMessageDialog] Conversation ID unchanged:', newConversationId)
        }
      } else {
        // If backend didn't return conversationId, ensure dialog uses the one we have
        if (conversationId.value) {
          targetDialog.conversationId = conversationId.value
        }
      }

      // Update assistant message with plan execution info
      if (response.planId) {
        const newRootPlanId = response.planId
        // Maintain rootPlanId independently (not persisted)
        rootPlanId.value = newRootPlanId
        // Also set on dialog for reference
        targetDialog.planId = newRootPlanId

        updateMessageInDialog(targetDialog.id, assistantMessage.id, {
          thinking: 'Planning execution...',
          planExecution: {
            currentPlanId: newRootPlanId,
            rootPlanId: newRootPlanId,
            status: 'running',
          },
          isStreaming: false,
        })

        // Actively notify usePlanExecution to track this plan
        planExecution.handlePlanExecutionRequested(newRootPlanId)
        console.log('[useMessageDialog] Root plan ID set and tracking started:', newRootPlanId)
      } else {
        const updates: Partial<ChatMessage> = {
          content: 'Plan execution started',
          isStreaming: false,
        }
        // Only set thinking if it exists, don't set undefined
        updateMessageInDialog(targetDialog.id, assistantMessage.id, updates)
      }

      return {
        success: true,
        ...(response.planId && { planId: response.planId }),
      }
      // Note: isLoading is NOT reset here - it will be reset when the plan execution completes
      // This prevents concurrent executions while a plan is still running
    } catch (err) {
      const errorMessage = err instanceof Error ? err.message : 'Failed to execute plan'
      error.value = errorMessage
      console.error('[useMessageDialog] Execute plan failed:', err)

      // Update assistant message with error
      if (targetDialog && assistantMessage) {
        updateMessageInDialog(targetDialog.id, assistantMessage.id, {
          content: `Error: ${errorMessage}`,
          error: errorMessage,
          isStreaming: false,
        })
      }

      // Reset isLoading on error since execution failed
      isLoading.value = false

      return {
        success: false,
        error: errorMessage,
      }
    }
  }

  /**
   * Update plan execution status in a dialog
   */
  const updatePlanExecutionStatus = (
    dialogId: string,
    messageId: string,
    planExecution: ChatMessage['planExecution']
  ) => {
    const updates: Partial<ChatMessage> = {}
    if (planExecution !== undefined) {
      updates.planExecution = planExecution
    }
    updateMessageInDialog(dialogId, messageId, updates)
  }

  /**
   * Convert readonly PlanExecutionRecord to mutable CompatiblePlanExecutionRecord
   */
  const convertPlanExecutionRecord = (
    record: PlanExecutionRecord | CompatiblePlanExecutionRecord | Readonly<PlanExecutionRecord>
  ): CompatiblePlanExecutionRecord => {
    // Create a mutable copy of the record
    const converted = { ...record } as CompatiblePlanExecutionRecord

    if ('agentExecutionSequence' in record && Array.isArray(record.agentExecutionSequence)) {
      // Convert readonly array to mutable array
      converted.agentExecutionSequence = record.agentExecutionSequence.map((agent: unknown) =>
        convertAgentExecutionRecord(agent as AgentExecutionRecord)
      )
    }

    return converted
  }

  /**
   * Convert readonly AgentExecutionRecord to mutable version
   */
  const convertAgentExecutionRecord = (record: AgentExecutionRecord): AgentExecutionRecord => {
    const converted = { ...record } as AgentExecutionRecord

    if ('subPlanExecutionRecords' in record && Array.isArray(record.subPlanExecutionRecords)) {
      converted.subPlanExecutionRecords = record.subPlanExecutionRecords.map((subPlan: unknown) =>
        convertPlanExecutionRecord(subPlan as PlanExecutionRecord)
      )
    }

    return converted
  }

  /**
   * Add message to active dialog (convenience method for ChatContainer)
   * Automatically converts readonly planExecution to mutable version
   */
  const addMessage = (
    type: 'user' | 'assistant',
    content: string,
    options?: Partial<ChatMessage>
  ): ChatMessage => {
    if (!activeDialog.value) {
      // Create a new dialog if none exists
      createDialog()
    }

    const dialog = activeDialog.value!

    // Convert planExecution if it exists
    const processedOptions: Partial<ChatMessage> = { ...options }
    if (options?.planExecution) {
      processedOptions.planExecution = convertPlanExecutionRecord(options.planExecution)
    }

    const message: ChatMessage = {
      id: `msg_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`,
      type,
      content,
      timestamp: new Date(),
      isStreaming: false,
      ...processedOptions,
    }

    dialog.messages.push(message)
    dialog.updatedAt = new Date()
    return message
  }

  /**
   * Update message in active dialog (convenience method for ChatContainer)
   * Automatically converts readonly planExecution to mutable version
   */
  const updateMessage = (messageId: string, updates: Partial<ChatMessage>) => {
    if (!activeDialog.value) {
      return
    }

    // Convert planExecution if it exists
    const processedUpdates: Partial<ChatMessage> = { ...updates }
    if (updates.planExecution) {
      processedUpdates.planExecution = convertPlanExecutionRecord(updates.planExecution)
    }

    const messageIndex = activeDialog.value.messages.findIndex(m => m.id === messageId)
    if (messageIndex !== -1) {
      activeDialog.value.messages[messageIndex] = {
        ...activeDialog.value.messages[messageIndex],
        ...processedUpdates,
      }
      activeDialog.value.updatedAt = new Date()
    }
  }

  /**
   * Find message in active dialog (convenience method for ChatContainer)
   */
  const findMessage = (messageId: string): ChatMessage | undefined => {
    if (!activeDialog.value) {
      return undefined
    }
    return activeDialog.value.messages.find(m => m.id === messageId)
  }

  /**
   * Start streaming for a message (convenience method for ChatContainer)
   */
  const startStreaming = (messageId: string) => {
    streamingMessageId.value = messageId
    updateMessage(messageId, { isStreaming: true })
  }

  /**
   * Stop streaming for a message (convenience method for ChatContainer)
   */
  const stopStreaming = (messageId?: string) => {
    if (messageId) {
      updateMessage(messageId, { isStreaming: false })
    }
    if (streamingMessageId.value === messageId || !messageId) {
      streamingMessageId.value = null
    }
  }

  /**
   * Clear messages in active dialog (convenience method for ChatContainer)
   */
  const clearMessages = () => {
    if (!activeDialog.value) {
      return
    }
    activeDialog.value.messages = []
    activeDialog.value.updatedAt = new Date()
    streamingMessageId.value = null
  }

  /**
   * Update input state (enabled/disabled)
   */
  const updateInputState = (enabled: boolean, placeholder?: string) => {
    // isLoading is the inverse of enabled
    isLoading.value = !enabled
    if (placeholder !== undefined) {
      inputPlaceholder.value = placeholder
    }
    console.log('[useMessageDialog] Input state updated:', {
      enabled,
      placeholder,
      isLoading: isLoading.value,
    })
  }

  /**
   * Set conversationId (internal method for restoring from history)
   * This is needed when loading conversation history to ensure new dialogs are linked correctly
   */
  const setConversationId = (id: string | null) => {
    conversationId.value = id
    console.log('[useMessageDialog] Set conversationId:', id)
  }

  /**
   * Reset state
   */
  const reset = () => {
    dialogList.value = []
    activeDialogId.value = null
    rootPlanId.value = null
    conversationId.value = null
    isLoading.value = false
    error.value = null
    inputPlaceholder.value = null
  }

  /**
   * Helper: Update message with plan execution record
   */
  const updateMessageWithPlanRecord = (
    dialog: MessageDialog,
    message: ChatMessage,
    record: PlanExecutionRecord
  ): void => {
    const updates: Partial<ChatMessage> = {
      planExecution: convertPlanExecutionRecord(record),
      isStreaming: !record.completed,
    }

    if (!record.completed) {
      updates.thinking = 'Processing...'
    } else {
      // When plan is completed, handle summary content
      // Only update content if there's no agent execution sequence (simple response)
      // or if we have a summary/result to display
      if (!record.agentExecutionSequence || record.agentExecutionSequence.length === 0) {
        const finalResponse =
          record.summary ?? record.result ?? record.message ?? 'Execution completed'
        if (finalResponse) {
          updates.content = finalResponse
          updates.thinking = ''
        }
      } else if (record.summary) {
        // Even with agent execution sequence, show summary if available
        updates.content = record.summary
        updates.thinking = ''
      }

      // Handle errors
      if (record.status === 'failed' && record.message) {
        updates.content = `Error: ${record.message}`
        updates.thinking = ''
      }
    }

    updateMessageInDialog(dialog.id, message.id, updates)
  }

  /**
   * Watch for PlanExecutionRecord changes and update dialog messages
   * Uses watchEffect for automatic dependency tracking (more Vue 3 idiomatic)
   * Processes all dialogs with planIds, not just active one
   */
  watchEffect(() => {
    // Access the ref value to establish reactivity tracking
    const records = planExecution.planExecutionRecords.value

    // Convert object to entries array for processing
    const recordsArray = Object.entries(records)

    // Also access dialogList to ensure we re-run when dialogs change
    const dialogs = dialogList.value

    console.log(
      '[useMessageDialog] watchEffect triggered - records count:',
      recordsArray.length,
      'keys:',
      Object.keys(records)
    )
    console.log('[useMessageDialog] watchEffect - dialogList count:', dialogs.length)
    console.log(
      '[useMessageDialog] watchEffect - dialogs with planId:',
      dialogs.filter(d => d.planId).map(d => ({ id: d.id, planId: d.planId }))
    )

    // Process all dialogs that have associated planIds
    for (const dialog of dialogs) {
      if (!dialog.planId) {
        console.log('[useMessageDialog] watchEffect: Skipping dialog without planId:', dialog.id)
        continue
      }

      // Find the assistant message with this planId
      // Try multiple matching strategies: dialog.planId, message.planExecution.rootPlanId, message.planExecution.currentPlanId
      const message = dialog.messages.find(
        m =>
          m.planExecution?.rootPlanId === dialog.planId ||
          m.planExecution?.currentPlanId === dialog.planId
      )
      if (!message) {
        console.log('[useMessageDialog] watchEffect: No message found for planId:', dialog.planId, {
          dialogMessages: dialog.messages.map(m => ({
            id: m.id,
            type: m.type,
            planExecutionRootPlanId: m.planExecution?.rootPlanId,
            planExecutionCurrentPlanId: m.planExecution?.currentPlanId,
            hasPlanExecution: !!m.planExecution,
          })),
        })
        continue
      }

      // Enhanced matching: try to find record by multiple keys
      // 1. Try dialog.planId (primary key)
      // 2. Try message.planExecution.rootPlanId
      // 3. Try message.planExecution.currentPlanId
      // 4. Try all record keys to find matching rootPlanId or currentPlanId
      let recordEntry = recordsArray.find(([planId]) => planId === dialog.planId)

      const planExecution = message.planExecution
      if (!recordEntry && planExecution?.rootPlanId) {
        recordEntry = recordsArray.find(([planId]) => planId === planExecution.rootPlanId)
      }

      if (!recordEntry && planExecution?.currentPlanId) {
        recordEntry = recordsArray.find(([planId]) => planId === planExecution.currentPlanId)
      }

      // If still not found, try to match by checking all records' rootPlanId/currentPlanId
      if (!recordEntry) {
        for (const [recordKey, recordValue] of recordsArray) {
          if (
            recordValue &&
            (recordValue.rootPlanId === dialog.planId ||
              recordValue.currentPlanId === dialog.planId ||
              (message.planExecution?.rootPlanId &&
                (recordValue.rootPlanId === message.planExecution.rootPlanId ||
                  recordValue.currentPlanId === message.planExecution.rootPlanId)) ||
              (message.planExecution?.currentPlanId &&
                (recordValue.rootPlanId === message.planExecution.currentPlanId ||
                  recordValue.currentPlanId === message.planExecution.currentPlanId)))
          ) {
            recordEntry = [recordKey, recordValue]
            console.log('[useMessageDialog] watchEffect: Found record by value matching:', {
              dialogPlanId: dialog.planId,
              recordKey,
              recordRootPlanId: recordValue.rootPlanId,
              recordCurrentPlanId: recordValue.currentPlanId,
            })
            break
          }
        }
      }

      // Handle case where message has planExecution but record not found yet
      // This is the initial state gap - keep showing the initial state
      if (!recordEntry) {
        // If message has planExecution with status 'running', keep it visible
        // Don't skip - this ensures the execution chain displays immediately
        const messagePlanExecution = message.planExecution
        if (messagePlanExecution && messagePlanExecution.status === 'running') {
          console.log(
            '[useMessageDialog] watchEffect: Message has planExecution but record not found yet, keeping initial state:',
            {
              dialogId: dialog.id,
              messageId: message.id,
              planId: dialog.planId,
              messagePlanExecution: messagePlanExecution,
              availableRecordKeys: recordsArray.map(([key]) => key),
            }
          )
          // Keep the initial state visible - don't update, just ensure it's displayed
          // The message already has planExecution with status 'running', which is correct
          continue
        }
        // If message doesn't have planExecution or status is not 'running', skip
        console.log(
          '[useMessageDialog] watchEffect: No record found and message has no running planExecution:',
          {
            dialogId: dialog.id,
            planId: dialog.planId,
            hasPlanExecution: !!message.planExecution,
            planExecutionStatus: message.planExecution?.status,
            availableRecordKeys: recordsArray.map(([key]) => key),
          }
        )
        continue
      }

      const [, readonlyRecord] = recordEntry

      // Convert readonly record to mutable for processing
      const record = convertPlanExecutionRecord(
        readonlyRecord as PlanExecutionRecord
      ) as PlanExecutionRecord

      console.log('[useMessageDialog] watchEffect: Updating message with plan record:', {
        dialogId: dialog.id,
        messageId: message.id,
        planId: dialog.planId,
        recordKey: recordEntry[0],
        recordCompleted: record.completed,
        recordStatus: record.status,
      })

      // Update message with latest plan execution record
      updateMessageWithPlanRecord(dialog, message, record)
    }

    // Reset isLoading when all plans are completed
    // Check both trackedPlanIds and planExecutionRecords to handle the case where
    // a plan has just started but hasn't been polled yet (no record in planExecutionRecords)
    const hasTrackedPlans = planExecution.trackedPlanIds.value.size > 0
    const hasRunningPlansInRecords = recordsArray.some(
      ([, record]) => record && !record.completed && record.status !== 'failed'
    )

    // If there are tracked plans but no records yet, consider it as running
    // This handles the race condition where a plan just started but hasn't been polled
    const hasRunningPlans = hasTrackedPlans || hasRunningPlansInRecords

    if (!hasRunningPlans && isLoading.value) {
      console.log('[useMessageDialog] All plans completed, resetting isLoading', {
        hasTrackedPlans,
        hasRunningPlansInRecords,
        trackedPlanIds: Array.from(planExecution.trackedPlanIds.value),
        recordsCount: recordsArray.length,
      })
      isLoading.value = false
    }
  })

  return {
    // State
    dialogList: readonly(dialogList),
    activeDialogId: readonly(activeDialogId),
    rootPlanId: readonly(rootPlanId),
    conversationId: readonly(conversationId),
    isLoading,
    error,
    streamingMessageId: readonly(streamingMessageId),
    inputPlaceholder: readonly(inputPlaceholder),

    // Computed
    activeDialog,
    activeRootPlanId,
    hasDialogs,
    dialogCount,
    messages,

    // Methods
    createDialog,
    getDialog,
    setActiveDialog,
    addMessageToDialog,
    updateMessageInDialog,
    deleteDialog,
    deleteConversationRoundByPlanId,
    deleteConversation,
    clearAllDialogs,
    sendMessage,
    executePlan,
    updatePlanExecutionStatus,
    updateInputState,
    setConversationId,
    reset,

    // Convenience methods for ChatContainer
    addMessage,
    updateMessage,
    findMessage,
    startStreaming,
    stopStreaming,
    clearMessages,
  }
}

// Singleton instance for global use
let singletonInstance: ReturnType<typeof useMessageDialog> | null = null

/**
 * Get or create singleton instance of useMessageDialog
 */
export function useMessageDialogSingleton() {
  if (!singletonInstance) {
    singletonInstance = useMessageDialog()
  }
  return singletonInstance
}
