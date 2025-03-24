package com.ethran.notable.utils


import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import com.ethran.notable.TAG
import com.ethran.notable.utils.MyNeoPenUtils.computeStrokePoints
import com.onyx.android.sdk.data.PenConstant
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.pen.PenUtils
import com.onyx.android.sdk.pen.RawInputCallback
import com.onyx.android.sdk.pen.TouchHelper
import com.onyx.android.sdk.pen.style.StrokeStyle
import io.shipbook.shipbooksdk.Log
import java.lang.reflect.Field
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy


enum class Pen(val penName: String) {
    BALLPEN("BALLPEN"),
    REDBALLPEN("REDBALLPEN"),
    GREENBALLPEN("GREENBALLPEN"),
    BLUEBALLPEN("BLUEBALLPEN"),
    PENCIL("PENCIL"),
    BRUSH("BRUSH"),
    MARKER("MARKER"),
    FOUNTAIN("FOUNTAIN");

    companion object {
        fun fromString(name: String?): Pen {
            return entries.find { it.penName.equals(name, ignoreCase = true) } ?: BALLPEN
        }
    }
}

fun penToStroke(pen: Pen): Int {
    return when (pen) {
        Pen.BALLPEN -> StrokeStyle.PENCIL
        Pen.REDBALLPEN -> StrokeStyle.PENCIL
        Pen.GREENBALLPEN -> StrokeStyle.PENCIL
        Pen.BLUEBALLPEN -> StrokeStyle.PENCIL
        Pen.PENCIL -> StrokeStyle.CHARCOAL
        Pen.BRUSH -> StrokeStyle.NEO_BRUSH
        Pen.MARKER -> StrokeStyle.MARKER
        Pen.FOUNTAIN -> StrokeStyle.FOUNTAIN
    }
}


@kotlinx.serialization.Serializable
data class PenSetting(
    var strokeSize: Float,
    //TODO: Rename to strokeColor
    var color: Int
)

typealias NamedSettings = Map<String, PenSetting>


class CustomTouchHelper private constructor(private val touchHelper: TouchHelper) {

    companion object {
        fun create(hostView: View, callback: RawInputCallback): CustomTouchHelper {
            val baseHelper = TouchHelper.create(hostView, callback)
            return CustomTouchHelper(baseHelper)
        }

        fun create(hostView: View, feature: Int, callback: RawInputCallback): CustomTouchHelper {
            val baseHelper = TouchHelper.create(hostView, feature, callback)
            return CustomTouchHelper(baseHelper)
        }
    }

    fun bindHostView(hostView: View, callback: RawInputCallback) =
        apply { touchHelper.bindHostView(hostView, callback) }

    fun onTouchEvent(event: MotionEvent): Boolean = touchHelper.onTouchEvent(event)

    fun setStrokeStyle(style: Int) = apply { touchHelper.setStrokeStyle(style) }

    fun setStrokeColor(color: Int) = apply { touchHelper.setStrokeColor(color) }

    fun setStrokeWidth(w: Float) = apply { touchHelper.setStrokeWidth(w) }

    fun debugLog(enable: Boolean) = apply { touchHelper.debugLog(enable) }

    fun openRawDrawing() = apply { touchHelper.openRawDrawing() }
    fun setExcludeRect(excludeRectList: List<Rect?>?) =
        apply { touchHelper.setExcludeRect(excludeRectList) }

    fun setLimitRect(excludeRectList: List<Rect?>?) =
        apply { touchHelper.setLimitRect(excludeRectList) }

    fun setLimitRect(limitRect: Rect?, excludeRectList: List<Rect?>?) =
        apply { touchHelper.setLimitRect(limitRect, excludeRectList) }

    fun closeRawDrawing() = touchHelper.closeRawDrawing()

    fun setRawDrawingEnabled(enabled: Boolean) = apply { touchHelper.setRawDrawingEnabled(enabled) }

    fun isRawDrawingInputEnabled(): Boolean = touchHelper.isRawDrawingInputEnabled()

    fun isRawDrawingRenderEnabled(): Boolean = touchHelper.isRawDrawingRenderEnabled()

    fun forceSetRawDrawingEnabled(enabled: Boolean) =
        apply { touchHelper.forceSetRawDrawingEnabled(enabled) }

    fun setRawDrawingRenderEnabled(enabled: Boolean) =
        apply { touchHelper.setRawDrawingRenderEnabled(enabled) }

