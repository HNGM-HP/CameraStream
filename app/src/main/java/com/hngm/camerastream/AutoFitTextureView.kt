package com.hngm.camerastream

import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.util.AttributeSet
import android.view.TextureView
import kotlin.math.max
import kotlin.math.min

class AutoFitTextureView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : TextureView(context, attrs, defStyleAttr) {
    private var bufferWidth = 0
    private var bufferHeight = 0
    private var relativeRotationDegrees = 0
    private var displayRotationDegrees = 0
    private var targetPortrait = false
    private var fillParent = true
    private var mirror = false
    private val matrixTransform = Matrix()

    fun configurePreviewTransform(
        width: Int,
        height: Int,
        relativeRotation: Int,
        displayRotation: Int,
        portrait: Boolean,
        fill: Boolean,
        mirrorEnabled: Boolean
    ) {
        if (width <= 0 || height <= 0) return
        bufferWidth = width
        bufferHeight = height
        relativeRotationDegrees = ((relativeRotation % 360) + 360) % 360
        displayRotationDegrees = ((displayRotation % 360) + 360) % 360
        targetPortrait = portrait
        fillParent = fill
        mirror = mirrorEnabled
        updateTextureTransform()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(width, height)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateTextureTransform()
    }

    fun mapViewPointToTextureNormalized(x: Float, y: Float): Pair<Float, Float> {
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        if (viewWidth <= 0f || viewHeight <= 0f) {
            return Pair(0.5f, 0.5f)
        }
        val inverse = Matrix()
        val points = floatArrayOf(x, y)
        if (matrixTransform.invert(inverse)) {
            inverse.mapPoints(points)
        }
        return Pair(
            (points[0] / viewWidth).coerceIn(0f, 1f),
            (points[1] / viewHeight).coerceIn(0f, 1f)
        )
    }

    private fun updateTextureTransform() {
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        if (viewWidth <= 0f || viewHeight <= 0f || bufferWidth <= 0 || bufferHeight <= 0) {
            translationX = 0f
            translationY = 0f
            matrixTransform.reset()
            setTransform(matrixTransform)
            return
        }

        translationX = 0f
        translationY = 0f

        val centerX = viewWidth / 2f
        val centerY = viewHeight / 2f
        if (!targetPortrait && (displayRotationDegrees == 90 || displayRotationDegrees == 270)) {
            updateLandscapeRotatedTransform(viewWidth, viewHeight, centerX, centerY)
            return
        }

        val contentWidth = if (targetPortrait) bufferHeight.toFloat() else bufferWidth.toFloat()
        val contentHeight = if (targetPortrait) bufferWidth.toFloat() else bufferHeight.toFloat()
        val defaultScaleX = viewWidth / contentWidth
        val defaultScaleY = viewHeight / contentHeight
        val uniformScale = if (fillParent) {
            // Center-crop: scale so smaller dimension fills the view
            max(defaultScaleX, defaultScaleY)
        } else {
            // Center-fit: scale so larger dimension fits in view, capped at 1x
            min(min(defaultScaleX, defaultScaleY), 1f)
        }
        val scaleX = max(uniformScale / defaultScaleX, 0.0001f)
        val scaleY = max(uniformScale / defaultScaleY, 0.0001f)

        matrixTransform.reset()
        matrixTransform.setScale(
            if (mirror) -scaleX else scaleX,
            scaleY,
            centerX,
            centerY
        )
        if (displayRotationDegrees == 180) {
            matrixTransform.postRotate(180f, centerX, centerY)
        }
        setTransform(matrixTransform)
    }

    private fun updateLandscapeRotatedTransform(
        viewWidth: Float,
        viewHeight: Float,
        centerX: Float,
        centerY: Float
    ) {
        val viewRect = RectF(0f, 0f, viewWidth, viewHeight)
        val bufferRect = RectF(0f, 0f, bufferHeight.toFloat(), bufferWidth.toFloat())
        bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())

        val scale = if (fillParent) {
            max(viewHeight / bufferHeight.toFloat(), viewWidth / bufferWidth.toFloat())
        } else {
            min(viewHeight / bufferHeight.toFloat(), viewWidth / bufferWidth.toFloat())
        }
        val rotation = if (displayRotationDegrees == 90) -90f else 90f

        matrixTransform.reset()
        matrixTransform.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
        matrixTransform.postScale(scale, scale, centerX, centerY)
        matrixTransform.postRotate(rotation, centerX, centerY)
        if (mirror) {
            matrixTransform.postScale(-1f, 1f, centerX, centerY)
        }
        setTransform(matrixTransform)
    }
}
