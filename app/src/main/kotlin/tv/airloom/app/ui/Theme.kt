package tv.airloom.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.res.stringResource
import tv.airloom.app.R
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.*
import androidx.tv.material3.*
import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import tv.airloom.core.Accent

internal val LocalUiInteractive = staticCompositionLocalOf { true }

internal val Lavender = Color(0xFFCDC0FF)
internal val Mint = Color(0xFFA2E5D4)
internal val Sky = Color(0xFFABCFFF)
internal val Peach = Color(0xFFFFB49C)
/** Live marker over video or a captured frame, where the surface is dark in either theme. */
internal val LiveOnVideo = Color(0xFFFF6B6B)
internal fun Accent.color(): Color = when (this) { Accent.LAVENDER -> Lavender; Accent.TEAL -> Mint; Accent.BLUE -> Sky; Accent.PEACH -> Peach }

/**
 * Colour roles. [focus] answers "where am I", the accent family answers "what is selected or on air";
 * the two never share a fill, so a row's state reads from across the room.
 */
@Immutable
internal class Palette(
    val background: Color, val panel: Color, val container: Color, val tile: Color,
    val on: Color, val onVariant: Color, val line: Color,
    val focus: Color, val onFocus: Color,
    val accent: Color, val accentContainer: Color, val onAccentContainer: Color, val accentOnFocus: Color,
    val live: Color, val archive: Color,
    /** The pastel accent itself, for marks drawn over video or a captured frame in either theme. */
    val accentBright: Color,
)

private class AccentTones(val container: Color, val onContainer: Color, val deep: Color, val lightContainer: Color)

private fun accentTones(accent: Color) = when (accent) {
    Lavender -> AccentTones(Color(0xFF3D3566), Color(0xFFE9E2FF), Color(0xFF5747B3), Color(0xFFE3DCFB))
    Mint -> AccentTones(Color(0xFF1D4A41), Color(0xFFD2F5EC), Color(0xFF1C6E5D), Color(0xFFCDEEE5))
    Sky -> AccentTones(Color(0xFF1F3F68), Color(0xFFDCEBFF), Color(0xFF245D9F), Color(0xFFD6E6FB))
    Peach -> AccentTones(Color(0xFF5C3426), Color(0xFFFFE2D8), Color(0xFF9B4A2D), Color(0xFFFBDDD2))
    else -> AccentTones(lerp(Color(0xFF121117), accent, .3f), lerp(accent, Color.White, .5f), lerp(accent, Color.Black, .45f), lerp(Color.White, accent, .45f))
}

internal fun palette(light: Boolean, accent: Color): Palette {
    val tones = accentTones(accent)
    return if (light) Palette(
        background = Color(0xFFF4F0FA), panel = Color(0xFFEBE6F3), container = Color(0xFFE1DBEB), tile = Color(0xFFD5CEE2),
        on = Color(0xFF1B1924), onVariant = Color(0xFF5A566B), line = Color(0xFFC6C0D4),
        focus = Color(0xFF1B1924), onFocus = Color(0xFFF4F0FA),
        accent = tones.deep, accentContainer = tones.lightContainer, onAccentContainer = Color(0xFF1B1924), accentOnFocus = accent,
        live = Color(0xFFC4302B), archive = Color(0xFF9B4A2D), accentBright = accent)
    else Palette(
        background = Color(0xFF121117), panel = Color(0xFF1A1921), container = Color(0xFF23222B), tile = Color(0xFF2F2D39),
        on = Color(0xFFECE7F5), onVariant = Color(0xFFB3AEC2), line = Color(0xFF3C3A48),
        focus = Color(0xFFECE7F5), onFocus = Color(0xFF17161D),
        accent = accent, accentContainer = tones.container, onAccentContainer = tones.onContainer, accentOnFocus = tones.deep,
        live = LiveOnVideo, archive = Peach, accentBright = accent)
}

internal val LocalPalette = staticCompositionLocalOf { palette(false, Lavender) }
internal val Muted: Color
    @Composable get() = LocalPalette.current.onVariant
internal val LocalAccent = staticCompositionLocalOf { Lavender }
internal val LocalTextScale = staticCompositionLocalOf { 1f }

/** TV overscan-safe margins: 5% of a 960×540 dp screen. */
internal val SafeHorizontal = 48.dp
internal val SafeVertical = 27.dp
internal val RowShape = RoundedCornerShape(14.dp)
internal val PanelShape = RoundedCornerShape(24.dp)
/** Nothing read from the sofa is set smaller than this. */
private const val MIN_TEXT_SP = 12

