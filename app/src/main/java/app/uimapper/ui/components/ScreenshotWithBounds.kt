@file:OptIn(ExperimentalMaterial3Api::class)

package app.uimapper.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.uimapper.core.NodeCapture
import app.uimapper.model.UiNode
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/** Actionable elements. */
val InspectorActionableColor = Color(0xFF2E9E4F)

/** Currently selected element. */
val InspectorSelectedColor = Color(0xFFFF8A00)

private const val WIREFRAME_MAX_TEXTS = 400
private const val PREVIEW_MAX_ZOOM = 8f

private class ScreenFit(val left: Float, val top: Float, val width: Float, val height: Float, val scale: Float)

private fun fitScreen(boxW: Float, boxH: Float, screenW: Int, screenH: Int): ScreenFit {
    val s = min(boxW / screenW, boxH / screenH).coerceAtLeast(0.0001f)
    val w = screenW * s
    val h = screenH * s
    return ScreenFit((boxW - w) / 2f, (boxH - h) / 2f, w, h, s)
}

/** True when a [imgW] x [imgH] image has the aspect ratio of the [sw] x [sh] screen (within 2 %). */
private fun aspectMatches(imgW: Int, imgH: Int, sw: Int, sh: Int): Boolean {
    if (imgW <= 0 || imgH <= 0 || sw <= 0 || sh <= 0) return false
    val img = imgW.toFloat() / imgH
    val scr = sw.toFloat() / sh
    return abs(img - scr) <= 0.02f * scr
}

private fun resolveScreenSize(w: Int, h: Int, nodes: List<UiNode>): Pair<Int, Int> {
    if (w > 0 && h > 0) return w to h
    val maxR = nodes.maxOfOrNull { it.bounds.r } ?: 0
    val maxB = nodes.maxOfOrNull { it.bounds.b } ?: 0
    return maxR.coerceAtLeast(1) to maxB.coerceAtLeast(1)
}

private fun clampPan(p: Offset, zoom: Float, size: IntSize): Offset {
    val minX = size.width * (1f - zoom)
    val minY = size.height * (1f - zoom)
    return Offset(p.x.coerceIn(minX, 0f), p.y.coerceIn(minY, 0f))
}

private class WireText(val node: UiNode, val layout: TextLayoutResult)

/**
 * The captured screen with element bounds drawn on top. Without a [screenshot] a wireframe of every
 * node's bounds (plus short texts) is drawn on a neutral canvas. The aspect ratio [screenW]:[screenH] is
 * always kept. Actionable elements are outlined green, [selected] is filled + stroked orange.
 *
 * A tap is mapped from view coordinates back to real screen pixels and reported via [onTapScreen]
 * (the caller decides which node that selects). Pinch to zoom, drag to pan while zoomed.
 *
 * @param nodes the flattened tree of the snapshot (pre-order).
 * @param onlyClickable when true and a screenshot is shown, only actionable bounds are drawn.
 */
