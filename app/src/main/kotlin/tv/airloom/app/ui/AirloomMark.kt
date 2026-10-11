package tv.airloom.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon

// Geometry mirrors design/airloom-mark.svg; preserve the launcher mark's silhouette.
private val outerMark = PathParser().parsePathString("M48,25Q54,14 60,25L88,77Q93,89 80,89L66,89L54,66L42,89L28,89Q15,89 20,77Z").toNodes()
private val innerMark = PathParser().parsePathString("M54,44L72,78Q77,89 65,89L43,89Q31,89 36,78Z").toNodes()

@Composable
internal fun AirloomMark(modifier: Modifier = Modifier) {
    val accent = LocalAccent.current
    val vector = remember(accent) {
        ImageVector.Builder("Airloom", 108.dp, 108.dp, 108f, 108f)
            .addPath(pathData = outerMark, fill = SolidColor(accent))
            .addPath(pathData = innerMark, fill = SolidColor(Color.Black.copy(alpha = .2f).compositeOver(accent)))
            .build()
    }
    Icon(vector, contentDescription = "Airloom", modifier = modifier, tint = Color.Unspecified)
}
