package tv.airloom.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import tv.airloom.app.LoadPhase
import tv.airloom.app.R
import tv.airloom.app.Screen
import tv.airloom.app.UiActions
import tv.airloom.app.UiState
import tv.airloom.app.channelSelection
import tv.airloom.core.Category
import tv.airloom.core.CategoryIcons
import tv.airloom.core.ChannelSelection

internal class NavigationFocus {
    var open by mutableStateOf(false)
    val content = FocusRequester()
    /** The first button of the rail header: search. «Назад» and a second ← land here. */
    val header = FocusRequester()
    var returnFocus: FocusRequester? = null
}
internal val LocalNavigationFocus = staticCompositionLocalOf<NavigationFocus?> { null }

/** Collapsed rail: the mark and the icons of the channel lists. Search and settings sit in its header and show when it opens. */
internal val RailWidth = 80.dp
private val DrawerWidth = 296.dp
/** The open drawer is a card clear of the left, top and bottom screen edges, rounded on every corner. */
internal val DrawerInset = 12.dp
internal val RailSlot = 52.dp
private val RailItemHeight = 36.dp

/** One row of the rail. The player's channel card lists the same rows as its sections. */
internal sealed interface RailEntry {
    val key: String
    data object Favourites : RailEntry { override val key = "system:favourites" }
    data object Recent : RailEntry { override val key = "system:recent" }
    data object All : RailEntry { override val key = "system:all" }
    data class Group(val category: Category) : RailEntry { override val key = "${category.playlistId}:${category.name}" }
}

internal fun ChannelSelection.railKey(): String = when (this) {
    ChannelSelection.All -> RailEntry.All.key
    ChannelSelection.Favourites -> RailEntry.Favourites.key
    ChannelSelection.Recent -> RailEntry.Recent.key
    is ChannelSelection.Category -> "$playlistId:$name"
}

/** Rail order: the two system lists, pinned categories, all channels, then the rest. [visible] is already sorted. */
internal fun railEntries(visible: List<Category>): List<RailEntry> =
    listOf(RailEntry.Favourites, RailEntry.Recent) + visible.filter { it.pinned }.map(RailEntry::Group) +
        RailEntry.All + visible.filterNot { it.pinned }.map(RailEntry::Group)

