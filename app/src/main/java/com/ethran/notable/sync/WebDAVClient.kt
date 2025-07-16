package com.ethran.notable.sync

import com.thegrizzlylabs.sardineandroid.Sardine
import com.thegrizzlylabs.sardineandroid.impl.OkHttpSardine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class WebDAVClient(
    private val serverUrl: String,
    private val username: String,
    private val password: String
) {
    private val sardine: Sardine = OkHttpSardine().apply {
        setCredentials(username, password)
    }
    
    private val baseUrl = serverUrl.trimEnd('/')
    private val syncPath = "$baseUrl/notable-sync"
    
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        try {
            // Create directory structure if it doesn't exist
            createDirectoryIfNotExists("$syncPath/")
            createDirectoryIfNotExists("$syncPath/devices/")
            createDirectoryIfNotExists("$syncPath/notebooks/")
            createDirectoryIfNotExists("$syncPath/pages/")
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
    
    suspend fun uploadFile(relativePath: String, content: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = "$syncPath/$relativePath"
            val data = content.toByteArray(Charsets.UTF_8)
            sardine.put(url, data)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
    
    suspend fun downloadFile(relativePath: String): String? = withContext(Dispatchers.IO) {
        try {
            val url = "$syncPath/$relativePath"
            val inputStream = sardine.get(url)
            inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
    
    suspend fun fileExists(relativePath: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = "$syncPath/$relativePath"
            sardine.exists(url)
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
    
    suspend fun deleteFile(relativePath: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = "$syncPath/$relativePath"
            sardine.delete(url)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
    
    suspend fun listFiles(relativePath: String): List<WebDAVFileInfo> = withContext(Dispatchers.IO) {
        try {
            val url = "$syncPath/$relativePath"
            val resources = sardine.list(url)
            
            resources.mapNotNull { resource ->
                if (!resource.isDirectory) {
                    WebDAVFileInfo(
                        name = resource.name,
                        path = resource.path,
                        size = resource.contentLength,
                        lastModified = resource.modified,
                        etag = resource.etag
                    )
                } else null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }
    
    suspend fun getFileModificationTime(relativePath: String): Long? = withContext(Dispatchers.IO) {
        try {
            val url = "$syncPath/$relativePath"
            val resources = sardine.list(url)
            resources.firstOrNull()?.modified?.time
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
    
    private suspend fun createDirectoryIfNotExists(url: String) = withContext(Dispatchers.IO) {
        try {
            if (!sardine.exists(url)) {
                sardine.createDirectory(url)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    // Sync-specific helper methods
    suspend fun uploadPageSync(notebookId: String, pageId: String, content: String): Boolean {
        val timestamp = System.currentTimeMillis()
        val filename = "${notebookId}_${pageId}_${timestamp}.json"
        return uploadFile("pages/$filename", content)
    }
    
    suspend fun uploadNotebookMetadata(notebookId: String, content: String): Boolean {
        val timestamp = System.currentTimeMillis()
        val filename = "${notebookId}_${timestamp}.json"
        return uploadFile("notebooks/$filename", content)
    }
    
    suspend fun uploadDeviceInfo(deviceId: String, content: String): Boolean {
        val filename = "$deviceId.json"
        return uploadFile("devices/$filename", content)
    }
    
    suspend fun downloadDeviceInfo(deviceId: String): String? {
        val filename = "$deviceId.json"
        return downloadFile("devices/$filename")
    }
    
    suspend fun listPageSyncs(notebookId: String? = null): List<WebDAVFileInfo> {
        val files = listFiles("pages/")
        return if (notebookId != null) {
            files.filter { it.name.startsWith("${notebookId}_") }
        } else {
            files
        }
    }
    
    suspend fun listNotebookMetadata(notebookId: String? = null): List<WebDAVFileInfo> {
        val files = listFiles("notebooks/")
        return if (notebookId != null) {
            files.filter { it.name.startsWith("${notebookId}_") }
        } else {
            files
        }
    }
    
    suspend fun listDevices(): List<WebDAVFileInfo> {
        return listFiles("devices/")
    }
    
    suspend fun testConnection(): Boolean = withContext(Dispatchers.IO) {
        try {
            android.util.Log.d("WebDAVClient", "Testing connection to: $baseUrl")
            val result = sardine.exists(baseUrl)
            android.util.Log.d("WebDAVClient", "Connection test result: $result")
            result
        } catch (e: Exception) {
            android.util.Log.e("WebDAVClient", "Connection test failed: ${e.message}", e)
            e.printStackTrace()
            false
        }
    }
}

data class WebDAVFileInfo(
    val name: String,
    val path: String,
    val size: Long,
    val lastModified: java.util.Date?,
    val etag: String?
) {
    fun parseFilename(): SyncFileInfo? {
        return try {
            when {
                name.endsWith(".json") && name.count { it == '_' } >= 2 -> {
                    // Page sync file: notebookId_pageId_timestamp.json
                    val parts = name.dropLast(5).split("_") // Remove .json extension
                    if (parts.size >= 3) {
                        SyncFileInfo(
                            type = SyncFileType.PAGE,
                            notebookId = parts[0],
                            pageId = parts[1],
                            timestamp = parts[2].toLongOrNull() ?: 0L
                        )
                    } else null
                }
                name.endsWith(".json") && name.count { it == '_' } == 1 -> {
                    // Notebook metadata file: notebookId_timestamp.json
                    val parts = name.dropLast(5).split("_")
                    if (parts.size == 2) {
                        SyncFileInfo(
                            type = SyncFileType.NOTEBOOK,
                            notebookId = parts[0],
                            timestamp = parts[1].toLongOrNull() ?: 0L
                        )
                    } else null
                }
                name.endsWith(".json") && !name.contains("_") -> {
                    // Device info file: deviceId.json
                    SyncFileInfo(
                        type = SyncFileType.DEVICE,
                        deviceId = name.dropLast(5)
                    )
                }
                else -> null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}

data class SyncFileInfo(
    val type: SyncFileType,
    val notebookId: String? = null,
    val pageId: String? = null,
    val deviceId: String? = null,
    val timestamp: Long = 0L
)

enum class SyncFileType {
    PAGE, NOTEBOOK, DEVICE
}