package com.ethran.notable.sync

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import com.ethran.notable.db.*
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.*

class SyncSerializer(private val context: Context) {
    
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }
    
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    
    fun serializePage(
        notebook: Notebook,
        page: Page,
        strokes: List<Stroke>,
        images: List<Image>,
        folderPath: List<Folder>,
        deviceId: String
    ): String {
        val syncFormat = SyncFormat(
            timestamp = dateFormat.format(Date()),
            deviceId = deviceId
        )
        
        val syncNotebook = SyncNotebook(
            id = notebook.id,
            title = notebook.title,
            parentFolderId = notebook.parentFolderId,
            defaultNativeTemplate = notebook.defaultNativeTemplate,
            createdAt = dateFormat.format(notebook.createdAt),
            updatedAt = dateFormat.format(notebook.updatedAt)
        )
        
        val pageOrder = notebook.pageIds.indexOf(page.id)
        val syncPage = SyncPage(
            id = page.id,
            notebookId = page.notebookId ?: notebook.id,
            pageOrder = pageOrder,
            scroll = page.scroll,
            background = page.background,
            backgroundType = page.backgroundType,
            createdAt = dateFormat.format(page.createdAt),
            updatedAt = dateFormat.format(page.updatedAt)
        )
        
        val syncStrokes = strokes.map { stroke ->
            SyncStroke(
                id = stroke.id,
                size = stroke.size,
                pen = stroke.pen.name,
                color = stroke.color,
                boundingBox = SyncBoundingBox(
                    top = stroke.top,
                    bottom = stroke.bottom,
                    left = stroke.left,
                    right = stroke.right
                ),
                points = stroke.points.map { point ->
                    SyncStrokePoint(
                        x = point.x,
                        y = point.y,
                        pressure = point.pressure,
                        size = point.size,
                        tiltX = point.tiltX,
                        tiltY = point.tiltY,
                        timestamp = point.timestamp
                    )
                },
                createdAt = dateFormat.format(stroke.createdAt),
                updatedAt = dateFormat.format(stroke.updatedAt)
            )
        }
        
        val syncImages = images.map { image ->
            val imageData = if (image.uri != null) {
                encodeImageToBase64(image.uri)
            } else null
            
            SyncImage(
                id = image.id,
                position = SyncPosition(image.x, image.y),
                dimensions = SyncDimensions(image.width, image.height),
                uri = image.uri,
                data = imageData,
                mimeType = getMimeType(image.uri),
                createdAt = dateFormat.format(image.createdAt),
                updatedAt = dateFormat.format(image.updatedAt)
            )
        }
        
        val syncFolderPath = folderPath.map { folder ->
            SyncFolderPathItem(
                id = folder.id,
                title = folder.title
            )
        }
        
        val syncPageData = SyncPageData(
            syncFormat = syncFormat,
            notebook = syncNotebook,
            page = syncPage,
            strokes = syncStrokes,
            images = syncImages,
            folderPath = syncFolderPath
        )
        
        return json.encodeToString(SyncPageData.serializer(), syncPageData)
    }
    
    fun deserializePage(jsonData: String): SyncPageData {
        return json.decodeFromString(SyncPageData.serializer(), jsonData)
    }
    
    fun serializeNotebookMetadata(
        notebook: Notebook,
        folderPath: List<Folder>,
        deviceId: String
    ): String {
        val syncFormat = SyncFormat(
            timestamp = dateFormat.format(Date()),
            deviceId = deviceId
        )
        
        val syncNotebook = SyncNotebook(
            id = notebook.id,
            title = notebook.title,
            parentFolderId = notebook.parentFolderId,
            defaultNativeTemplate = notebook.defaultNativeTemplate,
            createdAt = dateFormat.format(notebook.createdAt),
            updatedAt = dateFormat.format(notebook.updatedAt)
        )
        
        val syncFolderPath = folderPath.map { folder ->
            SyncFolderPathItem(
                id = folder.id,
                title = folder.title
            )
        }
        
        val metadata = SyncNotebookMetadata(
            syncFormat = syncFormat,
            notebook = syncNotebook,
            pageIds = notebook.pageIds,
            folderPath = syncFolderPath
        )
        
        return json.encodeToString(SyncNotebookMetadata.serializer(), metadata)
    }
    
    fun deserializeNotebookMetadata(jsonData: String): SyncNotebookMetadata {
        return json.decodeFromString(SyncNotebookMetadata.serializer(), jsonData)
    }
    
    fun serializeDeviceInfo(deviceId: String, deviceName: String): String {
        val deviceInfo = SyncDeviceInfo(
            deviceId = deviceId,
            deviceName = deviceName,
            lastSyncTime = dateFormat.format(Date())
        )
        
        return json.encodeToString(SyncDeviceInfo.serializer(), deviceInfo)
    }
    
    fun deserializeDeviceInfo(jsonData: String): SyncDeviceInfo {
        return json.decodeFromString(SyncDeviceInfo.serializer(), jsonData)
    }
    
    private fun encodeImageToBase64(uriString: String?): String? {
        if (uriString == null) return null
        
        return try {
            val uri = Uri.parse(uriString)
            val inputStream = if (uri.scheme == "content") {
                context.contentResolver.openInputStream(uri)
            } else {
                FileInputStream(File(uri.path!!))
            }
            
            inputStream?.use { stream ->
                val bitmap = BitmapFactory.decodeStream(stream)
                val outputStream = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
                Base64.encodeToString(outputStream.toByteArray(), Base64.DEFAULT)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
    
    private fun getMimeType(uriString: String?): String {
        if (uriString == null) return "image/png"
        
        return when {
            uriString.endsWith(".jpg", true) || uriString.endsWith(".jpeg", true) -> "image/jpeg"
            uriString.endsWith(".png", true) -> "image/png"
            uriString.endsWith(".gif", true) -> "image/gif"
            uriString.endsWith(".webp", true) -> "image/webp"
            else -> "image/png"
        }
    }
    
    fun parseDate(dateString: String): Date {
        return dateFormat.parse(dateString) ?: Date()
    }
    
    fun formatDate(date: Date): String {
        return dateFormat.format(date)
    }
}