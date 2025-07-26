package com.ethran.notable.modals

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.ethran.notable.TAG
import com.ethran.notable.db.FolderRepository
import com.ethran.notable.utils.noRippleClickable
import io.shipbook.shipbooksdk.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


@ExperimentalComposeUiApi
@Composable
fun FolderConfigDialog(folderId: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val folderRepository = FolderRepository(context)
    
    var folder by remember { mutableStateOf<com.ethran.notable.db.Folder?>(null) }
    var folderTitle by remember { mutableStateOf("") }

    // Load folder data asynchronously
    LaunchedEffect(folderId) {
        withContext(Dispatchers.IO) {
            val loadedFolder = folderRepository.get(folderId)
            folder = loadedFolder
            folderTitle = loadedFolder.title
        }
    }
    
    // Don't render dialog until folder is loaded
    if (folder == null) return


    Dialog(
        onDismissRequest = {
            Log.i(TAG, "Closing Directory Dialog - upstream")
            onClose()
        }
    ) {
        val focusManager = LocalFocusManager.current

        Column(
            modifier = Modifier
                .background(Color.White)
                .fillMaxWidth()
                .border(2.dp, Color.Black, RectangleShape)
        ) {
            Column(
                Modifier.padding(20.dp, 10.dp)
            ) {
                Text(text = "Folder Setting", fontWeight = FontWeight.Bold)
            }
            Box(
                Modifier
                    .height(1.dp)
                    .fillMaxWidth()
                    .background(Color.Black)
            )

            Column(
                Modifier.padding(20.dp, 10.dp)
            ) {

                Row {
                    Text(
                        text = "Folder Title",
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.width(10.dp))
                    BasicTextField(
                        value = folderTitle,
                        textStyle = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Light,
                            fontSize = 16.sp
                        ),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Text,
                            imeAction = androidx.compose.ui.text.input.ImeAction.Done
                        ),
                        onValueChange = { folderTitle = it },
                        keyboardActions = KeyboardActions(onDone = {
                            focusManager.clearFocus()
                        }),
                        modifier = Modifier
                            .background(Color(230, 230, 230, 255))
                            .padding(10.dp, 0.dp)


                    )

                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Action buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    // Cancel button
                    Text(
                        text = "Cancel",
                        textAlign = TextAlign.Center,
                        color = Color.Gray,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .noRippleClickable {
                                onClose()
                            }
                            .padding(vertical = 8.dp, horizontal = 16.dp)
                    )
                    
                    // Save button
                    Text(
                        text = "Save Changes",
                        textAlign = TextAlign.Center,
                        color = Color.Blue,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .noRippleClickable {
                                val updatedFolder = folder!!.copy(title = folderTitle)
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        folderRepository.update(updatedFolder)
                                    }
                                    onClose()
                                }
                            }
                            .padding(vertical = 8.dp, horizontal = 16.dp)
                    )
                }
            }

            Box(
                Modifier
                    .padding(20.dp, 0.dp)
                    .height(0.5.dp)
                    .fillMaxWidth()
                    .background(Color.Black)
            )

            Column(
                Modifier.padding(20.dp, 10.dp)
            ) {
                Text(text = "Delete Folder",
                    textAlign = TextAlign.Center,
                    modifier = Modifier.noRippleClickable {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                folderRepository.delete(folderId)
                            }
                            onClose()
                        }
                    })
            }
        }

    }
}