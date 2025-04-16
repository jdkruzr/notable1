package com.ethran.notable.modals


import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.ethran.notable.TAG
import com.ethran.notable.classes.LocalSnackContext
import com.ethran.notable.classes.SnackConf
import com.ethran.notable.components.BreadCrumb
import com.ethran.notable.components.PagePreview
import com.ethran.notable.components.SelectMenu
import com.ethran.notable.components.ShowConfirmationDialog
import com.ethran.notable.components.ShowExportDialog
import com.ethran.notable.components.ShowFolderSelectionDialog
import com.ethran.notable.db.BookRepository
import io.shipbook.shipbooksdk.Log
import kotlinx.coroutines.launch


@ExperimentalComposeUiApi
@Composable
fun NotebookConfigDialog(bookId: String, onClose: () -> Unit) {
    val bookRepository = BookRepository(LocalContext.current)
    val book by bookRepository.getByIdLive(bookId).observeAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackManager = LocalSnackContext.current

    if (book == null) return

    var bookTitle by remember {
        mutableStateOf(book!!.title)
    }
    val formattedCreatedAt =
        remember { android.text.format.DateFormat.format("dd MMM yyyy HH:mm", book!!.createdAt) }
    val formattedUpdatedAt =
        remember { android.text.format.DateFormat.format("dd MMM yyyy HH:mm", book!!.updatedAt) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showMoveDialog by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }

    var bookFolder by remember {  mutableStateOf(book?.parentFolderId)}


    // Confirmation Dialog for Deletion
    if (showDeleteDialog) {
        ShowConfirmationDialog(
            title = "Confirm Deletion",
            message = "Are you sure you want to delete \"${book!!.title}\"?",
            onConfirm = {
                bookRepository.delete(bookId)
                showDeleteDialog = false
                onClose()
            },
            onCancel = {
                showDeleteDialog = false
            }
        )
        return
    }
    // Confirmation Dialog for Deletion
    if (showExportDialog) {
        ShowExportDialog(
            snackManager = snackManager,
            bookId = bookId,
            context = context,
            onConfirm = {
                showExportDialog = false
                onClose()
            },
            onCancel = {
                showExportDialog = false
            }
        )
        return
    }
    // Folder Selection Dialog
    if (showMoveDialog) {

        ShowFolderSelectionDialog(
            book = book!!,
            notebookName = book!!.title,
            initialFolderId = book!!.parentFolderId,
            onCancel = { showMoveDialog = false },
            onConfirm = { selectedFolder ->
                showMoveDialog = false
                onClose()
                Log.i(TAG, "folder:" + selectedFolder.toString())
                val updatedBook = book!!.copy(parentFolderId = selectedFolder)
                bookFolder = selectedFolder
                scope.launch {
                    // be careful, not to cause race condition.
                    bookRepository.update(updatedBook)
                }
            }
        )
    }

    Dialog(
        onDismissRequest = {
            onClose()
        }
    ) {
        val focusManager = LocalFocusManager.current

        Column(
            modifier = Modifier
                .background(Color.White)
                .fillMaxWidth()
                .border(2.dp, Color.Black, RectangleShape)
                .padding(16.dp)
                .padding(top = 24.dp, bottom = 16.dp)
        ) {
            // Header Section
            Row(Modifier.padding(bottom = 16.dp)) {
                Box(
                    modifier = Modifier
                        .size(200.dp, 250.dp)
                        .background(Color.Gray),
                    contentAlignment = Alignment.Center
                ) {
                    val pageId = book!!.pageIds[0]
                    PagePreview(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(3f / 4f)
                            .border(1.dp, Color.Black, RectangleShape), pageId
                    )
                }
                Spacer(Modifier.width(16.dp))
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row {
                        Text(
                            text = "Title:",
                            fontWeight = FontWeight.Bold,
                            fontSize = 24.sp
                        )
                        Spacer(Modifier.width(20.dp))
                        BasicTextField(
                            value = bookTitle,
                            textStyle = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Light,
                                fontSize = 24.sp
                            ),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Text,
                                imeAction = androidx.compose.ui.text.input.ImeAction.Done
                            ),
                            onValueChange = { bookTitle = it },
                            keyboardActions = KeyboardActions(onDone = {
                                focusManager.clearFocus()
                            }),
                            modifier = Modifier
                                .background(Color(230, 230, 230, 255))
                                .padding(10.dp, 0.dp)
                                .onFocusChanged { focusState ->
                                    if (!focusState.isFocused) {
                                        Log.i(TAG, "loose focus")
                                        if (book!!.title != bookTitle) {
                                            val updatedBook = book!!.copy(title = bookTitle)
                                            bookRepository.update(updatedBook)
                                        }
                                    }
                                }


                        )
                    }

                    Row {
                        Text(text = "Default Background Template")

                        Spacer(Modifier.width(30.dp))
                        SelectMenu(
                            options = listOf(
                                "blank" to "Blank page",
                                "dotted" to "Dot grid",
                                "lined" to "Lines",
                                "squared" to "Small squares grid",
                                "hexed" to "Hexagon grid",
                            ),
                            onChange = {
                                if (book!!.defaultNativeTemplate != it) {
                                    val updatedBook = book!!.copy(defaultNativeTemplate = it)
                                    bookRepository.update(updatedBook)
                                }
                            },
                            // this once thrown null ptr exception, when deleting notebook.
                            value = book!!.defaultNativeTemplate
                        )

                    }
                    Text("Pages: ${book!!.pageIds.size}")
                    Text("Size: TODO!")
                    Row {
                        Text("In Folder: ")
                        BreadCrumb(bookFolder) { }
                    }
                    Text("Created: $formattedCreatedAt")
                    Text("Last Updated: $formattedUpdatedAt")
                }
            }

            Spacer(Modifier.height(16.dp))

            // Grid Actions Section
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                ActionButton("Delete") {
                    showDeleteDialog = true
                }
                ActionButton("Move") {
                    showMoveDialog = true
                }
                ActionButton("Export") {
                    showExportDialog=true
                }
                ActionButton("Copy") {
                    scope.launch {
                        snackManager.displaySnack(
                            SnackConf(text = "Not implemented!", duration = 2000)
                        )
                    }
                }

            }
        }

    }

}

@Composable
fun ActionButton(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(100.dp, 40.dp)
            .background(Color.LightGray, RectangleShape)
            .border(1.dp, Color.Black, RectangleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(text, fontWeight = FontWeight.Bold)
    }
}