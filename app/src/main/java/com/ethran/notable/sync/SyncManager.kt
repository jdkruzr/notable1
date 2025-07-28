package com.ethran.notable.sync

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.ethran.notable.classes.AppRepository
import com.ethran.notable.db.*
import com.ethran.notable.utils.Pen
import com.ethran.notable.utils.NetworkMonitor
import com.ethran.notable.utils.SyncLogger
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
    private val syncQueueRepository = SyncQueueRepository(context)
    private val deletionLogRepository = com.ethran.notable.db.DeletionLogRepository(context)
    private val networkMonitor = NetworkMonitor(context)
    private val database = AppDatabase.getDatabase(context)
    
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
        
        // Start network monitoring and queue processing
        startNetworkMonitoring()
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
        SyncLogger.info("Testing WebDAV connection...")
        Log.d(TAG, "testConnection() called")
        Log.d(TAG, "serverUrl: '$serverUrl', username: '$username', password: '${password.take(3)}...'")
        
        // Ensure we have a client initialized
        if (webdavClient == null) {
            SyncLogger.debug("WebDAV client not initialized, attempting to initialize")
            Log.d(TAG, "WebDAV client is null, trying to initialize")
            if (!initialize()) {
                SyncLogger.error("Failed to initialize WebDAV client")
                Log.d(TAG, "Failed to initialize WebDAV client")
                return false
            }
        }
        
        val result = webdavClient?.testConnection() ?: false
        if (result) {
            SyncLogger.info("✓ WebDAV connection test successful")
        } else {
            SyncLogger.error("✗ WebDAV connection test failed")
        }
        return result
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
            val page = withContext(Dispatchers.IO) {
                repository.getPageById(pageId)
            } ?: return SyncResult.PAGE_NOT_FOUND
            val notebook = withContext(Dispatchers.IO) {
                repository.getNotebookById(page.notebookId ?: "")
            } ?: return SyncResult.NOTEBOOK_NOT_FOUND
            val strokes = withContext(Dispatchers.IO) {
                repository.getStrokesByPageId(pageId)
            }
            val images = withContext(Dispatchers.IO) {
                repository.getImagesByPageId(pageId)
            }
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
            
            // Upload image files to WebDAV server and track individual results
            val failedImages = mutableListOf<Image>()
            var imageUploadSuccess = true
            
            for (image in images) {
                if (image.uri != null) {
                    try {
                        val success = uploadImageFile(client, image)
                        if (!success) {
                            Log.w(TAG, "Failed to upload image ${image.id}")
                            failedImages.add(image)
                            imageUploadSuccess = false
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error uploading image ${image.id}", e)
                        failedImages.add(image)
                        imageUploadSuccess = false
                    }
                }
            }
            
            // Upload page metadata to WebDAV
            val pageMetadataSuccess = client.uploadPageSync(notebook.id, pageId, jsonData)
            
            if (pageMetadataSuccess && imageUploadSuccess) {
                SyncLogger.info("✓ Page $pageId synced successfully (including ${images.size} images)")
                Log.d(TAG, "Page $pageId synced successfully (including ${images.size} images)")
                SyncResult.SUCCESS
            } else if (pageMetadataSuccess && !imageUploadSuccess) {
                SyncLogger.warn("⚠ Page $pageId metadata synced but ${failedImages.size} images failed")
                Log.w(TAG, "Page $pageId metadata synced but ${failedImages.size} images failed")
                
                // Only queue the specific images that failed, not the entire page
                for (image in failedImages) {
                    val localFile = java.io.File(image.uri!!)
                    if (localFile.exists()) {
                        syncQueueRepository.queueImageUpload(image.id, image.uri!!)
                    }
                }
                
                SyncResult.PARTIAL_SUCCESS
            } else {
                SyncLogger.error("✗ Failed to upload page $pageId metadata, queuing entire page for retry")
                Log.e(TAG, "Failed to upload page $pageId metadata, queuing entire page for retry")
                
                // Page metadata failed - queue the entire page for retry
                // This will re-attempt both metadata and all images
                syncQueueRepository.queuePageUpload(pageId, jsonData)
                
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
            val notebook = withContext(Dispatchers.IO) {
                repository.getNotebookById(notebookId)
            } ?: return SyncResult.NOTEBOOK_NOT_FOUND
            val folderPath = getFolderPath(notebook.parentFolderId)
            
            // Sync notebook metadata
            val metadataJson = serializer.serializeNotebookMetadata(
                notebook = notebook,
                folderPath = folderPath,
                deviceId = deviceId
            )
            
            val metadataSuccess = client.uploadNotebookMetadata(notebookId, metadataJson)
            
            if (!metadataSuccess) {
                Log.e(TAG, "Failed to upload notebook metadata $notebookId, queuing for retry")
                syncQueueRepository.queueNotebookUpload(notebookId, metadataJson)
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
    
    
    suspend fun syncDeletions(): SyncResult {
        if (!isEnabled) return SyncResult.DISABLED
        
        val client = webdavClient ?: return SyncResult.NOT_CONFIGURED
        
        return try {
            val unsyncedDeletions = withContext(Dispatchers.IO) {
                deletionLogRepository.getUnsyncedDeletions()
            }
            if (unsyncedDeletions.isEmpty()) {
                Log.d(TAG, "No deletions to sync")
                SyncLogger.debug("No deletions to sync")
                return SyncResult.UP_TO_DATE
            }
            
            Log.d(TAG, "Syncing ${unsyncedDeletions.size} deletions to server")
            SyncLogger.info("Found ${unsyncedDeletions.size} deletions to sync to server")
            var successCount = 0
            var failureCount = 0
            
            for (deletion in unsyncedDeletions) {
                try {
                    val success = when (deletion.deletedItemType) {
                        com.ethran.notable.db.DeletionType.NOTEBOOK -> {
                            // Delete notebook metadata file and all page files in the notebook
                            val notebookMetadataDeleted = client.deleteFile("notebooks/${deletion.deletedItemId}.json")
                            
                            // Also attempt to delete any page files that belonged to this notebook
                            // (We don't have the page list anymore, but the files might still exist)
                            val pageFiles = client.listFiles("pages/")
                            var pageFilesDeleted = true
                            for (pageFile in pageFiles) {
                                if (pageFile.name.startsWith("${deletion.deletedItemId}_")) {
                                    if (!client.deleteFile("pages/${pageFile.name}")) {
                                        pageFilesDeleted = false
                                    }
                                }
                            }
                            
                            notebookMetadataDeleted && pageFilesDeleted
                        }
                        com.ethran.notable.db.DeletionType.PAGE -> {
                            // Delete page sync file
                            // We need to find the file that matches this page ID
                            val pageFiles = client.listPageSyncs()
                            var pageFileDeleted = false
                            for (pageFile in pageFiles) {
                                val syncInfo = pageFile.parseFilename()
                                if (syncInfo?.pageId == deletion.deletedItemId) {
                                    pageFileDeleted = client.deleteFile("pages/${pageFile.name}")
                                    break
                                }
                            }
                            pageFileDeleted
                        }
                        com.ethran.notable.db.DeletionType.FOLDER -> {
                            // TODO: Implement folder deletion when folder sync is implemented
                            Log.w(TAG, "Folder deletion sync not yet implemented")
                            true // Skip for now
                        }
                    }
                    
                    if (success) {
                        withContext(Dispatchers.IO) {
                            deletionLogRepository.markDeletionAsSynced(deletion)
                        }
                        successCount++
                        Log.d(TAG, "Successfully synced deletion of ${deletion.deletedItemType} ${deletion.deletedItemId}")
                        SyncLogger.info("✓ Deleted ${deletion.deletedItemType.name.lowercase()} ${deletion.deletedItemId} from server")
                    } else {
                        failureCount++
                        Log.w(TAG, "Failed to sync deletion of ${deletion.deletedItemType} ${deletion.deletedItemId}")
                        SyncLogger.warn("✗ Failed to delete ${deletion.deletedItemType.name.lowercase()} ${deletion.deletedItemId} from server")
                    }
                } catch (e: Exception) {
                    failureCount++
                    Log.e(TAG, "Error syncing deletion of ${deletion.deletedItemType} ${deletion.deletedItemId}", e)
                }
            }
            
            Log.d(TAG, "Deletion sync completed: $successCount successes, $failureCount failures")
            
            when {
                successCount > 0 && failureCount == 0 -> SyncResult.SUCCESS
                successCount > 0 && failureCount > 0 -> SyncResult.PARTIAL_SUCCESS
                else -> SyncResult.ERROR
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing deletions", e)
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
            
            // Sync deletions first
            val deletionResult = syncDeletions()
            SyncLogger.info("Deletion sync result: $deletionResult")
            Log.d(TAG, "Deletion sync result: $deletionResult")
            
            // Get notebooks and pages modified since last sync (incremental sync)
            val lastSyncDate = Date(lastSyncTime)
            val allLocalNotebooks = withContext(Dispatchers.IO) {
                repository.getAllNotebooks()
            }
            Log.d(TAG, "DEBUG: repository.getAllNotebooks() returned ${allLocalNotebooks.size} notebooks")
            allLocalNotebooks.forEachIndexed { index, notebook ->
                Log.d(TAG, "DEBUG: Notebook $index: id=${notebook.id}, title='${notebook.title}', updatedAt=${notebook.updatedAt}")
            }
            
            val (modifiedNotebooks, modifiedPages) = if (lastSyncTime == 0L) {
                Log.d(TAG, "First sync - uploading all notebooks and pages")
                val allPages = withContext(Dispatchers.IO) {
                    repository.getPagesModifiedAfter(Date(0))
                }
                Log.d(TAG, "DEBUG: repository.getPagesModifiedAfter(Date(0)) returned ${allPages.size} pages")
                Pair(allLocalNotebooks, allPages)
            } else if (allLocalNotebooks.isEmpty()) {
                Log.d(TAG, "Empty local database detected - forcing full download instead of incremental sync")
                Pair(emptyList<com.ethran.notable.db.Notebook>(), emptyList<com.ethran.notable.db.Page>())
            } else {
                val incrementalNotebooks = withContext(Dispatchers.IO) {
                    repository.getNotebooksModifiedAfter(lastSyncDate)
                }
                val incrementalPages = withContext(Dispatchers.IO) {
                    repository.getPagesModifiedAfter(lastSyncDate)
                }
                
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
            val notebooksWithModifiedPages = withContext(Dispatchers.IO) {
                modifiedPages
                    .filter { it.notebookId != null }
                    .mapNotNull { repository.getNotebookById(it.notebookId!!) }
                    .distinctBy { it.id }
            }
            
            // Combine explicitly modified notebooks with notebooks having modified pages
            val allNotebooksToSync = (modifiedNotebooks + notebooksWithModifiedPages).distinctBy { it.id }
            
            
            Log.d(TAG, "Found ${allNotebooksToSync.size} notebooks to sync (${modifiedNotebooks.size} directly modified, ${notebooksWithModifiedPages.size} with modified pages)")
            
            if (allNotebooksToSync.isEmpty()) {
                Log.d(TAG, "No content to sync")
                // Don't update sync time here - wait until after download is also complete
                return SyncResult.UP_TO_DATE
            }
            
            // Sync all notebooks (including Quick Page notebooks)
            val allResults = allNotebooksToSync.map { notebook ->
                syncNotebook(notebook.id)
            }
            
            
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
            val allLocalNotebooks = withContext(Dispatchers.IO) {
                repository.getAllNotebooks()
            }
            val shouldForceFullDownload = lastSyncTime > 0L && allLocalNotebooks.isNotEmpty() && withContext(Dispatchers.IO) {
                repository.getNotebooksModifiedAfter(Date(lastSyncTime)).isEmpty() && 
                repository.getPagesModifiedAfter(Date(lastSyncTime)).isEmpty()
            }
            
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
            val modifiedNotebooks = withContext(Dispatchers.IO) {
                repository.getNotebooksModifiedAfter(lastSyncDate)
            }
            val modifiedPages = withContext(Dispatchers.IO) {
                repository.getPagesModifiedAfter(lastSyncDate)
            }
            
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
            val folder = withContext(Dispatchers.IO) {
                repository.getFolderById(currentFolderId)
            }
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
        val existingPage = runBlocking { 
            withContext(Dispatchers.IO) {
                repository.getPageById(syncPageData.page.id)
            }
        }
        
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
            Log.d(TAG, "Starting import of page ${syncPageData.page.id} with transaction boundaries")
            
            // Pre-download images outside of transaction to avoid holding database locks during network operations
            val imagesWithLocalPaths = mutableListOf<Image>()
            
            withContext(Dispatchers.IO) {
                syncPageData.images.forEach { syncImage ->
                    val baseImage = Image(
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
                    
                    // Download image file from server (outside transaction)
                    val client = webdavClient
                    val finalImage = if (client != null) {
                        val localPath = downloadImageFile(client, baseImage)
                        if (localPath != null) {
                            Log.d(TAG, "Pre-downloaded image ${baseImage.id} to $localPath")
                            baseImage.copy(uri = localPath)
                        } else {
                            Log.w(TAG, "Failed to download image ${baseImage.id}, keeping original URI")
                            baseImage
                        }
                    } else {
                        Log.w(TAG, "No WebDAV client available for image ${baseImage.id}")
                        baseImage
                    }
                    
                    imagesWithLocalPaths.add(finalImage)
                }
            }
            
            // Now perform all database operations in a single transaction
            withContext(Dispatchers.IO) {
                database.runInTransaction {
                    Log.d(TAG, "Starting database transaction for page ${syncPageData.page.id}")
                    
                    // 1. Create or update notebook
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
                        Log.d(TAG, "Created new notebook ${notebook.id}")
                    } else {
                        // Update notebook if this page isn't in the page list
                        if (!existingNotebook.pageIds.contains(syncPageData.page.id)) {
                            val updatedNotebook = existingNotebook.copy(
                                pageIds = existingNotebook.pageIds + syncPageData.page.id
                            )
                            repository.updateNotebook(updatedNotebook)
                            Log.d(TAG, "Updated notebook ${updatedNotebook.id} with page ${syncPageData.page.id}")
                        }
                    }
                    
                    // 2. Create or update page
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
                    Log.d(TAG, "Upserted page ${page.id}")
                    
                    // 3. Clear existing strokes and images for this page
                    repository.deleteStrokesByPageId(syncPageData.page.id)
                    repository.deleteImagesByPageId(syncPageData.page.id)
                    Log.d(TAG, "Cleared existing strokes and images for page ${syncPageData.page.id}")
                    
                    // 4. Import strokes
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
                    Log.d(TAG, "Imported ${syncPageData.strokes.size} strokes for page ${syncPageData.page.id}")
                    
                    // 5. Import images with pre-downloaded local paths
                    imagesWithLocalPaths.forEach { image ->
                        repository.insertImage(image)
                    }
                    Log.d(TAG, "Imported ${imagesWithLocalPaths.size} images for page ${syncPageData.page.id}")
                    
                    Log.d(TAG, "Database transaction completed successfully for page ${syncPageData.page.id}")
                }
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
            
            // First upload local changes (including deletions)
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
            
            // Clear all local data in a single transaction
            Log.d(TAG, "Clearing local data with transaction boundaries")
            withContext(Dispatchers.IO) {
                database.runInTransaction {
                    Log.d(TAG, "Starting database transaction to clear all local data")
                    repository.deleteAllStrokes()
                    repository.deleteAllImages()
                    repository.deleteAllPages()
                    repository.deleteAllNotebooks()
                    repository.deleteAllFolders()
                    Log.d(TAG, "Database transaction completed - all local data cleared")
                }
            }
            
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
    
    private suspend fun uploadImageFile(client: WebDAVClient, image: Image): Boolean {
        return try {
            if (image.uri == null) return true // No file to upload
            
            // Convert file:// URI to proper file path
            val filePath = if (image.uri.startsWith("file://")) {
                image.uri.removePrefix("file://")
            } else {
                image.uri
            }
            
            val localFile = java.io.File(filePath)
            if (!localFile.exists()) {
                Log.w(TAG, "Local image file not found: ${image.uri} (resolved to: $filePath)")
                return false
            }
            
            // Extract filename from URI
            val filename = localFile.name
            
            // Check if image already exists on server (deduplication)
            // Note: Some servers have auth issues with HEAD requests, so we handle this gracefully
            try {
                if (client.imageExists(image.id, filename)) {
                    Log.d(TAG, "Image ${image.id} already exists on server, skipping upload")
                    return true
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not check if image exists (server auth issue?), proceeding with upload: ${e.message}")
            }
            
            // Read image file data
            val imageData = localFile.readBytes()
            
            // Upload to server
            val success = client.uploadImage(image.id, filename, imageData)
            if (success) {
                Log.d(TAG, "Uploaded image ${image.id} ($filename): ${imageData.size} bytes")
            }
            
            success
        } catch (e: Exception) {
            Log.e(TAG, "Error uploading image file ${image.id}", e)
            false
        }
    }
    
    private suspend fun downloadImageFile(client: WebDAVClient, image: Image): String? {
        return try {
            if (image.uri == null) return null
            
            // Extract filename from original URI
            val originalFile = java.io.File(image.uri)
            val filename = originalFile.name
            
            // Download image data from server
            val imageData = client.downloadImage(image.id, filename)
            if (imageData == null) {
                Log.w(TAG, "Failed to download image ${image.id} from server")
                return null
            }
            
            // Save to local images folder
            val localImagesDir = com.ethran.notable.utils.ensureImagesFolder()
            val localFile = java.io.File(localImagesDir, filename)
            localFile.writeBytes(imageData)
            
            Log.d(TAG, "Downloaded image ${image.id} ($filename): ${imageData.size} bytes")
            return localFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading image file ${image.id}", e)
            null
        }
    }
    
    /**
     * Process queued sync operations that are ready for retry
     */
    suspend fun processQueue(): SyncResult {
        if (!isEnabled) return SyncResult.DISABLED
        
        val client = webdavClient ?: return SyncResult.NOT_CONFIGURED
        
        return try {
            val readyEntries = withContext(Dispatchers.IO) {
                syncQueueRepository.getReadyForRetry(limit = 10)
            }
            if (readyEntries.isEmpty()) {
                SyncLogger.debug("No queue entries ready for retry")
                Log.d(TAG, "No queue entries ready for retry")
                return SyncResult.UP_TO_DATE
            }
            
            SyncLogger.info("Processing ${readyEntries.size} queued operations from offline queue")
            Log.d(TAG, "Processing ${readyEntries.size} queued operations")
            
            var successCount = 0
            var failureCount = 0
            
            for (entry in readyEntries) {
                Log.d(TAG, "Processing queue entry: ${entry.operation} for ${entry.targetId}")
                
                val success = try {
                    when (entry.operation) {
                        SyncOperation.UPLOAD_PAGE -> {
                            if (entry.jsonData != null) {
                                // Extract notebook ID from the page data
                                val page = withContext(Dispatchers.IO) {
                                    repository.getPageById(entry.targetId)
                                }
                                val notebookId = page?.notebookId
                                if (notebookId != null) {
                                    client.uploadPageSync(notebookId, entry.targetId, entry.jsonData)
                                } else {
                                    Log.w(TAG, "Page ${entry.targetId} has null notebookId - this should not happen after migration")
                                    false
                                }
                            } else false
                        }
                        SyncOperation.UPLOAD_NOTEBOOK -> {
                            if (entry.jsonData != null) {
                                client.uploadNotebookMetadata(entry.targetId, entry.jsonData)
                            } else false
                        }
                        SyncOperation.UPLOAD_IMAGE -> {
                            if (entry.jsonData != null) {
                                val localFile = java.io.File(entry.jsonData)
                                if (localFile.exists()) {
                                    val imageData = localFile.readBytes()
                                    val filename = localFile.name
                                    client.uploadImage(entry.targetId, filename, imageData)
                                } else false
                            } else false
                        }
                        else -> {
                            Log.w(TAG, "Unsupported queue operation: ${entry.operation}")
                            false
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing queue entry ${entry.id}", e)
                    false
                }
                
                if (success) {
                    Log.d(TAG, "Queue entry ${entry.id} processed successfully")
                    withContext(Dispatchers.IO) {
                        syncQueueRepository.markEntrySuccessful(entry)
                    }
                    successCount++
                } else {
                    Log.w(TAG, "Queue entry ${entry.id} failed, updating retry info")
                    withContext(Dispatchers.IO) {
                        syncQueueRepository.markEntryFailed(entry, "Upload failed during queue processing")
                    }
                    failureCount++
                }
            }
            
            Log.d(TAG, "Queue processing completed: $successCount successes, $failureCount failures")
            
            when {
                successCount > 0 && failureCount == 0 -> SyncResult.SUCCESS
                successCount > 0 && failureCount > 0 -> SyncResult.PARTIAL_SUCCESS
                else -> SyncResult.ERROR
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing sync queue", e)
            SyncResult.ERROR
        }
    }
    
    /**
     * Get sync queue status information
     */
    suspend fun getQueueStatus(): SyncQueueStatus {
        return try {
            withContext(Dispatchers.IO) {
                SyncQueueStatus(
                    pendingCount = syncQueueRepository.getPendingCount(),
                    failedCount = syncQueueRepository.getFailedCount(),
                    totalCount = syncQueueRepository.getAllEntries().size
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting queue status", e)
            SyncQueueStatus(0, 0, 0)
        }
    }
    
    /**
     * Clear failed queue entries
     */
    suspend fun clearFailedQueueEntries() {
        try {
            withContext(Dispatchers.IO) {
                syncQueueRepository.deleteFailedEntries()
            }
            Log.d(TAG, "Cleared failed queue entries")
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing failed queue entries", e)
        }
    }
    
    /**
     * Clear all queue entries
     */
    suspend fun clearAllQueueEntries() {
        try {
            withContext(Dispatchers.IO) {
                syncQueueRepository.deleteAllEntries()
            }
            Log.d(TAG, "Cleared all queue entries")
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing all queue entries", e)
        }
    }
    
    /**
     * Start network monitoring and automatic queue processing
     */
    private fun startNetworkMonitoring() {
        Log.d(TAG, "Starting network monitoring for automatic queue processing")
        networkMonitor.startMonitoring()
        
        // Process queue automatically when network becomes available
        syncScope.launch {
            var previousNetworkState = false
            networkMonitor.isNetworkAvailable
                .collect { isAvailable ->
                    // Only trigger when network becomes available (false -> true)
                    if (isAvailable && !previousNetworkState && isEnabled) {
                        Log.d(TAG, "Network became available, checking queue for pending operations")
                        
                        // Small delay to ensure network is stable
                        delay(2000)
                        
                        try {
                            val queueStatus = getQueueStatus()
                            if (queueStatus.hasPendingItems) {
                                Log.d(TAG, "Found ${queueStatus.pendingCount} pending operations, processing queue")
                                val result = processQueue()
                                Log.d(TAG, "Automatic queue processing result: $result")
                            } else {
                                Log.d(TAG, "No pending operations in queue")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error during automatic queue processing", e)
                        }
                    }
                    previousNetworkState = isAvailable
                }
        }
    }
    
    /**
     * Stop network monitoring 
     */
    private fun stopNetworkMonitoring() {
        Log.d(TAG, "Stopping network monitoring")
        networkMonitor.stopMonitoring()
    }
    
    /**
     * Check if network is suitable for sync operations
     */
    fun isNetworkSuitableForSync(): Boolean {
        return networkMonitor.isNetworkSuitableForSync()
    }
    
    /**
     * Check if network is available but limited (cellular)
     */
    fun isNetworkLimited(): Boolean {
        return networkMonitor.isNetworkLimited()
    }
    
    /**
     * Manually trigger queue processing (for user-initiated retry)
     */
    suspend fun retryQueuedOperations(): SyncResult {
        if (!isEnabled) return SyncResult.DISABLED
        
        Log.d(TAG, "Manual retry of queued operations requested")
        
        return try {
            val queueStatus = getQueueStatus()
            if (!queueStatus.hasPendingItems) {
                Log.d(TAG, "No pending operations to retry")
                return SyncResult.UP_TO_DATE
            }
            
            Log.d(TAG, "Manually processing ${queueStatus.pendingCount} pending operations")
            val result = processQueue()
            Log.d(TAG, "Manual queue processing result: $result")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Error during manual queue processing", e)
            SyncResult.ERROR
        }
    }
    
    fun cleanup() {
        stopNetworkMonitoring()
        syncScope.cancel()
    }
    
    // Test method to try the custom REPORT method
    suspend fun testSyncCollectionReport(): Boolean {
        if (!isEnabled) return false
        val client = webdavClient ?: return false
        
        return try {
            Log.d(TAG, "Testing sync-collection REPORT method...")
            
            // First try against our pages/ subfolder
            Log.d(TAG, "Testing against pages/ subfolder...")
            val (files1, syncToken1) = client.listFilesWithSyncCollection("pages/")
            Log.d(TAG, "Pages test: ${files1.size} files returned, syncToken: $syncToken1")
            
            // Also try against root files collection (more likely to support sync-collection)
            Log.d(TAG, "Testing against root files collection...")
            val (files2, syncToken2) = client.listFilesWithSyncCollection("")
            Log.d(TAG, "Root test: ${files2.size} files returned, syncToken: $syncToken2")
            
            Log.d(TAG, "REPORT tests completed")
            true
        } catch (e: Exception) {
            Log.e(TAG, "REPORT test failed", e)
            false
        }
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

data class SyncQueueStatus(
    val pendingCount: Int,
    val failedCount: Int,
    val totalCount: Int
) {
    val hasItems: Boolean = totalCount > 0
    val hasPendingItems: Boolean = pendingCount > 0
    val hasFailedItems: Boolean = failedCount > 0
}