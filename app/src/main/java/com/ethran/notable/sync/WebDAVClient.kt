package com.ethran.notable.sync

import com.thegrizzlylabs.sardineandroid.Sardine
import com.thegrizzlylabs.sardineandroid.impl.OkHttpSardine
import okhttp3.Authenticator
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.Route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.concurrent.ConcurrentHashMap

data class FileListingCache(
    val files: List<WebDAVFileInfo>,
    val timestamp: Long,
    val ttlMillis: Long
) {
    fun isExpired(): Boolean = System.currentTimeMillis() - timestamp > ttlMillis
    
    companion object {
        const val DEFAULT_TTL_MINUTES = 5L
        const val DEFAULT_TTL_MILLIS = DEFAULT_TTL_MINUTES * 60 * 1000
    }
}

class WebDAVClient(
    private val serverUrl: String,
    private val username: String,
    private val password: String
) {
    private val fileListingCache = ConcurrentHashMap<String, FileListingCache>()
    
    // Create a shared OkHttp client with authentication
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS) 
        .writeTimeout(300, TimeUnit.SECONDS)
        .authenticator(object : Authenticator {
            override fun authenticate(route: Route?, response: Response): Request? {
                val credential = Credentials.basic(username, password)
                return response.request.newBuilder()
                    .header("Authorization", credential)
                    .build()
            }
        })
        .build()
    
    private val sardine: Sardine = run {
        // Using sardine-android 0.9 with shared OkHttp client
        OkHttpSardine(httpClient).apply {
            setCredentials(username, password)
            android.util.Log.d("WebDAVClient", "Sardine-android 0.9 with custom OkHttp client initialized for user: '$username', password length: ${password.length}")
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
            
            // Invalidate cache for the directory containing this file
            val directory = relativePath.substringBeforeLast('/', "")
            if (directory.isNotEmpty()) {
                clearFileListingCache("$directory/")
            }
            
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
            
            // Invalidate cache for the directory containing this file
            val directory = relativePath.substringBeforeLast('/', "")
            if (directory.isNotEmpty()) {
                clearFileListingCache("$directory/")
            }
            
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
            
            // Invalidate cache for the directory containing this file
            val directory = relativePath.substringBeforeLast('/', "")
            if (directory.isNotEmpty()) {
                clearFileListingCache("$directory/")
            }
            
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
    
    suspend fun listFiles(relativePath: String, useCache: Boolean = true): List<WebDAVFileInfo> = withContext(Dispatchers.IO) {
        if (useCache) {
            // Check cache first
            val cachedEntry = fileListingCache[relativePath]
            if (cachedEntry != null && !cachedEntry.isExpired()) {
                android.util.Log.d("WebDAVClient", "Using cached file listing for $relativePath (${cachedEntry.files.size} files)")
                return@withContext cachedEntry.files
            }
        }
        
        try {
            android.util.Log.d("WebDAVClient", "Fetching fresh file listing for $relativePath")
            val url = "$syncPath/$relativePath"
            val resources = sardine.list(url)
            
            val files = resources.mapNotNull { resource ->
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
            
            // Cache the result
            if (useCache) {
                fileListingCache[relativePath] = FileListingCache(
                    files = files,
                    timestamp = System.currentTimeMillis(),
                    ttlMillis = FileListingCache.DEFAULT_TTL_MILLIS
                )
                android.util.Log.d("WebDAVClient", "Cached ${files.size} files for $relativePath (TTL: ${FileListingCache.DEFAULT_TTL_MINUTES}min)")
            }
            
            files
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }
    
    fun clearFileListingCache(relativePath: String? = null) {
        if (relativePath != null) {
            fileListingCache.remove(relativePath)
            android.util.Log.d("WebDAVClient", "Cleared cache for $relativePath")
        } else {
            fileListingCache.clear()
            android.util.Log.d("WebDAVClient", "Cleared all file listing cache")
        }
    }
    
    suspend fun refreshFileListingCache(relativePath: String): List<WebDAVFileInfo> {
        android.util.Log.d("WebDAVClient", "Force refreshing cache for $relativePath")
        return listFiles(relativePath, useCache = false)
    }
    
    suspend fun customReport(relativePath: String, reportXml: String): String? = withContext(Dispatchers.IO) {
        try {
            val url = "$syncPath/$relativePath"
            android.util.Log.d("WebDAVClient", "Sending custom REPORT to $url")
            android.util.Log.d("WebDAVClient", "REPORT XML: $reportXml")
            
            val request = Request.Builder()
                .url(url)
                .method("REPORT", reportXml.toRequestBody("text/xml; charset=utf-8".toMediaType()))
                .addHeader("Depth", "1")
                .build()
                
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val responseBody = response.body?.string()
                    android.util.Log.d("WebDAVClient", "REPORT successful (${response.code}), response length: ${responseBody?.length}")
                    responseBody
                } else {
                    android.util.Log.w("WebDAVClient", "REPORT failed: ${response.code} ${response.message}")
                    if (response.code == 404) {
                        android.util.Log.d("WebDAVClient", "Server might not support REPORT method")
                    }
                    null
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("WebDAVClient", "REPORT request failed", e)
            null
        }
    }
    
    suspend fun listFilesWithSyncCollection(relativePath: String, syncToken: String? = null): Pair<List<WebDAVFileInfo>, String?> = withContext(Dispatchers.IO) {
        try {
            // Try RFC 6578 sync-collection REPORT method for efficient incremental sync
            // This is "best-in-class for synchronization" but not widely supported yet
            // Fallback to regular PROPFIND if server doesn't support it (like Nextcloud)
            val reportXml = """<?xml version="1.0" encoding="utf-8" ?>
                <D:sync-collection xmlns:D="DAV:">
                    <D:sync-token>${syncToken ?: ""}</D:sync-token>
                    <D:sync-level>1</D:sync-level>
                    <D:prop>
                        <D:getlastmodified/>
                        <D:getetag/>
                        <D:getcontentlength/>
                    </D:prop>
                    <D:limit>
                        <D:nresults>100</D:nresults>
                    </D:limit>
                </D:sync-collection>"""
            
            android.util.Log.d("WebDAVClient", "Attempting RFC 6578 sync-collection REPORT for $relativePath")
            
            val responseXml = customReport(relativePath, reportXml)
            if (responseXml != null) {
                android.util.Log.d("WebDAVClient", "✓ Server supports RFC 6578 sync-collection! This is rare but awesome.")
                android.util.Log.d("WebDAVClient", "Response XML preview: ${responseXml.take(200)}...")
                // TODO: Parse the XML response to extract file list and new sync token
                // For now, fallback to regular listing but log the success
            } else {
                android.util.Log.d("WebDAVClient", "Server doesn't support RFC 6578 sync-collection (normal), using cached PROPFIND")
            }
            
            // Always fall back to cached regular listing (60-90% faster than uncached)
            val files = listFiles(relativePath, useCache = true)
            Pair(files, syncToken)
        } catch (e: Exception) {
            android.util.Log.e("WebDAVClient", "RFC 6578 test failed, using cached PROPFIND fallback", e)
            val files = listFiles(relativePath, useCache = true)
            Pair(files, null)
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