package com.ethran.notable.components

import androidx.compose.foundation.layout.Row
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
import com.ethran.notable.db.Folder
import com.ethran.notable.db.FolderRepository
import com.ethran.notable.utils.noRippleClickable
import compose.icons.FeatherIcons
import compose.icons.feathericons.ChevronRight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun BreadCrumb(folderId: String? = null, onSelectFolderId: (String?) -> Unit) {
    val context = LocalContext.current
    var folders by remember { mutableStateOf<List<Folder>>(emptyList()) }

    // Load folder chain asynchronously
    LaunchedEffect(folderId) {
        folders = if (folderId != null) {
            withContext(Dispatchers.IO) {
                getFolderChain(context, folderId)
            }
        } else {
            emptyList()
        }
    }

    Row {
        Text(
            text = "Library",
            textDecoration = TextDecoration.Underline,
            modifier = Modifier.noRippleClickable { onSelectFolderId(null) })
        
        folders.forEach { folder ->
            Icon(imageVector = FeatherIcons.ChevronRight, contentDescription = "")
            Text(
                text = folder.title,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier.noRippleClickable { onSelectFolderId(folder.id) })
        }
    }
}

private suspend fun getFolderChain(context: android.content.Context, folderId: String): List<Folder> {
    val folderRepository = FolderRepository(context)
    val chain = mutableListOf<Folder>()
    var currentFolderId: String? = folderId
    
    while (currentFolderId != null) {
        val folder = folderRepository.get(currentFolderId)
        if (folder != null) {
            chain.add(0, folder) // Add to beginning to maintain order
            currentFolderId = folder.parentFolderId
        } else {
            break
        }
    }
    
    return chain
}