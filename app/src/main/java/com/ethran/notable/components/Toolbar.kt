package com.ethran.notable.components


import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.navigation.NavController
import com.ethran.notable.R
import com.ethran.notable.classes.AppRepository
import com.ethran.notable.classes.DrawCanvas
import com.ethran.notable.classes.EditorControlTower
import com.ethran.notable.db.Notebook
import com.ethran.notable.modals.AppSettings
import com.ethran.notable.modals.BUTTON_SIZE
import com.ethran.notable.modals.GlobalAppSettings
import com.ethran.notable.modals.PageSettingsModal
import com.ethran.notable.utils.EditorState
import com.ethran.notable.utils.History
import com.ethran.notable.utils.Mode
import com.ethran.notable.utils.Pen
import com.ethran.notable.utils.PenSetting
import com.ethran.notable.utils.UndoRedoType
import com.ethran.notable.utils.copyImageToDatabase
import com.ethran.notable.utils.noRippleClickable
import compose.icons.FeatherIcons
import compose.icons.feathericons.Clipboard
import compose.icons.feathericons.EyeOff
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val toolbarLog = ShipBook.getLogger("Toolbar")
fun presentlyUsedToolIcon(mode: Mode, pen: Pen): Int {
    return when (mode) {
        Mode.Draw -> {
            when (pen) {
                Pen.BALLPEN -> R.drawable.ballpen
                Pen.REDBALLPEN -> R.drawable.ballpenred
                Pen.BLUEBALLPEN -> R.drawable.ballpenblue
                Pen.GREENBALLPEN -> R.drawable.ballpengreen
                Pen.FOUNTAIN -> R.drawable.fountain
                Pen.BRUSH -> R.drawable.brush
                Pen.MARKER -> R.drawable.marker
                Pen.PENCIL -> R.drawable.pencil
            }
        }

        Mode.Erase -> R.drawable.eraser
        Mode.Select -> R.drawable.lasso
        Mode.Line -> R.drawable.line
    }
}

fun isSelected(state: EditorState, penType: Pen): Boolean {
    return if (state.mode == Mode.Draw && state.pen == penType) {
        true
    } else if (state.mode == Mode.Line && state.pen == penType) {
        true
    } else {
        false
    }
}

