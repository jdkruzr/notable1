package com.ethran.notable.db

import androidx.room.*
import java.util.Date

@Entity(tableName = "SyncQueueEntry")
data class SyncQueueEntry(
    @PrimaryKey val id: String,
    val operation: SyncOperation,
    val targetId: String, // page ID, notebook ID, or other identifier
    val targetType: SyncTargetType,
    val retryCount: Int = 0,
    val maxRetries: Int = 5,
    val jsonData: String? = null, // serialized data for upload operations
    val errorMessage: String? = null,
    val createdAt: Date,
    val lastAttemptAt: Date? = null,
    val nextRetryAt: Date? = null
)

enum class SyncOperation {
    UPLOAD_PAGE,
    UPLOAD_NOTEBOOK,
    UPLOAD_STANDALONE_PAGE,
    UPLOAD_IMAGE,
    DOWNLOAD_PAGE,
    DOWNLOAD_IMAGE
}

enum class SyncTargetType {
    PAGE,
    NOTEBOOK,
    STANDALONE_PAGE,
    IMAGE
}

@Dao
interface SyncQueueDao {
    @Query("SELECT * FROM SyncQueueEntry ORDER BY createdAt ASC")
    suspend fun getAllEntries(): List<SyncQueueEntry>
    
    @Query("SELECT * FROM SyncQueueEntry WHERE retryCount < maxRetries ORDER BY nextRetryAt ASC")
    suspend fun getPendingEntries(): List<SyncQueueEntry>
    
    @Query("SELECT * FROM SyncQueueEntry WHERE targetId = :targetId AND operation = :operation")
    suspend fun getEntriesForTarget(targetId: String, operation: SyncOperation): List<SyncQueueEntry>
    
    @Query("SELECT COUNT(*) FROM SyncQueueEntry WHERE retryCount < maxRetries")
    suspend fun getPendingCount(): Int
    
    @Query("SELECT COUNT(*) FROM SyncQueueEntry WHERE retryCount >= maxRetries")
    suspend fun getFailedCount(): Int
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEntry(entry: SyncQueueEntry)
    
    @Update
    suspend fun updateEntry(entry: SyncQueueEntry)
    
    @Delete
    suspend fun deleteEntry(entry: SyncQueueEntry)
    
    @Query("DELETE FROM SyncQueueEntry WHERE id = :id")
    suspend fun deleteById(id: String)
    
    @Query("DELETE FROM SyncQueueEntry WHERE targetId = :targetId AND operation = :operation")
    suspend fun deleteEntriesForTarget(targetId: String, operation: SyncOperation)
    
    @Query("DELETE FROM SyncQueueEntry WHERE retryCount >= maxRetries")
    suspend fun deleteFailedEntries()
    
    @Query("DELETE FROM SyncQueueEntry")
    suspend fun deleteAllEntries()
    
    @Query("SELECT * FROM SyncQueueEntry WHERE nextRetryAt <= :currentTime AND retryCount < maxRetries ORDER BY nextRetryAt ASC LIMIT :limit")
    suspend fun getReadyForRetry(currentTime: Long, limit: Int = 10): List<SyncQueueEntry>
}