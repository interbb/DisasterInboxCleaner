package com.interbb.disasterinboxcleaner.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Content never grows past this; on an unfolded inner screen a full-width line is unreadable. */
private val CONTENT_MAX_WIDTH = 560.dp

/** Below this, two portrait captures side by side shrink to about 150dp and the text is lost. */
private val SIDE_BY_SIDE_MIN_WIDTH = 480.dp

private val PLATE_MAX_WIDTH = 228.dp

private const val PORTRAIT_RATIO = 683f / 1080f
private const val LANDSCAPE_RATIO = 1080f / 392f

/**
 * One tutorial screen: fixed header, scrolling body, fixed footer. The primary button is reachable
 * without scrolling on every screen.
 */
@Composable
internal fun TutorialPage(
    title: String,
    primaryLabel: String,
    primaryEnabled: Boolean,
    onPrimary: () -> Unit,
    secondaryLabel: String?,
    onSecondary: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scrollState = rememberScrollState()
    Column(modifier.fillMaxSize()) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .widthIn(max = CONTENT_MAX_WIDTH)
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 16.dp),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .align(Alignment.CenterHorizontally)
                .widthIn(max = CONTENT_MAX_WIDTH)
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
            content = content,
        )
        // canScrollForward/Backward are read via derivedStateOf so this composable subscribes to
        // the derived boolean, not to every raw scroll-position change - it recomposes only when
        // the divider's visibility actually flips, not on every scroll frame.
        val showDivider by remember { derivedStateOf { scrollState.canScrollForward || scrollState.canScrollBackward } }
        if (showDivider) {
            HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
        }
        Column(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .widthIn(max = CONTENT_MAX_WIDTH)
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Button(
                onClick = onPrimary,
                enabled = primaryEnabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) {
                Text(primaryLabel)
            }
            if (secondaryLabel != null) {
                TextButton(
                    onClick = onSecondary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                ) {
                    Text(secondaryLabel)
                }
            }
        }
    }
}

@Composable
internal fun BodyText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        lineHeight = 1.5.em,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier,
    )
}

@Composable
internal fun HintText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/** The whole "this is already done" treatment: one line, no chip, no card, no green. */
@Composable
internal fun SatisfiedRow() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CheckGlyph()
        Text(
            text = TutorialCopy.SATISFIED,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Drawn rather than imported: the material-icons artifact is not in the offline cache. */
@Composable
internal fun CheckGlyph(modifier: Modifier = Modifier, tint: Color = MaterialTheme.colorScheme.primary) {
    Canvas(
        modifier = modifier
            .size(16.dp)
            .semantics { contentDescription = TutorialCopy.SATISFIED },
    ) {
        val path = Path().apply {
            moveTo(size.width * 0.18f, size.height * 0.55f)
            lineTo(size.width * 0.42f, size.height * 0.78f)
            lineTo(size.width * 0.84f, size.height * 0.26f)
        }
        drawPath(
            path = path,
            color = tint,
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

/**
 * Lays out the captures for one screen: portrait pairs go side by side only when there is room,
 * and the one landscape capture always takes a row of its own.
 */
@Composable
internal fun PlateGroup(
    plates: List<TutorialPlate>,
    onZoom: (TutorialPlate) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (plates.isEmpty()) return
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val sideBySide = maxWidth >= SIDE_BY_SIDE_MIN_WIDTH
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            for (group in groupPlates(plates)) {
                if (sideBySide && group.size == 2) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        group.forEach { Plate(it, onZoom, Modifier.weight(1f)) }
                    }
                } else {
                    group.forEach { Plate(it, onZoom, Modifier.fillMaxWidth()) }
                }
            }
        }
    }
}

/** Portrait captures pair up; a landscape one breaks the pair and stands alone. */
private fun groupPlates(plates: List<TutorialPlate>): List<List<TutorialPlate>> = buildList {
    var pending = mutableListOf<TutorialPlate>()
    fun flush() {
        if (pending.isNotEmpty()) {
            add(pending.toList())
            pending = mutableListOf()
        }
    }
    for (plate in plates) {
        if (plate.landscape) {
            flush()
            add(listOf(plate))
        } else {
            pending.add(plate)
            if (pending.size == 2) flush()
        }
    }
    flush()
}

/**
 * One capture at its own aspect ratio, never cropped: the red marker sits low in some of them, so
 * a crop band would hide the very thing the caption points at.
 */
@Composable
internal fun Plate(plate: TutorialPlate, onZoom: (TutorialPlate) -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(12.dp)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Image(
            painter = painterResource(plate.res),
            contentDescription = plate.caption,
            contentScale = ContentScale.FillWidth,
            modifier = (
                if (plate.landscape) {
                    Modifier.fillMaxWidth().aspectRatio(LANDSCAPE_RATIO)
                } else {
                    Modifier.widthIn(max = PLATE_MAX_WIDTH).fillMaxWidth().aspectRatio(PORTRAIT_RATIO)
                }
                )
                .clip(shape)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
                .clickable(onClickLabel = "크게 보기") { onZoom(plate) },
        )
        Text(
            text = plate.caption,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/** Full-screen so the 683x1080 captures are actually readable; a tap anywhere closes it. */
@Composable
internal fun ZoomDialog(plate: TutorialPlate, onDismiss: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.85f))
                .clickable(interactionSource = interactionSource, indication = null) { onDismiss() },
        ) {
            Image(
                painter = painterResource(plate.res),
                contentDescription = plate.caption,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
            )
        }
    }
}

/** A secondary path folded out of the way until someone needs it. */
@Composable
internal fun FoldedBlock(
    label: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = onToggle, contentPadding = PaddingValues(0.dp)) {
            Text(if (expanded) label + TutorialCopy.BLOCK_COLLAPSE_SUFFIX else label)
        }
        if (expanded) content()
    }
}
