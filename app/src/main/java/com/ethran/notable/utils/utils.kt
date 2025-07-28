package com.ethran.notable.utils

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Region
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.TypedValue
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.core.app.ShareCompat
import androidx.core.content.FileProvider
import androidx.core.graphics.createBitmap
import androidx.core.graphics.toRect
import androidx.core.graphics.toRegion
import com.ethran.notable.APP_SETTINGS_KEY
import com.ethran.notable.R
import com.ethran.notable.SCREEN_WIDTH
import com.ethran.notable.TAG
import com.ethran.notable.classes.AppRepository
import com.ethran.notable.classes.PageView
import com.ethran.notable.db.Image
import com.ethran.notable.db.Stroke
import com.ethran.notable.db.StrokePoint
import com.ethran.notable.modals.AppSettings
import com.onyx.android.sdk.data.note.TouchPoint
import io.shipbook.shipbooksdk.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

fun Modifier.noRippleClickable(
    onClick: () -> Unit
): Modifier = composed {
    clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
        onClick()
    }
}


fun convertDpToPixel(dp: Dp, context: Context): Float {
//    val resources = context.resources
//    val metrics: DisplayMetrics = resources.displayMetrics
    return TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        dp.value,
        context.resources.displayMetrics
    )
}

// TODO move this to repository
fun deletePage(context: Context, pageId: String) {
    val appRepository = AppRepository(context)
    
    runBlocking {
        val page = withContext(Dispatchers.IO) {
            appRepository.pageRepository.getById(pageId)
        } ?: return@runBlocking
        
        val proxy = appRepository.kvProxy
        val settings = withContext(Dispatchers.IO) {
            proxy.get(APP_SETTINGS_KEY, AppSettings.serializer())
        }

        // Remove from notebook (all pages now have notebookId after migration)
        page.notebookId?.let { notebookId ->
            withContext(Dispatchers.IO) {
                appRepository.bookRepository.removePage(notebookId, pageId)
            }
        }

        // remove from quick nav
        if (settings != null && settings.quickNavPages.contains(pageId)) {
            withContext(Dispatchers.IO) {
                proxy.setKv(
                    APP_SETTINGS_KEY,
                    settings.copy(quickNavPages = settings.quickNavPages - pageId),
                    AppSettings.serializer()
                )
            }
        }

        withContext(Dispatchers.IO) {
            // Check if sync is enabled and use logging deletion if so
            val prefs = context.getSharedPreferences("sync_settings", Context.MODE_PRIVATE)
            val syncEnabled = prefs.getBoolean("sync_enabled", false)
            
            if (syncEnabled) {
                val deviceId = prefs.getString("device_id", null) ?: java.util.UUID.randomUUID().toString()
                Log.d("PageDeletion", "Logging deletion of page $pageId for sync (device: $deviceId)")
                appRepository.pageRepository.deleteWithLogging(pageId, deviceId)
            } else {
                Log.d("PageDeletion", "Deleting page $pageId without sync logging (sync disabled)")
                appRepository.pageRepository.delete(pageId)
            }
        }
        withContext(Dispatchers.IO) {
            val imgFile = File(context.filesDir, "pages/previews/thumbs/$pageId")
            if (imgFile.exists()) {
                imgFile.delete()
            }
        }
        withContext(Dispatchers.IO) {
            val imgFile = File(context.filesDir, "pages/previews/full/$pageId")
            if (imgFile.exists()) {
                imgFile.delete()
            }
        }

    }
}

fun <T : Any> Flow<T>.withPrevious(): Flow<Pair<T?, T>> = flow {
    var prev: T? = null
    this@withPrevious.collect {
        emit(prev to it)
        prev = it
    }
}

fun pointsToPath(points: List<SimplePointF>): Path {
    val path = Path()
    val prePoint = PointF(points[0].x, points[0].y)
    path.moveTo(prePoint.x, prePoint.y)

    for (point in points) {
        // skip strange jump point.
        //if (abs(prePoint.y - point.y) >= 30) continue
        path.quadTo(prePoint.x, prePoint.y, point.x, point.y)
        prePoint.x = point.x
        prePoint.y = point.y
    }
    return path
}

