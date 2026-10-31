package com.elio.lifearchive.core.designsystem

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val presetCache = object : LruCache<String, Bitmap>(4 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
}

/** 预设与附件使用同一裁切方式，解码不占用列表主线程。 */
@Composable
fun CoverArtwork(preset: String = "mountain", image: ImageBitmap? = null, modifier: Modifier = Modifier, enabled: Boolean = true, description: String? = null, maxDimension: Int = 256) {
    val assets = LocalContext.current.assets
    val fallback by produceState<ImageBitmap?>(null, preset, enabled, image, maxDimension) {
        value = null
        if (enabled && image == null) value = withContext(Dispatchers.IO) {
            val key = preset.takeIf { it in setOf("mountain", "lake", "forest", "paper") } ?: "mountain"
            val cacheKey = "$key:$maxDimension"
            val bitmap = presetCache.get(cacheKey) ?: run {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                assets.open("covers/$key.png").use { BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1
                while (bounds.outWidth / sample > maxDimension || bounds.outHeight / sample > maxDimension) sample *= 2
                assets.open("covers/$key.png").use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
            }?.also { presetCache.put(cacheKey, it) }
            bitmap?.asImageBitmap()
        }
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest).semantics {
        stateDescription = if (image != null || fallback != null) "封面已加载" else "封面加载中"
    }) {
        (image ?: fallback)?.let { Image(it, description, Modifier.matchParentSize(), contentScale = ContentScale.Crop) }
    }
}
