package com.ethran.notable.views

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.Card
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Switch
import androidx.compose.material.Tab
import androidx.compose.material.TabRow
import androidx.compose.material.TabRowDefaults.Divider
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Update
import androidx.compose.material.icons.filled.Upgrade
import androidx.compose.material.icons.filled.List
import androidx.compose.material.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.navigation.NavController
import com.ethran.notable.BuildConfig
import com.ethran.notable.classes.showHint
import com.ethran.notable.components.SelectMenu
import com.ethran.notable.db.KvProxy
import com.ethran.notable.modals.AppSettings
import com.ethran.notable.modals.GlobalAppSettings
import com.ethran.notable.utils.isLatestVersion
import com.ethran.notable.utils.isNext
import com.ethran.notable.utils.noRippleClickable
import com.ethran.notable.sync.SyncManager
import com.ethran.notable.classes.AppRepository
import com.ethran.notable.utils.SyncLogger
import com.ethran.notable.utils.LogLevel
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import kotlin.concurrent.thread

@ExperimentalFoundationApi
@Composable
fun SettingsView(navController: NavController) {
    val context = LocalContext.current
    val kv = KvProxy(context)
    val settings = GlobalAppSettings.current
    val syncManager = remember { 
        try {
            val manager = SyncManager(context, AppRepository(context))
            // Initialize sync manager with current settings
            manager.isEnabled = settings.webdavSyncEnabled
            manager.serverUrl = settings.webdavServerUrl
            manager.username = settings.webdavUsername
            manager.password = settings.webdavPassword
            manager.isAutoSyncEnabled = settings.webdavAutoSyncOnClose
            if (settings.webdavSyncEnabled && 
                settings.webdavServerUrl.isNotEmpty() && 
                settings.webdavUsername.isNotEmpty() && 
                settings.webdavPassword.isNotEmpty()) {
                manager.initialize()
            }
            manager
        } catch (e: Exception) {
            // If sync manager initialization fails, create a dummy one
            null
        }
    }

    var selectedTabIndex by remember { mutableStateOf(0) }
    val tabs = listOf("General", "WebDAV", "Gestures", "About")

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colors.background
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Header with back button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(
                    onClick = { navController.popBackStack() },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = MaterialTheme.colors.onBackground
                    )
                }

                Text(
                    text = "Settings",
                    style = MaterialTheme.typography.h5,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center
                )

                // Empty box for balance
                Spacer(modifier = Modifier.size(48.dp))
            }

            // Tab Row
            TabRow(
                selectedTabIndex = selectedTabIndex,
                modifier = Modifier.fillMaxWidth(),
                backgroundColor = MaterialTheme.colors.surface,
                contentColor = MaterialTheme.colors.primary
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTabIndex == index,
                        onClick = { selectedTabIndex = index },
                        text = { Text(title) }
                    )
                }
            }

            // Tab Content
            when (selectedTabIndex) {
                0 -> GeneralSettingsTab(kv, settings)
                1 -> WebDAVSettingsTab(kv, syncManager)
                2 -> GesturesSettingsTab(kv, settings)
                3 -> AboutSettingsTab(context)
            }
        }
    }
}