@Composable
fun ScreenshotWithBounds(
    screenshot: ImageBitmap?,
    screenW: Int,
    screenH: Int,
    nodes: List<UiNode>,
    selected: UiNode?,
    onlyClickable: Boolean,
    onTapScreen: (x: Int, y: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val (sw, sh) = remember(screenW, screenH, nodes) { resolveScreenSize(screenW, screenH, nodes) }
    // An image whose shape does not match the tree (older data: the image came from a later, rotated or
    // otherwise different capture) would be stretched and every outline misplaced: draw the wireframe.
    val shotImage = remember(screenshot, sw, sh) { screenshot?.takeIf { aspectMatches(it.width, it.height, sw, sh) } }
    val actionable = remember(nodes) { nodes.filter { it.isActionable } }
    val outlines = remember(nodes) {
        nodes.filter { it.visible && !it.bounds.isEmpty() && it.cls != NodeCapture.SYNTHETIC_ROOT_CLS }
    }
    val textNodes = remember(nodes) {
        nodes.asSequence()
            .filter { it.visible && !it.bounds.isEmpty() }
            .filter { !it.text.isNullOrBlank() || (!it.desc.isNullOrBlank() && it.children.isEmpty()) }
            .take(WIREFRAME_MAX_TEXTS)
            .toList()
    }

    val cs = MaterialTheme.colorScheme
    val neutral = cs.surfaceVariant
    val wireColor = cs.onSurfaceVariant.copy(alpha = 0.45f)
    val overlayOutline = Color(0x993F51B5)
    val frameColor = cs.outline
    val wireTextColor = cs.onSurfaceVariant

    val textMeasurer = rememberTextMeasurer()
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    var zoom by remember(sw, sh, nodes) { mutableFloatStateOf(1f) }
    var pan by remember(sw, sh, nodes) { mutableStateOf(Offset.Zero) }
    val latestOnTap by rememberUpdatedState(onTapScreen)

    val hasShot = shotImage != null
    val wireTexts = remember(hasShot, textNodes, boxSize, sw, sh, textMeasurer, wireTextColor) {
        if (hasShot || boxSize.width <= 0 || boxSize.height <= 0) {
            emptyList()
        } else {
            val fit = fitScreen(boxSize.width.toFloat(), boxSize.height.toFloat(), sw, sh)
            textNodes.mapNotNull { n ->
                val w = (n.bounds.width * fit.scale - 4f).toInt()
                val h = n.bounds.height * fit.scale
                if (w < 12 || h < 8f) return@mapNotNull null
                val label = (n.text?.takeIf { it.isNotBlank() } ?: n.desc.orEmpty()).replace('\n', ' ').trim()
                if (label.isEmpty()) return@mapNotNull null
                WireText(
                    n,
                    textMeasurer.measure(
                        text = label,
                        style = TextStyle(color = wireTextColor, fontSize = 8.sp),
                        overflow = TextOverflow.Ellipsis,
                        softWrap = false,
                        maxLines = 1,
                        constraints = Constraints(maxWidth = w),
                    ),
                )
            }
        }
    }

    Box(modifier.clipToBounds()) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { boxSize = it }
                .pointerInput(sw, sh) {
                    detectTransformGestures { centroid, panChange, zoomChange, _ ->
                        val newZoom = (zoom * zoomChange).coerceIn(1f, PREVIEW_MAX_ZOOM)
                        val k = newZoom / zoom
                        val raw = centroid - (centroid - pan) * k + panChange
                        pan = clampPan(raw, newZoom, size)
                        zoom = newZoom
                    }
                }
                .pointerInput(sw, sh) {
                    detectTapGestures(onTap = { p ->
                        val fit = fitScreen(size.width.toFloat(), size.height.toFloat(), sw, sh)
                        val cx = (p.x - pan.x) / zoom
                        val cy = (p.y - pan.y) / zoom
                        // Scale by screenW / drawn width to get back to real screen pixels.
                        val sx = (cx - fit.left) * sw / fit.width
                        val sy = (cy - fit.top) * sh / fit.height
                        if (sx >= 0f && sy >= 0f && sx < sw && sy < sh) latestOnTap(sx.toInt(), sy.toInt())
                    })
                },
        ) {
            val fit = fitScreen(size.width, size.height, sw, sh)
            val inv = 1f / zoom
            val l = fit.left
            val t = fit.top
            val s = fit.scale
            val hairline = 1.dp.toPx() * inv
            val actionableStroke = 1.5.dp.toPx() * inv
            val selectedStroke = 2.5.dp.toPx() * inv

            withTransform({
                translate(pan.x, pan.y)
                scale(zoom, zoom, Offset.Zero)
            }) {
                if (shotImage != null) {
                    drawImage(
                        image = shotImage,
                        srcOffset = IntOffset.Zero,
                        srcSize = IntSize(shotImage.width, shotImage.height),
                        dstOffset = IntOffset(l.roundToInt(), t.roundToInt()),
                        dstSize = IntSize(fit.width.roundToInt().coerceAtLeast(1), fit.height.roundToInt().coerceAtLeast(1)),
                        filterQuality = FilterQuality.Medium,
                    )
                } else {
                    drawRect(color = neutral, topLeft = Offset(l, t), size = Size(fit.width, fit.height))
                }

                clipRect(left = l, top = t, right = l + fit.width, bottom = t + fit.height) {
                    if (shotImage == null || !onlyClickable) {
                        val c = if (shotImage == null) wireColor else overlayOutline
                        for (n in outlines) {
                            if (onlyClickable && shotImage == null && n.isActionable) continue
                            val b = n.bounds
                            drawRect(
                                color = c,
                                topLeft = Offset(l + b.l * s, t + b.t * s),
                                size = Size(b.width * s, b.height * s),
                                style = Stroke(width = hairline),
                            )
                        }
                    }
                    for (wt in wireTexts) {
                        val b = wt.node.bounds
                        val th = wt.layout.size.height
                        drawText(
                            wt.layout,
                            topLeft = Offset(l + b.l * s + 2f, t + b.t * s + (b.height * s - th) / 2f),
                        )
                    }
                    for (n in actionable) {
                        val b = n.bounds
                        val topLeft = Offset(l + b.l * s, t + b.t * s)
                        val sz = Size(b.width * s, b.height * s)
                        drawRect(color = InspectorActionableColor.copy(alpha = 0.12f), topLeft = topLeft, size = sz)
                        drawRect(color = InspectorActionableColor, topLeft = topLeft, size = sz, style = Stroke(width = actionableStroke))
                    }
                    if (selected != null && !selected.bounds.isEmpty()) {
                        val b = selected.bounds
                        val topLeft = Offset(l + b.l * s, t + b.t * s)
                        val sz = Size(b.width * s, b.height * s)
                        drawRect(color = InspectorSelectedColor.copy(alpha = 0.28f), topLeft = topLeft, size = sz)
                        drawRect(color = InspectorSelectedColor, topLeft = topLeft, size = sz, style = Stroke(width = selectedStroke))
                    }
                }

                drawRect(
                    color = frameColor,
                    topLeft = Offset(l, t),
                    size = Size(fit.width, fit.height),
                    style = Stroke(width = hairline),
                )
            }
        }

        if (zoom > 1.01f) {
            FilledTonalIconButton(
                onClick = {
                    zoom = 1f
                    pan = Offset.Zero
                },
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(36.dp),
            ) {
                Icon(Icons.Filled.FitScreen, contentDescription = "Kembalikan zoom", modifier = Modifier.size(20.dp))
            }
        }
    }
}
