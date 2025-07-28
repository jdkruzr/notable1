package com.ethran.notable.classes


import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.IntOffset
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import androidx.core.graphics.toRect
import androidx.core.graphics.withClip
import com.ethran.notable.SCREEN_HEIGHT
import com.ethran.notable.SCREEN_WIDTH
import com.ethran.notable.db.AppDatabase
import com.ethran.notable.db.BackgroundType
import com.ethran.notable.db.Image
import com.ethran.notable.db.Page
import com.ethran.notable.db.Stroke
import com.ethran.notable.db.getBackgroundType
import com.ethran.notable.modals.GlobalAppSettings
import com.ethran.notable.utils.drawBg
import com.ethran.notable.utils.drawImage
import com.ethran.notable.utils.drawStroke
import com.ethran.notable.utils.imageBounds
import com.ethran.notable.utils.logCallStack
import com.ethran.notable.utils.strokeBounds
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.Date
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.path.Path
import kotlin.math.abs
import kotlin.math.max
import kotlin.system.measureTimeMillis

class PageView(
    val context: Context,
    val coroutineScope: CoroutineScope,
    val id: String,
    val width: Int,
    var viewWidth: Int,
    var viewHeight: Int
) {
    val log = ShipBook.getLogger("PageView")
    private val logCache = ShipBook.getLogger("PageViewCache")

    private var loadingJob: Job? = null

    private var snack: SnackConf? = null

    var windowedBitmap = createBitmap(viewWidth, viewHeight)
        private set
    var windowedCanvas = Canvas(windowedBitmap)
        private set

    //    var strokes = listOf<Stroke>()
    var strokes: List<Stroke>
        get() = PageDataManager.getStrokes(id)
        set(value) = PageDataManager.setStrokes(id, value)

    var images: List<Image>
        get() = PageDataManager.getImages(id)
        set(value) = PageDataManager.setImages(id, value)

    private var currentBackground: CachedBackground
        get() = PageDataManager.getBackground(id)
        set(value) {
            PageDataManager.setBackground(id, value)
        }


    var scroll by mutableIntStateOf(0) // is observed by ui
    val scrollable: Boolean
        get() = when (pageFromDb?.backgroundType) {
            "native", null -> true
            "coverImage" -> false
            else -> true
        }

    // we need to observe zoom level, to adjust strokes size.
    var zoomLevel = MutableStateFlow(1.0f)

    private val saveTopic = MutableSharedFlow<Unit>()

    var height by mutableIntStateOf(viewHeight) // is observed by ui

    var pageFromDb: Page? = null

    private var dbStrokes = AppDatabase.getDatabase(context).strokeDao()
    private var dbImages = AppDatabase.getDatabase(context).ImageDao()


    /*
        If pageNumber is -1, its assumed that the background is image type.
     */
    fun getOrLoadBackground(filePath: String, pageNumber: Int, scale: Float): Bitmap? {
        if (!currentBackground.matches(filePath, pageNumber, scale))
            currentBackground = CachedBackground(filePath, pageNumber, scale)
        return currentBackground.bitmap
    }

    fun getBackgroundPageNumber(): Int {
        // There might be a bug here -- check it again.
        return currentBackground.pageNumber
    }


    init {
        PageDataManager.setPage(id)
        log.i("PageView init")
        
        // Load page data first
        coroutineScope.launch {
            withContext(Dispatchers.IO) {
                pageFromDb = AppRepository(context).pageRepository.getById(id)
            }
            
            // Now initialize bitmap after page is loaded
            PageDataManager.getCachedBitmap(id)?.let { cached ->
                log.i("PageView: using cached bitmap")
                windowedBitmap = cached
                windowedCanvas = Canvas(windowedBitmap)
            } ?: run {
                log.i("PageView: creating new bitmap")
                windowedBitmap = createBitmap(viewWidth, viewHeight)
                windowedCanvas = Canvas(windowedBitmap)
                launch {
                    // Load background immediately so users see the page
                    loadInitialBitmap()
                    PageDataManager.cacheBitmap(id, windowedBitmap)
                    
                    // Force immediate UI update to show the background
                    withContext(Dispatchers.Main) {
                        DrawCanvas.forceUpdate.emit(
                            Rect(0, 0, windowedCanvas.width, windowedCanvas.height)
                        )
                    }
                }
            }
            
            // Start loading page data immediately in background
            launch {
                loadPage()
            }
            saveTopic.debounce(1000).collect {
                launch { persistBitmap() }
                launch { persistBitmapThumbnail() }
            }
        }
    }

    /*
        Cancel loading strokes, and save bitmap to disk
    */
    fun disposeOldPage() {
        PageDataManager.setPageHeight(id, computeHeight())
        PageDataManager.calculateMemoryUsage(id, 0)
        logCache.d("disposeOldPage, ${loadingJob?.isActive}")
        if (loadingJob?.isActive != true) {
            // only if job is not active or it's false
            coroutineScope.launch {
                persistBitmap()
                persistBitmapThumbnail()
            }
            // TODO: if we exited the book, we should clear the cache.
        }
        cleanJob()
    }

    // Loads all the strokes on page
    private fun loadFromPersistLayer() {
        logCache.i("Init from persist layer, pageId: $id")
        loadingJob = coroutineScope.launch(Dispatchers.IO) {
            try {
                // Set duration as safety guard: in 60 s all strokes should be loaded
                snack = SnackConf(text = "Loading strokes...", duration = 60000)
                SnackState.globalSnackFlow.emit(snack!!)
                PageDataManager.awaitPageIfLoading(id)
                val timeToLoad = measureTimeMillis {
                    getPageData(id)
                    PageDataManager.dataLoadingJob?.join()
                    logCache.d("got page data. id $id")
                    height = computeHeight()
                }
                logCache.d("All strokes loaded in $timeToLoad ms")
            } finally {
                snack?.let { SnackState.cancelGlobalSnack.emit(it.id) }
                coroutineScope.launch(Dispatchers.Main.immediate) {
                    DrawCanvas.forceUpdate.emit(
                        Rect(
                            0,
                            0,
                            windowedCanvas.width,
                            windowedCanvas.height
                        )
                    )
                }

                logCache.d("Loaded page from persistent layer $id")
            }
        }
    }


    private fun isPageCached(pageId: String): Boolean {
        return PageDataManager.isPageLoaded(pageId)
    }

    private fun getPageData(pageId: String) {
        if (isPageCached(pageId)) {
            logCache.d("getPageData: Page already in memory")
            return
        }
        PageDataManager.dataLoadingJob = PageDataManager.dataLoadingScope.launch {
            if (PageDataManager.isPageLoading(pageId)) {
                logCallStack("Double loading of the same page")
                return@launch
            }
            try {
                PageDataManager.markPageLoading(pageId)
                logCache.d("Loading page $pageId")
//        sleep(5000)
                // Load strokes progressively in batches for better UX
                logCache.d("Loading strokes progressively for page $pageId")
                val dbStartTime = System.currentTimeMillis()
                val pageWithStrokes =
                    AppRepository(context).pageRepository.getWithStrokeByIdSuspend(pageId)
                val dbDuration = System.currentTimeMillis() - dbStartTime
                logCache.d("Database query completed in ${dbDuration}ms, found ${pageWithStrokes.strokes.size} strokes")
                
                // Load strokes in batches of 50 and render each batch immediately
                val allStrokes = pageWithStrokes.strokes
                val batchSize = 50
                var loadedStrokes = mutableListOf<Stroke>()
                
                for (i in allStrokes.indices step batchSize) {
                    val batch = allStrokes.subList(i, minOf(i + batchSize, allStrokes.size))
                    loadedStrokes.addAll(batch)
                    
                    // Cache the current batch
                    PageDataManager.cacheStrokes(pageId, loadedStrokes.toList())
                    
                    logCache.d("Loaded stroke batch ${i/batchSize + 1}/${(allStrokes.size + batchSize - 1)/batchSize}, total strokes: ${loadedStrokes.size}")
                    
                    // Only trigger UI update every few batches to prevent drawing conflicts
                    val batchNumber = i/batchSize + 1
                    val totalBatches = (allStrokes.size + batchSize - 1)/batchSize
                    if (batchNumber % 2 == 0 || batchNumber == totalBatches) {
                        try {
                            coroutineScope.launch(Dispatchers.Main.immediate) {
                                DrawCanvas.forceUpdate.emit(
                                    Rect(0, 0, windowedCanvas.width, windowedCanvas.height)
                                )
                            }
                        } catch (e: Exception) {
                            logCache.e("Error updating UI for stroke batch: ${e.message}")
                        }
                    }
                    
                    // Small delay to prevent blocking the main thread
                    if (i + batchSize < allStrokes.size) {
                        kotlinx.coroutines.delay(50) // Increased delay for stability
                    }
                }
                val pageWithImages = AppRepository(context).pageRepository.getWithImageById(pageId)
                PageDataManager.cacheImages(pageId, pageWithImages.images)
                PageDataManager.setPageHeight(pageId, computeHeight())
                PageDataManager.indexImages(coroutineScope, pageId)
                PageDataManager.indexStrokes(coroutineScope, pageId)
                PageDataManager.calculateMemoryUsage(pageId, 1)
            } catch (e: CancellationException) {
                logCache.w("Loading of page $pageId was cancelled.")
                if (!PageDataManager.isPageLoaded(pageId))
                    PageDataManager.removePage(pageId)
                throw e  // rethrow cancellation
            } finally {
                PageDataManager.markPageLoaded(pageId)
                logCache.d("Loaded page $pageId")
            }
        }
    }


    private fun redrawAll(scope: CoroutineScope) {
        scope.launch(Dispatchers.Main.immediate) {
            val viewRectangle = Rect(0, 0, windowedCanvas.width, windowedCanvas.height)
            drawAreaScreenCoordinates(viewRectangle)
        }
    }

    private suspend fun loadPage() {
        val page = withContext(Dispatchers.IO) {
            AppRepository(context).pageRepository.getById(id)
        }
        if (page == null) {
            log.e("Page not found in database")
            return
        }
        scroll = page.scroll
        val isInCache = PageDataManager.isPageLoaded(id)
        if (isInCache) {
            logCache.i("Page loaded from cache")
            height = PageDataManager.getPageHeight(id) ?: viewHeight //TODO: correct
            redrawAll(coroutineScope)
            coroutineScope.launch(Dispatchers.Main.immediate) {
                DrawCanvas.forceUpdate.emit(
                    Rect(
                        0,
                        0,
                        windowedCanvas.width,
                        windowedCanvas.height
                    )
                )
            }
        } else {
            logCache.i("Page not found in cache")
            // If cache is incomplete, load from persistent storage
            PageDataManager.ensureMemoryAvailable(15)
            loadFromPersistLayer()
        }
        PageDataManager.reduceCache(20)
        cacheNeighbors()
    }

    private suspend fun cacheNeighbors() {

        // Only attempt to cache neighbors if we have memory to spare.
        if (!PageDataManager.hasEnoughMemory(15)) return
        val appRepository = AppRepository(context)
        val bookId = pageFromDb?.notebookId ?: return
        try {
            // Cache next page if not already cached
            val nextPageId =
                appRepository.getNextPageIdFromBookAndPage(pageId = id, notebookId = bookId)
            logCache.d("Caching next page $nextPageId")

            nextPageId?.let { nextPage ->
                getPageData(nextPage)
            }
            if (PageDataManager.hasEnoughMemory(15)) {
                // Cache previous page if not already cached
                val prevPageId =
                    appRepository.getPreviousPageIdFromBookAndPage(
                        pageId = id,
                        notebookId = bookId
                    )
                logCache.d("Caching prev page $prevPageId")

                prevPageId?.let { prevPage ->
                    getPageData(prevPage)
                }
            }
        } catch (e: CancellationException) {
            logCache.i("Caching was cancelled: ${e.message}")
        } catch (e: Exception) {
            // All other unexpected exceptions
            logCache.e("Error caching neighbor pages", e)
            showHint("Error encountered while caching neighbors", duration = 5000)

        }

    }

    fun addStrokes(strokesToAdd: List<Stroke>) {
        strokes += strokesToAdd
        strokesToAdd.forEach {
            val bottomPlusPadding = it.bottom + 50
            if (bottomPlusPadding > height) height = bottomPlusPadding.toInt()
        }

        saveStrokesToPersistLayer(strokesToAdd)
        PageDataManager.indexStrokes(coroutineScope, id)
        
        // Update page timestamp for sync
        updatePageTimestamp()

        persistBitmapDebounced()
    }

    fun removeStrokes(strokeIds: List<String>) {
        strokes = strokes.filter { s -> !strokeIds.contains(s.id) }
        removeStrokesFromPersistLayer(strokeIds)
        PageDataManager.indexStrokes(coroutineScope, id)
        height = computeHeight()
        
        // Update page timestamp for sync
        updatePageTimestamp()

        persistBitmapDebounced()
    }

    fun getStrokes(strokeIds: List<String>): List<Stroke?> {
        return PageDataManager.getStrokes(strokeIds, id)
    }

    private fun saveStrokesToPersistLayer(strokes: List<Stroke>) {
        coroutineScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    dbStrokes.create(strokes)
                    log.d("Successfully created ${strokes.size} strokes")
                } catch (e: Exception) {
                    // If create fails due to existing strokes, try individual upsert
                    log.w("Batch stroke create failed (${e.message}), attempting individual upsert")
                    strokes.forEach { stroke ->
                        try {
                            // Check if stroke exists by trying to get it
                            val existingStrokes = dbStrokes.getAllByPageId(stroke.pageId)
                            val exists = existingStrokes.any { it.id == stroke.id }
                            
                            if (exists) {
                                // Stroke exists, update it
                                dbStrokes.update(stroke)
                                log.d("Updated existing stroke ${stroke.id}")
                            } else {
                                // Stroke doesn't exist, create it
                                dbStrokes.create(stroke)
                                log.d("Created new stroke ${stroke.id}")
                            }
                        } catch (individualEx: Exception) {
                            log.e("Failed to save individual stroke ${stroke.id}: ${individualEx.message}")
                        }
                    }
                }
            }
        }
    }

    private fun saveImagesToPersistLayer(image: List<Image>) {
        coroutineScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    dbImages.create(image)
                    log.d("Successfully created ${image.size} images")
                } catch (e: Exception) {
                    // If create fails due to existing images, try individual upsert
                    log.w("Batch create failed (${e.message}), attempting individual upsert")
                    image.forEach { img ->
                        try {
                            // Check if image exists by trying to get it
                            val existingImages = dbImages.getAllByPageId(img.pageId)
                            val exists = existingImages.any { it.id == img.id }
                            
                            if (exists) {
                                // Image exists, update it
                                dbImages.update(img)
                                log.d("Updated existing image ${img.id}")
                            } else {
                                // Image doesn't exist, create it
                                dbImages.create(img)
                                log.d("Created new image ${img.id}")
                            }
                        } catch (individualEx: Exception) {
                            log.e("Failed to save individual image ${img.id}: ${individualEx.message}")
                        }
                    }
                }
            }
        }
    }


    fun addImage(imageToAdd: Image) {
        images += listOf(imageToAdd)
        val bottomPlusPadding = imageToAdd.x + imageToAdd.height + 50
        if (bottomPlusPadding > height) height = bottomPlusPadding

        saveImagesToPersistLayer(listOf(imageToAdd))
        PageDataManager.indexImages(coroutineScope, id)
        
        // Update page timestamp for sync
        updatePageTimestamp()

        persistBitmapDebounced()
    }

    fun addImage(imageToAdd: List<Image>) {
        images += imageToAdd
        imageToAdd.forEach {
            val bottomPlusPadding = it.x + it.height + 50
            if (bottomPlusPadding > height) height = bottomPlusPadding
        }
        saveImagesToPersistLayer(imageToAdd)
        PageDataManager.indexImages(coroutineScope, id)
        
        // Update page timestamp for sync
        updatePageTimestamp()

        persistBitmapDebounced()
    }
    
    fun updateImages(imagesToUpdate: List<Image>) {
        // Update images in memory
        val imageMap = imagesToUpdate.associateBy { it.id }
        images = images.map { existing ->
            imageMap[existing.id] ?: existing
        }
        
        // Update height calculation
        imagesToUpdate.forEach {
            val bottomPlusPadding = it.x + it.height + 50
            if (bottomPlusPadding > height) height = bottomPlusPadding
        }
        
        // Update in database
        updateImagesPersistLayer(imagesToUpdate)
        PageDataManager.indexImages(coroutineScope, id)
        
        // Update page timestamp for sync
        updatePageTimestamp()

        persistBitmapDebounced()
    }
    
    private fun updateImagesPersistLayer(images: List<Image>) {
        coroutineScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    images.forEach { img ->
                        dbImages.update(img)
                        log.d("Updated image position ${img.id}")
                    }
                } catch (e: Exception) {
                    log.e("Failed to update images: ${e.message}")
                }
            }
        }
    }

    fun removeImages(imageIds: List<String>) {
        images = images.filter { s -> !imageIds.contains(s.id) }
        removeImagesFromPersistLayer(imageIds)
        PageDataManager.indexImages(coroutineScope, id)
        height = computeHeight()
        
        // Update page timestamp for sync
        updatePageTimestamp()

        persistBitmapDebounced()
    }

    fun getImage(imageId: String): Image? = PageDataManager.getImage(imageId, id)


    fun getImages(imageIds: List<String>): List<Image?> = PageDataManager.getImages(imageIds, id)


    private fun computeHeight(): Int {
        if (strokes.isEmpty()) {
            return viewHeight
        }
        val maxStrokeBottom = strokes.maxOf { it.bottom }.plus(50)
        return max(maxStrokeBottom.toInt(), viewHeight)
    }

    fun computeWidth(): Int {
        if (strokes.isEmpty()) {
            return viewWidth
        }
        val maxStrokeRight = strokes.maxOf { it.right }.plus(50)
        return max(maxStrokeRight.toInt(), viewWidth)
    }

    private fun removeStrokesFromPersistLayer(strokeIds: List<String>) {
        coroutineScope.launch {
            withContext(Dispatchers.IO) {
                AppRepository(context).strokeRepository.deleteAll(strokeIds)
            }
        }
    }

    private fun removeImagesFromPersistLayer(imageIds: List<String>) {
        coroutineScope.launch {
            withContext(Dispatchers.IO) {
                AppRepository(context).imageRepository.deleteAll(imageIds)
            }
        }
    }

    private suspend fun loadInitialBitmap(): Boolean = withContext(Dispatchers.IO) {
        val imgFile = File(context.filesDir, "pages/previews/full/$id")
        val imgBitmap: Bitmap?
        if (imgFile.exists()) {
            imgBitmap = BitmapFactory.decodeFile(imgFile.absolutePath)
            if (imgBitmap != null) {
                withContext(Dispatchers.Main) {
                    windowedCanvas.drawBitmap(imgBitmap, 0f, 0f, Paint())
                    log.i("Initial Bitmap for page rendered from cache")
                }
                // let's control that the last preview fits the present orientation. Otherwise we'll ask for a redraw.
                if (imgBitmap.height == windowedCanvas.height && imgBitmap.width == windowedCanvas.width) {
                    return@withContext true
                } else {
                    log.i("Image preview does not fit canvas area - redrawing")
                }
            } else {
                log.i("Cannot read cache image")
            }
        } else {
            log.i("Cannot find cache image")
        }
        // draw just background.
        withContext(Dispatchers.Main) {
            drawBg(
                context, windowedCanvas, pageFromDb?.getBackgroundType() ?: BackgroundType.Native,
                pageFromDb?.background ?: "blank", scroll, 1f, this@PageView
            )
        }
        return@withContext false
    }

    private suspend fun persistBitmap() = withContext(Dispatchers.IO) {
        val file = File(context.filesDir, "pages/previews/full/$id")
        Files.createDirectories(Path(file.absolutePath).parent)
        val os = BufferedOutputStream(FileOutputStream(file))
        windowedBitmap.compress(Bitmap.CompressFormat.PNG, 100, os)
        os.close()
    }

    private suspend fun persistBitmapThumbnail() = withContext(Dispatchers.IO) {
        val file = File(context.filesDir, "pages/previews/thumbs/$id")
        Files.createDirectories(Path(file.absolutePath).parent)
        val os = BufferedOutputStream(FileOutputStream(file))
        val ratio = windowedBitmap.height.toFloat() / windowedBitmap.width.toFloat()
        windowedBitmap.scale(500, (500 * ratio).toInt(), false)
            .compress(Bitmap.CompressFormat.JPEG, 80, os)
        os.close()
    }

    private fun cleanJob() {
        //ensure that snack is canceled, even on dispose of the page.
        CoroutineScope(Dispatchers.IO).launch {
            snack?.let { SnackState.cancelGlobalSnack.emit(it.id) }
            PageDataManager.removeMarkPageLoaded(id)
        }
        loadingJob?.cancel()
        if (loadingJob?.isActive == true) {
            log.e("Strokes are still loading, trying to cancel and resume")
        }
    }


    private fun drawDebugRectWithLabels(
        canvas: Canvas,
        rect: RectF,
        rectColor: Int = Color.RED,
        labelColor: Int = Color.BLUE
    ) {
        val rectPaint = Paint().apply {
            color = rectColor
            style = Paint.Style.STROKE
            strokeWidth = 6f
        }
        log.w("Drawing debug rect $rect")
        // Draw rectangle outline
        canvas.drawRect(rect, rectPaint)

        // Setup label paint
        val labelPaint = Paint().apply {
            color = labelColor
            textAlign = Paint.Align.LEFT
            textSize = 40f
            isAntiAlias = true
        }

        // Helper to format text
        fun format(x: Float, y: Float) = "(${x.toInt()}, ${y.toInt()})"

        val topLeftLabel = format(rect.left, rect.top)
        val topRightLabel = format(rect.right, rect.top)
        val bottomLeftLabel = format(rect.left, rect.bottom)
        val bottomRightLabel = format(rect.right, rect.bottom)

        val topRightTextWidth = labelPaint.measureText(topRightLabel)
        val bottomRightTextWidth = labelPaint.measureText(bottomRightLabel)

        // Draw coordinate labels at corners
        canvas.drawText(topLeftLabel, rect.left + 8f, rect.top + labelPaint.textSize, labelPaint)
        canvas.drawText(
            topRightLabel,
            rect.right - topRightTextWidth - 8f,
            rect.top + labelPaint.textSize,
            labelPaint
        )
        canvas.drawText(bottomLeftLabel, rect.left + 8f, rect.bottom - 8f, labelPaint)
        canvas.drawText(
            bottomRightLabel,
            rect.right - bottomRightTextWidth - 8f,
            rect.bottom - 8f,
            labelPaint
        )
    }


    fun drawAreaPageCoordinates(
        pageArea: Rect, // in page coordinates
        ignoredStrokeIds: List<String> = listOf(),
        ignoredImageIds: List<String> = listOf(),
        canvas: Canvas? = null
    ) {
        val areaInScreen = toScreenCoordinates(pageArea)
        drawAreaScreenCoordinates(areaInScreen, ignoredStrokeIds, ignoredImageIds, canvas)
    }

    /*
        provided a rectangle, in screen coordinates, its check
        for all images intersecting it, excluding ones set to be ignored,
        and redraws them.
     */
    fun drawAreaScreenCoordinates(
        screenArea: Rect,
        ignoredStrokeIds: List<String> = listOf(),
        ignoredImageIds: List<String> = listOf(),
        canvas: Canvas? = null
    ) {
        // TODO: make sure that rounding errors are not happening
        val activeCanvas = canvas ?: windowedCanvas
        val pageArea = toPageCoordinates(screenArea)
        val pageAreaWithoutScroll = removeScroll(pageArea)

        // Canvas is scaled, it will scale page area.
        activeCanvas.withClip(pageAreaWithoutScroll) {
            drawColor(Color.BLACK)

            val timeToDraw = measureTimeMillis {
                drawBg(
                    context, this, pageFromDb?.getBackgroundType() ?: BackgroundType.Native,
                    pageFromDb?.background ?: "blank", scroll, zoomLevel.value, this@PageView
                )
                if (GlobalAppSettings.current.debugMode) {
                    drawDebugRectWithLabels(activeCanvas, RectF(pageAreaWithoutScroll), Color.BLACK)
//                    drawDebugRectWithLabels(activeCanvas, RectF(screenArea))
                }
                // Trying to find what throws error when drawing quickly
                try {
                    // Create a copy to avoid ConcurrentModificationException during progressive loading
                    val imagesCopy = images.toList()
                    imagesCopy.forEach { image ->
                        if (ignoredImageIds.contains(image.id)) return@forEach
                        log.i("PageView.kt: drawing image!")
                        val bounds = imageBounds(image)
                        // if stroke is not inside page section
                        if (!bounds.toRect().intersect(pageArea)) return@forEach
                        drawImage(context, this, image, IntOffset(0, -scroll))

                    }
                } catch (e: Exception) {
                    log.e("PageView.kt: Drawing images failed: ${e.message}", e)

                    val errorMessage =
                        if (e.message?.contains("does not have permission") == true) {
                            "Permission error: Unable to access image."
                        } else {
                            "Failed to load images."
                        }
                    showHint(errorMessage, coroutineScope)
                }
                try {
                    // Create a copy to avoid ConcurrentModificationException during progressive loading
                    val strokesCopy = strokes.toList()
                    strokesCopy.forEach { stroke ->
                        if (ignoredStrokeIds.contains(stroke.id)) return@forEach
                        val bounds = strokeBounds(stroke)
                        // if stroke is not inside page section
                        if (!bounds.toRect().intersect(pageArea)) return@forEach

                        drawStroke(
                            this, stroke, IntOffset(0, -scroll)
                        )
                    }
                } catch (e: Exception) {
                    log.e("PageView.kt: Drawing strokes failed: ${e.message}", e)
                    showHint("Error drawing strokes", coroutineScope)
                }

            }
            log.i("Drew area in ${timeToDraw}ms")
        }
    }

    @Suppress("unused")
    suspend fun simpleUpdateScroll(dragDelta: Int) {
        // Just update scroll, for debugging.
        log.d("Simple update scroll")
        var delta = (dragDelta / zoomLevel.value).toInt()
        if (scroll + delta < 0) delta = 0 - scroll

        DrawCanvas.waitForDrawingWithSnack()

        scroll += delta

        val redrawRect = Rect(
            0, 0, SCREEN_WIDTH, SCREEN_HEIGHT
        )
        val scrolledBitmap = createBitmap(SCREEN_WIDTH, SCREEN_HEIGHT, windowedBitmap.config!!)

        // Swap in the new zoomed bitmap
        windowedBitmap.recycle()
        windowedBitmap = scrolledBitmap
        windowedCanvas.setBitmap(windowedBitmap)
        windowedCanvas.scale(zoomLevel.value, zoomLevel.value)
        drawAreaScreenCoordinates(redrawRect)
        persistBitmapDebounced()
        saveToPersistLayer()
        PageDataManager.cacheBitmap(id, windowedBitmap)
    }

    suspend fun updateScroll(dragDelta: Int) {
//        log.d("Update scroll, dragDelta: $dragDelta, scroll: $scroll, zoomLevel.value: $zoomLevel.value")
        // drag delta is in screen coordinates,
        // so we have to scale it back to page coordinates.
        var deltaInPageCord = (dragDelta / zoomLevel.value).toInt()
        if (scroll + deltaInPageCord < 0) deltaInPageCord = 0 - scroll

        // There is nothing to do, return.
        if (deltaInPageCord == 0) return

        // before scrolling, make sure that strokes are drawn.
        DrawCanvas.waitForDrawingWithSnack()

        scroll += deltaInPageCord
        // To avoid rounding errors, we just calculate it again.
        val movement = (deltaInPageCord * zoomLevel.value).toInt()


        // Shift the existing bitmap content
        val shiftedBitmap =
            createBitmap(windowedBitmap.width, windowedBitmap.height, windowedBitmap.config!!)
        val shiftedCanvas = Canvas(shiftedBitmap)
        shiftedCanvas.drawColor(Color.RED) //for debugging.
        shiftedCanvas.drawBitmap(windowedBitmap, 0f, -movement.toFloat(), null)

        // Swap in the shifted bitmap
        windowedBitmap.recycle() // Recycle old bitmap
        windowedBitmap = shiftedBitmap
        windowedCanvas.setBitmap(windowedBitmap)
        windowedCanvas.scale(zoomLevel.value, zoomLevel.value)

        //add 1 of overlap, to eliminate rounding errors.
        val redrawRect =
            if (deltaInPageCord > 0)
                Rect(0, SCREEN_HEIGHT - movement - 5, SCREEN_WIDTH, SCREEN_HEIGHT)
            else
                Rect(0, 0, SCREEN_WIDTH, -movement + 1)
//        windowedCanvas.drawRect(
//            removeScroll(toPageCoordinates(redrawRect)),
//            Paint().apply { color = Color.RED })

        drawAreaScreenCoordinates(redrawRect)
        persistBitmapDebounced()
        saveToPersistLayer()
    }


    private fun calculateZoomLevel(
        scaleDelta: Float,
        currentZoom: Float,
    ): Float {
        val portraitRatio = SCREEN_WIDTH.toFloat() / SCREEN_HEIGHT

        return if (!GlobalAppSettings.current.continuousZoom) {
            // Discrete zoom mode - snap to either 1.0 or screen ratio
            if (scaleDelta <= 1.0f) {
                if (SCREEN_HEIGHT > SCREEN_WIDTH) portraitRatio else 1.0f
            } else {
                if (SCREEN_HEIGHT > SCREEN_WIDTH) 1.0f else portraitRatio
            }
        } else {
            // Continuous zoom mode with snap behavior
            val newZoom = (scaleDelta / 3 + currentZoom).coerceIn(0.1f, 10.0f)

            // Snap to either 1.0 or screen ratio depending on which is closer
            val snapTarget = if (abs(newZoom - 1.0f) < abs(newZoom - portraitRatio)) {
                1.0f
            } else {
                portraitRatio
            }

            if (abs(newZoom - snapTarget) < ZOOM_SNAP_THRESHOLD) snapTarget else newZoom
        }
    }

    suspend fun updateZoom(scaleDelta: Float) {
        // TODO:
        // - Update only effected area if possible
        // - Find a better way to represent how much to zoom.
        log.d("Zoom: $scaleDelta")

        // Update the zoom factor
        val newZoomLevel = calculateZoomLevel(scaleDelta, zoomLevel.value)

        // If there's no actual zoom change, skip
        if (newZoomLevel == zoomLevel.value) {
            log.d("Zoom unchanged. Current level: ${zoomLevel.value}")
            return
        }
        log.d("New zoom level: $newZoomLevel")
        zoomLevel.value = newZoomLevel


        DrawCanvas.waitForDrawingWithSnack()

        // Create a scaled bitmap to represent zoomed view
        val scaledWidth = windowedCanvas.width
        val scaledHeight = windowedCanvas.height
        log.d("Canvas dimensions: width=$scaledWidth, height=$scaledHeight")
        log.d("Screen dimensions: width=$SCREEN_WIDTH, height=$SCREEN_HEIGHT")


        val zoomedBitmap = createBitmap(scaledWidth, scaledHeight, windowedBitmap.config!!)

        // Swap in the new zoomed bitmap
//        windowedBitmap.recycle()
// It causes race condition with init from persistent layer
        windowedBitmap = zoomedBitmap
        windowedCanvas.setBitmap(windowedBitmap)
        windowedCanvas.scale(zoomLevel.value, zoomLevel.value)


        // Redraw everything at new zoom level
        val redrawRect = Rect(0, 0, windowedBitmap.width, windowedBitmap.height)

        log.d("Redrawing full logical rect: $redrawRect")
        windowedCanvas.drawColor(Color.BLACK)

        drawBg(
            context,
            windowedCanvas,
            pageFromDb?.getBackgroundType() ?: BackgroundType.Native,
            pageFromDb?.background ?: "blank",
            scroll,
            zoomLevel.value,
            this,
            redrawRect
        )

        drawAreaScreenCoordinates(redrawRect)

        persistBitmapDebounced()
        saveToPersistLayer()
        PageDataManager.cacheBitmap(id, windowedBitmap)
        log.i("Zoom and redraw completed")
    }


    // updates page setting in db, (for instance type of background)
    // and redraws page to view.
    fun updatePageSettings(page: Page) {
        coroutineScope.launch {
            withContext(Dispatchers.IO) {
                AppRepository(context).pageRepository.update(page)
                pageFromDb = AppRepository(context).pageRepository.getById(id)
            }
            log.i("Page settings updated, ${pageFromDb?.background} | ${page.background}")
            drawAreaScreenCoordinates(Rect(0, 0, SCREEN_WIDTH, SCREEN_HEIGHT))
            persistBitmapDebounced()
        }
    }
    
    private fun updatePageTimestamp() {
        pageFromDb?.let { currentPage ->
            val updatedPage = currentPage.copy(updatedAt = Date())
            coroutineScope.launch {
                withContext(Dispatchers.IO) {
                    AppRepository(context).pageRepository.update(updatedPage)
                }
            }
            pageFromDb = updatedPage
        }
    }

    fun updateDimensions(newWidth: Int, newHeight: Int) {
        if (newWidth != viewWidth || newHeight != viewHeight) {
            viewWidth = newWidth
            viewHeight = newHeight

            // Recreate bitmap and canvas with new dimensions
            windowedBitmap = createBitmap(viewWidth, viewHeight)
            windowedCanvas = Canvas(windowedBitmap)

            //Reset zoom level.
            zoomLevel.value = 1.0f
            drawAreaScreenCoordinates(Rect(0, 0, SCREEN_WIDTH, SCREEN_HEIGHT))
            persistBitmapDebounced()
            PageDataManager.cacheBitmap(id, windowedBitmap)
        }
    }

    private fun persistBitmapDebounced() {
        coroutineScope.launch {
            saveTopic.emit(Unit)
        }
    }

    private fun saveToPersistLayer() {
        coroutineScope.launch {
            withContext(Dispatchers.IO) {
                AppRepository(context).pageRepository.updateScroll(id, scroll)
                pageFromDb = AppRepository(context).pageRepository.getById(id)
            }
        }
    }


    fun applyZoom(point: IntOffset): IntOffset {
        return IntOffset(
            (point.x * zoomLevel.value).toInt(),
            (point.y * zoomLevel.value).toInt()
        )
    }

    fun removeZoom(point: IntOffset): IntOffset {
        return IntOffset(
            (point.x / zoomLevel.value).toInt(),
            (point.y / zoomLevel.value).toInt()
        )
    }

    private fun removeScroll(rect: Rect): Rect {
        return Rect(
            (rect.left.toFloat()).toInt(),
            ((rect.top - scroll).toFloat()).toInt(),
            (rect.right.toFloat()).toInt(),
            ((rect.bottom - scroll).toFloat()).toInt()
        )
    }

    fun toScreenCoordinates(rect: Rect): Rect {
        return Rect(
            (rect.left.toFloat() * zoomLevel.value).toInt(),
            ((rect.top - scroll).toFloat() * zoomLevel.value).toInt(),
            (rect.right.toFloat() * zoomLevel.value).toInt(),
            ((rect.bottom - scroll).toFloat() * zoomLevel.value).toInt()
        )
    }

    private fun toPageCoordinates(rect: Rect): Rect {
        return Rect(
            (rect.left.toFloat() / zoomLevel.value).toInt(),
            (rect.top.toFloat() / zoomLevel.value).toInt() + scroll,
            (rect.right.toFloat() / zoomLevel.value).toInt(),
            (rect.bottom.toFloat() / zoomLevel.value).toInt() + scroll
        )
    }
}