@Composable
fun GeneralSettings(kv: KvProxy, settings: AppSettings) {
    Row {
        Text(text = "Default Page Background Template")
        Spacer(Modifier.width(10.dp))
        SelectMenu(
            options = listOf(
                "blank" to "Blank page",
                "dotted" to "Dot grid",
                "lined" to "Lines",
                "squared" to "Small squares grid",
                "hexed" to "Hexagon grid",
            ),
            onChange = {
                kv.setAppSettings(settings.copy(defaultNativeTemplate = it))
            },
            value = settings.defaultNativeTemplate
        )
    }
    Spacer(Modifier.height(3.dp))

    SettingToggleRow(
        label = "Show welcome screen",
        value = settings.showWelcome,
        onToggle = { isChecked ->
            kv.setAppSettings(settings.copy(showWelcome = isChecked))
        }
    )
    SettingToggleRow(
        label = "Debug Mode (show changed area)",
        value = settings.debugMode,
        onToggle = { isChecked ->
            kv.setAppSettings(settings.copy(debugMode = isChecked))
        }
    )

    SettingToggleRow(
        label = "Use Onyx NeoTools (may cause crashes)",
        value = settings.neoTools,
        onToggle = { isChecked ->
            kv.setAppSettings(settings.copy(neoTools = isChecked))
        }
    )

    SettingToggleRow(
        label = "Enable smooth scrolling",
        value = settings.smoothScroll,
        onToggle = { isChecked ->
            kv.setAppSettings(settings.copy(smoothScroll = isChecked))
        }
    )

    SettingToggleRow(
        label = "Continuous Zoom (Work in progress)",
        value = settings.continuousZoom,
        onToggle = { isChecked ->
            kv.setAppSettings(settings.copy(continuousZoom = isChecked))
        }
    )
    SettingToggleRow(
        label = "Monochrome mode (Work in progress)",
        value = settings.monochromeMode,
        onToggle = { isChecked ->
            kv.setAppSettings(settings.copy(monochromeMode = isChecked))
        }
    )

    SettingToggleRow(
        label = "Paginate PDF",
        value = settings.paginatePdf,
        onToggle = { isChecked ->
            kv.setAppSettings(settings.copy(paginatePdf = isChecked))
        }
    )

    SettingToggleRow(
        label = "Visualize PDF Pagination",
        value = settings.visualizePdfPagination,
        onToggle = { isChecked ->
            kv.setAppSettings(settings.copy(visualizePdfPagination = isChecked))
        }
    )
    Spacer(Modifier.height(5.dp))

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = "Toolbar Position (Work in progress)")
            Spacer(modifier = Modifier.width(10.dp))

            SelectMenu(
                options = listOf(
                    AppSettings.Position.Top to "Top",
                    AppSettings.Position.Bottom to "Bottom"
                ),
                value = settings.toolbarPosition,
                onChange = { newPosition ->
                    settings.let {
                        kv.setAppSettings(it.copy(toolbarPosition = newPosition))
                    }
                }
            )
        }
    }
}

@Composable
fun SettingToggleRow(
    label: String,
    value: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label)
        Spacer(Modifier.width(10.dp))
        Switch(
            checked = value,
            onCheckedChange = onToggle
        )
    }
}


@Composable
fun GestureSelectorRow(
    title: String,
    kv: KvProxy,
    settings: AppSettings?,
    update: AppSettings.(AppSettings.GestureAction?) -> AppSettings,
    default: AppSettings.GestureAction,
    override: AppSettings.() -> AppSettings.GestureAction?,
) {
    Row {
        Text(text = title)
        Spacer(Modifier.width(10.dp))
        SelectMenu(
            options = listOf(
                null to "None",
                AppSettings.GestureAction.Undo to "Undo",
                AppSettings.GestureAction.Redo to "Redo",
                AppSettings.GestureAction.PreviousPage to "Previous Page",
                AppSettings.GestureAction.NextPage to "Next Page",
                AppSettings.GestureAction.ChangeTool to "Toggle Pen / Eraser",
                AppSettings.GestureAction.ToggleZen to "Toggle Zen Mode",
            ),
            value = if (settings != null) settings.override() else default,
            onChange = {
                if (settings != null) {
                    kv.setAppSettings(settings.update(it))
                }
            },
        )
    }
}


