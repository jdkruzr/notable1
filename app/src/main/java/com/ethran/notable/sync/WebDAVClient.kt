package com.ethran.notable.sync

import com.thegrizzlylabs.sardineandroid.Sardine
import com.thegrizzlylabs.sardineandroid.impl.OkHttpSardine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

class WebDAVClient(
    private val serverUrl: String,
    private val username: String,
    private val password: String
) {
    private val sardine: Sardine = run {
        // Configure custom OkHttpClient with longer timeouts for large uploads
        val customClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)      // 30s to establish connection
            .readTimeout(300, TimeUnit.SECONDS)        // 5 minutes for reading response
            .writeTimeout(300, TimeUnit.SECONDS)       // 5 minutes for uploading data
            .build()
        
        // Use constructor that accepts custom OkHttpClient (available in sardineandroid 0.8+)
        OkHttpSardine(customClient).apply {
            setCredentials(username, password)
            android.util.Log.d("WebDAVClient", "Credentials set for user: '$username', password length: ${password.length}")
            android.util.Log.d("WebDAVClient", "Custom timeouts configured successfully via constructor")
        }
    }
    
    private val baseUrl = serverUrl.trimEnd('/')
    private val syncPath = "$baseUrl/notable-sync"
    
    // Compression utilities
    private fun gzipCompress(data: String): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { gzipOut ->
            gzipOut.write(data.toByteArray(Charsets.UTF_8))
        }
        return bos.toByteArray()
    }
    
    private fun gzipDecompress(compressedData: ByteArray): String {
        return GZIPInputStream(ByteArrayInputStream(compressedData)).use { gzipIn ->
            gzipIn.readBytes().toString(Charsets.UTF_8)
        }
    }
    
    private fun isGzipped(data: ByteArray): Boolean {
        // Check for gzip magic number: 0x1f, 0x8b
        return data.size >= 2 && 
               data[0] == 0x1f.toByte() && 
               data[1] == 0x8b.toByte()
    }
    
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        try {
            // Create directory structure if it doesn't exist
            createDirectoryIfNotExists("$syncPath/")
            createDirectoryIfNotExists("$syncPath/devices/")
            createDirectoryIfNotExists("$syncPath/notebooks/")
            createDirectoryIfNotExists("$syncPath/pages/")
            createDirectoryIfNotExists("$syncPath/images/")
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
    
    suspend fun uploadFile(relativePath: String, content: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = "$syncPath/$relativePath"
            
            // Compress the JSON content before uploading
            val compressedData = gzipCompress(content)
            val originalSize = content.toByteArray(Charsets.UTF_8).size
            val compressedSize = compressedData.size
            val compressionRatio = (1.0 - compressedSize.toDouble() / originalSize) * 100
            
            android.util.Log.d("WebDAVClient", "Uploading $relativePath: ${originalSize} bytes → ${compressedSize} bytes (${String.format("%.1f", compressionRatio)}% compression)")
            
            sardine.put(url, compressedData)
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
            
            // Read raw bytes to check if compressed
            val data = inputStream.readBytes()
            
            if (isGzipped(data)) {
                // Decompress gzipped data
                try {
                    val decompressed = gzipDecompress(data)
                    android.util.Log.d("WebDAVClient", "Downloaded $relativePath: ${data.size} bytes → ${decompressed.toByteArray().size} bytes (decompressed)")
                    decompressed
                } catch (e: Exception) {
                    android.util.Log.e("WebDAVClient", "Failed to decompress $relativePath: ${e.message}")
                    throw e
                }
            } else {
                // Legacy uncompressed data
                val content = String(data, Charsets.UTF_8)
                android.util.Log.d("WebDAVClient", "Downloaded $relativePath: ${data.size} bytes (uncompressed legacy format)")
                android.util.Log.d("WebDAVClient", "DEBUG: First 100 chars of uncompressed content: ${content.take(100)}")
                content
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
    
    // Binary file operations for images
    suspend fun uploadBinaryFile(relativePath: String, data: ByteArray): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = "$syncPath/$relativePath"
            android.util.Log.d("WebDAVClient", "Uploading binary file $relativePath: ${data.size} bytes")
            sardine.put(url, data)
            true
        } catch (e: Exception) {
            android.util.Log.e("WebDAVClient", "Failed to upload binary file $relativePath", e)
            e.printStackTrace()
            false
        }
    }
    
    suspend fun downloadBinaryFile(relativePath: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val url = "$syncPath/$relativePath"
            val inputStream = sardine.get(url)
            val data = inputStream.readBytes()
            android.util.Log.d("WebDAVClient", "Downloaded binary file $relativePath: ${data.size} bytes")
            data
        } catch (e: Exception) {
            android.util.Log.e("WebDAVClient", "Failed to download binary file $relativePath", e)
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
    
    // Image sync helper methods
    suspend fun uploadImage(imageId: String, filename: String, imageData: ByteArray): Boolean {
        val remotePath = "images/${imageId}_${filename}"
        return uploadBinaryFile(remotePath, imageData)
    }
    
    suspend fun downloadImage(imageId: String, filename: String): ByteArray? {
        val remotePath = "images/${imageId}_${filename}"
        return downloadBinaryFile(remotePath)
    }
    
    suspend fun imageExists(imageId: String, filename: String): Boolean {
        val remotePath = "images/${imageId}_${filename}"
        return fileExists(remotePath)
    }
    
    suspend fun listImages(): List<WebDAVFileInfo> {
        return listFiles("images/")
    }
    
    suspend fun testConnection(): Boolean = withContext(Dispatchers.IO) {
        try {
            android.util.Log.d("WebDAVClient", "Testing connection to: $baseUrl")
            android.util.Log.d("WebDAVClient", "Also testing sync path: $syncPath")
            
            // Test both base URL and sync path
            val baseExists = sardine.exists(baseUrl)
            android.util.Log.d("WebDAVClient", "Base URL exists: $baseExists")
            
            val syncPathExists = sardine.exists(syncPath)
            android.util.Log.d("WebDAVClient", "Sync path exists: $syncPathExists")
            
            val result = baseExists && syncPathExists
            android.util.Log.d("WebDAVClient", "Overall connection test result: $result")
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