@Composable
fun AirloomTheme(light: Boolean = false, accent: Color = Lavender, textScale: Float = 1f, content: @Composable () -> Unit) {
    val palette = remember(light, accent) { palette(light, accent) }
    val colors = remember(palette) {
        if (light) lightColorScheme(primary = palette.accent, onPrimary = palette.background, background = palette.background, onBackground = palette.on,
            surface = palette.panel, onSurface = palette.on, surfaceVariant = palette.container, onSurfaceVariant = palette.onVariant,
            inverseSurface = palette.focus, inverseOnSurface = palette.onFocus, border = palette.line)
        else darkColorScheme(primary = palette.accent, onPrimary = palette.background, background = palette.background, onBackground = palette.on,
            surface = palette.panel, onSurface = palette.on, surfaceVariant = palette.container, onSurfaceVariant = palette.onVariant,
            inverseSurface = palette.focus, inverseOnSurface = palette.onFocus, border = palette.line)
    }
    CompositionLocalProvider(LocalPalette provides palette, LocalAccent provides palette.accent, LocalTextScale provides textScale, LocalMotionEnabled provides rememberMotionEnabled()) { MaterialTheme(colorScheme = colors, content = content) }
}

/**
 * The one focus treatment: a light fill fading in under the content, optionally with a slight lift.
 * The animated value is read only while drawing, so a focus change costs a single recomposition
 * (for the text colour) instead of one per animation frame.
 */
@Composable
internal fun Modifier.focusHighlight(focused: Boolean, rest: Color = Color.Transparent, shape: Shape = RowShape, scale: Float = 1f): Modifier {
    val focus = LocalPalette.current.focus
    val amount = animateFloatAsState(if (focused) 1f else 0f, tween(motionDuration(120)), label = "focus")
    return graphicsLayer {
        val lift = 1f + (scale - 1f) * amount.value
        scaleX = lift; scaleY = lift
        this.shape = shape; clip = true
    }.drawBehind {
        if (rest.alpha > 0f) drawRect(rest)
        val alpha = amount.value
        if (alpha > 0f) drawRect(focus, alpha = alpha)
    }
}

/** D-pad activation shared by custom focus targets: OK fires on key-up, a held OK fires [onLongClick] once. */
@Composable
internal fun Modifier.dpadClickable(enabled: Boolean = true, onLongClick: (() -> Unit)? = null, onClick: () -> Unit): Modifier {
    val interactive = LocalUiInteractive.current
    val press = remember { booleanArrayOf(false, false) }
    val click by rememberUpdatedState(onClick)
    val longClick by rememberUpdatedState(onLongClick)
    return onFocusChanged { if (!it.isFocused) { press[0] = false; press[1] = false } }
        .onPreviewKeyEvent { event ->
            if (!interactive) return@onPreviewKeyEvent false
            if (event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter) {
                when (event.type) {
                    KeyEventType.KeyDown -> {
                        if (event.nativeKeyEvent.repeatCount == 0) { press[0] = true; press[1] = false }
                        else if (press[0] && !press[1] && longClick != null) { press[1] = true; longClick?.invoke() }
                    }
                    KeyEventType.KeyUp -> { if (press[0] && !press[1] && enabled) click(); press[0] = false; press[1] = false }
                }
                true
            } else false
        }
}

/** Remember the exact content target before entering primary navigation. */
@Composable
internal fun Modifier.navigationReturnFocus(): Modifier {
    val navigation = LocalNavigationFocus.current ?: return this
    val requester = remember { FocusRequester() }
    return this.focusRequester(requester).onFocusChanged { if (it.isFocused) navigation.returnFocus = requester }
}