// points is in page coordinates
fun handleErase(
    page: PageView,
    history: History,
    points: List<SimplePointF>,
    eraser: Eraser
) {
    val paint = Paint().apply {
        this.strokeWidth = 30f
        this.style = Paint.Style.STROKE
        this.strokeCap = Paint.Cap.ROUND
        this.strokeJoin = Paint.Join.ROUND
        this.isAntiAlias = true
    }
    val path = pointsToPath(points)
    var outPath = Path()

    if (eraser == Eraser.SELECT) {
        path.close()
        outPath = path
    }


    if (eraser == Eraser.PEN) {
        paint.getFillPath(path, outPath)
    }

    val deletedStrokes = selectStrokesFromPath(page.strokes, outPath)

    val deletedStrokeIds = deletedStrokes.map { it.id }
    page.removeStrokes(deletedStrokeIds)

    history.addOperationsToHistory(listOf(Operation.AddStroke(deletedStrokes)))

    page.drawAreaScreenCoordinates(
        screenArea = page.toScreenCoordinates(strokeBounds(deletedStrokes))
    )
}

enum class SelectPointPosition {
    LEFT,
    RIGHT,
    CENTER
}


fun scaleRect(rect: Rect, scale: Float): Rect {
    return Rect(
        (rect.left / scale).toInt(),
        (rect.top / scale).toInt(),
        (rect.right / scale).toInt(),
        (rect.bottom / scale).toInt()
    )
}

fun toPageCoordinates(rect: Rect, scale: Float, scroll: Int): Rect {
    return Rect(
        (rect.left.toFloat() / scale).toInt(),
        ((rect.top.toFloat() / scale) + scroll).toInt(),
        (rect.right.toFloat() / scale).toInt(),
        ((rect.bottom.toFloat() / scale) + scroll).toInt()
    )
}

fun copyInput(touchPoints: List<TouchPoint>, scroll: Int, scale: Float): List<StrokePoint> {
    val points = touchPoints.map {
        StrokePoint(
            x = it.x / scale,
            y = (it.y / scale + scroll),
            pressure = it.pressure,
            size = it.size,
            tiltX = it.tiltX,
            tiltY = it.tiltY,
            timestamp = it.timestamp,
        )
    }
    return points
}

fun copyInputToSimplePointF(
    touchPoints: List<TouchPoint>,
    scroll: Int,
    scale: Float
): List<SimplePointF> {
    val points = touchPoints.map {
        SimplePointF(
            x = it.x / scale,
            y = (it.y / scale + scroll),
        )
    }
    return points
}

fun calculateBoundingBox(touchPoints: List<StrokePoint>): RectF {
    val initialPoint = touchPoints[0]
    val boundingBox = RectF(
        initialPoint.x,
        initialPoint.y,
        initialPoint.x,
        initialPoint.y
    )

    for (point in touchPoints) {
        boundingBox.union(point.x, point.y)
    }
    return boundingBox
}

// touchpoints are in page coordinates
fun handleDraw(
    page: PageView,
    historyBucket: MutableList<String>,
    strokeSize: Float,
    color: Int,
    pen: Pen,
    touchPoints: List<StrokePoint>
) {
    try {
        val boundingBox = calculateBoundingBox(touchPoints)

        //move rectangle
        boundingBox.inset(-strokeSize, -strokeSize)

        val stroke = Stroke(
            size = strokeSize,
            pen = pen,
            pageId = page.id,
            top = boundingBox.top,
            bottom = boundingBox.bottom,
            left = boundingBox.left,
            right = boundingBox.right,
            points = touchPoints,
            color = color
        )
        page.addStrokes(listOf(stroke))
        // this is causing lagging and crushing, neo pens are not good
        page.drawAreaPageCoordinates(strokeBounds(stroke).toRect())
        historyBucket.add(stroke.id)
    } catch (e: Exception) {
        Log.e(TAG, "Handle Draw: An error occurred while handling the drawing: ${e.message}")
    }
}

/*
* Gets list of points, and return line from first point to last.
* The line consist of 100 points, I do not know how it works (for 20 it want draw correctly)
 */
