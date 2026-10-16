package com.inkwell.notes.ink.render

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlendMode
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import com.inkwell.notes.core.model.PenType
import kotlin.random.Random

/**
 * Paint configuration of each pen, shared by every renderer (front buffer, overlay, tiles,
 * export) so live and committed ink match.
 */
object InkPaints {
    /** Size of one graphite grain texel in page points. */
    private const val GRAIN_PT = 0.32f
    private const val TEXTURE_SIZE = 96

    private val grainAlpha: ByteArray by lazy { buildGrain() }
    private val textures = object : LinkedHashMap<Int, Bitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Bitmap>?): Boolean = size > 24
    }

    /** Per-thread shaders (Shader native instances are created lazily and are not shared across threads). */
    private val shaders = ThreadLocal.withInitial { HashMap<Int, BitmapShader>() }

    /** Configures [paint] for drawing a filled stroke outline of [pen] in [color] (ARGB incl. opacity). */
    fun configureStroke(paint: Paint, pen: PenType, color: Int) {
        paint.style = Paint.Style.FILL
        paint.isAntiAlias = true
        paint.shader = null
        paint.blendMode = null
        paint.clearShadowLayer()
        paint.color = color
        when (pen) {
            PenType.HIGHLIGHTER -> paint.blendMode = BlendMode.MULTIPLY
            PenType.PENCIL -> {
                paint.shader = pencilShader(color)
                // Shader output is modulated by the paint alpha only.
                paint.alpha = (Color.alpha(color) * 0.92f).toInt()
            }
            else -> Unit
        }
    }

    private fun pencilShader(color: Int): BitmapShader {
        val key = color or 0xFF000000.toInt()
        val map = shaders.get()!!
        map[key]?.let { return it }
        val shader = BitmapShader(texture(key), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        shader.setLocalMatrix(Matrix().apply { setScale(GRAIN_PT, GRAIN_PT) })
        if (map.size > 24) map.clear()
        map[key] = shader
        return shader
    }

    private fun texture(opaqueColor: Int): Bitmap = synchronized(textures) {
        textures[opaqueColor] ?: run {
            val px = IntArray(TEXTURE_SIZE * TEXTURE_SIZE)
            val rgb = opaqueColor and 0x00FFFFFF
            val g = grainAlpha
            for (i in px.indices) px[i] = ((g[i].toInt() and 0xFF) shl 24) or rgb
            val bmp = Bitmap.createBitmap(px, TEXTURE_SIZE, TEXTURE_SIZE, Bitmap.Config.ARGB_8888)
            textures[opaqueColor] = bmp
            bmp
        }
    }

    /** Deterministic graphite grain: blurred value noise mapped to alpha 105..255. */
    private fun buildGrain(): ByteArray {
        val n = TEXTURE_SIZE
        val rnd = Random(0x1DEA)
        val raw = FloatArray(n * n) { rnd.nextFloat() }
        val blurred = FloatArray(n * n)
        for (y in 0 until n) for (x in 0 until n) {
            var sum = 0f
            for (dy in -1..1) for (dx in -1..1) {
                val xx = (x + dx + n) % n; val yy = (y + dy + n) % n
                sum += raw[yy * n + xx] * if (dx == 0 && dy == 0) 2f else 1f
            }
            blurred[y * n + x] = sum / 10f
        }
        var lo = 1f; var hi = 0f
        for (v in blurred) { if (v < lo) lo = v; if (v > hi) hi = v }
        val range = (hi - lo).coerceAtLeast(1e-3f)
        return ByteArray(n * n) { i ->
            val t = (blurred[i] - lo) / range
            // Bias towards dense graphite with lighter speckles.
            val shaped = 1f - (1f - t) * (1f - t)
            val a = 105f + 150f * shaped.coerceIn(0f, 1f)
            a.toInt().coerceIn(0, 255).toByte()
        }
    }
}
