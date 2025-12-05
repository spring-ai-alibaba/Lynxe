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

/**
 * Step data structure for plan execution
 * Based on backend ExecutionStep.java
 */
export interface StepData {
  stepRequirement: string
  agentName: string
  modelName: string | null
  selectedToolKeys: string[]
  terminateColumns: string
  agentType?: string
  stepContent: string
}

/**
 * Plan data structure for execution
 * Based on backend DynamicAgentExecutionPlan.java
 */
export interface PlanData {
  title: string
  steps: StepData[]
  planTemplateId?: string
  planType?: string
  replacementParams?: Record<string, string>
  uploadedFiles?: string[]
  uploadKey?: string | null
}

/**
 * Display plan data structure for JSON editor
 * This is the same as PlanData but used specifically for display/editing
 */
export interface DisplayPlanData {
  title: string
  steps: StepData[]
  planTemplateId?: string
  planType?: string
}

/**
 * Payload for plan execution request event
 */
export interface PlanExecutionRequestPayload {
  /** Plan title */
  title: string
  /** Plan data object */
  planData: PlanData
  /** Optional parameters string */
  params?: string | undefined
  /** Optional replacement parameters for parameter substitution */
  replacementParams?: Record<string, string> | undefined
  /** Optional array of uploaded file names */
  uploadedFiles?: string[] | undefined
  /** Optional upload session key */
  uploadKey?: string | null | undefined
  /** Tool name (from template.title) for API execution */
  toolName?: string | undefined
  /** Service group (from template.serviceGroup) for API execution */
  serviceGroup?: string | undefined
}