@Composable
fun GitHubSponsorButton(modifier: Modifier = Modifier) {
    val context = LocalContext.current

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .background(
                    color = Color(0xFF24292E),
                    shape = RoundedCornerShape(25.dp)
                )
                .clickable {
                    val urlIntent = Intent(
                        Intent.ACTION_VIEW,
                        "https://github.com/sponsors/ethran".toUri()
                    )
                    context.startActivity(urlIntent)
                },
            contentAlignment = Alignment.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(modifier = Modifier.width(8.dp))
                Icon(
                    imageVector = Icons.Default.FavoriteBorder,
                    contentDescription = "Heart Icon",
                    tint = Color(0xFFEA4AAA),
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Sponsor",
                    color = Color.White,
                    style = MaterialTheme.typography.button.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    ),
                )
            }
        }
    }
}


@Composable
fun ShowUpdateButton(context: Context, modifier: Modifier = Modifier) {
    var isLatestVersion by remember { mutableStateOf(true) }
    LaunchedEffect(key1 = Unit, block = { thread { isLatestVersion = isLatestVersion(context) } })

    if (!isLatestVersion) {
        Column(modifier = modifier) {
            Text(
                text = "It seems a new version of Notable is available on GitHub.",
                fontStyle = FontStyle.Italic,
                style = MaterialTheme.typography.h6,
            )

            Spacer(Modifier.height(10.dp))

            Button(
                onClick = {
                    val urlIntent = Intent(
                        Intent.ACTION_VIEW,
                        "https://github.com/ethran/notable/releases".toUri()
                    )
                    context.startActivity(urlIntent)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Upgrade, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = "See release in browser")
            }
        }
        Spacer(Modifier.height(10.dp))
    } else {
        Button(
            onClick = {
                thread {
                    isLatestVersion = isLatestVersion(context, true)
                    if (isLatestVersion) {
                        showHint(
                            "You are on the latest version.",
                            duration = 1000
                        )
                    }
                }
            },
            modifier = modifier // Adjust the modifier as needed
        ) {
            Icon(Icons.Default.Update, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = "Check for newer version")
        }
    }
}


@Composable
fun GeneralSettingsTab(kv: KvProxy, settings: AppSettings) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = 2.dp,
            shape = MaterialTheme.shapes.medium
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                GeneralSettings(kv, settings)
            }
        }
    }
}

@Composable
fun WebDAVSettingsTab(kv: KvProxy, syncManager: SyncManager?) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        if (syncManager != null) {
            SyncSettings(kv, syncManager)
            Spacer(modifier = Modifier.height(16.dp))
            SyncLogViewer()
        } else {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                elevation = 2.dp,
                shape = MaterialTheme.shapes.medium
            ) {
                Text(
                    text = "Sync functionality unavailable",
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colors.error
                )
            }
        }
    }
}

@Composable
fun GesturesSettingsTab(kv: KvProxy, settings: AppSettings) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = 2.dp,
            shape = MaterialTheme.shapes.medium
        ) {
            EditGestures(kv, settings)
        }
    }
}

@Composable
fun AboutSettingsTab(context: Context) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        // Version info card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            elevation = 2.dp,
            shape = MaterialTheme.shapes.medium
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "App Version",
                    style = MaterialTheme.typography.subtitle1,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                )
                Text(
                    text = "v${BuildConfig.VERSION_NAME}${if (isNext) " [NEXT]" else ""}",
                    style = MaterialTheme.typography.h6,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        // Additional actions
        Column {
            GitHubSponsorButton(
                Modifier
                    .padding(horizontal = 60.dp, vertical = 16.dp)
                    .height(48.dp)
                    .fillMaxWidth()
            )
            ShowUpdateButton(
                context = context, modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 30.dp, vertical = 8.dp)
                    .height(48.dp)
            )
        }
    }
}

