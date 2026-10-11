package tv.airloom.app

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import tv.airloom.app.ui.AirloomTheme
import tv.airloom.app.ui.ChannelsScreen
import tv.airloom.app.ui.NavigationLayout
import tv.airloom.app.ui.AirloomMark
import tv.airloom.app.ui.Lavender
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import tv.airloom.app.ui.SettingsScreen
import androidx.compose.ui.graphics.asAndroidBitmap
import android.graphics.Bitmap
import java.io.File
import tv.airloom.app.ui.TvAction
import tv.airloom.app.ui.logoImageRequest
import tv.airloom.app.ui.logoCandidates
import tv.airloom.app.ui.resolveLogo
import tv.airloom.core.Category
import tv.airloom.core.Channel
import tv.airloom.core.PlaylistSource
import tv.airloom.core.ProviderProfile
import tv.airloom.core.Programme
import tv.airloom.core.AppSettings

/** Isolated composables and synthetic sources: never opens or changes the user's playlists. */
@OptIn(ExperimentalTestApi::class)
class NavigationRailUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val route = AtomicReference<Screen?>(null)
    private val category = AtomicReference<String?>(null)
    private val backs = AtomicInteger()
    private val actions = Proxy.newProxyInstance(UiActions::class.java.classLoader, arrayOf(UiActions::class.java)) { proxy, method, args ->
        when (method.name) {
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.firstOrNull()
            "toString" -> "NavigationTestActions"
            "navigate" -> { route.set(args?.firstOrNull() as Screen); null }
            "back" -> { backs.incrementAndGet(); null }
            "selectCategory" -> { category.set((args?.firstOrNull() as? tv.airloom.core.Category)?.name ?: args?.firstOrNull() as String); null }
            else -> null
        }
    } as UiActions

    private val state = UiState(
        loading = false,
        screen = Screen.GUIDE,
        playlists = listOf(PlaylistSource("test", "Тестовый плейлист", "https://example.invalid/test.m3u")),
        selectedPlaylistId = "test",
        categories = listOf(Category("test", "Новости", 2), Category("test", "Кино", 3)),
    )
    private val collapsed = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Меню свёрнуто")
    private val expanded = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Меню развёрнуто")

    @Test fun logoResolvesAgainstPlaylistAndUsesChannelAccessHeaders() {
        val channel = Channel("channel", "test", "Тестовый канал", "https://example.invalid/live.m3u8",
            logo = "images/logo.png", userAgent = "TestPlayer/1", referer = "https://example.invalid/")
        val url = resolveLogo(channel.logo, "https://example.invalid/path/list.m3u")
        assertEquals("https://example.invalid/path/images/logo.png", url)
        val request = logoImageRequest(compose.activity, url!!, channel)
        assertEquals("TestPlayer/1", request.headers["User-Agent"])
        assertEquals("https://example.invalid/", request.headers["Referer"])
        assertEquals("https://example.invalid/path/My%20Logo.png",
            resolveLogo("My Logo.png", "https://example.invalid/path/list.m3u"))
        assertEquals(null, resolveLogo("ftp://example.invalid/logo.png", null))
        assertEquals(listOf("https://example.invalid/path/images/logo.png", "https://example.invalid/epg.png"),
            logoCandidates(channel.copy(epgLogo = "https://example.invalid/epg.png"), "https://example.invalid/path/list.m3u"))
        assertEquals(listOf("https://iptv.online/images/ico/pervysd.png"),
            logoCandidates(channel.copy(logo = "https://iptv.pm/images/ico/pervysd.png"), null, ProviderProfile(key = "FIXTURE")))
        assertEquals("https://iptv.online/epg/logo.png",
            logoCandidates(channel.copy(logo = "https://iptv.pm/epg/logo.png"), null, ProviderProfile(key = "FIXTURE")).first())
    }

    @Test fun railStaysOnScreenAndOpensOverContentWithDpadFocus() {
        val contentFocus = FocusRequester()
        compose.setContent {
            AirloomTheme {
                NavigationLayout(state, actions) {
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        TvAction("Содержимое", Modifier.align(Alignment.CenterStart).focusRequester(contentFocus)) { }
                    }
                }
                LaunchedEffect(Unit) { contentFocus.requestFocus() }
            }
        }
        val rail = compose.onNodeWithTag("primary_navigation_rail")
        val content = compose.onNodeWithText("Содержимое")
        content.assertIsFocused()
        rail.assert(collapsed)
        // Collapsed, the rail shows the mark and the channel sections. Search and settings share the mark's row
        // and lie beyond the 80 dp strip, inside the 296 dp drawer.
        val brand = compose.onNodeWithTag("primary_navigation_brand").assertIsDisplayed().getUnclippedBoundsInRoot()
        compose.onNodeWithContentDescription("Airloom").assertIsDisplayed()
        val search = compose.onNodeWithContentDescription("Поиск")
        val settings = compose.onNodeWithContentDescription("Настройки")
        val searchBounds = search.getUnclippedBoundsInRoot()
        val settingsBounds = settings.getUnclippedBoundsInRoot()
        assertTrue(searchBounds.left >= 80.dp && searchBounds.right <= settingsBounds.left && settingsBounds.right <= 296.dp)
        for (bounds in listOf(searchBounds, settingsBounds)) assertTrue(bounds.top >= brand.top && bounds.bottom <= brand.bottom)
        compose.onNodeWithTag("guide_categories_separator").assertIsDisplayed()
        for (name in listOf("Избранное", "Последние", "Все каналы", "Новости", "Кино")) compose.onNodeWithText(name).assertIsDisplayed()
        compose.onNodeWithText("Категории").assertDoesNotExist()
        compose.onNodeWithText("Каналы").assertDoesNotExist()
        compose.onNodeWithText("IPTV.ONLINE").assertDoesNotExist()
        val contentLeft = content.getUnclippedBoundsInRoot().left
        assertTrue(contentLeft >= 80.dp)

        // ← from the content opens the drawer on the current selection, not on the icon level with the focus.
        content.performKeyInput { pressKey(Key.DirectionLeft) }
        val all = compose.onNodeWithText("Все каналы")
        all.assertIsFocused()
        rail.assert(expanded)
        compose.waitForIdle()
        // The drawer is drawn over the content; the content itself does not move.
        assertEquals(contentLeft, content.getUnclippedBoundsInRoot().left)
        // The drawer is a card 12 dp clear of the screen edge: its rows keep a margin inside the card on both sides.
        val unfocusedRow = compose.onNodeWithText("Новости").getUnclippedBoundsInRoot()
        assertTrue(unfocusedRow.left >= 20.dp && unfocusedRow.right <= 286.dp)
        File(compose.activity.cacheDir, "synthetic-navigation.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        // ↑ from the first row leads to search in the header.
        all.performKeyInput { pressKey(Key.DirectionUp) }
        compose.onNodeWithText("Последние").assertIsFocused().performKeyInput { pressKey(Key.DirectionUp) }
        val favourites = compose.onNodeWithText("Избранное")
        favourites.assertIsFocused().performKeyInput { pressKey(Key.DirectionUp) }
        search.assertIsFocused().performKeyInput { pressKey(Key.DirectionUp) }
        search.assertIsFocused().performKeyInput { pressKey(Key.DirectionLeft) }
        search.assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals(Screen.SEARCH, route.get()) }
        File(compose.activity.cacheDir, "synthetic-navigation-header.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        // Inside the header ← and → switch the two buttons, ↓ returns to the list.
        search.performKeyInput { pressKey(Key.DirectionRight) }
        settings.assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals(Screen.SETTINGS, route.get()) }
        rail.assert(expanded)
        settings.performKeyInput { pressKey(Key.DirectionLeft) }
        search.assertIsFocused().performKeyInput { pressKey(Key.DirectionDown) }
        favourites.assertIsFocused().performKeyInput { pressKey(Key.DirectionUp) }

        // → from the last header button returns to the exact content target and collapses the drawer.
        search.assertIsFocused().performKeyInput { pressKey(Key.DirectionRight) }
        settings.assertIsFocused().performKeyInput { pressKey(Key.DirectionRight) }
        content.assertIsFocused()
        rail.assert(collapsed)

        // OK on a category selects it and hands focus back to the content.
        content.performKeyInput { pressKey(Key.DirectionLeft) }
        all.assertIsFocused().performKeyInput { pressKey(Key.DirectionDown) }
        val news = compose.onNodeWithText("Новости")
        news.assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals("Новости", category.get()) }
        content.assertIsFocused()
        rail.assert(collapsed)
        // Focus requested directly on a rail item opens the drawer as well.
        news.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        news.assertIsFocused()
        rail.assert(expanded)
        // A second ← jumps from any row to search: settings are four presses away from the content.
        news.performKeyInput { pressKey(Key.DirectionLeft) }
        search.assertIsFocused()
        rail.assert(expanded)
        search.performKeyInput { pressKey(Key.DirectionRight) }
        settings.assertIsFocused()
    }

    @Test fun brandFollowsAccentWithoutLosingTwoTone() {
        val accent = mutableStateOf(Lavender)
        compose.setContent {
            AirloomTheme(accent = accent.value) { AirloomMark(Modifier.size(44.dp)) }
        }
        for (color in listOf(Lavender, Color(0xFFA2E5D4), Color(0xFFABCFFF), Color(0xFFFFB49C))) {
            compose.runOnIdle { accent.value = color }
            compose.waitForIdle()
            val pixels = compose.onNodeWithContentDescription("Airloom").captureToImage().toPixelMap()
            val outer = pixels[pixels.width / 2, pixels.height * 30 / 108]
            val inner = pixels[pixels.width / 2, pixels.height * 75 / 108]
            val expectedInner = Color(color.red * .8f, color.green * .8f, color.blue * .8f)
            for ((expected, actual) in listOf(color to outer, expectedInner to inner)) {
                assertEquals(expected.red, actual.red, .015f)
                assertEquals(expected.green, actual.green, .015f)
                assertEquals(expected.blue, actual.blue, .015f)
            }
        }
    }

    @Test fun longCategoryNamesFitWithoutWrappingAtLargeTextSize() {
        val names = listOf("Познавательные", "Познавательные UA")
        compose.setContent {
            AirloomTheme(textScale = 1.15f) {
                NavigationLayout(state.copy(categories = names.map { Category("test", it, 3) }), actions) {
                    Box(Modifier.weight(1f).fillMaxHeight())
                }
            }
        }
        for (name in names) {
            val node = compose.onNodeWithText(name)
            node.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            node.assertIsFocused()
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertEquals(1, layouts.single().lineCount)
            org.junit.Assert.assertFalse(layouts.single().hasVisualOverflow)
        }
        File(compose.activity.cacheDir, "synthetic-wide-categories.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun longPressPinsCategoryAboveAllChannelsWithoutDuplicate() {
        val ui = mutableStateOf(state)
        val pinActions = Proxy.newProxyInstance(UiActions::class.java.classLoader, arrayOf(UiActions::class.java)) { proxy, method, args ->
            when (method.name) {
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "toString" -> "PinActions"
                "setCategoryPinned" -> {
                    val category = args!![0] as Category
                    ui.value = ui.value.copy(categories = ui.value.categories.map { if (it.name == category.name) it.copy(pinned = args[1] as Boolean) else it })
                    null
                }
                else -> null
            }
        } as UiActions
        compose.setContent { AirloomTheme { NavigationLayout(ui.value, pinActions) { Box(Modifier.weight(1f).fillMaxHeight()) } } }
        val cinema = compose.onNodeWithText("Кино")
        cinema.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        cinema.performSemanticsAction(SemanticsActions.OnLongClick) { it() }
        compose.onNodeWithTag("row_actions_popup").assertIsDisplayed()
        compose.onNodeWithContentDescription("Закрепить").performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()
        assertTrue(cinema.getUnclippedBoundsInRoot().top < compose.onNodeWithText("Все каналы").getUnclippedBoundsInRoot().top)
        cinema.assertIsFocused()
        compose.onAllNodesWithText("Кино").assertCountEquals(1)
        cinema.performSemanticsAction(SemanticsActions.OnLongClick) { it() }
        compose.onNodeWithContentDescription("Открепить").performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()
        assertTrue(cinema.getUnclippedBoundsInRoot().top > compose.onNodeWithText("Все каналы").getUnclippedBoundsInRoot().top)
        cinema.assertIsFocused()
    }

    @Test fun categoryPaletteBackSelectionAndResetRestoreFocus() {
        val ui = mutableStateOf(state.copy(categories = state.categories.map { if (it.name == "Кино") it.copy(pinned = true) else it }))
        val iconActions = Proxy.newProxyInstance(UiActions::class.java.classLoader, arrayOf(UiActions::class.java)) { proxy, method, args ->
            when (method.name) {
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "toString" -> "IconActions"
                "setCategoryIcon" -> {
                val category = args!![0] as Category
                ui.value = ui.value.copy(categories = ui.value.categories.map { if (it.name == category.name) it.copy(iconOverride = args[1] as String?) else it })
                    null
                }
                else -> null
            }
        } as UiActions
        compose.setContent { AirloomTheme(textScale = 1.15f, accent = Color(0xFFA2E5D4)) { NavigationLayout(ui.value, iconActions) { Box(Modifier.weight(1f).fillMaxHeight()) } } }
        val cinema = compose.onNodeWithText("Кино")
        cinema.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        cinema.performSemanticsAction(SemanticsActions.OnLongClick) { it() }
        compose.onNodeWithContentDescription("Выбрать иконку").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithContentDescription("Выбрать иконку").performKeyInput { pressKey(Key.DirectionCenter) }
        compose.onNodeWithContentDescription("Авто").assertIsFocused().performKeyInput { pressKey(Key.Back) }
        compose.onNodeWithTag("category_icon_picker").assertDoesNotExist()
        compose.onNodeWithContentDescription("Выбрать иконку").assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        val picker = compose.onNodeWithTag("category_icon_picker").getUnclippedBoundsInRoot()
        assertTrue(picker.right > picker.left && picker.bottom > picker.top)
        File(compose.activity.cacheDir, "synthetic-category-palette.png").outputStream().use {
            compose.onNodeWithTag("row_actions_popup").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.onNodeWithContentDescription("Авто").performKeyInput { pressKey(Key.DirectionRight); pressKey(Key.DirectionRight) }
        compose.onNodeWithContentDescription("Иконка: Кино").assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.onNodeWithTag("row_actions_popup").assertDoesNotExist()
        cinema.assertIsFocused()
        compose.runOnIdle { assertEquals("cinema", ui.value.categories.first { it.name == "Кино" }.iconOverride) }
        compose.onNodeWithContentDescription("Закреплён").assertIsDisplayed()
        cinema.performSemanticsAction(SemanticsActions.OnLongClick) { it() }
        compose.onNodeWithContentDescription("Выбрать иконку").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithContentDescription("Выбрать иконку").performKeyInput { pressKey(Key.DirectionCenter) }
        compose.onNodeWithContentDescription("Иконка: Кино").assertIsFocused().performKeyInput { pressKey(Key.DirectionLeft); pressKey(Key.DirectionLeft); pressKey(Key.DirectionCenter) }
        cinema.assertIsFocused()
        compose.runOnIdle { assertEquals(null, ui.value.categories.first { it.name == "Кино" }.iconOverride) }
    }

    @Test fun channelPopupHasOnlyActionsAndBackRestoresRow() {
        val played = AtomicInteger()
        val localActions = Proxy.newProxyInstance(UiActions::class.java.classLoader, arrayOf(UiActions::class.java)) { proxy, method, args ->
            when (method.name) { "hashCode" -> System.identityHashCode(proxy); "equals" -> proxy === args?.firstOrNull(); "toString" -> "PopupActions"; "play" -> { played.incrementAndGet(); null }; else -> null }
        } as UiActions
        val channel = Channel("c", "test", "Канал", "fixture://c")
        compose.setContent { AirloomTheme { ChannelsScreen(state.copy(channels = listOf(channel), allChannels = listOf(channel), focusedChannel = channel, settings = AppSettings(preview = false)), localActions) } }
        val bounds = compose.onRoot().getUnclippedBoundsInRoot()
        val row = compose.onNodeWithContentDescription("Канал: Нет программы")
        row.assertIsFocused().performSemanticsAction(SemanticsActions.OnLongClick) { it() }
        val popup = compose.onNodeWithTag("row_actions_popup").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue(popup.left >= bounds.left && popup.right <= bounds.right && popup.top >= bounds.top && popup.bottom <= bounds.bottom)
        compose.onNodeWithContentDescription("Закрепить").assertIsFocused()
        compose.onNodeWithContentDescription("Перетянуть").assertIsDisplayed()
        compose.onNodeWithContentDescription("Скрыть").assertIsDisplayed()
        compose.onNodeWithContentDescription("Закрепить").performKeyInput { pressKey(Key.Back) }
        compose.onNodeWithTag("row_actions_popup").assertDoesNotExist()
        row.assertIsFocused()
        assertEquals(0, played.get())
    }

    @Test fun popupIconsAreHorizontalAndFavouriteToggles() {
        val favourite = mutableStateOf(false)
        compose.setContent { AirloomTheme {
            Box(Modifier.fillMaxSize()) {
                tv.airloom.app.ui.RowActionsPopup("Канал", false, {}, {}, {}, {},
                    favourite = favourite.value, onFavourite = { favourite.value = !favourite.value })
            }
        } }
        val labels = listOf("Закрепить", "В избранное", "Перетянуть", "Скрыть")
        val bounds = labels.map { compose.onNodeWithContentDescription(it).assertIsDisplayed().getUnclippedBoundsInRoot() }
        // The focused icon is drawn slightly larger, which shifts its reported origin by a fraction of its size.
        bounds.zipWithNext().forEach { (a, b) -> assertEquals(a.top.value, b.top.value, 2f); assertTrue(a.right < b.left) }
        labels.forEach { compose.onNodeWithText(it).assertDoesNotExist() }
        compose.onNodeWithContentDescription("Закрепить").performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNodeWithContentDescription("В избранное").assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertTrue(favourite.value) }
        compose.onNodeWithContentDescription("Убрать из избранного").performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertTrue(!favourite.value) }
    }

    @Test fun recentChannelPopupCannotPinOrReorder() {
        val channel = Channel("c", "test", "Канал", "fixture://c")
        compose.setContent { AirloomTheme { ChannelsScreen(state.copy(selectedCategory = "Последние", channels = listOf(channel), allChannels = listOf(channel), focusedChannel = channel, settings = AppSettings(preview = false)), actions) } }
        compose.onNodeWithContentDescription("Канал: Нет программы").performSemanticsAction(SemanticsActions.OnLongClick) { it() }
        compose.onNodeWithContentDescription("Закрепить").assertDoesNotExist()
        compose.onNodeWithContentDescription("Перетянуть").assertDoesNotExist()
        compose.onNodeWithContentDescription("В избранное").assertIsFocused()
    }

    @Test fun popupPositionFitsAtEveryWindowEdge() {
        val provider = tv.airloom.app.ui.RowMenuPosition(8)
        val window = androidx.compose.ui.unit.IntSize(1000, 600)
        val size = androidx.compose.ui.unit.IntSize(224, 240)
        for (x in listOf(0, 300, 950)) for (y in listOf(0, 250, 570)) {
            val anchor = androidx.compose.ui.unit.IntRect(x, y, x + 50, y + 30)
            val p = provider.calculatePosition(anchor, window, androidx.compose.ui.unit.LayoutDirection.Ltr, size)
            assertTrue(p.x >= 0 && p.y >= 0 && p.x + size.width <= window.width && p.y + size.height <= window.height)
            if (x == 950) assertTrue(p.x + size.width < anchor.left)
        }
    }

    @Test fun settingsMenuReturnsToExactContentTarget() {
        compose.setContent { AirloomTheme { SettingsScreen(state.copy(screen = Screen.SETTINGS), actions) } }
        compose.onNodeWithText("К каналам").assertDoesNotExist()
        val content = compose.onNodeWithTag("settings_section_CONNECTION")
        content.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        content.performKeyInput { pressKey(Key.DirectionLeft) }
        compose.onNodeWithContentDescription("Настройки").assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionRight) }
        content.assertIsFocused()
        compose.onNodeWithTag("primary_navigation_rail").assert(collapsed)
    }

    @Test fun channelWithoutArchiveShowsCurrentAndNextProgramme() {
        val visible = mutableStateOf(true)
        val description = mutableStateOf("")
        val channel = Channel("channel", "test", "Тестовый канал", "https://example.invalid/live.m3u8")
        val now = 1_000_000L
        val past = Programme("past", "epg", "channel", "Старая передача", now - 200_000, now - 100_000)
        val current = Programme("current", "epg", "channel", "Текущая передача", now - 50_000, now + 50_000)
        val next = Programme("next", "epg", "channel", "Следующая передача", now + 50_000, now + 150_000)
        compose.setContent {
            AirloomTheme {
                if (visible.value) ChannelsScreen(
                    state.copy(screen = Screen.PLAYER, channels = listOf(channel), allChannels = listOf(channel),
                        focusedChannel = channel, now = now, programmes = mapOf(channel.id to listOf(past, current.copy(description = description.value), next)),
                        settings = AppSettings(preview = false)),
                    actions,
                    overlay = true,
                )
            }
        }
        compose.waitForIdle()
        compose.onAllNodesWithText("Текущая передача").assertAny(hasText("Текущая передача"))
        compose.onNodeWithText("Следующая передача").assertExists()
        compose.onNodeWithText("Старая передача").assertDoesNotExist()
        compose.onNodeWithText("Описание передачи отсутствует").assertDoesNotExist()
        compose.runOnIdle { description.value = "Тестовое описание передачи" }
        compose.onNodeWithText("Тестовое описание передачи").assertIsDisplayed()
        compose.runOnIdle { description.value = "  " }
        compose.onNodeWithText("Тестовое описание передачи").assertDoesNotExist()
        compose.onNodeWithText("Описание передачи отсутствует").assertDoesNotExist()
        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
        compose.onNodeWithText("Следующая передача").assertDoesNotExist()
    }

    @Test fun channelListFocusReachesRailAndEmptyProgrammeWithDpad() {
        val channel = Channel("channel", "test", "Тестовый канал", "https://example.invalid/live.m3u8")
        compose.setContent {
            AirloomTheme {
                ChannelsScreen(state.copy(channels = listOf(channel), allChannels = listOf(channel),
                    focusedChannel = channel, settings = AppSettings(preview = false)), actions)
            }
        }
        compose.waitForIdle()
        // The list is titled by the current selection and keeps the TV-safe margins.
        compose.onNode(hasText("Все каналы") and hasAnyAncestor(hasTestTag("channels_header"))).assertIsDisplayed()
        val root = compose.onRoot().getUnclippedBoundsInRoot()
        val list = compose.onNodeWithTag("channels_list").getUnclippedBoundsInRoot()
        assertTrue(list.left >= 80.dp && list.bottom <= root.bottom - 27.dp)
        val header = compose.onNodeWithTag("channels_header").getUnclippedBoundsInRoot()
        assertTrue(header.top >= 27.dp)
        val row = compose.onNodeWithContentDescription("Тестовый канал: Нет программы")
        row.assertIsFocused()
        row.performKeyInput { pressKey(Key.DirectionLeft) }
        compose.onNode(hasText("Все каналы") and hasAnyAncestor(hasTestTag("primary_navigation_rail"))).assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionRight) }
        row.assertIsFocused()
        row.performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNodeWithTag("programme_empty_day").assertIsFocused()
        compose.onNodeWithText("Смотреть").assertDoesNotExist()
    }

    @Test fun backFromChannelListOpensRailHeaderAndKeepsExitHint() {
        val channel = Channel("channel", "test", "Тестовый канал", "https://example.invalid/live.m3u8")
        compose.setContent {
            AirloomTheme {
                ChannelsScreen(state.copy(channels = listOf(channel), allChannels = listOf(channel),
                    focusedChannel = channel, settings = AppSettings(preview = false)), actions)
            }
        }
        val rail = compose.onNodeWithTag("primary_navigation_rail")
        val row = compose.onNodeWithContentDescription("Тестовый канал: Нет программы")
        row.assertIsFocused()
        rail.assert(collapsed)
        // «Назад» opens the rail on search and still reaches the exit logic, which shows its hint.
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        val search = compose.onNodeWithContentDescription("Поиск")
        search.assertIsFocused()
        rail.assert(expanded)
        compose.runOnIdle { assertEquals(1, backs.get()) }
        // Settings are three presses from the channel: «Назад», →, OK.
        search.performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNodeWithContentDescription("Настройки").assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals(Screen.SETTINGS, route.get()) }
        // With the rail open «Назад» goes straight to the exit logic and moves nothing.
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithContentDescription("Настройки").assertIsFocused()
        compose.runOnIdle { assertEquals(2, backs.get()) }
        compose.onNodeWithContentDescription("Настройки").performKeyInput { pressKey(Key.DirectionRight) }
        row.assertIsFocused()
        rail.assert(collapsed)
    }
}