/**
 * Screen frame: a navigation rail on the left and [content] beside it. Taking focus, the rail opens
 * into a labelled drawer drawn over the content; the content is dimmed but never moved or laid out again.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun NavigationLayout(state: UiState, actions: UiActions, translucent: Boolean = false, content: @Composable RowScope.() -> Unit) {
    val navigation = remember { NavigationFocus() }
    val manager = LocalFocusManager.current
    val palette = LocalPalette.current
    // Read only in draw blocks: opening the drawer redraws, it does not recompose or relayout.
    val expansion = animateFloatAsState(if (navigation.open) 1f else 0f, tween(motionDuration(200)), label = "navigation drawer")
    // «Назад» on the channel list opens the rail on its header. The exit hint is shown with it,
    // so leaving the app still takes two presses. Handlers inside the content are registered later and win.
    BackHandler(LocalUiInteractive.current && state.screen == Screen.GUIDE && state.detailChannel == null &&
        state.movingChannelId == null && state.movingCategory == null) {
        if (!navigation.open) runCatching { navigation.header.requestFocus() }
        actions.back()
    }
    CompositionLocalProvider(LocalNavigationFocus provides navigation) {
        Box(Modifier.fillMaxSize().background(palette.background.copy(alpha = if (translucent) .94f else 1f))) {
            Row(Modifier.fillMaxSize().padding(start = RailWidth).focusRequester(navigation.content)
                // A leftward search must not land on whichever rail item is level with the focus:
                // it is cancelled here and the rail opens on its own entry instead.
                .focusProperties { exit = { if (it == FocusDirection.Left) FocusRequester.Cancel else FocusRequester.Default } }
                .focusGroup()
                .onKeyEvent { event ->
                    if (event.key == Key.DirectionLeft && event.type == KeyEventType.KeyDown) {
                        if (!manager.moveFocus(FocusDirection.Left)) navigation.open = true
                        true
                    } else false
                }, content = content)
            Box(Modifier.fillMaxSize().drawBehind { val amount = expansion.value; if (amount > 0f) drawRect(Color.Black, alpha = .6f * amount) })
            // Only what the rail draws: focus moves and clock ticks elsewhere in the state leave it alone.
            // Search is a layer over this screen, so the rail under it keeps showing the channel selection.
            NavigationRail(state.screen.takeUnless { it == Screen.PLAYER || it == Screen.SEARCH } ?: Screen.GUIDE, state.channelSelection, state.categories,
                state.movingCategory, state.movingCategoryNames, state.moveSaving, state.categoryLoad.phase, actions, navigation, expansion)
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun NavigationRail(screen: Screen, selection: ChannelSelection, categories: List<Category>, movingCategory: Category?,
    movingNames: List<String>, moveSaving: Boolean, loadPhase: LoadPhase, actions: UiActions, navigation: NavigationFocus, expansion: State<Float>) {
    val palette = LocalPalette.current
    val selectedKey = if (screen == Screen.GUIDE) selection.railKey() else null
    val listState = rememberLazyListState()
    val requesters = remember { mutableMapOf<String, FocusRequester>() }
    val searchFocus = navigation.header
    val settingsFocus = remember { FocusRequester() }
    // Which header button holds the focus: 0 none, 1 search, 2 settings. Read by key handlers only.
    var headerFocus by remember { mutableIntStateOf(0) }
    var railFocused by remember { mutableStateOf(false) }
    var menuKey by remember { mutableStateOf<String?>(null) }
    var focusKey by remember { mutableStateOf<String?>(null) }
    var restore by remember { mutableIntStateOf(0) }
    var hiding by remember { mutableStateOf<Category?>(null) }
    val ordered: List<RailEntry> = remember(categories, movingCategory, movingNames) {
        val visible = categories.filterNot { it.hidden }.sortedBy {
            if (movingCategory?.playlistId == it.playlistId) movingNames.indexOf(it.name).takeIf { i -> i >= 0 } ?: it.order else it.order
        }
        railEntries(visible)
    }
    suspend fun focusEntry(key: String?) {
        val index = ordered.indexOfFirst { it.key == key }.coerceAtLeast(0)
        val target = ordered[index].key
        val info = listState.layoutInfo
        if (info.visibleItemsInfo.none { it.index == index && it.offset >= 0 && it.offset + it.size <= info.viewportEndOffset }) listState.scrollToItem(index)
        withFrameNanos { }
        runCatching { requesters[target]?.requestFocus() }
    }
    val toContent: () -> Unit = {
        if (runCatching { (navigation.returnFocus ?: navigation.content).requestFocus() }.isFailure) runCatching { navigation.content.requestFocus() }
    }
    // Opened from the content: land on the entry for the current screen, not on the nearest icon.
    LaunchedEffect(navigation.open) {
        if (!navigation.open || railFocused) return@LaunchedEffect
        when (screen) {
            Screen.SETTINGS -> runCatching { settingsFocus.requestFocus() }
            else -> focusEntry(selectedKey)
        }
        withFrameNanos { }
        // The entry may be missing while categories load; never leave the drawer open without focus in it.
        if (!railFocused) { runCatching { searchFocus.requestFocus() }; withFrameNanos { } }
        if (!railFocused) navigation.open = false
    }
    // A closed popup or a reordered list returns focus to the row the user was on.
    LaunchedEffect(ordered.map { it.key }, movingNames, restore) {
        if (navigation.open && menuKey == null && focusKey != null) focusEntry(focusKey)
    }
    // Hiding the focused category: focus steps to its neighbour first, so no focused row is ever removed.
    LaunchedEffect(hiding) {
        val category = hiding ?: return@LaunchedEffect
        focusEntry(focusKey)
        withFrameNanos { }
        actions.setCategoryHidden(category, true)
        hiding = null
    }
    // Collapsed, the rail shows the current selection, so it is also composed when the rail opens.
    LaunchedEffect(selectedKey, navigation.open) {
        if (!navigation.open && selectedKey != null) {
            val index = ordered.indexOfFirst { it.key == selectedKey }
            if (index >= 0 && listState.layoutInfo.visibleItemsInfo.none { it.index == index }) listState.scrollToItem(index)
        }
    }
    val choose: (() -> Unit) -> Unit = { select -> select(); if (screen != Screen.GUIDE) actions.navigate(Screen.GUIDE); toContent() }
    Column(
        Modifier.width(DrawerWidth).fillMaxHeight()
            .testTag("primary_navigation_rail")
            .semantics { stateDescription = if (navigation.open) "Меню развёрнуто" else "Меню свёрнуто" }
            .drawWithContent {
                val amount = expansion.value
                val visible = RailWidth.toPx() + (size.width - RailWidth.toPx()) * amount
                clipRect(right = visible) {
                    if (amount > 0f) {
                        val inset = DrawerInset.toPx()
                        drawRoundRect(palette.panel, Offset(inset, inset), Size(visible - inset, size.height - inset * 2), CornerRadius(28.dp.toPx()), alpha = amount)
                    }
                    this@drawWithContent.drawContent()
                }
            }
            .onFocusChanged {
                railFocused = it.hasFocus
                if (it.hasFocus) navigation.open = true
                else if (menuKey == null) { navigation.open = false; focusKey = null }
            }
            .onPreviewKeyEvent { event ->
                if (movingCategory != null) false else when (event.key) {
                    // → walks the header to its end, then returns to the content.
                    Key.DirectionRight -> {
                        if (event.type == KeyEventType.KeyDown) { if (headerFocus == 1) runCatching { settingsFocus.requestFocus() } else toContent() }
                        true
                    }
                    // A second ← takes the focus from the list to the header, so search and settings are close from any category.
                    Key.DirectionLeft -> {
                        if (event.type == KeyEventType.KeyDown && headerFocus != 1) runCatching { searchFocus.requestFocus() }
                        true
                    }
                    else -> false
                }
            }
            .focusProperties { exit = { if (it == FocusDirection.Up || it == FocusDirection.Down || it == FocusDirection.Left) FocusRequester.Cancel else FocusRequester.Default } }
            .focusGroup()
            .padding(start = DrawerInset + 10.dp, end = 12.dp, top = SafeVertical, bottom = SafeVertical),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(Modifier.fillMaxWidth().height(40.dp).testTag("primary_navigation_brand"), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(RailSlot), contentAlignment = Alignment.Center) { AirloomMark(Modifier.size(32.dp)) }
            Label("Airloom", Modifier.padding(start = 2.dp).graphicsLayer { alpha = expansion.value }.clearAndSetSemantics { }, size = 16, bold = true, maxLines = 1)
            Spacer(Modifier.weight(1f))
            // Laid out at the drawer's right edge, so the collapsed rail clips both buttons away.
            HeaderButton(stringResource(R.string.ui_search), Icons.Outlined.Search, false, Modifier.focusRequester(searchFocus),
                onFocus = { if (it) { headerFocus = 1; focusKey = null } else if (headerFocus == 1) headerFocus = 0 }) {
                actions.navigate(Screen.SEARCH)
            }
            Spacer(Modifier.width(4.dp))
            HeaderButton(stringResource(R.string.ui_settings), Icons.Outlined.Settings, screen == Screen.SETTINGS, Modifier.focusRequester(settingsFocus),
                onFocus = { if (it) { headerFocus = 2; focusKey = null } else if (headerFocus == 2) headerFocus = 0 }) {
                if (screen != Screen.SETTINGS) actions.navigate(Screen.SETTINGS) else toContent()
            }
        }
        Box(Modifier.padding(start = 12.dp, top = 5.dp, bottom = 5.dp).width(28.dp).height(1.dp).background(palette.line).testTag("guide_categories_separator"))
        if (movingCategory != null) MoveHint(moveSaving)
        if (delayedLoading(loadPhase == LoadPhase.LOADING)) SkeletonRows(count = 6, height = 36, logos = false)
        if (loadPhase == LoadPhase.ERROR) RetryLoad(stringResource(R.string.ui_categories_load_failed), actions::retryCatalogue)
        LazyColumn(state = listState, modifier = Modifier.weight(1f).testTag("guide_categories_list"), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            items(if (loadPhase == LoadPhase.LOADING) emptyList() else ordered, key = { it.key }) { entry ->
                val rowKey = entry.key
                val requester = requesters.getOrPut(rowKey) { FocusRequester() }
                val placement = Modifier.fillMaxWidth().animateItem(fadeInSpec = null, fadeOutSpec = null, placementSpec = tween(motionDuration(150)))
                when (entry) {
                    // The first row: ↑ leads to search, not to whichever header button is nearer.
                    RailEntry.Favourites -> RailItem(stringResource(R.string.ui_favourites), Icons.Outlined.FavoriteBorder, selectedKey == rowKey, expansion,
                        placement.focusRequester(requester).focusProperties { up = searchFocus }, onFocus = { focusKey = rowKey }) { choose { actions.selectCategory("Избранное") } }
                    RailEntry.Recent -> RailItem(stringResource(R.string.ui_recent_channels), Icons.Outlined.History, selectedKey == rowKey, expansion,
                        placement.focusRequester(requester), onFocus = { focusKey = rowKey }) { choose { actions.selectCategory("Последние") } }
                    RailEntry.All -> RailItem(stringResource(R.string.ui_all_channels), Icons.Outlined.GridView, selectedKey == rowKey, expansion,
                        placement.focusRequester(requester), onFocus = { focusKey = rowKey }) { choose { actions.selectCategory("Все каналы") } }
                    is RailEntry.Group -> {
                        val category = entry.category
                        val moving = movingCategory?.let { it.playlistId == category.playlistId && it.name == category.name } == true
                        Box(placement) {
                            RailItem((if (moving) "↕ " else "") + category.name, CategoryIcons.resolve(category.name, category.iconOverride).vector(),
                                selectedKey == rowKey || moving, expansion,
                                Modifier.focusRequester(requester).moveKeys(moving, moveSaving, actions::moveCategoryRow, actions::finishCategoryMove),
                                pinned = category.pinned, onFocus = { focusKey = rowKey },
                                onLongClick = if (moving) null else ({ menuKey = rowKey })) { choose { actions.selectCategory(category) } }
                            if (menuKey == rowKey) {
                                val close: () -> Unit = { menuKey = null; restore++ }
                                RowActionsPopup(category.name, category.pinned, close,
                                    iconOverride = category.iconOverride, onIcon = { icon -> close(); actions.setCategoryIcon(category, icon) },
                                    onPin = { close(); actions.setCategoryPinned(category, !category.pinned) },
                                    onMove = { close(); actions.startCategoryMove(category) },
                                    onHide = {
                                        val remaining = ordered.filterNot { it.key == rowKey }
                                        focusKey = remaining.getOrNull(ordered.indexOf(entry).coerceAtMost(remaining.lastIndex))?.key
                                        menuKey = null; hiding = category
                                    })
                            }
                        }
                    }
                }
            }
        }
        RemoteHints("←" to "поиск и настройки", modifier = Modifier.padding(start = 12.dp, top = 7.dp).graphicsLayer { alpha = expansion.value }.clearAndSetSemantics { })
    }
}

/** Round button of the rail header. Its name is a description only, so screen titles stay unique for accessibility and tests. */
@Composable
private fun HeaderButton(label: String, icon: ImageVector, selected: Boolean, modifier: Modifier, onFocus: (Boolean) -> Unit, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val palette = LocalPalette.current
    val interactive = LocalUiInteractive.current
    Box(modifier.size(36.dp)
        .focusHighlight(focused, if (selected) palette.accentContainer else Color.Transparent, CircleShape, 1.06f)
        .onFocusChanged { focused = it.isFocused; onFocus(it.isFocused) }
        .dpadClickable(onClick = onClick)
        .semantics(mergeDescendants = true) {
            role = Role.Button
            this.selected = selected
            contentDescription = label
            onClick { if (interactive) onClick(); interactive }
        }
        .focusable(interactive), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(22.dp), tint = when { focused -> palette.onFocus; selected -> palette.onAccentContainer; else -> palette.onVariant })
    }
}