@Composable
@ExperimentalComposeUiApi
fun Toolbar(
    navController: NavController, state: EditorState, controlTower: EditorControlTower
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current


    // Create an activity result launcher for picking visual media (images in this case)
    val pickMedia =
        rememberLauncherForActivityResult(contract = PickVisualMedia()) { uri ->
            uri?.let {
                // Grant read URI permission to access the selected URI
                val flag = Intent.FLAG_GRANT_READ_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(uri, flag)

                //  copy image to documents/notabledb/images/filename
                val copiedFile = copyImageToDatabase(context, uri)

                // Set isImageLoaded to true
                toolbarLog.i("Image was received and copied, it is now at:${copiedFile.toUri()}")
                DrawCanvas.addImageByUri.value = copiedFile.toUri()

            }
        }

    // on exit of toolbar, update drawing state
    LaunchedEffect(state.menuStates.isPageSettingsModalOpen, state.menuStates.isMenuOpen) {
        // TODO: move it to menuState.
        toolbarLog.i("Updating drawing state")
        state.checkForSelectionsAndMenus()
    }
    fun handleChangePen(pen: Pen) {
        if (state.mode == Mode.Draw && state.pen == pen) {
            state.menuStates.isStrokeSelectionOpen = true
        } else {
            state.mode = Mode.Draw
            state.pen = pen
        }
    }

    fun handleEraser() {
        state.mode = Mode.Erase
    }

    fun handleSelection() {
        state.mode = Mode.Select
    }

    fun handleLine() {
        state.mode = Mode.Line
    }

    fun onChangeStrokeSetting(penName: String, setting: PenSetting) {
        val settings = state.penSettings.toMutableMap()
        settings[penName] = setting.copy()
        state.penSettings = settings
    }

    if (state.menuStates.isPageSettingsModalOpen) {
        toolbarLog.i("Opening page settings modal")
        PageSettingsModal(pageView = state.pageView) {
            state.menuStates.isPageSettingsModalOpen = false
        }
    }
    if (state.isToolbarOpen) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height((BUTTON_SIZE + 51).dp)
                .padding(bottom = 50.dp) // TODO: fix this
        ) {
            if (GlobalAppSettings.current.toolbarPosition == AppSettings.Position.Bottom) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(Color.Black)
                )
            }
            Row(
                Modifier
                    .background(Color.White)
                    .height(BUTTON_SIZE.dp)
                    .fillMaxWidth()
            ) {
                ToolbarButton(
                    onSelect = {
                        state.isToolbarOpen = !state.isToolbarOpen
                    }, vectorIcon = FeatherIcons.EyeOff, contentDescription = "close toolbar"
                )
                Box(
                    Modifier
                        .fillMaxHeight()
                        .width(0.5.dp)
                        .background(Color.Black)
                )

                PenToolbarButton(
                    onStrokeMenuOpenChange = { state.isDrawing = !it },
                    pen = Pen.BALLPEN,
                    icon = R.drawable.ballpen,
                    isSelected = isSelected(state, Pen.BALLPEN),
                    onSelect = { handleChangePen(Pen.BALLPEN) },
                    sizes = listOf("S" to 3f, "M" to 5f, "L" to 10f, "XL" to 20f),
                    penSetting = state.penSettings[Pen.BALLPEN.penName] ?: return,
                    onChangeSetting = { onChangeStrokeSetting(Pen.BALLPEN.penName, it) })

                if (!GlobalAppSettings.current.monochromeMode) {
                    PenToolbarButton(
                        onStrokeMenuOpenChange = { state.isDrawing = !it },
                        pen = Pen.REDBALLPEN,
                        icon = R.drawable.ballpenred,
                        isSelected = isSelected(state, Pen.REDBALLPEN),
                        onSelect = { handleChangePen(Pen.REDBALLPEN) },
                        sizes = listOf("S" to 3f, "M" to 5f, "L" to 10f, "XL" to 20f),
                        penSetting = state.penSettings[Pen.REDBALLPEN.penName] ?: return,
                        onChangeSetting = { onChangeStrokeSetting(Pen.REDBALLPEN.penName, it) },
                    )

                    PenToolbarButton(
                        onStrokeMenuOpenChange = { state.isDrawing = !it },
                        pen = Pen.BLUEBALLPEN,
                        icon = R.drawable.ballpenblue,
                        isSelected = isSelected(state, Pen.BLUEBALLPEN),
                        onSelect = { handleChangePen(Pen.BLUEBALLPEN) },
                        sizes = listOf("S" to 3f, "M" to 5f, "L" to 10f, "XL" to 20f),
                        penSetting = state.penSettings[Pen.BLUEBALLPEN.penName] ?: return,
                        onChangeSetting = { onChangeStrokeSetting(Pen.BLUEBALLPEN.penName, it) },
                    )
//              Removed to make space for insert tool
                    PenToolbarButton(
                        onStrokeMenuOpenChange = { state.isDrawing = !it },
                        pen = Pen.GREENBALLPEN,
                        icon = R.drawable.ballpengreen,
                        isSelected = isSelected(state, Pen.GREENBALLPEN),
                        onSelect = { handleChangePen(Pen.GREENBALLPEN) },
                        sizes = listOf("S" to 3f, "M" to 5f, "L" to 10f, "XL" to 20f),
                        penSetting = state.penSettings[Pen.GREENBALLPEN.penName] ?: return,
                        onChangeSetting = { onChangeStrokeSetting(Pen.GREENBALLPEN.penName, it) },
                    )
                }
                if (GlobalAppSettings.current.neoTools) {
                    PenToolbarButton(
                        onStrokeMenuOpenChange = { state.isDrawing = !it },
                        pen = Pen.PENCIL,
                        icon = R.drawable.pencil,
                        isSelected = isSelected(state, Pen.PENCIL),
                        onSelect = { handleChangePen(Pen.PENCIL) }, // Neo-tool! Usage not recommended
                        sizes = listOf("S" to 3f, "M" to 5f, "L" to 10f, "XL" to 20f),
                        penSetting = state.penSettings[Pen.PENCIL.penName] ?: return,
                        onChangeSetting = { onChangeStrokeSetting(Pen.PENCIL.penName, it) },
                    )

                    PenToolbarButton(
                        onStrokeMenuOpenChange = { state.isDrawing = !it },
                        pen = Pen.BRUSH,
                        icon = R.drawable.brush,
                        isSelected = isSelected(state, Pen.BRUSH),
                        onSelect = { handleChangePen(Pen.BRUSH) }, // Neo-tool! Usage not recommended
                        sizes = listOf("S" to 3f, "M" to 5f, "L" to 10f, "XL" to 20f),
                        penSetting = state.penSettings[Pen.BRUSH.penName] ?: return,
                        onChangeSetting = { onChangeStrokeSetting(Pen.BRUSH.penName, it) },
                    )
                }
                PenToolbarButton(
                    onStrokeMenuOpenChange = { state.isDrawing = !it },
                    pen = Pen.FOUNTAIN,
                    icon = R.drawable.fountain,
                    isSelected = isSelected(state, Pen.FOUNTAIN),
                    onSelect = { handleChangePen(Pen.FOUNTAIN) },// Neo-tool! Usage not recommended
                    sizes = listOf("S" to 3f, "M" to 5f, "L" to 10f, "XL" to 20f),
                    penSetting = state.penSettings[Pen.FOUNTAIN.penName] ?: return,
                    onChangeSetting = { onChangeStrokeSetting(Pen.FOUNTAIN.penName, it) },
                )

                LineToolbarButton(
                    unSelect = { state.mode = Mode.Draw },
                    icon = R.drawable.line,
                    isSelected = state.mode == Mode.Line,
                    onSelect = { handleLine() },
                )

                Box(
                    Modifier
                        .fillMaxHeight()
                        .width(0.5.dp)
                        .background(Color.Black)
                )

                PenToolbarButton(
                    onStrokeMenuOpenChange = { state.isDrawing = !it },
                    pen = Pen.MARKER,
                    icon = R.drawable.marker,
                    isSelected = isSelected(state, Pen.MARKER),
                    onSelect = { handleChangePen(Pen.MARKER) },
                    sizes = listOf("L" to 40f, "XL" to 60f),
                    penSetting = state.penSettings[Pen.MARKER.penName] ?: return,
                    onChangeSetting = { onChangeStrokeSetting(Pen.MARKER.penName, it) })
                Box(
                    Modifier
                        .fillMaxHeight()
                        .width(0.5.dp)
                        .background(Color.Black)
                )
                EraserToolbarButton(
                    isSelected = state.mode == Mode.Erase,
                    onSelect = {
                        handleEraser()
                    },
                    onMenuOpenChange = { state.menuStates.isStrokeSelectionOpen = it },
                    value = state.eraser,
                    onChange = { state.eraser = it })
                Box(
                    Modifier
                        .fillMaxHeight()
                        .width(0.5.dp)
                        .background(Color.Black)
                )
                ToolbarButton(
                    isSelected = state.mode == Mode.Select,
                    onSelect = { handleSelection() },
                    iconId = R.drawable.lasso,
                    contentDescription = "lasso"
                )
                Box(
                    Modifier
                        .fillMaxHeight()
                        .width(0.5.dp)
                        .background(Color.Black)
                )

                ToolbarButton(
                    iconId = R.drawable.image,
                    contentDescription = "library",
                    onSelect = {
                        // Call insertImage when the button is tapped
                        toolbarLog.i("Launching image picker...")
                        pickMedia.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly))
                    }
                )
                Box(
                    Modifier
                        .fillMaxHeight()
                        .width(0.5.dp)
                        .background(Color.Black)
                )

                if (state.clipboard != null) {
                    ToolbarButton(
                        vectorIcon = FeatherIcons.Clipboard,
                        contentDescription = "paste",
                        onSelect = {
                            controlTower.pasteFromClipboard()
                        }
                    )
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .width(0.5.dp)
                            .background(Color.Black)
                    )
                }

                Spacer(Modifier.weight(1f))

                Box(
                    Modifier
                        .fillMaxHeight()
                        .width(0.5.dp)
                        .background(Color.Black)
                )

                ToolbarButton(
                    onSelect = {
                        scope.launch {
                            History.moveHistory(UndoRedoType.Undo)
                            DrawCanvas.refreshUi.emit(Unit)
                        }
                    },
                    iconId = R.drawable.undo,
                    contentDescription = "undo"
                )

                ToolbarButton(
                    onSelect = {
                        scope.launch {
                            History.moveHistory(UndoRedoType.Redo)
                            DrawCanvas.refreshUi.emit(Unit)
                        }
                    },
                    iconId = R.drawable.redo,
                    contentDescription = "redo"
                )

                Box(
                    Modifier
                        .fillMaxHeight()
                        .width(0.5.dp)
                        .background(Color.Black)
                )

                if (state.bookId != null) {
                    var book by remember { mutableStateOf<Notebook?>(null) }
                    
                    LaunchedEffect(state.bookId) {
                        withContext(Dispatchers.IO) {
                            book = AppRepository(context).bookRepository.getById(state.bookId)
                        }
                    }

                    book?.let { notebook ->
                        // TODO maybe have generic utils for this ?
                        val pageNumber = notebook.pageIds.indexOf(state.pageId) + 1
                        val totalPageNumber = notebook.pageIds.size

                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .height(35.dp)
                                .padding(10.dp, 0.dp)
                        ) {
                            Text(
                                text = "${pageNumber}/${totalPageNumber}",
                                fontWeight = FontWeight.Light,
                                modifier = Modifier.noRippleClickable {
                                    navController.navigate("books/${state.bookId}/pages")
                                },
                                textAlign = TextAlign.Center
                            )
                        }
                        Box(
                            Modifier
                                .fillMaxHeight()
                                .width(0.5.dp)
                                .background(Color.Black)
                        )
                    }
                }
                // Add Library Button
                ToolbarButton(
                    iconId = R.drawable.home, // Replace with your library icon resource
                    contentDescription = "library",
                    onSelect = {
                        navController.navigate("library") // Navigate to main library
                    }
                )
                Box(
                    Modifier
                        .fillMaxHeight()
                        .width(0.5.dp)
                        .background(Color.Black)
                )
                Column {
                    ToolbarButton(
                        onSelect = {
                            state.menuStates.isMenuOpen = !state.menuStates.isMenuOpen
                        }, iconId = R.drawable.menu, contentDescription = "menu"
                    )
                    if (state.menuStates.isMenuOpen) ToolbarMenu(
                        navController = navController,
                        state = state,
                        onClose = { state.menuStates.isMenuOpen = false },
                        onPageSettingsOpen = {
                            toolbarLog.i("Opening page settings modal")
                            state.menuStates.isPageSettingsModalOpen = true
                        })
                }
            }

            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Color.Black)
            )
        }
    } else {
        ToolbarButton(
            onSelect = { state.isToolbarOpen = true },
            iconId = presentlyUsedToolIcon(state.mode, state.pen),
            penColor = if (state.mode != Mode.Erase) state.penSettings[state.pen.penName]?.color?.let {
                Color(
                    it
                )
            } else null,
            contentDescription = "open toolbar",
            modifier = Modifier
                .height((BUTTON_SIZE + 51).dp)
                .padding(bottom = 50.dp)
        )
    }
}