@Composable
internal fun Label(text: String, modifier: Modifier = Modifier, size: Int = 14, color: Color = MaterialTheme.colorScheme.onSurface, bold: Boolean = false, maxLines: Int = Int.MAX_VALUE) {
    val scaled = size.coerceAtLeast(MIN_TEXT_SP) * LocalTextScale.current
    Text(text, modifier, color = color, fontSize = scaled.sp, lineHeight = (scaled * 1.2f).sp, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal, maxLines = maxLines, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
}

/** The same label for text with marked spans, such as search matches. */
@Composable
internal fun Label(text: AnnotatedString, modifier: Modifier = Modifier, size: Int = 14, color: Color = MaterialTheme.colorScheme.onSurface, bold: Boolean = false, maxLines: Int = Int.MAX_VALUE) {
    val scaled = size.coerceAtLeast(MIN_TEXT_SP) * LocalTextScale.current
    Text(text, modifier, color = color, fontSize = scaled.sp, lineHeight = (scaled * 1.2f).sp, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal, maxLines = maxLines, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
}

/** [text] with every occurrence of [query] drawn in [color], ignoring case. */
internal fun markMatches(text: String, query: String, color: Color): AnnotatedString = buildAnnotatedString {
    append(text)
    if (query.isEmpty()) return@buildAnnotatedString
    var from = text.indexOf(query, ignoreCase = true)
    while (from >= 0) {
        addStyle(SpanStyle(color = color), from, from + query.length)
        from = text.indexOf(query, from + query.length, ignoreCase = true)
    }
}

@Composable
internal fun LiveIndicator(text: String, modifier: Modifier = Modifier, size: Int = 12, color: Color = LocalPalette.current.live) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(7.dp).clip(RoundedCornerShape(50)).background(color))
        Label(text, color = color, size = size, bold = true, maxLines = 1)
    }
}

/** Thin progress line; [fraction] is a plain number so a row redraws only when its own value changes. */
@Composable
internal fun ProgressLine(fraction: Float, modifier: Modifier = Modifier, track: Color = LocalPalette.current.on.copy(alpha = .14f), fill: Color = LocalPalette.current.accent) {
    Box(modifier.height(3.dp).drawBehind {
        val radius = CornerRadius(size.height / 2)
        drawRoundRect(track, cornerRadius = radius)
        drawRoundRect(fill, size = Size(size.width * fraction.coerceIn(0f, 1f), size.height), cornerRadius = radius)
    })
}

/** Remote legend, so a held OK is discoverable. */
@Composable
internal fun RemoteHints(vararg hints: Pair<String, String>, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        hints.forEach { (key, action) ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Label(key, Modifier.border(1.dp, palette.line, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 1.dp), size = 12, maxLines = 1)
                Label(action, size = 12, color = palette.onVariant, maxLines = 1)
            }
        }
    }
}

/** Fixed-size TV focus target. Default is a tonal button; [quiet] makes it a list row with no fill at rest. */