/**
 * One rail row. Collapsed, only the icon and a pill around it are visible; the pill and the label
 * follow [expansion] while drawing.
 */
@Composable
internal fun RailItem(label: String, icon: ImageVector, selected: Boolean, expansion: State<Float>, modifier: Modifier = Modifier,
    pinned: Boolean = false, onFocus: () -> Unit = {}, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val palette = LocalPalette.current
    val interactive = LocalUiInteractive.current
    val focus = animateFloatAsState(if (focused) 1f else 0f, tween(motionDuration(120)), label = "rail focus")
    val foreground = when { focused -> palette.onFocus; selected -> palette.onAccentContainer; else -> palette.on }
    Row(modifier.fillMaxWidth().height(RailItemHeight)
        .graphicsLayer { val lift = 1f + .02f * focus.value; scaleX = lift; scaleY = lift }
        .drawBehind {
            val slot = RailSlot.toPx()
            val pill = Size(slot + (size.width - slot) * expansion.value, size.height)
            val radius = CornerRadius(size.height / 2)
            if (selected) drawRoundRect(palette.accentContainer, size = pill, cornerRadius = radius)
            if (focus.value > 0f) drawRoundRect(palette.focus, size = pill, cornerRadius = radius, alpha = focus.value)
        }
        .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocus() }
        .dpadClickable(onLongClick = onLongClick, onClick = onClick)
        .semantics(mergeDescendants = true) {
            role = Role.Button
            this.selected = selected
            if (onLongClick != null) onLongClick { if (interactive) onLongClick.invoke(); interactive }
            onClick { if (interactive) onClick(); interactive }
        }
        .focusable(interactive), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(RailSlot), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(22.dp), tint = if (focused || selected) foreground else palette.onVariant)
        }
        Row(Modifier.weight(1f).graphicsLayer { alpha = expansion.value }, verticalAlignment = Alignment.CenterVertically) {
            Label(label, Modifier.weight(1f).padding(start = 2.dp, end = 8.dp), size = 14, color = foreground, bold = focused || selected, maxLines = 1)
            if (pinned) Icon(Icons.Outlined.PushPin, "Закреплён", Modifier.padding(end = 14.dp).size(14.dp), tint = foreground)
        }
    }
}
