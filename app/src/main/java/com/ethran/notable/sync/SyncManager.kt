package com.ethran.notable.sync

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.ethran.notable.classes.AppRepository
import com.ethran.notable.db.*
import com.ethran.notable.utils.Pen
import kotlinx.coroutines.*
import java.util.*
import java.util.Date

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
    
    init {
        // Initialize WebDAV client if settings are already configured
        Log.d(TAG, "SyncManager constructor - checking for existing settings")
        if (isEnabled && serverUrl.isNotEmpty() && username.isNotEmpty() && password.isNotEmpty()) {
            Log.d(TAG, "Found existing sync settings, initializing WebDAV client")
            initialize()
        } else {
            Log.d(TAG, "No valid sync settings found on startup")
        }
    }
    
    // Configuration
    var isEnabled: Boolean
        get() = prefs.getBoolean("sync_enabled", false)
        set(value) {
            prefs.edit().putBoolean("sync_enabled", value).apply()
            // Re-initialize client when settings change
            if (value) initialize()
        }
    
    var isAutoSyncEnabled: Boolean
        get() = prefs.getBoolean("auto_sync_on_close_enabled", true) // Default to enabled
        set(value) {
            prefs.edit().putBoolean("auto_sync_on_close_enabled", value).apply()
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
    
    /**
     * Auto-sync a notebook when it's closed, if connection is available
     * This is a lightweight background sync that only runs if connectivity is good
     */
    suspend fun autoSyncNotebook(notebookId: String): Boolean {
        if (!isEnabled) {
            Log.d(TAG, "Auto-sync skipped: sync disabled")
            return false
        }
        
        if (!isAutoSyncEnabled) {
            Log.d(TAG, "Auto-sync skipped: auto-sync feature disabled")
            return false
        }
        
        Log.d(TAG, "Auto-sync triggered for notebook: $notebookId")
        
        // Quick connection test (timeout should be shorter for auto-sync)
        val isConnected = try {
            withContext(Dispatchers.IO) {
                webdavClient?.testConnection() ?: false
            }
        } catch (e: Exception) {
            Log.d(TAG, "Auto-sync skipped: connection test failed - ${e.message}")
            false
        }
        
        if (!isConnected) {
            Log.d(TAG, "Auto-sync skipped: no connection to server")
            return false
        }
        
        // Sync the specific notebook in background
        return try {
            val result = syncNotebook(notebookId)
            when (result) {
                SyncResult.SUCCESS -> {
                    Log.d(TAG, "Auto-sync completed successfully for notebook: $notebookId")
                    true
                }
                SyncResult.UP_TO_DATE -> {
                    Log.d(TAG, "Auto-sync completed (up to date) for notebook: $notebookId")
                    true
                }
                else -> {
                    Log.d(TAG, "Auto-sync failed for notebook: $notebookId - result: $result")
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Auto-sync error for notebook: $notebookId", e)
            false
        }
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
            
            Log.d(TAG, "Serialized page $pageId: ${jsonData.length} bytes, ${strokes.size} strokes, ${images.size} images")
            
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
    
    suspend fun syncStandalonePage(pageId: String): SyncResult {
        if (!isEnabled) return SyncResult.DISABLED
        
        val client = webdavClient ?: return SyncResult.NOT_CONFIGURED
        
        return try {
            // Get page data from database (Quick Pages have notebookId = null)
            val page = repository.getPageById(pageId) ?: return SyncResult.PAGE_NOT_FOUND
            
            // Ensure this is actually a standalone page
            if (page.notebookId != null) {
                Log.w(TAG, "syncStandalonePage called on notebook page $pageId, using syncPage instead")
                return syncPage(pageId)
            }
            
            val strokes = repository.getStrokesByPageId(pageId)
            val images = repository.getImagesByPageId(pageId)
            val folderPath = getFolderPath(page.parentFolderId)
            
            // Create a synthetic notebook for the standalone page
            val syntheticNotebook = com.ethran.notable.db.Notebook(
                id = "quickpage_${pageId}",
                title = "Quick Page",
                parentFolderId = page.parentFolderId,
                pageIds = listOf(pageId),
                createdAt = page.createdAt,
                updatedAt = page.updatedAt
            )
            
            // Serialize page data
            val jsonData = serializer.serializePage(
                notebook = syntheticNotebook,
                page = page,
                strokes = strokes,
                images = images,
                folderPath = folderPath,
                deviceId = deviceId
            )
            
            Log.d(TAG, "Serialized standalone page $pageId: ${jsonData.length} bytes, ${strokes.size} strokes, ${images.size} images")
            
            // Upload to WebDAV using the synthetic notebook ID
            val success = client.uploadPageSync(syntheticNotebook.id, pageId, jsonData)
            
            if (success) {
                Log.d(TAG, "Standalone page $pageId synced successfully")
                SyncResult.SUCCESS
            } else {
                Log.e(TAG, "Failed to upload standalone page $pageId")
                SyncResult.UPLOAD_FAILED
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing standalone page $pageId", e)
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
            
            // Get notebooks and pages modified since last sync (incremental sync)
            val lastSyncDate = Date(lastSyncTime)
            val allLocalNotebooks = repository.getAllNotebooks()
            Log.d(TAG, "DEBUG: repository.getAllNotebooks() returned ${allLocalNotebooks.size} notebooks")
            allLocalNotebooks.forEachIndexed { index, notebook ->
                Log.d(TAG, "DEBUG: Notebook $index: id=${notebook.id}, title='${notebook.title}', updatedAt=${notebook.updatedAt}")
            }
            
            val (modifiedNotebooks, modifiedPages) = if (lastSyncTime == 0L) {
                Log.d(TAG, "First sync - uploading all notebooks and pages")
                val allPages = repository.getPagesModifiedAfter(Date(0))
                Log.d(TAG, "DEBUG: repository.getPagesModifiedAfter(Date(0)) returned ${allPages.size} pages")
                Pair(allLocalNotebooks, allPages)
            } else if (allLocalNotebooks.isEmpty()) {
                Log.d(TAG, "Empty local database detected - forcing full download instead of incremental sync")
                Pair(emptyList<com.ethran.notable.db.Notebook>(), emptyList<com.ethran.notable.db.Page>())
            } else {
                val incrementalNotebooks = repository.getNotebooksModifiedAfter(lastSyncDate)
                val incrementalPages = repository.getPagesModifiedAfter(lastSyncDate)
                
                // Check if we have local data but incremental sync finds nothing (data older than sync timestamp)
                if (incrementalNotebooks.isEmpty() && incrementalPages.isEmpty() && allLocalNotebooks.isNotEmpty()) {
                    Log.d(TAG, "Local data exists but is older than sync timestamp - forcing full download to merge with server")
                    Pair(emptyList<com.ethran.notable.db.Notebook>(), emptyList<com.ethran.notable.db.Page>())
                } else {
                    Log.d(TAG, "Incremental sync - uploading content modified after $lastSyncDate")
                    Log.d(TAG, "DEBUG: Incremental sync found ${incrementalNotebooks.size} notebooks and ${incrementalPages.size} pages")
                    Pair(incrementalNotebooks, incrementalPages)
                }
            }
            
            // Find notebooks that need syncing due to page changes
            val notebooksWithModifiedPages = modifiedPages
                .filter { it.notebookId != null }
                .mapNotNull { repository.getNotebookById(it.notebookId!!) }
                .distinctBy { it.id }
            
            // Combine explicitly modified notebooks with notebooks having modified pages
            val allNotebooksToSync = (modifiedNotebooks + notebooksWithModifiedPages).distinctBy { it.id }
            
            // Find Quick Pages (standalone pages with notebookId = null)
            val quickPages = modifiedPages.filter { it.notebookId == null }
            
            Log.d(TAG, "Found ${allNotebooksToSync.size} notebooks to sync (${modifiedNotebooks.size} directly modified, ${notebooksWithModifiedPages.size} with modified pages)")
            Log.d(TAG, "Found ${quickPages.size} Quick Pages to sync")
            
            if (allNotebooksToSync.isEmpty() && quickPages.isEmpty()) {
                Log.d(TAG, "No content to sync")
                // Don't update sync time here - wait until after download is also complete
                return SyncResult.UP_TO_DATE
            }
            
            // Sync notebooks (including those with modified pages)
            val notebookResults = allNotebooksToSync.map { notebook ->
                syncNotebook(notebook.id)
            }
            
            // Sync Quick Pages as individual pages
            val quickPageResults = quickPages.map { page ->
                syncStandalonePage(page.id)
            }
            
            val allResults = notebookResults + quickPageResults
            
            val successCount = allResults.count { it == SyncResult.SUCCESS }
            val totalCount = allResults.size
            
            // Don't update sync time here - will be updated after bidirectional sync is complete
            
            when {
                successCount == totalCount -> {
                    Log.d(TAG, "Incremental sync completed successfully ($successCount notebooks)")
                    SyncResult.SUCCESS
                }
                successCount > 0 -> {
                    Log.w(TAG, "Partial sync completed: $successCount/$totalCount")
                    SyncResult.PARTIAL_SUCCESS
                }
                else -> {
                    Log.e(TAG, "Incremental sync failed")
                    SyncResult.ERROR
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in incremental sync", e)
            SyncResult.ERROR
        }
    }
    
    suspend fun pullChanges(): SyncResult {
        if (!isEnabled) return SyncResult.DISABLED
        
        val client = webdavClient ?: return SyncResult.NOT_CONFIGURED
        
        return try {
            Log.d(TAG, "Starting pullChanges() with incremental approach")
            
            // Get list of all page sync files with metadata
            val pageFiles = client.listPageSyncs()
            Log.d(TAG, "Found ${pageFiles.size} page files on server")
            
            // Check if we need to force full download due to local data being older than sync timestamp
            val allLocalNotebooks = repository.getAllNotebooks()
            val shouldForceFullDownload = lastSyncTime > 0L && allLocalNotebooks.isNotEmpty() && 
                repository.getNotebooksModifiedAfter(Date(lastSyncTime)).isEmpty() && 
                repository.getPagesModifiedAfter(Date(lastSyncTime)).isEmpty()
            
            // Filter files based on last sync time (only process files newer than last sync)
            val filesToProcess = if (lastSyncTime > 0L && !shouldForceFullDownload) {
                val lastSyncDate = Date(lastSyncTime)
                Log.d(TAG, "Incremental pull - only checking files modified after $lastSyncDate")
                pageFiles.filter { fileInfo ->
                    (fileInfo.lastModified?.time ?: 0L) > lastSyncTime
                }
            } else if (shouldForceFullDownload) {
                Log.d(TAG, "Forcing full download - local data exists but is older than sync timestamp")
                pageFiles
            } else {
                Log.d(TAG, "First pull - checking all files")
                pageFiles
            }
            
            Log.d(TAG, "Filtered down to ${filesToProcess.size} files to process")
            
            if (filesToProcess.isEmpty()) {
                Log.d(TAG, "No new files to process")
                return SyncResult.UP_TO_DATE
            }
            
            var importCount = 0
            var errorCount = 0
            
            for (fileInfo in filesToProcess) {
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
            
            Log.d(TAG, "Incremental pull completed: imported $importCount pages, $errorCount errors")
            
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
    
    suspend fun hasLocalChanges(): Boolean {
        return try {
            val lastSyncDate = Date(lastSyncTime)
            val modifiedNotebooks = repository.getNotebooksModifiedAfter(lastSyncDate)
            val modifiedPages = repository.getPagesModifiedAfter(lastSyncDate)
            
            val hasChanges = modifiedNotebooks.isNotEmpty() || modifiedPages.isNotEmpty()
            Log.d(TAG, "hasLocalChanges: $hasChanges (${modifiedNotebooks.size} notebooks, ${modifiedPages.size} pages)")
            hasChanges
        } catch (e: Exception) {
            Log.e(TAG, "Error checking local changes", e)
            false
        }
    }
    
    suspend fun hasRemoteChanges(): Boolean {
        if (!isEnabled) return false
        val client = webdavClient ?: return false
        
        return try {
            if (lastSyncTime == 0L) {
                // First sync - check if server has any files
                val pageFiles = client.listPageSyncs()
                val hasChanges = pageFiles.isNotEmpty()
                Log.d(TAG, "hasRemoteChanges: $hasChanges (first sync, ${pageFiles.size} files on server)")
                hasChanges
            } else {
                // Check if any files are newer than last sync
                val pageFiles = client.listPageSyncs()
                val newFiles = pageFiles.filter { (it.lastModified?.time ?: 0L) > lastSyncTime }
                val hasChanges = newFiles.isNotEmpty()
                Log.d(TAG, "hasRemoteChanges: $hasChanges (${newFiles.size} files newer than last sync)")
                hasChanges
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking remote changes", e)
            false
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
        try {
            // Create or update notebook
            val existingNotebook = repository.getNotebookById(syncPageData.notebook.id)
        if (existingNotebook == null) {
            val notebook = Notebook(
                id = syncPageData.notebook.id,
                title = syncPageData.notebook.title,
                parentFolderId = null, // Set to null to avoid foreign key constraint failures until folder sync is implemented
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
        } catch (e: Exception) {
            Log.e(TAG, "Error importing page ${syncPageData.page.id}: ${e.message}", e)
            throw e
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
            val combinedResult = when {
                uploadResult == SyncResult.SUCCESS && downloadResult == SyncResult.SUCCESS -> SyncResult.SUCCESS
                uploadResult == SyncResult.SUCCESS && downloadResult == SyncResult.UP_TO_DATE -> SyncResult.SUCCESS
                uploadResult == SyncResult.UP_TO_DATE && downloadResult == SyncResult.SUCCESS -> SyncResult.SUCCESS
                uploadResult == SyncResult.UP_TO_DATE && downloadResult == SyncResult.UP_TO_DATE -> SyncResult.UP_TO_DATE
                uploadResult == SyncResult.ERROR || downloadResult == SyncResult.ERROR -> SyncResult.ERROR
                else -> SyncResult.PARTIAL_SUCCESS
            }
            
            // Update sync timestamp only after both upload and download are complete
            if (combinedResult == SyncResult.SUCCESS || combinedResult == SyncResult.UP_TO_DATE) {
                lastSyncTime = System.currentTimeMillis()
                Log.d(TAG, "Updated lastSyncTime to ${Date(lastSyncTime)}")
            }
            
            combinedResult
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