@Composable
internal fun TvAction(text: String, modifier: Modifier = Modifier, selected: Boolean = false, icon: ImageVector? = null, enabled: Boolean = true, onFocus: () -> Unit = {}, onLongClick: (() -> Unit)? = null, maxLines: Int = 2, iconOnly: Boolean = false, trailingIcon: ImageVector? = null, leadingContent: (@Composable () -> Unit)? = null, trailingContent: (@Composable () -> Unit)? = null, textContent: (@Composable (Color) -> Unit)? = null, quiet: Boolean = false, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    var longHandled by remember { mutableStateOf(false) }
    var pressed by remember { mutableStateOf(false) }
    val interactive = LocalUiInteractive.current
    val palette = LocalPalette.current
    val rest = when { selected -> palette.accentContainer; quiet -> Color.Transparent; else -> palette.container }
    val fg = when { focused -> palette.onFocus; selected -> palette.onAccentContainer; else -> palette.on }
    Row(modifier.settingsReturnFocus(text).navigationReturnFocus()
        .focusHighlight(focused, rest, if (iconOnly) CircleShape else RowShape, if (iconOnly) 1.06f else 1f)
        .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocus() else { pressed = false; longHandled = false } }
        .onPreviewKeyEvent { event ->
            if (!interactive) return@onPreviewKeyEvent false
            if (event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter) {
                if (event.type == KeyEventType.KeyDown) {
                    if (event.nativeKeyEvent.repeatCount == 0) { pressed = true; longHandled = false }
                    else if (pressed && onLongClick != null && !longHandled) { longHandled = true; onLongClick() }
                    true
                } else if (event.type == KeyEventType.KeyUp) { if (pressed && !longHandled && enabled) onClick(); pressed = false; longHandled = false; true } else true
            } else false
        }.semantics(mergeDescendants = true) { role = Role.Button; if (iconOnly) contentDescription = text; if (onLongClick != null) onLongClick { if (enabled && interactive) onLongClick.invoke(); enabled && interactive }; if (!enabled || !interactive) disabled(); onClick { if (enabled && interactive) onClick(); enabled && interactive } }.focusable(enabled && interactive).padding(horizontal = if (iconOnly) 0.dp else 13.dp, vertical = if (iconOnly) 0.dp else 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = if (iconOnly) Arrangement.Center else Arrangement.spacedBy(9.dp)) {
        if (leadingContent != null) leadingContent()
        else if (icon != null) Icon(icon, null, Modifier.size(19.dp), tint = fg)
        if (!iconOnly) {
            val labelModifier = if (trailingIcon != null || trailingContent != null || textContent != null) Modifier.weight(1f) else Modifier
            if (textContent != null) Box(labelModifier) { textContent(fg) }
            else Label(text, modifier = labelModifier, color = if (enabled || focused) fg else Muted, size = 14, bold = focused || selected, maxLines = maxLines)
        }
        if (trailingIcon != null) Icon(trailingIcon, "Закреплён", Modifier.size(14.dp), tint = fg)
        CompositionLocalProvider(LocalContentColor provides fg) { trailingContent?.invoke() }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun Entry(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, private: Boolean = false, enabled: Boolean = true) {
    val navigation = LocalNavigationFocus.current
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val fieldFocus = remember { FocusRequester() }
    val imeVisible = WindowInsets.isImeVisible
    var focused by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var inputFocused by remember { mutableStateOf(false) }
    var keyboardWasVisible by remember { mutableStateOf(false) }
    val finishEditing = { editing = false; keyboard?.hide(); Unit }
    val editHint = stringResource(R.string.ui_entry_edit_hint)
    val doneHint = stringResource(R.string.ui_entry_done_hint)
    BackHandler(enabled = editing) { finishEditing() }
    LaunchedEffect(editing) {
        if (editing) { withFrameNanos { }; keyboard?.show() }
        else keyboardWasVisible = false
    }
    // A TV IME may consume Back itself. Its closing also ends our edit mode.
    LaunchedEffect(imeVisible, editing) {
        if (editing && imeVisible) keyboardWasVisible = true
        else if (editing && keyboardWasVisible) editing = false
    }
    Column(modifier.onPreviewKeyEvent { event ->
        if (!focused) return@onPreviewKeyEvent false
        when (event.key) {
            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) {
                    if (editing) finishEditing() else editing = true
                }
                true
            }
            Key.DirectionUp, Key.DirectionDown -> {
                if (event.type == KeyEventType.KeyDown) {
                    if (editing) finishEditing()
                    focusManager.moveFocus(if (event.key == Key.DirectionUp) FocusDirection.Previous else FocusDirection.Next)
                }
                true
            }
            Key.DirectionLeft, Key.DirectionRight -> {
                if (!editing) {
                    if (event.type == KeyEventType.KeyDown) if (!focusManager.moveFocus(if (event.key == Key.DirectionLeft) FocusDirection.Left else FocusDirection.Right) && event.key == Key.DirectionLeft) navigation?.open = true
                    true
                } else false
            }
            else -> false
        }
    }, verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Label(label, size = 12, color = Muted)
            if (focused) Label(if (editing) doneHint else editHint, size = 12, color = Muted)
        }
        Row(Modifier.fillMaxWidth().testTag("entry_input_$label").onFocusChanged { inputFocused = it.hasFocus }.focusGroup()
            .clip(RowShape).background(LocalPalette.current.container)
            .border(if (inputFocused) 2.dp else 1.dp, if (inputFocused) LocalPalette.current.focus else LocalPalette.current.line, RowShape)
            .padding(end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(value, onChange, Modifier.weight(1f).navigationReturnFocus().focusRequester(fieldFocus).semantics {
                contentDescription = label
                stateDescription = if (editing) doneHint else editHint
                onClick { fieldFocus.requestFocus(); editing = true; true }
            }.onFocusChanged {
                focused = it.isFocused
                if (!it.isFocused && editing) finishEditing()
            }.padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                readOnly = !editing, enabled = enabled, singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { finishEditing() }),
                textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = (15 * LocalTextScale.current).sp),
                visualTransformation = if (private) PasswordVisualTransformation() else VisualTransformation.None,
                cursorBrush = androidx.compose.ui.graphics.SolidColor(LocalAccent.current), decorationBox = { inner -> Box { if (value.isEmpty()) Label(if (label.contains("URL")) "https://…" else "Введите значение", color = Muted, size = 14); inner() } })
            TvAction("Вставить", Modifier.size(40.dp).testTag("entry_paste_$label"), icon = Icons.Outlined.ContentPaste, iconOnly = true, quiet = true, enabled = enabled) {
                val text = clipboard.getText()?.text?.trim()
                if (text.isNullOrEmpty()) Toast.makeText(context, "Буфер обмена пуст", Toast.LENGTH_SHORT).show()
                else onChange(text)
            }
        }
    }
}

@Composable
internal fun SectionTitle(title: String, subtitle: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { Label(title, size = 22, bold = true); if (subtitle != null) Label(subtitle, size = 12, color = Muted) }
}
