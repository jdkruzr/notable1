package com.ethran.notable.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import java.util.Date
import java.util.UUID

@Entity
data class DeletionLog(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val deletedItemId: String, // The ID of the deleted notebook/page
    val deletedItemType: DeletionType, // What type of item was deleted
    val deletedAt: Date = Date(), // When it was deleted locally
    val syncedAt: Date? = null, // When the deletion was synced to server (null = not synced yet)
    val deviceId: String // Which device performed the deletion
)

enum class DeletionType {
    NOTEBOOK,
    PAGE,
    FOLDER
}

@Dao
interface DeletionLogDao {
    @Insert
    fun logDeletion(deletionLog: DeletionLog)
    
    @Query("SELECT * FROM DeletionLog WHERE syncedAt IS NULL")
    fun getUnsyncedDeletions(): List<DeletionLog>
    
    @Query("UPDATE DeletionLog SET syncedAt = :syncedAt WHERE id = :logId")
    fun markAsSynced(logId: String, syncedAt: Date)
    
    @Query("DELETE FROM DeletionLog WHERE syncedAt IS NOT NULL AND deletedAt < :cutoffDate")
    fun cleanupOldSyncedDeletions(cutoffDate: Date)
    
    @Query("SELECT * FROM DeletionLog WHERE deletedItemId = :itemId AND deletedItemType = :itemType")
    fun getDeletionForItem(itemId: String, itemType: DeletionType): DeletionLog?
    
    @Query("DELETE FROM DeletionLog WHERE deletedItemId = :itemId AND deletedItemType = :itemType")
    fun removeDeletionLog(itemId: String, itemType: DeletionType)
}

class DeletionLogRepository(context: Context) {
    private val db = AppDatabase.getDatabase(context).deletionLogDao()
    
    fun logNotebookDeletion(notebookId: String, deviceId: String) {
        val deletionLog = DeletionLog(
            deletedItemId = notebookId,
            deletedItemType = DeletionType.NOTEBOOK,
            deviceId = deviceId
        )
        db.logDeletion(deletionLog)
    }
    
    fun logPageDeletion(pageId: String, deviceId: String) {
        val deletionLog = DeletionLog(
            deletedItemId = pageId,
            deletedItemType = DeletionType.PAGE,
            deviceId = deviceId
        )
        db.logDeletion(deletionLog)
    }
    
    fun logFolderDeletion(folderId: String, deviceId: String) {
        val deletionLog = DeletionLog(
            deletedItemId = folderId,
            deletedItemType = DeletionType.FOLDER,
            deviceId = deviceId
        )
        db.logDeletion(deletionLog)
    }
    
    fun getUnsyncedDeletions(): List<DeletionLog> {
        return db.getUnsyncedDeletions()
    }
    
    fun markDeletionAsSynced(deletionLog: DeletionLog) {
        db.markAsSynced(deletionLog.id, Date())
    }
    
    fun cleanupOldDeletions() {
        // Remove deletion logs older than 30 days that have been synced
        val cutoffDate = Date(System.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000))
        db.cleanupOldSyncedDeletions(cutoffDate)
    }
    
    fun getDeletionForItem(itemId: String, itemType: DeletionType): DeletionLog? {
        return db.getDeletionForItem(itemId, itemType)
    }
    
    fun removeDeletionLog(itemId: String, itemType: DeletionType) {
        db.removeDeletionLog(itemId, itemType)
    }
}