fun transformToLine(
    touchPoints: List<TouchPoint>
): List<TouchPoint> {
    val startPoint = touchPoints.first()
    val endPoint = touchPoints.last()

    // Setting intermediate values for tilt and pressure
    startPoint.tiltX = touchPoints[touchPoints.size / 10].tiltX
    startPoint.tiltY = touchPoints[touchPoints.size / 10].tiltY
    startPoint.pressure = touchPoints[touchPoints.size / 10].pressure
    endPoint.tiltX = touchPoints[9 * touchPoints.size / 10].tiltX
    endPoint.tiltY = touchPoints[9 * touchPoints.size / 10].tiltY
    endPoint.pressure = touchPoints[9 * touchPoints.size / 10].pressure

    // Helper function to interpolate between two values
    fun lerp(start: Float, end: Float, fraction: Float) = start + (end - start) * fraction

    val numberOfPoints = 100 // Define how many points should line have
    val points2 = List(numberOfPoints) { i ->
        val fraction = i.toFloat() / (numberOfPoints - 1)
        val x = lerp(startPoint.x, endPoint.x, fraction)
        val y = lerp(startPoint.y, endPoint.y, fraction)
        val pressure = lerp(startPoint.pressure, endPoint.pressure, fraction)
        val size = lerp(startPoint.size, endPoint.size, fraction)
        val tiltX = (lerp(startPoint.tiltX.toFloat(), endPoint.tiltX.toFloat(), fraction)).toInt()
        val tiltY = (lerp(startPoint.tiltY.toFloat(), endPoint.tiltY.toFloat(), fraction)).toInt()
        val timestamp = System.currentTimeMillis()

        TouchPoint(x, y, pressure, size, tiltX, tiltY, timestamp)
    }
    return (points2)
}


inline fun Modifier.ifTrue(predicate: Boolean, builder: () -> Modifier) =
    then(if (predicate) builder() else Modifier)

fun strokeToTouchPoints(stroke: Stroke): List<TouchPoint> {
    return stroke.points.map {
        TouchPoint(
            it.x,
            it.y,
            it.pressure,
            stroke.size,
            it.tiltX,
            it.tiltY,
            it.timestamp
        )
    }
}

//fun pageAreaToCanvasArea(pageArea: Rect, scroll: Int, scale: Float = 1f): Rect {
//    return scaleRect(
//        Rect(
//            pageArea.left, pageArea.top - scroll, pageArea.right, pageArea.bottom - scroll
//        ), scale
//    )
//}

fun strokeBounds(stroke: Stroke): RectF {
    return RectF(
        stroke.left, stroke.top, stroke.right, stroke.bottom
    )
}

fun imageBounds(image: Image): RectF {
    return RectF(
        image.x.toFloat(),
        image.y.toFloat(),
        image.x + image.width.toFloat(),
        image.y + image.height.toFloat()
    )
}

fun imagePoints(image: Image): Array<Point> {
    return arrayOf(
        Point(image.x, image.y),
        Point(image.x, image.y + image.height),
        Point(image.x + image.width, image.y),
        Point(image.x + image.width, image.y + image.height),
    )
}

fun strokeBounds(strokes: List<Stroke>): Rect {
    if (strokes.isEmpty()) return Rect()
    val stroke = strokes[0]
    val rect = Rect(
        stroke.left.toInt(), stroke.top.toInt(), stroke.right.toInt(), stroke.bottom.toInt()
    )
    strokes.forEach {
        rect.union(
            Rect(
                it.left.toInt(), it.top.toInt(), it.right.toInt(), it.bottom.toInt()
            )
        )
    }
    return rect
}

fun imageBoundsInt(image: Image, padding: Int = 0): Rect {
    return Rect(
        image.x + padding,
        image.y + padding,
        image.x + image.width + padding,
        image.y + image.height + padding
    )
}

fun imageBoundsInt(images: List<Image>): Rect {
    if (images.isEmpty()) return Rect()
    val rect = imageBoundsInt(images[0])
    images.forEach {
        rect.union(
            imageBoundsInt(it)
        )
    }
    return rect
}

