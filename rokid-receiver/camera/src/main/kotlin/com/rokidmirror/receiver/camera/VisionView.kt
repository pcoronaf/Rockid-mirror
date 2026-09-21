package com.rokidmirror.receiver.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.math.min

/**
 * Draws the enhanced camera picture and marks each person with four corner brackets.
 *
 * Corners rather than a full rectangle: on a see-through panel an unbroken box hides the very
 * thing it is pointing at.
 */
class VisionView(context: Context) : View(context) {
    private companion object {
        const val CORNER_SHARE = 0.22f
        const val MIN_CORNER_PX = 8f
    }

    private val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val bracket = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 3f; strokeCap = Paint.Cap.SQUARE
    }
    private val bracketShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 6f; strokeCap = Paint.Cap.SQUARE
    }
    private val matrix = Matrix()
    private val target = RectF()

    @Volatile private var frame: Bitmap? = null
    @Volatile private var rotationDegrees = 0
    @Volatile private var people: List<DetectedPerson> = emptyList()

    /** Latest picture and the people found in it. Safe to call from a camera thread. */
    fun submit(bitmap: Bitmap, rotationDegrees: Int, people: List<DetectedPerson>) {
        this.frame = bitmap
        this.rotationDegrees = rotationDegrees
        this.people = people
        postInvalidateOnAnimation()
    }

    fun clear() {
        frame = null
        people = emptyList()
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        val bitmap = frame ?: return
        if (bitmap.isRecycled) return

        val rotated = rotationDegrees % 180 != 0
        val sourceWidth = if (rotated) bitmap.height else bitmap.width
        val sourceHeight = if (rotated) bitmap.width else bitmap.height
        val scale = min(width.toFloat() / sourceWidth, height.toFloat() / sourceHeight)
        val drawnWidth = sourceWidth * scale
        val drawnHeight = sourceHeight * scale
        val left = (width - drawnWidth) / 2f
        val top = (height - drawnHeight) / 2f

        matrix.reset()
        matrix.postRotate(rotationDegrees.toFloat(), bitmap.width / 2f, bitmap.height / 2f)
        // Rotation about the centre moves the image off origin when the aspect flips.
        if (rotated) matrix.postTranslate((bitmap.height - bitmap.width) / 2f, (bitmap.width - bitmap.height) / 2f)
        matrix.postScale(scale, scale)
        matrix.postTranslate(left, top)
        canvas.drawBitmap(bitmap, matrix, imagePaint)

        for (person in people) {
            target.set(
                left + person.left * drawnWidth,
                top + person.top * drawnHeight,
                left + person.right * drawnWidth,
                top + person.bottom * drawnHeight,
            )
            drawCorners(canvas, target)
        }
    }

    private fun drawCorners(canvas: Canvas, box: RectF) {
        val arm = (min(box.width(), box.height()) * CORNER_SHARE).coerceAtLeast(MIN_CORNER_PX)
        for (paint in arrayOf(bracketShadow, bracket)) {
            canvas.drawLine(box.left, box.top, box.left + arm, box.top, paint)
            canvas.drawLine(box.left, box.top, box.left, box.top + arm, paint)
            canvas.drawLine(box.right, box.top, box.right - arm, box.top, paint)
            canvas.drawLine(box.right, box.top, box.right, box.top + arm, paint)
            canvas.drawLine(box.left, box.bottom, box.left + arm, box.bottom, paint)
            canvas.drawLine(box.left, box.bottom, box.left, box.bottom - arm, paint)
            canvas.drawLine(box.right, box.bottom, box.right - arm, box.bottom, paint)
            canvas.drawLine(box.right, box.bottom, box.right, box.bottom - arm, paint)
        }
    }
}
