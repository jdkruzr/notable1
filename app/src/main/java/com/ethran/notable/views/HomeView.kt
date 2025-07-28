package com.ethran.notable.views

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Badge
import androidx.compose.material.BadgedBox
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.ethran.notable.TAG
import com.ethran.notable.classes.AppRepository
import com.ethran.notable.classes.LocalSnackContext
import com.ethran.notable.classes.PageDataManager
import com.ethran.notable.classes.SnackConf
import com.ethran.notable.classes.XoppFile
import com.ethran.notable.components.BreadCrumb
import com.ethran.notable.components.PageMenu
import com.ethran.notable.components.PagePreview
import com.ethran.notable.components.ShowConfirmationDialog
import com.ethran.notable.components.Topbar
import com.ethran.notable.db.BackgroundType
import com.ethran.notable.db.BookRepository
import com.ethran.notable.db.Folder
import com.ethran.notable.db.Notebook
import com.ethran.notable.db.Page
import com.ethran.notable.db.PageRepository
import com.ethran.notable.modals.FolderConfigDialog
import com.ethran.notable.modals.GlobalAppSettings
import com.ethran.notable.modals.NotebookConfigDialog
import com.ethran.notable.modals.getPdfPageCount
import com.ethran.notable.utils.copyBackgroundToDatabase
import com.ethran.notable.utils.isLatestVersion
import com.ethran.notable.utils.noRippleClickable
import com.ethran.notable.utils.setAnimationMode
import compose.icons.FeatherIcons
import compose.icons.feathericons.FilePlus
import compose.icons.feathericons.Folder
import compose.icons.feathericons.FolderPlus
import compose.icons.feathericons.Settings
import compose.icons.feathericons.Upload
import io.shipbook.shipbooksdk.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.concurrent.thread
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@ExperimentalFoundationApi
@ExperimentalComposeUiApi
@Composable
fun Library(navController: NavController, folderId: String? = null) {
    PageDataManager.clearAllPages()

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val appRepository = AppRepository(LocalContext.current)

    val allBooks by appRepository.bookRepository.getAllInFolder(folderId).observeAsState()
    val folders by appRepository.folderRepository.getAllInFolder(folderId).observeAsState()
    
    // Separate regular notebooks from Quick Pages
    val books = allBooks?.filter { !it.isQuickPage() }
    val quickPages = allBooks?.filter { it.isQuickPage() }
    val bookRepository = BookRepository(LocalContext.current)
    
    // State for new notebook creation and properties dialog
    var newlyCreatedNotebookId by remember { mutableStateOf<String?>(null) }
    var showNotebookProperties by remember { mutableStateOf(false) }

    var isLatestVersion by remember {
        mutableStateOf(true)
    }
    LaunchedEffect(key1 = Unit, block = {
        thread {
            isLatestVersion = isLatestVersion(context, true)
        }
    })

    var importInProgress = false

    var showFloatingEditor by remember { mutableStateOf(false) }
    var floatingEditorPageId by remember { mutableStateOf<String?>(null) }

    val snackManager = LocalSnackContext.current


    // ensure scrolling is done in animation mode.
    val lazyGridStateNotebooks = rememberLazyGridState()
    val lazyListStateFolders = rememberLazyListState()
    var isScrolling by remember { mutableStateOf(false) }
    var scrollJob by remember { mutableStateOf<Job?>(null) }
    fun handleAnimations(scope: CoroutineScope, scrolling: Boolean){
        if (scrolling) {
            // User started scrolling
            isScrolling = true
            setAnimationMode(true)
            scrollJob?.cancel()
        } else {
            // User stopped scrolling - delay before resetting
            scrollJob = scope.launch {
                delay(500) // Wait 500ms to ensure scrolling really stopped
                setAnimationMode(false)
                isScrolling = false
            }
        }
    }
    LaunchedEffect(lazyGridStateNotebooks, lazyListStateFolders) {
        snapshotFlow { lazyGridStateNotebooks.isScrollInProgress }
            .collect { scrolling ->
                handleAnimations(this, scrolling)
            }
        snapshotFlow { lazyListStateFolders.isScrollInProgress }
            .collect { scrolling ->
                handleAnimations(this, scrolling)
            }
    }


    Column(
        Modifier.fillMaxSize()
    ) {
        Topbar {
            Row(Modifier.fillMaxWidth()) {
                Spacer(modifier = Modifier.weight(1f))
                BadgedBox(
                    badge = {
                        if (!isLatestVersion) Badge(
                            backgroundColor = Color.Black,
                            modifier = Modifier.offset((-12).dp, 10.dp)
                        )
                    }
                ) {
                    Icon(
                        imageVector = FeatherIcons.Settings,
                        contentDescription = "",
                        Modifier
                            .padding(8.dp)
                            .noRippleClickable {
                                navController.navigate("settings")
                            })
                }
            }
            Row(
                Modifier
                    .padding(10.dp)
            ) {
                BreadCrumb(folderId) { navController.navigate("library" + if (it == null) "" else "?folderId=${it}") }
            }
//           I do not know what the idea behind it was
//            // Add the new "Floating Editor" button here
//            Text(text = "Floating Editor",
//                textAlign = TextAlign.Center,
//                modifier = Modifier
//                    .noRippleClickable {
//                        val page = Page(
//                            notebookId = null,
//                            parentFolderId = folderId,
//                            nativeTemplate = appRepository.kvProxy.get(
//                                APP_SETTINGS_KEY, AppSettings.serializer()
//                            )?.defaultNativeTemplate ?: "blank"
//                        )
//                        appRepository.pageRepository.create(page)
//                        floatingEditorPageId = page.id
//                        showFloatingEditor = true
//                    }
//                    .padding(10.dp))

        }

        Column(
            Modifier.padding(10.dp)
        ) {

            Spacer(Modifier.height(10.dp))

            LazyRow(
                state = lazyListStateFolders,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                item {
                    // Add new folder row
                    Row(
                        Modifier
                            .noRippleClickable {
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        val folder = Folder(parentFolderId = folderId)
                                        appRepository.folderRepository.create(folder)
                                    }
                                }
                            }
                            .border(0.5.dp, Color.Black)
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Icon(
                            imageVector = FeatherIcons.FolderPlus,
                            contentDescription = "Add Folder Icon",
                            Modifier.height(20.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(text = "Add new folder")
                    }
                }
                if (folders?.isNotEmpty() == true) {
                    items(folders!!) { folder ->
                        var isFolderSettingsOpen by remember { mutableStateOf(false) }
                        if (isFolderSettingsOpen) FolderConfigDialog(
                            folderId = folder.id,
                            onClose = {
                                Log.i(TAG, "Closing Directory Dialog")
                                isFolderSettingsOpen = false
                            })
                        Row(
                            Modifier
                                .combinedClickable(
                                    onClick = {
                                        navController.navigate("library?folderId=${folder.id}")
                                    },
                                    onLongClick = {
                                        isFolderSettingsOpen = !isFolderSettingsOpen
                                    },
                                )
                                .border(0.5.dp, Color.Black)
                                .padding(10.dp, 5.dp)
                        ) {
                            Icon(
                                imageVector = FeatherIcons.Folder,
                                contentDescription = "folder icon",
                                Modifier.height(20.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(text = folder.title)
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(text = "Quick pages")
            Spacer(Modifier.height(10.dp))

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                // Add the "Add quick page" button
                item {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .width(100.dp)
                            .aspectRatio(3f / 4f)
                            .border(1.dp, Color.Gray, RectangleShape)
                            .noRippleClickable {
                                scope.launch {
                                    // Create Quick Page as single-page notebook
                                    val pageId = java.util.UUID.randomUUID().toString()
                                    val notebookId = "__quickpage_${pageId}__"
                                    
                                    val notebook = com.ethran.notable.db.Notebook(
                                        id = notebookId,
                                        title = "Quick Page",
                                        pageIds = listOf(pageId),
                                        parentFolderId = folderId,
                                        defaultNativeTemplate = GlobalAppSettings.current.defaultNativeTemplate
                                    )
                                    
                                    val page = Page(
                                        id = pageId,
                                        notebookId = notebookId,
                                        background = GlobalAppSettings.current.defaultNativeTemplate,
                                        parentFolderId = folderId
                                    )
                                    
                                    withContext(Dispatchers.IO) {
                                        appRepository.insertNotebook(notebook)
                                        appRepository.pageRepository.create(page)
                                    }
                                    navController.navigate("books/$notebookId/pages/${page.id}")
                                }
                            }
                    ) {
                        Icon(
                            imageVector = FeatherIcons.FilePlus,
                            contentDescription = "Add Quick Page",
                            tint = Color.Gray,
                            modifier = Modifier.size(40.dp),
                        )
                    }
                }
                
                // Display existing Quick Pages
                if (quickPages?.isNotEmpty() == true) {
                    items(quickPages!!.reversed()) { quickPageNotebook ->
                        val pageId = quickPageNotebook.pageIds.firstOrNull()
                        if (pageId != null) {
                            var isPageSelected by remember { mutableStateOf(false) }
                            var isQuickPagePropertiesOpen by remember { mutableStateOf(false) }
                            Box {
                                PagePreview(
                                    modifier = Modifier
                                        .combinedClickable(
                                            onClick = {
                                                navController.navigate("books/${quickPageNotebook.id}/pages/$pageId")
                                            },
                                            onLongClick = {
                                                isPageSelected = true
                                            },
                                        )
                                        .width(100.dp)
                                        .aspectRatio(3f / 4f)
                                        .border(1.dp, Color.Black, RectangleShape),
                                    pageId = pageId
                                )
                                if (isPageSelected) PageMenu(
                                    notebookId = quickPageNotebook.id,
                                    pageId = pageId,
                                    canDelete = true,
                                    onShowProperties = { 
                                        isQuickPagePropertiesOpen = true
                                        isPageSelected = false
                                    },
                                    onClose = { isPageSelected = false })
                            }
                            
                            // Quick Page properties dialog
                            if (isQuickPagePropertiesOpen) {
                                NotebookConfigDialog(
                                    bookId = quickPageNotebook.id,
                                    onClose = { isQuickPagePropertiesOpen = false }
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(text = "Notebooks")
            Spacer(Modifier.height(10.dp))

            LazyVerticalGrid(
                state = lazyGridStateNotebooks,
                columns = GridCells.Adaptive(100.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Box(
                        modifier = Modifier
                            .width(100.dp)
                            .aspectRatio(3f / 4f)
                            .border(1.dp, Color.Gray, RectangleShape),
                    ) {
                        Column(
                            modifier = Modifier.fillMaxSize()
                        ) {
                            // Create New Notebook Button (Top Half)
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .weight(1f) // Takes half the height
                                    .fillMaxWidth()
                                    .background(Color.LightGray.copy(alpha = 0.3f))
                                    .noRippleClickable {
                                        scope.launch {
                                            val settings = GlobalAppSettings.current
                                            val defaultTitle = if (settings.prefixNotebookNamesWithDate) {
                                                val dateFormat = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.getDefault())
                                                "${dateFormat.format(java.util.Date())} New notebook"
                                            } else {
                                                "New notebook"
                                            }
                                            
                                            val newNotebook = Notebook(
                                                title = defaultTitle,
                                                parentFolderId = folderId,
                                                defaultNativeTemplate = settings.defaultNativeTemplate
                                            )
                                            
                                            withContext(Dispatchers.IO) {
                                                appRepository.bookRepository.create(newNotebook)
                                            }
                                            
                                            // Open properties dialog immediately
                                            newlyCreatedNotebookId = newNotebook.id
                                            showNotebookProperties = true
                                        }
                                    }
                                    .border(2.dp, Color.Black, RectangleShape)
                            ) {
                                Icon(
                                    imageVector = FeatherIcons.FilePlus,
                                    contentDescription = "Add Quick Page",
                                    tint = Color.Gray,
                                    modifier = Modifier.size(40.dp),
                                )
                            }

                            val launcher = rememberLauncherForActivityResult(
                                contract = ActivityResultContracts.OpenDocument()
                            ) { uri: Uri? ->
                                uri?.let {
                                    val mimeType = context.contentResolver.getType(uri)
                                    Log.d(TAG, "Selected file mimeType: $mimeType, uri: $uri")
                                    if (mimeType == "application/pdf" || uri.toString()
                                            .endsWith(".pdf", ignoreCase = true)
                                    ) {
                                        // Handle PDF import here
                                        CoroutineScope(Dispatchers.IO).launch {
                                            val removeSnack = snackManager.displaySnack(
                                                SnackConf(text = "importing PDF background")
                                            )
                                            val flag = Intent.FLAG_GRANT_READ_URI_PERMISSION
                                            context.contentResolver.takePersistableUriPermission(
                                                uri,
                                                flag
                                            )
                                            importInProgress = true
                                            // Call your PDF-specific logic
                                            handlePdfImport(context, folderId, uri)
                                            importInProgress = false
                                            removeSnack()
                                        }
                                    } else {
                                        CoroutineScope(Dispatchers.IO).launch {
                                            val removeSnack =
                                                snackManager.displaySnack(
                                                    SnackConf(text = "importing from xopp file")
                                                )
                                            importInProgress = true
                                            XoppFile.importBook(context, uri, folderId)
                                            importInProgress = false
                                            removeSnack()
                                        }
                                    }
                                }
                            }
                            // Import Notebook (Bottom Half)
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .background(Color.LightGray.copy(alpha = 0.3f))
                                    .noRippleClickable {
                                        launcher.launch(
                                            arrayOf(
                                                "application/x-xopp",
                                                "application/gzip",
                                                "application/octet-stream",
                                                "application/pdf"
                                            )
                                        )
                                    }
                                    .border(2.dp, Color.Black, RectangleShape)

                            ) {
                                Icon(
                                    imageVector = FeatherIcons.Upload,
                                    contentDescription = "Import Notebook",
                                    tint = Color.Gray,
                                    modifier = Modifier.size(40.dp),
                                )
                            }
                        }
                    }
                }
                if (books?.isNotEmpty() == true) {
                    items(books!!.reversed()) { item ->
                        if (item.pageIds.isEmpty()) {
                            if (!importInProgress) {
                                ShowConfirmationDialog(
                                    title = "There is a book without pages!!!",
                                    message = "We suggest deleting book title \"${item.title}\", it was created at ${item.createdAt}. Do you want to do it?",
                                    onConfirm = {
                                        // Get device ID for deletion logging
                                        val prefs = context.getSharedPreferences("sync_settings", Context.MODE_PRIVATE)
                                        val deviceId = prefs.getString("device_id", null) ?: java.util.UUID.randomUUID().toString()
                                        
                                        // Use logging deletion method if sync is enabled
                                        val syncEnabled = prefs.getBoolean("sync_enabled", false)
                                        if (syncEnabled) {
                                            bookRepository.deleteWithLogging(item.id, deviceId)
                                        } else {
                                            bookRepository.delete(item.id)
                                        }
                                    },
                                    onCancel = { }
                                )
                            }
                            return@items
                        }
                        var isSettingsOpen by remember { mutableStateOf(false) }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(3f / 4f)
                                .border(1.dp, Color.Black, RectangleShape)
                                .background(Color.White)
                                .clip(RoundedCornerShape(2))
                        ) {
                            Box {
                                val pageId = item.pageIds[0]

                                PagePreview(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(3f / 4f)
                                        .border(1.dp, Color.Black, RectangleShape)
                                        .combinedClickable(
                                            onClick = {
                                                val bookId = item.id
                                                val pageId = item.openPageId ?: item.pageIds[0]
                                                navController.navigate("books/$bookId/pages/$pageId")
                                            },
                                            onLongClick = {
                                                isSettingsOpen = true
                                            },
                                        ), pageId
                                )
                            }
                            Text(
                                text = item.pageIds.size.toString(),
                                modifier = Modifier
                                    .background(Color.Black)
                                    .padding(5.dp),
                                color = Color.White
                            )
                            Text(
                                text = item.title,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .fillMaxWidth()
                                    .padding(bottom = 8.dp) // Add some padding above the row
                                    .background(Color.White)
                            )
                        }
                        if (isSettingsOpen) NotebookConfigDialog(
                            bookId = item.id,
                            onClose = { isSettingsOpen = false })
                    }
                }
            }
        }
    }

// Add the FloatingEditorView here
    if (showFloatingEditor && floatingEditorPageId != null) {
        FloatingEditorView(
            navController = navController,
            pageId = floatingEditorPageId!!,
            onDismissRequest = {
                showFloatingEditor = false
                floatingEditorPageId = null
            }
        )
    }
    
    // Notebook properties dialog for newly created notebooks
    if (showNotebookProperties && newlyCreatedNotebookId != null) {
        NotebookConfigDialog(
            bookId = newlyCreatedNotebookId!!,
            onClose = {
                showNotebookProperties = false
                newlyCreatedNotebookId = null
            }
        )
    }
}

fun handlePdfImport(context: Context, folderId: String?, uri: Uri) {
    Log.v(TAG, "Importing PDF from $uri")
    if (Looper.getMainLooper().isCurrentThread)
        Log.e(TAG, "Importing is done on main thread.")

    //copy file:
    val flag = Intent.FLAG_GRANT_READ_URI_PERMISSION
    context.contentResolver.takePersistableUriPermission(uri, flag)
    val subfolder = BackgroundType.Pdf(0).folderName
    val copiedFile = copyBackgroundToDatabase(context, uri, subfolder)

    val pageRepo = PageRepository(context)
    val bookRepo = BookRepository(context)

    val book = Notebook(
        title = copiedFile.nameWithoutExtension,
        parentFolderId = folderId,
        defaultNativeTemplate = "blank"
    )
    bookRepo.createEmpty(book)

    val numberOfPages = getPdfPageCount(copiedFile.toString())

    for (i in 0 until numberOfPages) {
        val page = Page(
            notebookId = book.id,
            background = copiedFile.toString(),
            backgroundType = BackgroundType.Pdf(i).key,
        )
        pageRepo.create(page)
        bookRepo.addPage(book.id, page.id)
    }

}



