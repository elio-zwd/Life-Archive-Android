package com.elio.lifearchive

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.*
import kotlin.math.max
import kotlin.math.roundToInt

internal data class SwitchBounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun absolute(position: SwitchPosition): Offset {
        val safe = position.normalized()
        return Offset(left + (right - left) * safe.x, top + (bottom - top) * safe.y)
    }
    fun clamp(point: Offset) = Offset(point.x.coerceIn(left, right), point.y.coerceIn(top, bottom))
    fun relative(point: Offset): SwitchPosition {
        val safe = clamp(point)
        return SwitchPosition(if (right > left) (safe.x - left) / (right - left) else 0f,
            if (bottom > top) (safe.y - top) / (bottom - top) else 0f)
    }
    companion object {
        fun create(width: Int, height: Int, button: IntSize, left: Int, top: Int, right: Int, bottom: Int, bar: Int, margin: Int): SwitchBounds {
            val minimumX = (left + margin).toFloat()
            val minimumY = (top + margin).toFloat()
            return SwitchBounds(minimumX, minimumY, max(minimumX, (width - right - margin - button.width).toFloat()),
                max(minimumY, (height - max(bottom, bar) - margin - button.height).toFloat()))
        }
    }
}

@Composable
internal fun BusinessSwitcher(
    current: Business,
    bounds: SwitchBounds,
    position: SwitchPosition,
    onPosition: (SwitchPosition) -> Unit,
    onSelect: (Business) -> Unit,
    onMeasured: (IntSize) -> Unit,
    companionVisible: Boolean = false,
) {
    var expanded by remember { mutableStateOf(false) }
    var localPosition by remember { mutableStateOf(position) }
    var dragging by remember { mutableStateOf(false) }
    var dragPoint by remember { mutableStateOf(Offset.Zero) }
    LaunchedEffect(position) { if (!dragging) localPosition = position }
    val latestPosition by rememberUpdatedState(onPosition)
    val point = if (dragging) bounds.clamp(dragPoint) else bounds.absolute(localPosition)
    val label = if (companionVisible && current != Business.PET) "伙伴 · 切换" else "切换"
    fun finishDrag() {
        if (dragging) {
            localPosition = bounds.relative(dragPoint)
            dragging = false
            latestPosition(localPosition)
        }
    }
    Box(modifier = Modifier
        .offset { IntOffset(point.x.roundToInt(), point.y.roundToInt()) }
        .onSizeChanged(onMeasured)
        .pointerInput(bounds) {
            detectDragGestures(onDragStart = { dragPoint = bounds.absolute(localPosition); dragging = true },
                onDragEnd = ::finishDrag, onDragCancel = ::finishDrag) { change, delta ->
                change.consume()
                dragPoint = bounds.clamp(dragPoint + delta)
            }
        }) {
        FloatingActionButton(onClick = { if (!dragging) expanded = true }, modifier = Modifier
            .testTag("business-switch").semantics { contentDescription = "切换业务" }) {
            Text(label, modifier = Modifier.padding(horizontal = 16.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            Business.entries.forEach { business -> DropdownMenuItem(
                text = { Text(when (business) { Business.LIFE -> "Life"; Business.JIANYU -> "见域"; Business.PET -> "伙伴" }) },
                modifier = Modifier.testTag("business-select-${business.name}"),
                enabled = business != current,
                onClick = { expanded = false; onSelect(business) },
            ) }
        }
    }
}
