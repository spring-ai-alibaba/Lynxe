# File Upload (附加文件) Call Tree

Simplest call flow for how file upload works in the frontend:

## Frontend Flow

```
FileUploadComponent.vue
  └─ handleFileUpload()
      └─ fileInputRef.click() (triggers file picker)
          └─ handleFileChange(event)
              └─ uploadFiles(files)
                  └─ FileUploadApiService.uploadFiles(files)
                      └─ POST /api/file-upload/upload
                          └─ FormData with files
```

## Backend Flow

```
FileUploadController.uploadFiles(files)
  ├─ FileValidationService.validateFiles(files)
  └─ FileUploadService.uploadFiles(files)
      ├─ generateUploadKey()
      ├─ createUploadDirectory(uploadKey)
      └─ uploadSingleFile(file, directory) (for each file)
          └─ Filesystem: Save file to upload directory
```

## Complete Call Tree

```
User clicks "附加文件" button
  │
  ├─ [Frontend] FileUploadComponent.vue::handleFileUpload()
  │   └─ fileInputRef.click() (opens file picker)
  │       │
  │       └─ [Frontend] FileUploadComponent.vue::handleFileChange(event)
  │           └─ [Frontend] FileUploadComponent.vue::uploadFiles(files)
  │               └─ [Frontend] FileUploadApiService.uploadFiles(files)
  │                   └─ HTTP POST /api/file-upload/upload
  │                       │ (FormData with files)
  │                       │
  │                       └─ [Backend] FileUploadController::uploadFiles(files)
  │                           ├─ FileValidationService::validateFiles(files)
  │                           └─ FileUploadService::uploadFiles(files)
  │                               ├─ generateUploadKey()
  │                               ├─ createUploadDirectory(uploadKey)
  │                               └─ uploadSingleFile(file, directory)
  │                                   └─ Filesystem: Save to extensions/uploaded_files/{uploadKey}/
  │
  └─ [Frontend] FileUploadComponent emits 'files-uploaded' event
      └─ [Frontend] InputArea.vue / ExecutionController.vue::handleFilesUploaded()
          └─ Update local state (uploadedFiles, uploadKey)
```

## Key Components

- **Frontend Component**: `ui-vue3/src/components/file-upload/FileUploadComponent.vue` - File upload UI
- **Frontend API**: `ui-vue3/src/api/file-upload-api-service.ts` - HTTP client for file operations
- **Frontend Consumers**: 
  - `ui-vue3/src/components/input/InputArea.vue` - Chat input area
  - `ui-vue3/src/components/sidebar/ExecutionController.vue` - Execution controller
- **Backend Controller**: `FileUploadController.java` - REST endpoint handler
- **Backend Service**: `FileUploadService.java` - File upload logic
- **Backend Validation**: `FileValidationService.java` - File type/size validation
- **Storage**: Files saved to `extensions/uploaded_files/{uploadKey}/` directory

