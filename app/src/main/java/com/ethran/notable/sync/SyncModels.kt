package com.ethran.notable.sync

import kotlinx.serialization.Serializable
import java.util.Date

@Serializable
data class SyncFormat(
    val version: String = "1.0",
    val timestamp: String, // ISO 8601 format
    val deviceId: String
)

@Serializable
data class SyncNotebook(
    val id: String,
    val title: String,
    val parentFolderId: String? = null,
    val defaultNativeTemplate: String = "blank",
    val createdAt: String, // ISO 8601 format
    val updatedAt: String  // ISO 8601 format
)

@Serializable
data class SyncPage(
    val id: String,
    val notebookId: String,
    val pageOrder: Int,
    val scroll: Int = 0,
    val background: String = "blank",
    val backgroundType: String = "native",
    val createdAt: String, // ISO 8601 format
    val updatedAt: String  // ISO 8601 format
)

@Serializable
data class SyncBoundingBox(
    val top: Float,
    val bottom: Float,
    val left: Float,
    val right: Float
)

@Serializable
data class SyncStrokePoint(
    val x: Float,
    val y: Float,
    val pressure: Float,
    val size: Float,
    val tiltX: Int,
    val tiltY: Int,
    val timestamp: Long
)

@Serializable
data class SyncStroke(
    val id: String,
    val size: Float,
    val pen: String, // Pen enum as string
    val color: Int,
    val boundingBox: SyncBoundingBox,
    val points: List<SyncStrokePoint>,
    val createdAt: String, // ISO 8601 format
    val updatedAt: String  // ISO 8601 format
)

@Serializable
data class SyncPosition(
    val x: Int,
    val y: Int
)

@Serializable
data class SyncDimensions(
    val width: Int,
    val height: Int
)

@Serializable
data class SyncImage(
    val id: String,
    val position: SyncPosition,
    val dimensions: SyncDimensions,
    val uri: String? = null,
    val data: String? = null, // Base64 encoded
    val mimeType: String,
    val createdAt: String, // ISO 8601 format
    val updatedAt: String  // ISO 8601 format
)

@Serializable
data class SyncBackgroundImage(
    val data: String, // Base64 encoded
    val mimeType: String
)

@Serializable
data class SyncAssets(
    val backgroundImage: SyncBackgroundImage? = null
)

@Serializable
data class SyncFolderPathItem(
    val id: String,
    val title: String
)

@Serializable
data class SyncPageData(
    val syncFormat: SyncFormat,
    val notebook: SyncNotebook,
    val page: SyncPage,
    val strokes: List<SyncStroke>,
    val images: List<SyncImage>,
    val assets: SyncAssets = SyncAssets(),
    val folderPath: List<SyncFolderPathItem> = emptyList()
)

@Serializable
data class SyncDeviceInfo(
    val deviceId: String,
    val deviceName: String,
    val lastSyncTime: String, // ISO 8601 format
    val capabilities: List<String> = listOf("sync", "render"),
    val version: String = "1.0"
)

@Serializable
data class SyncNotebookMetadata(
    val syncFormat: SyncFormat,
    val notebook: SyncNotebook,
    val pageIds: List<String>, // Ordered list of page IDs
    val folderPath: List<SyncFolderPathItem> = emptyList()
)