//data class SimplePoint(val x: Int, val y: Int)
data class SimplePointF(val x: Float, val y: Float)

fun pathToRegion(path: Path): Region {
    val bounds = RectF()
    // TODO: it deprecated, find replacement.
    path.computeBounds(bounds, true)
    val region = Region()
    region.setPath(
        path,
        bounds.toRegion()
    )
    return region
}

fun divideStrokesFromCut(
    strokes: List<Stroke>,
    cutLine: List<SimplePointF>
): Pair<List<Stroke>, List<Stroke>> {
    val maxY = cutLine.maxOfOrNull { it.y }
    val cutArea = listOf(SimplePointF(0f, maxY!!)) + cutLine + listOf(
        SimplePointF(
            cutLine.last().x,
            maxY
        )
    )
    val cutPath = pointsToPath(cutArea)
    cutPath.close()

    val bounds = RectF().apply {
        cutPath.computeBounds(this, true)
    }
    val cutRegion = pathToRegion(cutPath)

    val strokesOver: MutableList<Stroke> = mutableListOf()
    val strokesUnder: MutableList<Stroke> = mutableListOf()

    strokes.forEach { stroke ->
        if (stroke.top > bounds.bottom) strokesUnder.add(stroke)
        else if (stroke.bottom < bounds.top) strokesOver.add(stroke)
        else {
            if (stroke.points.any { point ->
                    cutRegion.contains(
                        point.x.toInt(),
                        point.y.toInt()
                    )
                }) strokesUnder.add(stroke)
            else strokesOver.add(stroke)
        }
    }

    return strokesOver to strokesUnder
}

fun selectStrokesFromPath(strokes: List<Stroke>, path: Path): List<Stroke> {
    val bounds = RectF()
    path.computeBounds(bounds, true)

    //region is only 16 bit, so we need to move our region
    val translatedPath = Path(path)
    translatedPath.offset(0f, -bounds.top)
    val region = pathToRegion(translatedPath)

    return strokes.filter {
        strokeBounds(it).intersect(bounds)
    }.filter { it.points.any { region.contains(it.x.toInt(), (it.y - bounds.top).toInt()) } }
}

fun selectImagesFromPath(images: List<Image>, path: Path): List<Image> {
    val bounds = RectF()
    path.computeBounds(bounds, true)

    //region is only 16 bit, so we need to move our region
    val translatedPath = Path(path)
    translatedPath.offset(0f, -bounds.top)
    val region = pathToRegion(translatedPath)

    return images.filter {
        imageBounds(it).intersect(bounds)
    }.filter {
        // include image if all its corners are within region
        imagePoints(it).all { region.contains(it.x, (it.y - bounds.top).toInt()) }
    }
}

fun offsetStroke(stroke: Stroke, offset: Offset): Stroke {
    return stroke.copy(
        points = stroke.points.map { p -> p.copy(x = p.x + offset.x, y = p.y + offset.y) },
        top = stroke.top + offset.y,
        bottom = stroke.bottom + offset.y,
        left = stroke.left + offset.x,
        right = stroke.right + offset.x,
    )
}

fun offsetImage(image: Image, offset: Offset): Image {
    return image.copy(
        x = image.x + offset.x.toInt(),
        y = image.y + offset.y.toInt(),
        height = image.height,
        width = image.width,
        uri = image.uri,
        pageId = image.pageId
    )
}

// Why it is needed? I try to removed it, and sharing bimap seems to work.
class Provider : FileProvider(R.xml.file_paths)

fun shareBitmap(context: Context, bitmap: Bitmap) {
    val bmpWithBackground = createBitmap(bitmap.width, bitmap.height)
    val canvas = Canvas(bmpWithBackground)
    canvas.drawColor(Color.WHITE)
    canvas.drawBitmap(bitmap, 0f, 0f, null)

    val cachePath = File(context.cacheDir, "images")
    Log.i(TAG, cachePath.toString())
    cachePath.mkdirs()
    try {
        val stream = FileOutputStream(File(cachePath, "share.png"))
        bmpWithBackground.compress(Bitmap.CompressFormat.PNG, 100, stream)
        stream.close()
    } catch (e: IOException) {
        e.printStackTrace()
        return
    }

    val bitmapFile = File(cachePath, "share.png")
    val contentUri = FileProvider.getUriForFile(
        context,
        "com.ethran.notable.provider", //(use your app signature + ".provider" )
        bitmapFile
    )

    // Use ShareCompat for safe sharing
    val shareIntent = ShareCompat.IntentBuilder.from(context as Activity)
        .setStream(contentUri)
        .setType("image/png")
        .intent
        .apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

    context.startActivity(Intent.createChooser(shareIntent, "Choose an app"))
}


