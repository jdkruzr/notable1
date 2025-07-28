package com.ethran.notable.views

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.navigation.NavController
import com.ethran.notable.classes.AppRepository
import com.ethran.notable.db.Page
import com.ethran.notable.modals.GlobalAppSettings
import com.ethran.notable.ui.theme.InkaTheme

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FloatingEditorView(
    navController: NavController,
    bookId: String? = null,
    pageId: String? = null,
    onDismissRequest: () -> Unit
) {
    // TODO:
    var isFullScreen by remember { mutableStateOf(false) } // State for full-screen mode

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = true)
    ) {
        InkaTheme {
            Box(
                modifier = Modifier
                    .fillMaxSize() // Ensure it fills the entire screen
                    .background(Color.White)
            ) {
                Column {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .fillMaxHeight()
                            .background(Color.White)
                    ) {
                        if (pageId != null) {
                            EditorView(
                                navController = navController,
                                bookId = null,
                                pageId = pageId
                            )
                        } else if (bookId != null) {
                            // get first page of notebook and use it as pageId
                            val appRepository = AppRepository(LocalContext.current)
                            val firstPageId =
                                appRepository.bookRepository.getById(bookId)?.pageIds?.firstOrNull()
                            if (firstPageId == null) {
                                // Create Quick Page as single-page notebook
                                val pageId = java.util.UUID.randomUUID().toString()
                                val notebookId = "__quickpage_${pageId}__"
                                
                                val notebook = com.ethran.notable.db.Notebook(
                                    id = notebookId,
                                    title = "Quick Page",
                                    pageIds = listOf(pageId),
                                    parentFolderId = null,
                                    defaultNativeTemplate = GlobalAppSettings.current.defaultNativeTemplate
                                )
                                
                                val page = Page(
                                    id = pageId,
                                    notebookId = notebookId,
                                    background = GlobalAppSettings.current.defaultNativeTemplate,
                                    parentFolderId = null
                                )
                                
                                // Insert the notebook first
                                appRepository.insertNotebook(notebook)
                                EditorView(
                                    navController = navController,
                                    bookId = bookId,
                                    pageId = page.id
                                )
                            } else {
                                EditorView(
                                    navController = navController,
                                    bookId = bookId,
                                    pageId = firstPageId
                                )
                            }


                        }
                    }
                }
            }
        }
    }
}
