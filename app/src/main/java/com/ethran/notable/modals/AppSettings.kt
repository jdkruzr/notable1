package com.ethran.notable.modals

import androidx.compose.runtime.mutableStateOf
import kotlinx.serialization.Serializable


// Define the target page size (A4 in points: 595 x 842)
const val A4_WIDTH = 595
const val A4_HEIGHT = 842
const val BUTTON_SIZE = 37


object GlobalAppSettings {
    private val _current = mutableStateOf(AppSettings(version = 1))
    val current: AppSettings
        get() = _current.value

    fun update(settings: AppSettings) {
        _current.value = settings
    }
}


@Serializable
data class AppSettings(
    val version: Int,
    val showWelcome: Boolean = true,
    val defaultNativeTemplate: String = "blank",
    val quickNavPages: List<String> = listOf(),
    val debugMode: Boolean = false,
    val neoTools: Boolean = false,
    val toolbarPosition: Position = Position.Top,
    val smoothScroll: Boolean = true,
    val monochromeMode: Boolean = false,
    val continuousZoom: Boolean = false,
    val visualizePdfPagination: Boolean = false,
    val paginatePdf: Boolean = true,
    
    // WebDAV sync settings
    val webdavSyncEnabled: Boolean = false,
    val webdavServerUrl: String = "",
    val webdavUsername: String = "",
    val webdavPassword: String = "",
    val webdavAutoSync: Boolean = true,
    val webdavSyncInterval: Int = 300, // seconds
    val webdavAutoSyncOnClose: Boolean = true, // Auto-sync when closing notebooks
    
    // Notebook creation settings
    val prefixNotebookNamesWithDate: Boolean = false,

    val doubleTapAction: GestureAction? = defaultDoubleTapAction,
    val twoFingerTapAction: GestureAction? = defaultTwoFingerTapAction,
    val swipeLeftAction: GestureAction? = defaultSwipeLeftAction,
    val swipeRightAction: GestureAction? = defaultSwipeRightAction,
    val twoFingerSwipeLeftAction: GestureAction? = defaultTwoFingerSwipeLeftAction,
    val twoFingerSwipeRightAction: GestureAction? = defaultTwoFingerSwipeRightAction,
    val holdAction: GestureAction? = defaultHoldAction,

    ) {
    companion object {
        val defaultDoubleTapAction get() = GestureAction.Undo
        val defaultTwoFingerTapAction get() = GestureAction.ChangeTool
        val defaultSwipeLeftAction get() = GestureAction.NextPage
        val defaultSwipeRightAction get() = GestureAction.PreviousPage
        val defaultTwoFingerSwipeLeftAction get() = GestureAction.ToggleZen
        val defaultTwoFingerSwipeRightAction get() = GestureAction.ToggleZen
        val defaultHoldAction get() = GestureAction.Select
    }

    enum class GestureAction {
        Undo, Redo, PreviousPage, NextPage, ChangeTool, ToggleZen, Select
    }

    enum class Position {
        Top, Bottom, // Left,Right,
    }
}