// move to SelectionState?
fun copyBitmapToClipboard(context: Context, bitmap: Bitmap) {
    // Save bitmap to cache and get a URI
    val uri = saveBitmapToCache(context, bitmap) ?: return

    // Grant temporary permission to read the URI
    context.grantUriPermission(
        context.packageName,
        uri,
        Intent.FLAG_GRANT_READ_URI_PERMISSION
    )

    // Create a ClipData holding the URI
    val clipData = ClipData.newUri(context.contentResolver, "Image", uri)

    // Set the ClipData to the clipboard
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(clipData)
}

fun saveBitmapToCache(context: Context, bitmap: Bitmap): Uri? {
    val bmpWithBackground = createBitmap(bitmap.width, bitmap.height)
    val canvas = Canvas(bmpWithBackground)
    canvas.drawColor(Color.WHITE)
    canvas.drawBitmap(bitmap, 0f, 0f, null)

    val cachePath = File(context.cacheDir, "images")
    Log.i(TAG, cachePath.toString())
    cachePath.mkdirs()
    try {
        val stream =
            FileOutputStream("$cachePath/share.png")
        bmpWithBackground.compress(
            Bitmap.CompressFormat.PNG,
            100,
            stream
        )
        stream.close()
    } catch (e: IOException) {
        e.printStackTrace()
    }

    val bitmapFile = File(cachePath, "share.png")
    return FileProvider.getUriForFile(
        context,
        "com.ethran.notable.provider", //(use your app signature + ".provider" )
        bitmapFile
    )
}


fun loadBackgroundBitmap(filePath: String, pageNumber: Int, scale: Float): Bitmap? {
    if(filePath.isEmpty())
        return null
    Log.v(TAG, "Reloading background, path: $filePath, scale: $scale")
    val file = File(filePath)
    if (!file.exists()) {
        Log.e(TAG, "getOrLoadBackground: File does not exist at path: $filePath")
        return null
    }

    val newBitmap: ImageBitmap? = try {
        if (filePath.endsWith(".pdf", ignoreCase = true)) {
            // PDF rendering
            val fileDescriptor =
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            PdfRenderer(fileDescriptor).use { renderer ->
                if (pageNumber < 0 || pageNumber >= renderer.pageCount) {
                    Log.e(
                        TAG,
                        "getOrLoadBackground: Invalid page number $pageNumber (total: ${renderer.pageCount})"
                    )
                    return null
                }

                renderer.openPage(pageNumber).use { pdfPage ->
                    val targetWidth = SCREEN_WIDTH*scale
                    val scaleFactor = targetWidth.toFloat() / pdfPage.width

                    val width = (pdfPage.width * scaleFactor).toInt()
                    val height = (pdfPage.height * scaleFactor).toInt()

                    val bitmap = createBitmap(width, height)
                    pdfPage.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmap.asImageBitmap()
                }
            }
        } else {
            // Image file
            BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
        }
    } catch (e: Exception) {
        Log.e(TAG, "getOrLoadBackground: Error loading background - ${e.message}", e)
        null
    }
    return newBitmap?.asAndroidBitmap()
}

fun logCallStack(reason: String) {
    val stackTrace = Thread.currentThread().stackTrace
        .drop(3) // Skip internal calls
        .take(8) // Limit depth
        .joinToString("\n") {
            "${it.className.removePrefix("com.ethran.notable.")}.${it.methodName} (${it.fileName}:${it.lineNumber})"
        }
    Log.d(TAG, "$reason Call stack:\n$stackTrace")
}