package io.github.soichi11208.zinna.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.widget.LinearLayout
import java.io.File
import kotlin.math.max

/**
 * The keyboard's backdrop: candidate strip and keys sit on top of it.
 *
 * Everything above draws transparently, so this one view owns the panel's appearance. That is what
 * lets a background image run continuously behind the strip and the keys instead of stopping at a
 * seam between them.
 */
@SuppressLint("ViewConstructor")
class KeyboardPanelView(context: Context) : LinearLayout(context) {

    private var bitmap: Bitmap? = null
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val matrix = Matrix()

    init {
        orientation = VERTICAL
        // The flick guide draws outside the keyboard view's bounds, up over the candidate strip.
        clipChildren = false
        clipToPadding = false
        setWillNotDraw(false)
    }

    /**
     * Sets the background image, or clears it with null.
     *
     * @param opacity 0f–1f. A photo at full strength swallows the key labels, so this is how the
     *   user trades legibility against the image.
     */
    fun setBackgroundImage(file: File?, opacity: Float) {
        bitmap?.recycle()
        bitmap = file?.let { decodeScaled(it) }
        bitmapPaint.alpha = (opacity.coerceIn(0f, 1f) * 255).toInt()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val image = bitmap ?: return
        if (width == 0 || height == 0) return

        // Centre-crop: fill the panel without distorting the picture.
        val scale = max(width.toFloat() / image.width, height.toFloat() / image.height)
        matrix.setScale(scale, scale)
        matrix.postTranslate(
            (width - image.width * scale) / 2f,
            (height - image.height * scale) / 2f,
        )
        canvas.drawBitmap(image, matrix, bitmapPaint)
    }

    // Deliberately no recycle on detach: InputMethodService detaches and re-attaches the same input
    // view every time the keyboard hides and shows, so freeing the bitmap there would blank the
    // background for the rest of the session. It is released with the view.

    /**
     * Decodes at roughly panel resolution rather than the camera's.
     *
     * The IME shares a process with nothing that can absorb a 12-megapixel bitmap, and the panel is
     * a few hundred pixels tall — decoding full size would be tens of MB for no visible gain.
     */
    private fun decodeScaled(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val targetWidth = resources.displayMetrics.widthPixels.coerceAtLeast(1)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetWidth) sample *= 2

        return BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample },
        )
    }
}
