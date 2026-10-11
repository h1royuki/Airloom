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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import kotlinx.coroutines.delay

@Composable
internal fun delayedLoading(loading: Boolean): Boolean {
    var visible by remember(loading) { mutableStateOf(false) }
    LaunchedEffect(loading) { if (loading) { delay(150); visible = true } }
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

@Composable
internal fun SkeletonRows(count: Int = 6, height: Int = 56, logos: Boolean = true, modifier: Modifier = Modifier) {
    val alpha = pulse(1_100)
    val loadingDescription = stringResource(R.string.ui_loading_list)
    Column(modifier.fillMaxWidth().testTag("skeleton_rows").semantics { contentDescription = loadingDescription },
        verticalArrangement = Arrangement.spacedBy(2.dp)) {
        repeat(count) {
            Row(Modifier.fillMaxWidth().height(height.dp).clip(RowShape)
                .background(LocalPalette.current.container.copy(alpha = .5f)).padding(horizontal = 12.dp)
                .graphicsLayer { this.alpha = alpha }, verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (logos) SkeletonBlock(Modifier.size(36.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    SkeletonBlock(Modifier.fillMaxWidth(.7f).height(12.dp))
                    if (logos) SkeletonBlock(Modifier.fillMaxWidth(.92f).height(9.dp))
                }
            }
        }
    }
}

@Composable
private fun SkeletonBlock(modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(5.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = .14f)))
}

@Composable
internal fun EpgSkeleton() {
    val alpha = pulse(1_100)
    Column(Modifier.fillMaxWidth().graphicsLayer { this.alpha = alpha }.testTag("epg_skeleton"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SkeletonBlock(Modifier.fillMaxWidth(.78f).height(25.dp))
        SkeletonBlock(Modifier.width(130.dp).height(12.dp))
        Spacer(Modifier.height(7.dp))
        repeat(3) { SkeletonBlock(Modifier.fillMaxWidth(.9f).height(14.dp)) }
    }
}

@Composable
internal fun RetryLoad(label: String, retry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Label(label, size = 14, color = Muted)
        InitialFocus { TvAction("Повторить", it, onClick = retry) }
    }
}
