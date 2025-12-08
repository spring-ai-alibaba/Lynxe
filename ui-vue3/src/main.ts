/*
 * Copyright 2024-2026 the original author or authors.
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

import Antd from 'ant-design-vue'
import { createPinia } from 'pinia'
import { createApp } from 'vue'

import { i18n, initializeLanguage } from '@/base/i18n'
import { useMessageDialogSingleton } from '@/composables/useMessageDialog'
import 'ant-design-vue/dist/reset.css'
import App from './App.vue'
import router from './router'

// Configure Iconify
import { addAPIProvider } from '@iconify/vue'

// Add fallback API providers
addAPIProvider('', {
  resources: ['https://api.iconify.design', 'https://api.unisvg.com', 'https://api.simplesvg.com'],
})

import 'nprogress/nprogress.css'
import Vue3ColorPicker from 'vue3-colorpicker'
import 'vue3-colorpicker/style.css'

const app = createApp(App)
const pinia = createPinia()

app.use(pinia).use(Antd).use(Vue3ColorPicker).use(i18n).use(router)

// Initialize message dialog singleton early to ensure watchEffect is registered
// This ensures plan execution tracking works regardless of which route is accessed
useMessageDialogSingleton()

// Initialize language before mounting the app
initializeLanguage()
  .then(() => {
    app.mount('#app')
  })
  .catch(error => {
    console.error('Failed to initialize language, mounting app with default language:', error)
    app.mount('#app')
  })
