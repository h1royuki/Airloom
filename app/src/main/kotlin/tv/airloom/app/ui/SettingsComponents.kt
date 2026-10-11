package tv.airloom.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.semantics.*

internal class SettingsFocus { var last: FocusRequester? = null; val targets = mutableMapOf<String, FocusRequester>() }
internal val LocalSettingsFocus = staticCompositionLocalOf<SettingsFocus?> { null }

@Composable
internal fun Modifier.settingsReturnFocus(id: String): Modifier {
    val owner = LocalSettingsFocus.current ?: return this
    val identity = "$id:$currentCompositeKeyHash"
    val requester = remember(owner, identity) { owner.targets.getOrPut(identity) { FocusRequester() } }
    return focusRequester(requester).onFocusChanged { if (it.isFocused) owner.last = requester }
}

@Composable
internal fun SettingRow(title: String, value: String = "", description: String? = null, modifier: Modifier = Modifier,
    icon: ImageVector? = null, enabled: Boolean = true, onClick: () -> Unit) {
    TvAction(title, modifier.fillMaxWidth(), enabled = enabled, icon = icon, quiet = true,
        textContent = { foreground -> Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Label(title, color = foreground, size = 14, bold = true)
            description?.let { Label(it, color = foreground.copy(alpha = .75f), size = 12) }
            if (value.isNotBlank()) Label(value, color = foreground.copy(alpha = .9f), size = 13)
        } }, trailingContent = { Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp)) }, onClick = onClick)
}

/** On/off row: the state is a switch at the row's end, not a word to read. */
@Composable
internal fun SettingSwitch(title: String, checked: Boolean, modifier: Modifier = Modifier, description: String? = null,
    icon: ImageVector? = null, onClick: () -> Unit) {
    TvAction(title, modifier.fillMaxWidth().semantics { stateDescription = if (checked) "Включено" else "Выключено" }, icon = icon, quiet = true,
        textContent = { foreground -> Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Label(title, color = foreground, size = 14, bold = true)
            description?.let { Label(it, color = foreground.copy(alpha = .75f), size = 12) }
        } }, trailingContent = {
            val palette = LocalPalette.current
            val content = LocalContentColor.current
            val onFocus = content == palette.onFocus
            Box(Modifier.size(44.dp, 24.dp).clip(CircleShape)
                .then(if (checked) Modifier.background(if (onFocus) palette.accentOnFocus else palette.accent) else Modifier.border(2.dp, content.copy(alpha = .5f), CircleShape))
                .padding(3.dp), contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart) {
                Box(Modifier.size(if (checked) 18.dp else 12.dp).clip(CircleShape)
                    .background(if (!checked) content.copy(alpha = .6f) else if (onFocus) palette.focus else palette.background))
            }
        }, onClick = onClick)
}

/**
 * A choice among a few short options, laid out as chips: the current value is visible among the
 * others and changing it takes one press. Longer lists keep [SettingChoice] and its dialog.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SettingChips(title: String, id: String, options: List<String>, selected: Int, swatches: List<Color>? = null, choose: (Int) -> Unit) {
    val requesters = remember(options.size) { List(options.size) { FocusRequester() } }
    Column(Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Label(title, size = 12, color = Muted)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEachIndexed { index, text ->
                // The screen sends an unhandled ← to its section menu, so moves between chips are explicit.
                Chip(text, index == selected, "$id:$index", Modifier.focusRequester(requesters[index]).testTag("${id}_$index")
                    .onPreviewKeyEvent { event ->
                        val step = when (event.key) { Key.DirectionLeft -> -1; Key.DirectionRight -> 1; else -> 0 }
                        if (step == 0 || index + step !in requesters.indices) false
                        else { if (event.type == KeyEventType.KeyDown) requesters[index + step].requestFocus(); true }
                    }, swatch = swatches?.getOrNull(index)) { choose(index) }
            }
        }
    }
}

@Composable
internal fun Chip(text: String, selected: Boolean, focusId: String, modifier: Modifier = Modifier, swatch: Color? = null, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val palette = LocalPalette.current
    val interactive = LocalUiInteractive.current
    val foreground = when { focused -> palette.onFocus; selected -> palette.onAccentContainer; else -> palette.on }
    Row(modifier.settingsReturnFocus(focusId).navigationReturnFocus().height(36.dp)
        .focusHighlight(focused, if (selected) palette.accentContainer else Color.Transparent, CircleShape, 1.04f)
        .then(if (selected || focused) Modifier else Modifier.border(1.dp, palette.line, CircleShape))
        .onFocusChanged { focused = it.isFocused }
        .dpadClickable(onClick = onClick)
        .semantics(mergeDescendants = true) { role = Role.RadioButton; this.selected = selected; onClick { if (interactive) onClick(); interactive } }
        .focusable(interactive).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (swatch != null) Box(Modifier.size(12.dp).clip(CircleShape).background(swatch))
        Label(text, size = 14, bold = selected || focused, color = foreground, maxLines = 1)
    }
}

@Composable
internal fun SettingChoice(title: String, id: String, options: List<String>, selected: Int, description: String? = null,
    enabled: Boolean = true, choose: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var restore by remember { mutableIntStateOf(0) }
    val trigger = remember { FocusRequester() }
    LaunchedEffect(restore) { if (restore > 0) { withFrameNanos { }; runCatching { trigger.requestFocus() } } }
    SettingRow(title, options.getOrNull(selected) ?: "Свой", description,
        Modifier.focusRequester(trigger).testTag(id), enabled = enabled) { expanded = true }
    if (expanded) AirloomDialog(title, { expanded = false; restore++ }) {
        options.forEachIndexed { index, text ->
            val click: () -> Unit = { choose(index); expanded = false; restore++ }
            if (index == selected.coerceAtLeast(0)) InitialFocus { TvAction(text, it.fillMaxWidth().testTag("${id}_$index"), selected = index == selected, onClick = click) }
            else TvAction(text, Modifier.fillMaxWidth().testTag("${id}_$index"), selected = index == selected, onClick = click)
        }
    }
}

@Composable
internal fun SettingsPage(title: String, subtitle: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionTitle(title, subtitle)
        Spacer(Modifier.height(4.dp))
        content()
    }
}