    fun setRawInputReaderEnable(enabled: Boolean) =
        apply { touchHelper.setRawInputReaderEnable(enabled) }

    fun isRawDrawingCreated(): Boolean = touchHelper.isRawDrawingCreated()

    fun setSingleRegionMode() = apply { touchHelper.setSingleRegionMode() }

    fun setMultiRegionMode() = apply { touchHelper.setMultiRegionMode() }

    fun setPenUpRefreshTimeMs(timeMs: Int) = apply { touchHelper.setPenUpRefreshTimeMs(timeMs) }

    fun setPenUpRefreshEnabled(enable: Boolean) =
        apply { touchHelper.setPenUpRefreshEnabled(enable) }

    fun enableSideBtnErase(enable: Boolean) = apply { touchHelper.enableSideBtnErase(enable) }

    fun enableFingerTouch(enable: Boolean) = apply { touchHelper.enableFingerTouch(enable) }

    fun onlyEnableFingerTouch(only: Boolean) = apply { touchHelper.onlyEnableFingerTouch(only) }

    fun setBrushRawDrawingEnabled(enable: Boolean) =
        apply { touchHelper.setBrushRawDrawingEnabled(enable) }

    fun setEraserRawDrawingEnabled(drawing: Boolean) =
        apply { touchHelper.setEraserRawDrawingEnabled(drawing) }

    fun resetPenDefaultRawDrawing() = touchHelper.resetPenDefaultRawDrawing()

    fun restartRawDrawing() = touchHelper.restartRawDrawing()

    // Add any extra custom functionality here if needed!
}


class NeoDryBrushPen {
    companion object {
        private const val ROUGHNESS_FACTOR = 0.2f
    }

    fun drawStroke(
        canvas: Canvas,
        paint: Paint,
        points: List<TouchPoint>,
        displayScale: Float,
        strokeWidth: Float,
        maxTouchPressure: Float,
        erase: Boolean
    ) {
        val modifiedPoints =
            computeDryBrushStroke(points, displayScale, strokeWidth, maxTouchPressure)
        PenUtils.drawStrokeByPointSize(canvas, paint, modifiedPoints, erase)
    }

    private fun computeDryBrushStroke(
        points: List<TouchPoint>,
        displayScale: Float,
        strokeWidth: Float,
        maxTouchPressure: Float
    ): List<TouchPoint> {
        val processedPoints = mutableListOf<TouchPoint>()

        for (point in points) {
            val modifiedPoint = TouchPoint(point)

            // Randomize stroke width to create a dry brush effect
            val randomFactor = (Math.random() * ROUGHNESS_FACTOR).toFloat()
            modifiedPoint.size =
                PenConstant.checkPenWidth((strokeWidth * point.pressure) * (1f + randomFactor))

            // Reduce opacity based on pressure (lighter strokes = more transparent)

            processedPoints.add(modifiedPoint)
        }
        return processedPoints
    }
}


object MyNeoPenUtils {
    fun computeStrokePoints(
        type: Int,
        points: List<TouchPoint?>?,
        strokeWidth: Float,
        maxTouchPressure: Float
    ): List<TouchPoint> {
        Log.i(
            TAG,
            "computeStrokePoints called"
        )
        return ArrayList() // Modify this with your logic
    }
}


object ReflectionHook {
    fun replaceNeoPenUtils() {
        try {
            // Get the original class
            val clazz = Class.forName("com.onyx.android.sdk.pen.NeoPenUtils")

            // Find the method we want to override
            val method = clazz.getDeclaredMethod(
                "computeStrokePoints",
                Int::class.javaPrimitiveType,
                MutableList::class.java,
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType
            )
            method.isAccessible = true // Allow modifying private methods (if necessary)

            // Replace the method logic
            val handler = InvocationHandler { proxy: Any?, method1: Method, args: Array<Any> ->
                if (method1.name == "computeStrokePoints") {
                    println("Intercepted call to computeStrokePoints!")
                    return@InvocationHandler computeStrokePoints(
                        args[0] as Int,
                        args[1] as List<TouchPoint?>,
                        args[2] as Float,
                        args[3] as Float
                    )
                }
                method1.invoke(proxy, *args)
            }

            val proxyInstance = Proxy.newProxyInstance(clazz.classLoader, arrayOf(clazz), handler)

            // Set the proxy to override original method calls
            val field: Field =
                clazz.getDeclaredField("INSTANCE") // If there's a static instance field
            field.setAccessible(true)
            field.set(null, proxyInstance)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