@Composable
fun EditGestures(kv: KvProxy, settings: AppSettings?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Text(
            text = "Gesture Settings",
            style = MaterialTheme.typography.h6,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        GestureSelectorRow(
            title = "Double Tap Action",
            kv = kv,
            settings = settings,
            update = { copy(doubleTapAction = it) },
            default = AppSettings.defaultDoubleTapAction,
            override = { doubleTapAction }
        )

        Spacer(modifier = Modifier.height(8.dp))

        GestureSelectorRow(
            title = "Two Finger Tap Action",
            kv = kv,
            settings = settings,
            update = { copy(twoFingerTapAction = it) },
            default = AppSettings.defaultTwoFingerTapAction,
            override = { twoFingerTapAction }
        )

        Spacer(modifier = Modifier.height(8.dp))

        GestureSelectorRow(
            title = "Swipe Left Action",
            kv = kv,
            settings = settings,
            update = { copy(swipeLeftAction = it) },
            default = AppSettings.defaultSwipeLeftAction,
            override = { swipeLeftAction }
        )

        Spacer(modifier = Modifier.height(8.dp))

        GestureSelectorRow(
            title = "Swipe Right Action",
            kv = kv,
            settings = settings,
            update = { copy(swipeRightAction = it) },
            default = AppSettings.defaultSwipeRightAction,
            override = { swipeRightAction }
        )

        Spacer(modifier = Modifier.height(8.dp))

        GestureSelectorRow(
            title = "Two Finger Swipe Left Action",
            kv = kv,
            settings = settings,
            update = { copy(twoFingerSwipeLeftAction = it) },
            default = AppSettings.defaultTwoFingerSwipeLeftAction,
            override = { twoFingerSwipeLeftAction }
        )

        Spacer(modifier = Modifier.height(8.dp))

        GestureSelectorRow(
            title = "Two Finger Swipe Right Action",
            kv = kv,
            settings = settings,
            update = { copy(twoFingerSwipeRightAction = it) },
            default = AppSettings.defaultTwoFingerSwipeRightAction,
            override = { twoFingerSwipeRightAction }
        )
    }
}

@Composable
fun SyncLogViewer() {
    val logs by SyncLogger.logs.collectAsState()
    var expanded by remember { mutableStateOf(false) }
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        elevation = 2.dp,
        shape = MaterialTheme.shapes.medium
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .noRippleClickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.List,
                    contentDescription = "Sync Log",
                    tint = MaterialTheme.colors.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Sync Log (${logs.size} entries)",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandMore else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = if (expanded) "Collapse" else "Expand"
                )
            }
            
            if (expanded) {
                Spacer(modifier = Modifier.height(8.dp))
                
                // Clear logs button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Button(
                        onClick = { SyncLogger.clear() },
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color.LightGray)
                    ) {
                        Text("Clear Log")
                    }
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // Log entries
                if (logs.isEmpty()) {
                    Text(
                        text = "No sync events logged yet",
                        style = MaterialTheme.typography.body2,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier.padding(8.dp)
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.height(200.dp),
                        reverseLayout = false // Show newest first (already handled in SyncLogger)
                    ) {
                        items(logs) { logEntry ->
                            LogEntryItem(logEntry)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LogEntryItem(logEntry: com.ethran.notable.utils.SyncLogEntry) {
    val logColor = when (logEntry.level) {
        LogLevel.DEBUG -> MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
        LogLevel.INFO -> MaterialTheme.colors.primary
        LogLevel.WARN -> Color(0xFFFF9800) // Orange
        LogLevel.ERROR -> MaterialTheme.colors.error
    }
    
    val timeFormat = remember { java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()) }
    
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
    ) {
        Row(
            verticalAlignment = Alignment.Top
        ) {
            Text(
                text = timeFormat.format(logEntry.timestamp),
                style = MaterialTheme.typography.caption,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.width(60.dp)
            )
            
            Text(
                text = "[${logEntry.level.name.first()}]",
                style = MaterialTheme.typography.caption,
                color = logColor,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.width(25.dp)
            )
            
            Text(
                text = logEntry.message,
                style = MaterialTheme.typography.caption,
                color = logColor,
                modifier = Modifier.weight(1f)
            )
        }
    }
}


