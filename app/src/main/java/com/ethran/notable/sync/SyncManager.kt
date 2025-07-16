package com.ethran.notable.sync

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.ethran.notable.classes.AppRepository
import com.ethran.notable.db.*
import com.ethran.notable.utils.Pen
import kotlinx.coroutines.*
import java.util.*

class SyncManager(
    private val context: Context,
    private val repository: AppRepository
) {
    private val TAG = "SyncManager"
    
    private val prefs: SharedPreferences = context.getSharedPreferences("sync_settings", Context.MODE_PRIVATE)
    private val serializer = SyncSerializer(context)
    
    private var webdavClient: WebDAVClient? = null
    private var deviceId: String = getOrCreateDeviceId()
    
    private val syncScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Configuration
    var isEnabled: Boolean
        get() = prefs.getBoolean("sync_enabled", false)
        set(value) {
            prefs.edit().putBoolean("sync_enabled", value).apply()
            // Re-initialize client when settings change
            if (value) initialize()
        }
    
    var serverUrl: String
        get() = prefs.getString("server_url", "") ?: ""
        set(value) {
            prefs.edit().putString("server_url", value).apply()
            webdavClient = null // Force re-initialization
        }
    
    var username: String
        get() = prefs.getString("username", "") ?: ""
        set(value) {
            prefs.edit().putString("username", value).apply()
            webdavClient = null // Force re-initialization
        }
    
    var password: String
        get() = prefs.getString("password", "") ?: ""
        set(value) {
            prefs.edit().putString("password", value).apply()
            webdavClient = null // Force re-initialization
        }
    
    var deviceName: String
        get() = prefs.getString("device_name", android.os.Build.MODEL) ?: android.os.Build.MODEL
        set(value) = prefs.edit().putString("device_name", value).apply()
    
    private var lastSyncTime: Long
        get() = prefs.getLong("last_sync_time", 0L)
        set(value) = prefs.edit().putLong("last_sync_time", value).apply()
    
    fun initialize(): Boolean {
        Log.d(TAG, "initialize() called")
        Log.d(TAG, "isEnabled: $isEnabled, serverUrl: '$serverUrl', username: '$username', password: '${password.take(3)}...'")
        
        if (!isEnabled || serverUrl.isEmpty() || username.isEmpty() || password.isEmpty()) {
            Log.d(TAG, "Initialize failed: missing required settings")
            return false
        }
        
        webdavClient = WebDAVClient(serverUrl, username, password)
        Log.d(TAG, "WebDAV client initialized successfully")
        return true
    }
    
    suspend fun testConnection(): Boolean {
        Log.d(TAG, "testConnection() called")
        Log.d(TAG, "serverUrl: '$serverUrl', username: '$username', password: '${password.take(3)}...'")
        
        // Ensure we have a client initialized
        if (webdavClient == null) {
            Log.d(TAG, "WebDAV client is null, trying to initialize")
            if (!initialize()) {
                Log.d(TAG, "Failed to initialize WebDAV client")
                return false
            }
        }
        
        return webdavClient?.testConnection() ?: false
    }
    
    suspend fun initializeRemoteStructure(): Boolean {
        return webdavClient?.initialize() ?: false
    }
    
    suspend fun syncPage(pageId: String): SyncResult {
        if (!isEnabled) return SyncResult.DISABLED
        
        val client = webdavClient ?: return SyncResult.NOT_CONFIGURED
        
        return try {
            // Get page data from database
            val page = repository.getPageById(pageId) ?: return SyncResult.PAGE_NOT_FOUND
            val notebook = repository.getNotebookById(page.notebookId ?: "") ?: return SyncResult.NOTEBOOK_NOT_FOUND
            val strokes = repository.getStrokesByPageId(pageId)
            val images = repository.getImagesByPageId(pageId)
            val folderPath = getFolderPath(notebook.parentFolderId)
            
            // Serialize page data
            val jsonData = serializer.serializePage(
                notebook = notebook,
                page = page,
                strokes = strokes,
                images = images,
                folderPath = folderPath,
                deviceId = deviceId
            )
            
            // Upload to WebDAV
            val success = client.uploadPageSync(notebook.id, pageId, jsonData)
            
            if (success) {
                Log.d(TAG, "Page $pageId synced successfully")
                SyncResult.SUCCESS
            } else {
                Log.e(TAG, "Failed to upload page $pageId")
                SyncResult.UPLOAD_FAILED
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing page $pageId", e)
            SyncResult.ERROR
        }
    }
    
    suspend fun syncNotebook(notebookId: String): SyncResult {
        if (!isEnabled) return SyncResult.DISABLED
        
        val client = webdavClient ?: return SyncResult.NOT_CONFIGURED
        
        return try {
            val notebook = repository.getNotebookById(notebookId) ?: return SyncResult.NOTEBOOK_NOT_FOUND
            val folderPath = getFolderPath(notebook.parentFolderId)
            
            // Sync notebook metadata
            val metadataJson = serializer.serializeNotebookMetadata(
                notebook = notebook,
                folderPath = folderPath,
                deviceId = deviceId
            )
            
            val metadataSuccess = client.uploadNotebookMetadata(notebookId, metadataJson)
            
            if (!metadataSuccess) {
                return SyncResult.UPLOAD_FAILED
            }
            
            // Sync all pages in the notebook
            val pageResults = notebook.pageIds.map { pageId ->
                syncPage(pageId)
            }
            
            val allPagesSuccess = pageResults.all { it == SyncResult.SUCCESS }
            
            if (allPagesSuccess) {
                Log.d(TAG, "Notebook $notebookId synced successfully")
                SyncResult.SUCCESS
            } else {
                Log.w(TAG, "Some pages in notebook $notebookId failed to sync")
                SyncResult.PARTIAL_SUCCESS
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing notebook $notebookId", e)
            SyncResult.ERROR
        }
    }
    
    suspend fun syncAll(): SyncResult {
        if (!isEnabled) return SyncResult.DISABLED
        
        val client = webdavClient ?: return SyncResult.NOT_CONFIGURED
        
        return try {
            // Initialize remote directory structure first
            val initSuccess = initializeRemoteStructure()
            if (!initSuccess) {
                Log.e(TAG, "Failed to initialize remote directory structure")
                return SyncResult.ERROR
            }
            
            // Update device info
            updateDeviceInfo()
            
            // Get all notebooks
            val notebooks = repository.getAllNotebooks()
            
            val results = notebooks.map { notebook ->
                syncNotebook(notebook.id)
            }
            
            val successCount = results.count { it == SyncResult.SUCCESS }
            val totalCount = results.size
            
            lastSyncTime = System.currentTimeMillis()
            
            when {
                successCount == totalCount -> {
                    Log.d(TAG, "Full sync completed successfully")
                    SyncResult.SUCCESS
                }
                successCount > 0 -> {
                    Log.w(TAG, "Partial sync completed: $successCount/$totalCount")
                    SyncResult.PARTIAL_SUCCESS
                }
                else -> {
                    Log.e(TAG, "Full sync failed")
                    SyncResult.ERROR
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in full sync", e)
            SyncResult.ERROR
        }
    }
    
    suspend fun pullChanges(): SyncResult {
        if (!isEnabled) return SyncResult.DISABLED
        
        val client = webdavClient ?: return SyncResult.NOT_CONFIGURED
        
        return try {
            Log.d(TAG, "Starting pullChanges()")
            
            // Get list of all page sync files
            val pageFiles = client.listPageSyncs()
            Log.d(TAG, "Found ${pageFiles.size} page files on server")
            
            var importCount = 0
            var errorCount = 0
            
            for (fileInfo in pageFiles) {
                Log.d(TAG, "Processing file: ${fileInfo.name}")
                val syncInfo = fileInfo.parseFilename()
                if (syncInfo?.type == SyncFileType.PAGE) {
                    try {
                        Log.d(TAG, "Downloading file: ${fileInfo.name}")
                        val jsonData = client.downloadFile("pages/${fileInfo.name}")
                        if (jsonData != null) {
                            Log.d(TAG, "Downloaded ${jsonData.length} bytes, deserializing...")
                            val syncPageData = serializer.deserializePage(jsonData)
                            
                            // Check if we should import this (not from this device, newer than local)
                            Log.d(TAG, "Checking if should import page from device: ${syncPageData.syncFormat.deviceId}")
                            if (shouldImportPage(syncPageData)) {
                                Log.d(TAG, "Importing page: ${syncPageData.page.id}")
                                importPage(syncPageData)
                                importCount++
                            } else {
                                Log.d(TAG, "Skipping page: ${syncPageData.page.id} (not newer or from same device)")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error importing page from ${fileInfo.name}", e)
                        errorCount++
                    }
                }
            }
            
            Log.d(TAG, "Pull completed: imported $importCount pages, $errorCount errors")
            
            when {
                importCount > 0 && errorCount == 0 -> SyncResult.SUCCESS
                importCount > 0 && errorCount > 0 -> SyncResult.PARTIAL_SUCCESS
                importCount == 0 && errorCount == 0 -> SyncResult.UP_TO_DATE
                else -> SyncResult.ERROR
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error pulling changes", e)
            SyncResult.ERROR
        }
    }
    
    private suspend fun updateDeviceInfo() {
        val client = webdavClient ?: return
        
        try {
            val deviceInfoJson = serializer.serializeDeviceInfo(deviceId, deviceName)
            client.uploadDeviceInfo(deviceId, deviceInfoJson)
        } catch (e: Exception) {
            Log.e(TAG, "Error updating device info", e)
        }
    }
    
    private suspend fun getFolderPath(parentFolderId: String?): List<Folder> {
        val path = mutableListOf<Folder>()
        var currentFolderId = parentFolderId
        
        while (currentFolderId != null) {
            val folder = repository.getFolderById(currentFolderId)
            if (folder != null) {
                path.add(0, folder) // Add to beginning to maintain order
                currentFolderId = folder.parentFolderId
            } else {
                break
            }
        }
        
        return path
    }
    
    private fun shouldImportPage(syncPageData: SyncPageData): Boolean {
        Log.d(TAG, "shouldImportPage: Checking page ${syncPageData.page.id}")
        
        // Don't import our own changes
        if (syncPageData.syncFormat.deviceId == deviceId) {
            Log.d(TAG, "shouldImportPage: Skipping own device changes")
            return false
        }
        
        // Check if page exists locally
        Log.d(TAG, "shouldImportPage: Checking if page exists locally")
        val existingPage = runBlocking { repository.getPageById(syncPageData.page.id) }
        
        if (existingPage == null) {
            Log.d(TAG, "shouldImportPage: New page, importing")
            return true // New page, import it
        }
        
        // Compare timestamps
        val localUpdateTime = existingPage.updatedAt.time
        val remoteUpdateTime = serializer.parseDate(syncPageData.page.updatedAt).time
        
        Log.d(TAG, "shouldImportPage: Local time: $localUpdateTime, Remote time: $remoteUpdateTime")
        val shouldImport = remoteUpdateTime > localUpdateTime
        Log.d(TAG, "shouldImportPage: Result: $shouldImport")
        
        return shouldImport
    }
    
    private suspend fun importPage(syncPageData: SyncPageData) {
        // Create or update notebook
        val existingNotebook = repository.getNotebookById(syncPageData.notebook.id)
        if (existingNotebook == null) {
            val notebook = Notebook(
                id = syncPageData.notebook.id,
                title = syncPageData.notebook.title,
                parentFolderId = syncPageData.notebook.parentFolderId,
                defaultNativeTemplate = syncPageData.notebook.defaultNativeTemplate,
                pageIds = listOf(syncPageData.page.id),
                createdAt = serializer.parseDate(syncPageData.notebook.createdAt),
                updatedAt = serializer.parseDate(syncPageData.notebook.updatedAt)
            )
            repository.insertNotebook(notebook)
        } else {
            // Update notebook if this page isn't in the page list
            if (!existingNotebook.pageIds.contains(syncPageData.page.id)) {
                val updatedNotebook = existingNotebook.copy(
                    pageIds = existingNotebook.pageIds + syncPageData.page.id
                )
                repository.updateNotebook(updatedNotebook)
            }
        }
        
        // Create or update page
        val page = Page(
            id = syncPageData.page.id,
            notebookId = syncPageData.page.notebookId,
            scroll = syncPageData.page.scroll,
            background = syncPageData.page.background,
            backgroundType = syncPageData.page.backgroundType,
            createdAt = serializer.parseDate(syncPageData.page.createdAt),
            updatedAt = serializer.parseDate(syncPageData.page.updatedAt)
        )
        repository.upsertPage(page)
        
        // Clear existing strokes and images for this page
        repository.deleteStrokesByPageId(syncPageData.page.id)
        repository.deleteImagesByPageId(syncPageData.page.id)
        
        // Import strokes
        syncPageData.strokes.forEach { syncStroke ->
            val stroke = Stroke(
                id = syncStroke.id,
                size = syncStroke.size,
                pen = Pen.valueOf(syncStroke.pen),
                color = syncStroke.color,
                top = syncStroke.boundingBox.top,
                bottom = syncStroke.boundingBox.bottom,
                left = syncStroke.boundingBox.left,
                right = syncStroke.boundingBox.right,
                points = syncStroke.points.map { point ->
                    StrokePoint(
                        x = point.x,
                        y = point.y,
                        pressure = point.pressure,
                        size = point.size,
                        tiltX = point.tiltX,
                        tiltY = point.tiltY,
                        timestamp = point.timestamp
                    )
                },
                pageId = syncPageData.page.id,
                createdAt = serializer.parseDate(syncStroke.createdAt),
                updatedAt = serializer.parseDate(syncStroke.updatedAt)
            )
            repository.insertStroke(stroke)
        }
        
        // Import images (placeholder - would need full image handling)
        syncPageData.images.forEach { syncImage ->
            val image = Image(
                id = syncImage.id,
                x = syncImage.position.x,
                y = syncImage.position.y,
                width = syncImage.dimensions.width,
                height = syncImage.dimensions.height,
                uri = syncImage.uri,
                pageId = syncPageData.page.id,
                createdAt = serializer.parseDate(syncImage.createdAt),
                updatedAt = serializer.parseDate(syncImage.updatedAt)
            )
            repository.insertImage(image)
        }
    }
    
    private fun getOrCreateDeviceId(): String {
        val existingId = prefs.getString("device_id", null)
        if (existingId != null) {
            return existingId
        }
        
        val newId = UUID.randomUUID().toString()
        prefs.edit().putString("device_id", newId).apply()
        return newId
    }
    
    suspend fun syncBidirectional(): SyncResult {
        if (!isEnabled) return SyncResult.DISABLED
        
        val client = webdavClient ?: return SyncResult.NOT_CONFIGURED
        
        return try {
            Log.d(TAG, "Starting bidirectional sync")
            
            // First upload local changes
            val uploadResult = syncAll()
            Log.d(TAG, "Upload result: $uploadResult")
            
            // Then download remote changes
            val downloadResult = pullChanges()
            Log.d(TAG, "Download result: $downloadResult")
            
            // Combine results
            when {
                uploadResult == SyncResult.SUCCESS && downloadResult == SyncResult.SUCCESS -> SyncResult.SUCCESS
                uploadResult == SyncResult.SUCCESS && downloadResult == SyncResult.UP_TO_DATE -> SyncResult.SUCCESS
                uploadResult == SyncResult.UP_TO_DATE && downloadResult == SyncResult.SUCCESS -> SyncResult.SUCCESS
                uploadResult == SyncResult.UP_TO_DATE && downloadResult == SyncResult.UP_TO_DATE -> SyncResult.UP_TO_DATE
                uploadResult == SyncResult.ERROR || downloadResult == SyncResult.ERROR -> SyncResult.ERROR
                else -> SyncResult.PARTIAL_SUCCESS
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in bidirectional sync", e)
            SyncResult.ERROR
        }
    }
    
    suspend fun replaceLocalWithServer(): SyncResult {
        if (!isEnabled) return SyncResult.DISABLED
        
        val client = webdavClient ?: return SyncResult.NOT_CONFIGURED
        
        return try {
            Log.d(TAG, "Starting replace local with server")
            
            // Clear all local data
            Log.d(TAG, "Clearing local data")
            repository.deleteAllStrokes()
            repository.deleteAllImages()
            repository.deleteAllPages()
            repository.deleteAllNotebooks()
            repository.deleteAllFolders()
            
            // Download everything from server
            val result = pullChanges()
            Log.d(TAG, "Replace local with server result: $result")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Error replacing local with server", e)
            SyncResult.ERROR
        }
    }
    
    suspend fun replaceServerWithLocal(): SyncResult {
        if (!isEnabled) return SyncResult.DISABLED
        
        val client = webdavClient ?: return SyncResult.NOT_CONFIGURED
        
        return try {
            Log.d(TAG, "Starting replace server with local [DANGER!]")
            
            // Clear all remote data
            Log.d(TAG, "Clearing remote data")
            val success = clearRemoteData()
            if (!success) {
                Log.e(TAG, "Failed to clear remote data")
                return SyncResult.ERROR
            }
            
            // Re-initialize directory structure
            val initSuccess = initializeRemoteStructure()
            if (!initSuccess) {
                Log.e(TAG, "Failed to re-initialize remote structure")
                return SyncResult.ERROR
            }
            
            // Upload everything from local
            val result = syncAll()
            Log.d(TAG, "Replace server with local result: $result")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Error replacing server with local", e)
            SyncResult.ERROR
        }
    }
    
    private suspend fun clearRemoteData(): Boolean {
        val client = webdavClient ?: return false
        
        return try {
            // Delete all files in each directory
            val pageFiles = client.listFiles("pages/")
            pageFiles.forEach { file ->
                client.deleteFile("pages/${file.name}")
            }
            
            val notebookFiles = client.listFiles("notebooks/")
            notebookFiles.forEach { file ->
                client.deleteFile("notebooks/${file.name}")
            }
            
            val deviceFiles = client.listFiles("devices/")
            deviceFiles.forEach { file ->
                client.deleteFile("devices/${file.name}")
            }
            
            Log.d(TAG, "Cleared ${pageFiles.size} page files, ${notebookFiles.size} notebook files, ${deviceFiles.size} device files")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing remote data", e)
            false
        }
    }
    
    fun cleanup() {
        syncScope.cancel()
    }
}

enum class SyncResult {
    SUCCESS,
    PARTIAL_SUCCESS,
    UP_TO_DATE,
    ERROR,
    DISABLED,
    NOT_CONFIGURED,
    PAGE_NOT_FOUND,
    NOTEBOOK_NOT_FOUND,
    UPLOAD_FAILED
}