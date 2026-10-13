package tv.airloom.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.*
import androidx.tv.material3.MaterialTheme

internal class RowMenuPosition(private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val right = anchorBounds.right + gap
        val left = anchorBounds.left - gap - popupContentSize.width
        val x = if (right + popupContentSize.width <= windowSize.width - gap) right else left
        val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
        val maxY = (windowSize.height - popupContentSize.height).coerceAtLeast(0)
        return IntOffset(x.coerceIn(if (maxX >= gap * 2) gap else 0, if (maxX >= gap * 2) maxX - gap else maxX),
            anchorBounds.top.coerceIn(if (maxY >= gap * 2) gap else 0, if (maxY >= gap * 2) maxY - gap else maxY))
    }
}

@Composable
internal fun RowActionsPopup(title: String, pinned: Boolean, onDismiss: () -> Unit, onPin: (() -> Unit)?,
    onMove: (() -> Unit)?, onHide: () -> Unit, favourite: Boolean = false, onFavourite: (() -> Unit)? = null,
    iconOverride: String? = null, onIcon: ((String?) -> Unit)? = null, hidden: Boolean = false,
    onSelect: (() -> Unit)? = null, onDetails: (() -> Unit)? = null, moveLabel: String = "Перетянуть") {
    var pickingIcon by remember { mutableStateOf(false) }
    var returnedToPalette by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf(title) }
    val dismiss = { if (pickingIcon) { pickingIcon = false; returnedToPalette = true } else onDismiss() }
    val gap = with(LocalDensity.current) { 8.dp.roundToPx() }
    Popup(popupPositionProvider = remember(gap) { RowMenuPosition(gap) }, onDismissRequest = dismiss,
        properties = PopupProperties(focusable = true, dismissOnBackPress = true, dismissOnClickOutside = true)) {
        // The menu grows out of its row: one value, read only while drawing.
        val appear = remember { Animatable(0f) }
        val appearDuration = motionDuration(120)
        LaunchedEffect(Unit) { appear.animateTo(1f, tween(appearDuration, easing = Motion.Decelerate)) }
        CompositionLocalProvider(LocalNavigationFocus provides null, LocalSettingsFocus provides null) {
            Column(Modifier.graphicsLayer {
                alpha = appear.value; val scale = .96f + .04f * appear.value; scaleX = scale; scaleY = scale
                transformOrigin = TransformOrigin(0f, .5f)
            }.clip(RoundedCornerShape(20.dp)).background(LocalPalette.current.container)
                .border(1.dp, LocalPalette.current.line, RoundedCornerShape(20.dp)).padding(10.dp).testTag("row_actions_popup")
                .onPreviewKeyEvent { event ->
                    if (event.key == Key.Back) { if (event.type == KeyEventType.KeyUp) dismiss(); true } else false
                }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (pickingIcon && onIcon != null) CategoryIconPicker(iconOverride, onIcon)
                else {
                    val options = buildList {
                        if (onPin != null) add(RowMenuAction(if (pinned) "Открепить" else "Закрепить", Icons.Outlined.PushPin, pinned, onPin))
                        if (onFavourite != null) add(RowMenuAction(if (favourite) "Убрать из избранного" else "В избранное",
                            if (favourite) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder, favourite, onFavourite))
                        if (onIcon != null) add(RowMenuAction("Выбрать иконку", Icons.Outlined.Palette) { pickingIcon = true })
                        if (onSelect != null) add(RowMenuAction("Выбрать несколько", Icons.Outlined.Checklist, action = onSelect))
                        if (onMove != null) add(RowMenuAction(moveLabel, Icons.Outlined.SwapVert, action = onMove))
                        add(RowMenuAction(if (hidden) "Показать" else "Скрыть", if (hidden) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff, action = onHide))
                        if (onDetails != null) add(RowMenuAction("Настройки", Icons.Outlined.Tune, action = onDetails))
                    }
                    if (onSelect != null || onDetails != null) Label(hint, Modifier.padding(horizontal = 6.dp), size = 12, maxLines = 2)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        options.forEachIndexed { index, option ->
                            val focus = if (returnedToPalette) option.label == "Выбрать иконку" else index == 0
                            if (focus) InitialFocus { TvAction(option.label, it.size(44.dp), selected = option.selected,
                                icon = option.icon, iconOnly = true, onFocus = { hint = option.label }, onClick = option.action) }
                            else TvAction(option.label, Modifier.size(44.dp), selected = option.selected, icon = option.icon,
                                iconOnly = true, onFocus = { hint = option.label }, onClick = option.action)
                        }
                    }
                }
            }
        }
    }
}

private data class RowMenuAction(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val selected: Boolean = false, val action: () -> Unit)

internal fun Modifier.moveKeys(active: Boolean, saving: Boolean, move: (Int) -> Unit, finish: (Boolean) -> Unit): Modifier =
    onPreviewKeyEvent { event ->
        if (!active) false else when (event.key) {
            Key.DirectionUp, Key.DirectionDown -> { if (!saving && event.type == KeyEventType.KeyDown) move(if (event.key == Key.DirectionUp) -1 else 1); true }
            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { if (!saving && event.type == KeyEventType.KeyUp) finish(true); true }
            Key.Back -> { if (!saving && event.type == KeyEventType.KeyUp) finish(false); true }
            Key.DirectionLeft, Key.DirectionRight -> true
            else -> false
        }
    }

@Composable
internal fun MoveHint(saving: Boolean) { Label(if (saving) "Сохранение…" else "↑↓ — место · OK — сохранить · Назад — отменить", size = 12, color = LocalAccent.current, maxLines = 2) }
