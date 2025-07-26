package com.ethran.notable.db

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.ethran.notable.utils.Pen
import java.util.Date
import java.util.UUID

@kotlinx.serialization.Serializable
data class StrokePoint(
    val x: Float,
    var y: Float,
    val pressure: Float,
    val size: Float, //TODO: remove? It seams the same as Stroke size
    val tiltX: Int,
    val tiltY: Int,
    val timestamp: Long,
)

// Lightweight stroke data without points for fast loading
data class StrokeMetadata(
    val id: String,
    val size: Float,
    val pen: Pen,
    val color: Int,
    val top: Float,
    val bottom: Float,
    val left: Float,
    val right: Float,
    val pageId: String,
    val createdAt: Date,
    val updatedAt: Date
)

@Entity(
    foreignKeys = [ForeignKey(
        entity = Page::class,
        parentColumns = arrayOf("id"),
        childColumns = arrayOf("pageId"),
        onDelete = ForeignKey.CASCADE
    )]
)
data class Stroke(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val size: Float,
    val pen: Pen,
    @ColumnInfo(defaultValue = "0xFF000000")
    val color: Int = 0xFF000000.toInt(),

    var top: Float,
    var bottom: Float,
    var left: Float,
    var right: Float,

    val points: List<StrokePoint>,

    @ColumnInfo(index = true)
    val pageId: String,

    val createdAt: Date = Date(),
    val updatedAt: Date = Date()
)

// DAO
@Dao
interface StrokeDao {
    @Insert
    fun create(stroke: Stroke): Long

    @Insert
    fun create(strokes: List<Stroke>)

    @Update
    fun update(stroke: Stroke)

    @Query("DELETE FROM stroke WHERE id IN (:ids)")
    fun deleteAll(ids: List<String>)

    @Query("SELECT * FROM stroke WHERE pageId = :pageId")
    fun getAllByPageId(pageId: String): List<Stroke>
    
    @Query("SELECT * FROM stroke WHERE pageId = :pageId ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getByPageIdLimited(pageId: String, limit: Int): List<Stroke>
    
    // Lightweight query: Get stroke metadata without points (much faster!)
    @Query("SELECT id, size, pen, color, top, bottom, left, right, pageId, createdAt, updatedAt FROM stroke WHERE pageId = :pageId ORDER BY createdAt ASC")
    suspend fun getStrokeMetadataByPageId(pageId: String): List<StrokeMetadata>
    
    // Load full stroke when we need the points (Room will handle the points list properly)
    @Query("SELECT * FROM stroke WHERE id = :strokeId")
    suspend fun getStrokeById(strokeId: String): Stroke?

    @Query("DELETE FROM stroke WHERE pageId = :pageId")
    fun deleteByPageId(pageId: String)

    @Transaction
    @Query("SELECT * FROM stroke WHERE id =:strokeId")
    fun getById(strokeId: String): Stroke

}

class StrokeRepository(context: Context) {
    var db = AppDatabase.getDatabase(context).strokeDao()

    fun create(stroke: Stroke): Long {
        return db.create(stroke)
    }

    fun create(strokes: List<Stroke>) {
        return db.create(strokes)
    }

    fun update(stroke: Stroke) {
        return db.update(stroke)
    }

    fun deleteAll(ids: List<String>) {
        return db.deleteAll(ids)
    }

    fun getStrokeWithPointsById(strokeId: String): Stroke {
        return db.getById(strokeId)
    }

    fun getAllByPageId(pageId: String): List<Stroke> {
        return db.getAllByPageId(pageId)
    }
    
    suspend fun getByPageIdLimited(pageId: String, limit: Int): List<Stroke> {
        return db.getByPageIdLimited(pageId, limit)
    }
    
    // Lightweight metadata queries for performance
    suspend fun getStrokeMetadataByPageId(pageId: String): List<StrokeMetadata> {
        return db.getStrokeMetadataByPageId(pageId)
    }
    
    suspend fun getStrokeById(strokeId: String): Stroke? {
        return db.getStrokeById(strokeId)
    }

    fun deleteByPageId(pageId: String) {
        db.deleteByPageId(pageId)
    }
}