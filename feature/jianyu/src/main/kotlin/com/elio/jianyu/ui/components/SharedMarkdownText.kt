package com.elio.jianyu.ui.components

import android.widget.Toast
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.elio.lifearchive.core.markdown.MarkdownFragment

/** 见域不给共用渲染器笔记或任意私有文件能力，资源缺失明确保留在正文中。 */
@Composable
fun SharedMarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
    fontSize: TextUnit = 15.sp,
    maxHeight: Dp = 12000.dp,
    scrollable: Boolean = true,
    onContentHeight: (Dp) -> Unit = {},
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val dark = MaterialTheme.colorScheme.background.let { it.red + it.green + it.blue < 1.5f }
    MarkdownFragment(
        markdown = markdown, modifier = modifier, color = color, fontSize = fontSize,
        dark = dark, maxHeight = maxHeight, scrollable = scrollable, onContentHeight = onContentHeight,
        onLink = { target ->
            val uri = android.net.Uri.parse(target)
            if (uri.scheme in setOf("https", "http", "mailto", "tel")) {
                runCatching { uriHandler.openUri(target) }.onFailure { Toast.makeText(context, "设备无法打开此链接", Toast.LENGTH_SHORT).show() }
            } else Toast.makeText(context, "此引用没有可用的见域资源", Toast.LENGTH_SHORT).show()
        },
    )
}
