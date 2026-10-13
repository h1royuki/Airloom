package tv.airloom.app.ui

import androidx.compose.ui.res.stringResource
import tv.airloom.app.R

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import kotlinx.coroutines.delay

/** True once [loading] has lasted [delayMs]. For indicators over content that is already there, such as buffering; a first load shows its skeleton at once. */
@Composable
internal fun delayedLoading(loading: Boolean, delayMs: Long = 150): Boolean {
    var visible by remember(loading) { mutableStateOf(false) }
    LaunchedEffect(loading) { if (loading) { delay(delayMs); visible = true } }
    return loading && visible
}

@Composable
internal fun LoadingIndicator(modifier: Modifier = Modifier, label: String = stringResource(R.string.ui_loading)) {
    val color = LocalAccent.current
    val rotation = if (LocalMotionEnabled.current) {
        val transition = rememberInfiniteTransition(label = "spinner")
        transition.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "rotation").value
    } else 0f
    Canvas(modifier.size(30.dp).semantics { contentDescription = label; progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate }) {
        drawArc(color, rotation, 270f, false, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
    }
}

@Composable
internal fun StartupLoading() {
    val alpha = pulse(1_200, .72f)
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).testTag("startup_loading"),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        AirloomMark(Modifier.size(96.dp).graphicsLayer { this.alpha = alpha; scaleX = .97f + .03f * ((alpha - .72f) / .28f).coerceIn(0f, 1f); scaleY = scaleX })
        Label("Airloom", Modifier.padding(top = 12.dp), size = 28, bold = true)
    }
}

/**
 * Placeholder rows in the geometry of [ChannelRowBody]: number, logo tile and two lines, with no fill of
 * their own, so the real rows land exactly where the skeleton was. Rows keep their height when the
 * column is given less room; a caller that fills a viewport clips the overflow.
 */
@Composable
internal fun SkeletonRows(count: Int = 6, height: Int = 56, numbered: Boolean = false, modifier: Modifier = Modifier) {
    val clock = skeletonClock()
    val loadingDescription = stringResource(R.string.ui_loading_list)
    Column(modifier.fillMaxWidth().testTag("skeleton_rows").semantics { contentDescription = loadingDescription },
        verticalArrangement = Arrangement.spacedBy(2.dp)) {
        repeat(count) { index ->
            Row(Modifier.fillMaxWidth().requiredHeight(height.dp).padding(horizontal = 12.dp).skeletonWave(clock, index),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (numbered) Box(Modifier.width(26.dp)) { SkeletonBlock(Modifier.size(14.dp, 10.dp)) }
                SkeletonBlock(Modifier.size(36.dp), radius = 10)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    SkeletonBlock(Modifier.fillMaxWidth(.52f).height(12.dp))
                    SkeletonBlock(Modifier.fillMaxWidth(.78f).height(9.dp))
                }
            }
        }
    }
}

/** The rail while categories load: a dot in every icon slot, which is all a collapsed rail shows of a row. */
@Composable
internal fun RailSkeleton(count: Int = 8, modifier: Modifier = Modifier) {
    val clock = skeletonClock()
    val loadingDescription = stringResource(R.string.ui_loading_list)
    Column(modifier.fillMaxWidth().testTag("skeleton_rows").semantics { contentDescription = loadingDescription },
        verticalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(count) { index ->
            Box(Modifier.width(RailSlot).height(36.dp).skeletonWave(clock, index), contentAlignment = Alignment.Center) {
                SkeletonBlock(Modifier.size(22.dp), radius = 11)
            }
        }
    }
}

@Composable
private fun SkeletonBlock(modifier: Modifier, radius: Int = 5) {
    Box(modifier.clip(RoundedCornerShape(radius.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = .14f)))
}

/** Schedule rows while a day loads: a time and a title per row, in the geometry of the programme list. */
@Composable
internal fun ScheduleSkeleton(count: Int = 4, modifier: Modifier = Modifier, clock: State<Float>? = skeletonClock(), offset: Int = 0) {
    Column(modifier.fillMaxWidth().testTag("epg_skeleton"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        repeat(count) { index ->
            Row(Modifier.fillMaxWidth().requiredHeight(38.dp).padding(horizontal = 13.dp).skeletonWave(clock, offset + index),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SkeletonBlock(Modifier.size(34.dp, 12.dp))
                SkeletonBlock(Modifier.fillMaxWidth(listOf(.55f, .4f, .62f, .48f)[index % 4]).height(12.dp))
            }
        }
    }
}

/**
 * The stage while the first list loads: the frame, the date row and a few schedule rows where
 * [ChannelProgrammePanel] will put them. The frame has the real frame's colour, so nothing shifts on arrival.
 */
@Composable
internal fun StageSkeleton(modifier: Modifier = Modifier) {
    val clock = skeletonClock()
    Column(modifier.fillMaxSize().testTag("stage_skeleton").clearAndSetSemantics { }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.height(24.dp).skeletonWave(clock, 0), contentAlignment = Alignment.CenterStart) { SkeletonBlock(Modifier.size(120.dp, 14.dp)) }
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).skeletonWave(clock, 1).clip(PanelShape).background(LocalPalette.current.container))
        Row(Modifier.skeletonWave(clock, 4), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SkeletonBlock(Modifier.size(36.dp), radius = 18)
            SkeletonBlock(Modifier.size(190.dp, 36.dp), radius = 18)
            SkeletonBlock(Modifier.size(36.dp), radius = 18)
        }
        ScheduleSkeleton(4, Modifier.weight(1f).clipToBounds(), clock, offset = 5)
    }
}

@Composable
internal fun RetryLoad(label: String, retry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Label(label, size = 14, color = Muted)
        InitialFocus { TvAction("Повторить", it, onClick = retry) }
    }
}
