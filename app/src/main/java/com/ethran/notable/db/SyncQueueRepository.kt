package com.ethran.notable.db

import android.content.Context
import java.util.Date

class SyncQueueRepository(context: Context) {
    private val syncQueueDao = AppDatabase.getDatabase(context).syncQueueDao()
    
    suspend fun insertEntry(entry: SyncQueueEntry) = syncQueueDao.insertEntry(entry)
    
    suspend fun updateEntry(entry: SyncQueueEntry) = syncQueueDao.updateEntry(entry)
    
    suspend fun deleteEntry(entry: SyncQueueEntry) = syncQueueDao.deleteEntry(entry)
    
    suspend fun deleteById(id: String) = syncQueueDao.deleteById(id)
    
    suspend fun deleteEntriesForTarget(targetId: String, operation: SyncOperation) = 
        syncQueueDao.deleteEntriesForTarget(targetId, operation)
    
    suspend fun getAllEntries(): List<SyncQueueEntry> = syncQueueDao.getAllEntries()
    
    suspend fun getPendingEntries(): List<SyncQueueEntry> = syncQueueDao.getPendingEntries()
    
    suspend fun getEntriesForTarget(targetId: String, operation: SyncOperation): List<SyncQueueEntry> = 
        syncQueueDao.getEntriesForTarget(targetId, operation)
    
    suspend fun getPendingCount(): Int = syncQueueDao.getPendingCount()
    
    suspend fun getFailedCount(): Int = syncQueueDao.getFailedCount()
    
    suspend fun deleteFailedEntries() = syncQueueDao.deleteFailedEntries()
    
    suspend fun deleteAllEntries() = syncQueueDao.deleteAllEntries()
    
    suspend fun getReadyForRetry(limit: Int = 10): List<SyncQueueEntry> = 
        syncQueueDao.getReadyForRetry(System.currentTimeMillis(), limit)
    
    suspend fun queuePageUpload(pageId: String, jsonData: String) {
        // Remove any existing entries for this page upload to avoid duplicates
        deleteEntriesForTarget(pageId, SyncOperation.UPLOAD_PAGE)
        
        val entry = SyncQueueEntry(
            id = "${SyncOperation.UPLOAD_PAGE}_${pageId}_${System.currentTimeMillis()}",
            operation = SyncOperation.UPLOAD_PAGE,
            targetId = pageId,
            targetType = SyncTargetType.PAGE,
            jsonData = jsonData,
            createdAt = Date(),
            nextRetryAt = Date() // Retry immediately when online
        )
        insertEntry(entry)
    }
    
    suspend fun queueNotebookUpload(notebookId: String, jsonData: String) {
        deleteEntriesForTarget(notebookId, SyncOperation.UPLOAD_NOTEBOOK)
        
        val entry = SyncQueueEntry(
            id = "${SyncOperation.UPLOAD_NOTEBOOK}_${notebookId}_${System.currentTimeMillis()}",
            operation = SyncOperation.UPLOAD_NOTEBOOK,
            targetId = notebookId,
            targetType = SyncTargetType.NOTEBOOK,
            jsonData = jsonData,
            createdAt = Date(),
            nextRetryAt = Date()
        )
        insertEntry(entry)
    }
    
    suspend fun queueStandalonePageUpload(pageId: String, jsonData: String) {
        deleteEntriesForTarget(pageId, SyncOperation.UPLOAD_STANDALONE_PAGE)
        
        val entry = SyncQueueEntry(
            id = "${SyncOperation.UPLOAD_STANDALONE_PAGE}_${pageId}_${System.currentTimeMillis()}",
            operation = SyncOperation.UPLOAD_STANDALONE_PAGE,
            targetId = pageId,
            targetType = SyncTargetType.STANDALONE_PAGE,
            jsonData = jsonData,
            createdAt = Date(),
            nextRetryAt = Date()
        )
        insertEntry(entry)
    }
    
    suspend fun queueImageUpload(imageId: String, imagePath: String) {
        deleteEntriesForTarget(imageId, SyncOperation.UPLOAD_IMAGE)
        
        val entry = SyncQueueEntry(
            id = "${SyncOperation.UPLOAD_IMAGE}_${imageId}_${System.currentTimeMillis()}",
            operation = SyncOperation.UPLOAD_IMAGE,
            targetId = imageId,
            targetType = SyncTargetType.IMAGE,
            jsonData = imagePath, // Store the local file path in jsonData
            createdAt = Date(),
            nextRetryAt = Date()
        )
        insertEntry(entry)
    }
    
    suspend fun markEntryFailed(entry: SyncQueueEntry, errorMessage: String) {
        val updatedEntry = entry.copy(
            retryCount = entry.retryCount + 1,
            errorMessage = errorMessage,
            lastAttemptAt = Date(),
            nextRetryAt = if (entry.retryCount + 1 < entry.maxRetries) {
                // Exponential backoff: 1min, 5min, 15min, 30min, 60min
                val delayMinutes = when (entry.retryCount + 1) {
                    1 -> 1
                    2 -> 5  
                    3 -> 15
                    4 -> 30
                    else -> 60
                }
                Date(System.currentTimeMillis() + delayMinutes * 60 * 1000)
            } else {
                null // Max retries reached, no more retries
            }
        )
        updateEntry(updatedEntry)
    }
    
    suspend fun markEntrySuccessful(entry: SyncQueueEntry) {
        deleteEntry(entry)
    }
}