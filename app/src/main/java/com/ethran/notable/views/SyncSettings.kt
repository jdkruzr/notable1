package com.ethran.notable.views

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.*
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ethran.notable.db.KvProxy
import com.ethran.notable.modals.AppSettings
import com.ethran.notable.modals.GlobalAppSettings
import com.ethran.notable.sync.SyncManager
import com.ethran.notable.components.SelectMenu
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.ethran.notable.utils.SyncLogger
import com.ethran.notable.components.ShowConfirmationDialog

@Composable
fun SyncSettings(
    kv: KvProxy,
    syncManager: SyncManager,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val settings = GlobalAppSettings.current
    val scope = rememberCoroutineScope()
    
    var syncStatus by remember { mutableStateOf("") }
    var isTestingConnection by remember { mutableStateOf(false) }
    var isSyncing by remember { mutableStateOf(false) }
    var showPassword by remember { mutableStateOf(false) }
    var showReplaceServerConfirmation by remember { mutableStateOf(false) }
    
    // Add some sample log entries when the component first loads (for testing)
    LaunchedEffect(Unit) {
        if (SyncLogger.logs.value.isEmpty()) {
            SyncLogger.info("WebDAV settings opened")
            SyncLogger.debug("Sync configuration loaded")
        }
    }
    
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        // Header
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = Icons.Default.CloudSync,
                contentDescription = "Sync Settings",
                tint = MaterialTheme.colors.primary
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "WebDAV Sync",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Enable/Disable Sync
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Enable WebDAV Sync")
            Switch(
                checked = settings.webdavSyncEnabled,
                onCheckedChange = { isChecked ->
                    scope.launch {
                        val updatedSettings = settings.copy(webdavSyncEnabled = isChecked)
                        withContext(Dispatchers.IO) {
                            kv.setAppSettings(updatedSettings)
                        }
                        
                        // Update sync manager configuration
                        syncManager.isEnabled = isChecked
                        if (isChecked) {
                            syncManager.initialize()
                        }
                    }
                }
            )
        }
        
        if (settings.webdavSyncEnabled) {
            Spacer(modifier = Modifier.height(16.dp))
            
            // Server URL
            OutlinedTextField(
                value = settings.webdavServerUrl,
                onValueChange = { url ->
                    scope.launch {
                        val updatedSettings = settings.copy(webdavServerUrl = url)
                        withContext(Dispatchers.IO) {
                            kv.setAppSettings(updatedSettings)
                        }
                        syncManager.serverUrl = url
                        syncManager.initialize()
                    }
                },
                label = { Text("Server URL") },
                placeholder = { Text("https://your-server.com/remote.php/dav/files/username/") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Username
            OutlinedTextField(
                value = settings.webdavUsername,
                onValueChange = { username ->
                    scope.launch {
                        val updatedSettings = settings.copy(webdavUsername = username)
                        withContext(Dispatchers.IO) {
                            kv.setAppSettings(updatedSettings)
                        }
                        syncManager.username = username
                        syncManager.initialize()
                    }
                },
                label = { Text("Username") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Password
            OutlinedTextField(
                value = settings.webdavPassword,
                onValueChange = { password ->
                    scope.launch {
                        val updatedSettings = settings.copy(webdavPassword = password)
                        withContext(Dispatchers.IO) {
                            kv.setAppSettings(updatedSettings)
                        }
                        syncManager.password = password
                        syncManager.initialize()
                    }
                },
                label = { Text("Password") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    TextButton(onClick = { showPassword = !showPassword }) {
                        Text(if (showPassword) "Hide" else "Show")
                    }
                }
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Test Connection Button
            Button(
                onClick = {
                    android.util.Log.d("SyncSettings", "Test Connection button clicked")
                    scope.launch {
                        android.util.Log.d("SyncSettings", "Starting connection test coroutine")
                        isTestingConnection = true
                        syncStatus = "Testing connection..."
                        
                        val success = syncManager.testConnection()
                        android.util.Log.d("SyncSettings", "Connection test result: $success")
                        syncStatus = if (success) {
                            "✓ Connection successful"
                        } else {
                            "✗ Connection failed"
                        }
                        isTestingConnection = false
                    }
                },
                enabled = !isTestingConnection && 
                         settings.webdavServerUrl.isNotEmpty() && 
                         settings.webdavUsername.isNotEmpty() && 
                         settings.webdavPassword.isNotEmpty(),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isTestingConnection) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(if (isTestingConnection) "Testing..." else "Test Connection")
            }
            
            if (syncStatus.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = syncStatus,
                    color = if (syncStatus.contains("✓")) Color.Green else Color.Red,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Auto Sync Setting
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Auto Sync")
                Switch(
                    checked = settings.webdavAutoSync,
                    onCheckedChange = { isChecked ->
                        scope.launch {
                            val updatedSettings = settings.copy(webdavAutoSync = isChecked)
                            withContext(Dispatchers.IO) {
                                kv.setAppSettings(updatedSettings)
                            }
                        }
                    }
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Auto Sync on Notebook Close
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Auto Sync on Notebook Close")
                    Text(
                        text = "Automatically sync notebooks when closing them",
                        style = androidx.compose.material.MaterialTheme.typography.caption,
                        color = androidx.compose.material.MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                    )
                }
                Switch(
                    checked = syncManager.isAutoSyncEnabled,
                    onCheckedChange = { isChecked ->
                        syncManager.isAutoSyncEnabled = isChecked
                    },
                    enabled = settings.webdavSyncEnabled // Only enable if sync is enabled
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Sync Interval
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Sync Interval")
                SelectMenu(
                    options = listOf(
                        60 to "1 minute",
                        300 to "5 minutes",
                        600 to "10 minutes",
                        1800 to "30 minutes",
                        3600 to "1 hour"
                    ),
                    value = settings.webdavSyncInterval,
                    onChange = { interval ->
                        scope.launch {
                            val updatedSettings = settings.copy(webdavSyncInterval = interval)
                            withContext(Dispatchers.IO) {
                                kv.setAppSettings(updatedSettings)
                            }
                        }
                    }
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Manual Sync Button
            Button(
                onClick = {
                    scope.launch {
                        isSyncing = true
                        syncStatus = "Syncing..."
                        
                        val result = syncManager.syncBidirectional()
                        syncStatus = when (result) {
                            com.ethran.notable.sync.SyncResult.SUCCESS -> "✓ Sync completed successfully"
                            com.ethran.notable.sync.SyncResult.PARTIAL_SUCCESS -> "⚠ Partial sync completed"
                            com.ethran.notable.sync.SyncResult.UP_TO_DATE -> "✓ Already up to date"
                            com.ethran.notable.sync.SyncResult.ERROR -> "✗ Sync failed"
                            com.ethran.notable.sync.SyncResult.DISABLED -> "✗ Sync disabled"
                            com.ethran.notable.sync.SyncResult.NOT_CONFIGURED -> "✗ Not configured"
                            else -> "✗ Unknown error"
                        }
                        
                        isSyncing = false
                    }
                },
                enabled = !isSyncing && settings.webdavServerUrl.isNotEmpty(),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isSyncing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Icon(
                    imageVector = Icons.Default.Sync,
                    contentDescription = "Sync",
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (isSyncing) "Syncing..." else "Sync Now")
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Test REPORT Method Button (for development/testing)
            var isTestingReport by remember { mutableStateOf(false) }
            OutlinedButton(
                onClick = {
                    scope.launch {
                        isTestingReport = true
                        syncStatus = "Testing REPORT method..."
                        
                        val success = syncManager.testSyncCollectionReport()
                        syncStatus = if (success) {
                            "✓ REPORT method test completed - check logs for details"
                        } else {
                            "✗ REPORT method not supported or failed - check logs"
                        }
                        
                        isTestingReport = false
                    }
                },
                enabled = !isTestingReport && !isSyncing && settings.webdavServerUrl.isNotEmpty(),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isTestingReport) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Icon(
                    imageVector = Icons.Default.CloudSync,
                    contentDescription = "Test REPORT",
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (isTestingReport) "Testing..." else "Test REPORT Method")
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Replace Local with Server Button
            Button(
                onClick = {
                    scope.launch {
                        isSyncing = true
                        syncStatus = "Replacing local with server..."
                        
                        val result = syncManager.replaceLocalWithServer()
                        syncStatus = when (result) {
                            com.ethran.notable.sync.SyncResult.SUCCESS -> "✓ Local data replaced with server data"
                            com.ethran.notable.sync.SyncResult.PARTIAL_SUCCESS -> "⚠ Partial replacement completed"
                            com.ethran.notable.sync.SyncResult.UP_TO_DATE -> "✓ Already up to date"
                            com.ethran.notable.sync.SyncResult.ERROR -> "✗ Replacement failed"
                            com.ethran.notable.sync.SyncResult.DISABLED -> "✗ Sync disabled"
                            com.ethran.notable.sync.SyncResult.NOT_CONFIGURED -> "✗ Not configured"
                            else -> "✗ Unknown error"
                        }
                        
                        isSyncing = false
                    }
                },
                enabled = !isSyncing && settings.webdavServerUrl.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF2196F3))
            ) {
                if (isSyncing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(if (isSyncing) "Replacing..." else "Replace Local with Server")
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Replace Server with Local Button (DANGER!)
            Button(
                onClick = {
                    showReplaceServerConfirmation = true
                },
                enabled = !isSyncing && settings.webdavServerUrl.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFFF5722))
            ) {
                if (isSyncing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(if (isSyncing) "Replacing..." else "Replace Server with Local [DANGER!]")
            }
            
            // Show confirmation dialog for dangerous operation
            if (showReplaceServerConfirmation) {
                ShowConfirmationDialog(
                    title = "Replace Server with Local Data?",
                    message = "⚠️ WARNING: This will permanently delete ALL data on the server and replace it with your local data. This action cannot be undone. Are you absolutely sure you want to continue?",
                    onConfirm = {
                        showReplaceServerConfirmation = false
                        scope.launch {
                            isSyncing = true
                            syncStatus = "Replacing server with local... [DANGER!]"
                            
                            val result = syncManager.replaceServerWithLocal()
                            syncStatus = when (result) {
                                com.ethran.notable.sync.SyncResult.SUCCESS -> "✓ Server data replaced with local data"
                                com.ethran.notable.sync.SyncResult.PARTIAL_SUCCESS -> "⚠ Partial replacement completed"
                                com.ethran.notable.sync.SyncResult.UP_TO_DATE -> "✓ Already up to date"
                                com.ethran.notable.sync.SyncResult.ERROR -> "✗ Replacement failed"
                                com.ethran.notable.sync.SyncResult.DISABLED -> "✗ Sync disabled"
                                com.ethran.notable.sync.SyncResult.NOT_CONFIGURED -> "✗ Not configured"
                                else -> "✗ Unknown error"
                            }
                            
                            isSyncing = false
                        }
                    },
                    onCancel = {
                        showReplaceServerConfirmation = false
                    }
                )
            }
        }
    }
}