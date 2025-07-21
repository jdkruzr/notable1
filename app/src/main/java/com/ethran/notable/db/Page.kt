package com.ethran.notable.db

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Update
import com.ethran.notable.TAG
import io.shipbook.shipbooksdk.Log
import java.util.Date
import java.util.UUID

@Entity(
    foreignKeys = [ForeignKey(
        entity = Folder::class,
        parentColumns = arrayOf("id"),
        childColumns = arrayOf("parentFolderId"),
        onDelete = ForeignKey.CASCADE
    ), ForeignKey(
        entity = Notebook::class,
        parentColumns = arrayOf("id"),
        childColumns = arrayOf("notebookId"),
        onDelete = ForeignKey.CASCADE
    )]
)
data class Page(
    @PrimaryKey val id: String = UUID.randomUUID().toString(), val scroll: Int = 0,
    @ColumnInfo(index = true) val notebookId: String? = null,
    @ColumnInfo(defaultValue = "blank") val background: String = "blank",
    @ColumnInfo(defaultValue = "native") val backgroundType: String = "native",
    @ColumnInfo(index = true) val parentFolderId: String? = null,
    val createdAt: Date = Date(), val updatedAt: Date = Date()
)

data class PageWithStrokes(
    @Embedded val page: Page, @Relation(
        parentColumn = "id", entityColumn = "pageId", entity = Stroke::class
    ) val strokes: List<Stroke>
)

data class PageWithImages(
    @Embedded val page: Page, @Relation(
        parentColumn = "id", entityColumn = "pageId", entity = Image::class
    ) val images: List<Image>
)


sealed class BackgroundType(val key: String, val folderName: String) {
    data object Image : BackgroundType("image", "images")
    data object ImageRepeating : BackgroundType("imagerepeating", "images")
    data object CoverImage : BackgroundType("coverImage", "covers")
    data object Native : BackgroundType("native", "")

    data class Pdf(val page: Int) : BackgroundType("pdf$page", "pdfs")


    companion object {
        fun fromKey(key: String): BackgroundType = when {
            key == Image.key -> Image
            key == ImageRepeating.key -> ImageRepeating
            key == CoverImage.key -> CoverImage
            key == Native.key -> Native
            key.startsWith("pdf") && key.removePrefix("pdf").toIntOrNull() != null -> {
                val page = key.removePrefix("pdf").toInt()
                Pdf(page)
            }

            else -> {
                Log.e(TAG, "BackgroundType.fromKey: Unknown key: $key")
                Native
            } // fallback
        }
    }
}

// DAO
@Dao
interface PageDao {
    @Query("SELECT * FROM page WHERE id IN (:ids)")
    fun getMany(ids: List<String>): List<Page>

    @Query("SELECT * FROM page WHERE id = (:pageId)")
    fun getById(pageId: String): Page?

    @Transaction
    @Query("SELECT * FROM page WHERE id =:pageId")
    fun getPageWithStrokesById(pageId: String): PageWithStrokes

    @Transaction
    @Query("SELECT * FROM page WHERE id =:pageId")
    suspend fun getPageWithStrokesByIdSuspend(pageId: String): PageWithStrokes

    @Transaction
    @Query("SELECT * FROM page WHERE id =:pageId")
    fun getPageWithImagesById(pageId: String): PageWithImages

    @Query("UPDATE page SET scroll=:scroll WHERE id =:pageId")
    fun updateScroll(pageId: String, scroll: Int)

    @Query("SELECT * FROM page WHERE notebookId is null AND parentFolderId is :folderId")
    fun getSinglePagesInFolder(folderId: String? = null): LiveData<List<Page>>

    @Query("SELECT * FROM page WHERE updatedAt > :lastSyncTime")
    fun getModifiedAfter(lastSyncTime: Long): List<Page>

    @Insert
    fun create(page: Page): Long

    @Update
    fun update(page: Page)

    @Query("DELETE FROM page WHERE id = :pageId")
    fun delete(pageId: String)
}

class PageRepository(context: Context) {
    var db = AppDatabase.getDatabase(context).pageDao()

    fun create(page: Page): Long {
        return db.create(page)
    }

    fun updateScroll(id: String, scroll: Int) {
        return db.updateScroll(id, scroll)
    }

    fun getById(pageId: String): Page? {
        return db.getById(pageId)
    }

    fun getWithStrokeById(pageId: String): PageWithStrokes {
        return db.getPageWithStrokesById(pageId)
    }

    suspend fun getWithStrokeByIdSuspend(pageId: String): PageWithStrokes {
        return db.getPageWithStrokesByIdSuspend(pageId)
    }

    fun getWithImageById(pageId: String): PageWithImages {
        return db.getPageWithImagesById(pageId)
    }

    fun getSinglePagesInFolder(folderId: String? = null): LiveData<List<Page>> {
        return db.getSinglePagesInFolder(folderId)
    }

    fun update(page: Page) {
        return db.update(page)
    }

    fun delete(pageId: String) {
        return db.delete(pageId)
    }

    fun getPagesModifiedAfter(lastSyncTime: Long): List<Page> {
        return db.getModifiedAfter(lastSyncTime)
    }


}

fun Page.getBackgroundType(): BackgroundType {
    val type = this.backgroundType
    return when {
        type == BackgroundType.Image.key -> BackgroundType.Image
        type == BackgroundType.ImageRepeating.key -> BackgroundType.ImageRepeating
        type == BackgroundType.CoverImage.key -> BackgroundType.CoverImage
        type == BackgroundType.Native.key -> BackgroundType.Native
        type.startsWith("pdf") -> {
            val page = type.removePrefix("pdf").toIntOrNull() ?: 1
            BackgroundType.Pdf(page)
        }

        else -> {
            Log.e(TAG, "Page.getBackgroundType: Unknown background type: $type")
            BackgroundType.Native
        }
    }
}