package com.ethran.notable.classes

import android.content.Context
import com.ethran.notable.db.BookRepository
import com.ethran.notable.db.FolderRepository
import com.ethran.notable.db.ImageRepository
import com.ethran.notable.db.KvProxy
import com.ethran.notable.db.KvRepository
import com.ethran.notable.db.Page
import com.ethran.notable.db.PageRepository
import com.ethran.notable.db.StrokeRepository
import com.onyx.android.sdk.extension.isNotNull
import java.util.Date
import java.util.UUID


class AppRepository(context: Context) {
    val context = context
    val bookRepository = BookRepository(context)
    val pageRepository = PageRepository(context)
    val strokeRepository = StrokeRepository(context)
    val imageRepository = ImageRepository(context)
    val folderRepository = FolderRepository(context)
    val kvRepository = KvRepository(context)
    val kvProxy = KvProxy(context)

    fun getNextPageIdFromBookAndPageOrCreate(
        notebookId: String,
        pageId: String
    ): String {
        val index = getNextPageIdFromBookAndPage(notebookId, pageId)
        if (index.isNotNull())
            return index
        val book = bookRepository.getById(notebookId = notebookId)
        // creating a new page
        val page = Page(
            notebookId = notebookId,
            background = book!!.defaultNativeTemplate,
            backgroundType = "native"
        )
        pageRepository.create(page)
        bookRepository.addPage(notebookId, page.id)
        return page.id
    }

    fun getNextPageIdFromBookAndPage(
        notebookId: String,
        pageId: String
    ): String? {
        val book = bookRepository.getById(notebookId = notebookId)
        val pages = book!!.pageIds
        val index = pages.indexOf(pageId)
        if (index == pages.size - 1)
            return null
        return pages[index + 1]
    }

    fun getPreviousPageIdFromBookAndPage(
        notebookId: String,
        pageId: String
    ): String? {
        val book = bookRepository.getById(notebookId = notebookId)
        val pages = book!!.pageIds
        val index = pages.indexOf(pageId)
        if (index == 0 || index == -1) {
            return null
        }
        return pages[index - 1]
    }

    fun duplicatePage(pageId: String) {
        val pageWithStrokes = pageRepository.getWithStrokeById(pageId)
        val pageWithImages = pageRepository.getWithImageById(pageId)
        val duplicatedPage = pageWithStrokes.page.copy(
            id = UUID.randomUUID().toString(),
            scroll = 0,
            createdAt = Date(),
            updatedAt = Date()
        )
        pageRepository.create(duplicatedPage)
        strokeRepository.create(pageWithStrokes.strokes.map {
            it.copy(
                id = UUID.randomUUID().toString(),
                pageId = duplicatedPage.id,
                updatedAt = Date(),
                createdAt = Date()
            )
        })
        imageRepository.create(pageWithImages.images.map {
            it.copy(
                id = UUID.randomUUID().toString(),
                pageId = duplicatedPage.id,
                updatedAt = Date(),
                createdAt = Date()
            )
        })
        if (pageWithStrokes.page.notebookId != null) {
            val book = bookRepository.getById(pageWithStrokes.page.notebookId) ?: return
            val pageIndex = book.pageIds.indexOf(pageWithStrokes.page.id)
            if (pageIndex == -1) return
            val pageIds = book.pageIds.toMutableList()
            pageIds.add(pageIndex + 1, duplicatedPage.id)
            bookRepository.update(book.copy(pageIds = pageIds))
        }
        if (pageWithImages.page.notebookId != null) {
            val book = bookRepository.getById(pageWithImages.page.notebookId) ?: return
            val pageIndex = book.pageIds.indexOf(pageWithImages.page.id)
            if (pageIndex == -1) return
            val pageIds = book.pageIds.toMutableList()
            pageIds.add(pageIndex + 1, duplicatedPage.id)
            bookRepository.update(book.copy(pageIds = pageIds))
        }
    }

    // Sync support methods
    fun getAllNotebooks(): List<com.ethran.notable.db.Notebook> {
        return bookRepository.getAllNotebooks()
    }

    fun getNotebookById(notebookId: String): com.ethran.notable.db.Notebook? {
        return bookRepository.getById(notebookId)
    }

    fun getPageById(pageId: String): Page? {
        return pageRepository.getById(pageId)
    }

    fun getStrokesByPageId(pageId: String): List<com.ethran.notable.db.Stroke> {
        return strokeRepository.getAllByPageId(pageId)
    }

    fun getImagesByPageId(pageId: String): List<com.ethran.notable.db.Image> {
        return imageRepository.getAllByPageId(pageId)
    }

    fun getFolderById(folderId: String): com.ethran.notable.db.Folder? {
        return folderRepository.get(folderId)
    }

    fun insertNotebook(notebook: com.ethran.notable.db.Notebook) {
        bookRepository.createEmpty(notebook)
    }

    fun updateNotebook(notebook: com.ethran.notable.db.Notebook) {
        bookRepository.update(notebook)
    }

    fun upsertPage(page: Page) {
        val existing = pageRepository.getById(page.id)
        if (existing != null) {
            pageRepository.update(page)
        } else {
            pageRepository.create(page)
        }
    }

    fun insertStroke(stroke: com.ethran.notable.db.Stroke) {
        strokeRepository.create(stroke)
    }

    fun insertImage(image: com.ethran.notable.db.Image) {
        imageRepository.create(image)
    }

    fun deleteStrokesByPageId(pageId: String) {
        strokeRepository.deleteByPageId(pageId)
    }

    fun deleteImagesByPageId(pageId: String) {
        imageRepository.deleteByPageId(pageId)
    }
    
    // Clear all data methods for sync functionality
    fun deleteAllStrokes() {
        // Deleting notebooks will cascade to pages and their strokes
        // But to be explicit, we'll delete strokes first
        val allNotebooks = bookRepository.getAllNotebooks()
        allNotebooks.forEach { notebook ->
            notebook.pageIds.forEach { pageId ->
                strokeRepository.deleteByPageId(pageId)
            }
        }
    }
    
    fun deleteAllImages() {
        // Deleting notebooks will cascade to pages and their images
        // But to be explicit, we'll delete images first
        val allNotebooks = bookRepository.getAllNotebooks()
        allNotebooks.forEach { notebook ->
            notebook.pageIds.forEach { pageId ->
                imageRepository.deleteByPageId(pageId)
            }
        }
    }
    
    fun deleteAllPages() {
        // Deleting notebooks will cascade to pages
        // But to be explicit, we'll delete pages first
        val allNotebooks = bookRepository.getAllNotebooks()
        allNotebooks.forEach { notebook ->
            notebook.pageIds.forEach { pageId ->
                pageRepository.delete(pageId)
            }
        }
    }
    
    fun deleteAllNotebooks() {
        // Delete all notebooks - this should cascade to pages, strokes, images
        val allNotebooks = bookRepository.getAllNotebooks()
        allNotebooks.forEach { notebook ->
            bookRepository.delete(notebook.id)
        }
    }
    
    fun deleteAllFolders() {
        // For simplicity, we'll skip folder deletion for now
        // Room database foreign key constraints should handle
        // cleanup when notebooks are deleted
        // TODO: Implement proper folder cleanup if needed
    }
}
