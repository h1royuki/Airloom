package tv.airloom.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import tv.airloom.app.*
import tv.airloom.core.*

@Composable
internal fun ListManager(state: UiState, actions: UiActions) {
    val playlistId = state.selectedPlaylistId ?: state.playlists.firstOrNull()?.id ?: state.allChannels.firstOrNull()?.playlistId
    var kind by rememberSaveable { mutableStateOf<ChannelListKind?>(null) }
    var category by rememberSaveable { mutableStateOf("") }
    val holder = rememberSaveableStateHolder()
    val listScope = playlistId?.let { id -> kind?.let { ChannelListScope(id, it, if (it == ChannelListKind.CATEGORY) category else "") } }
    LaunchedEffect(listScope) { actions.openManagerScope(listScope) }
    DisposableEffect(Unit) { onDispose { actions.openManagerScope(null) } }
    val back = { kind = null; Unit }
    BackHandler(kind != null && !state.managerSaving, onBack = back)
    holder.SaveableStateProvider(if (kind == null) "root" else "${kind?.name}:$category") {
        ManagerList(state, actions, playlistId, listScope, back) { next ->
            category = next.category; kind = next.kind
        }
    }
}

private data class ManagedRow(val id: String, val title: String, val hidden: Boolean, val pinned: Boolean,
    val subtitle: String, val channel: Channel? = null, val category: Category? = null)
private enum class BatchAction(val title: String) {
    SHOW("Показать выбранные"), HIDE("Скрыть выбранные"), FAVOURITE("Добавить в избранное"), UNFAVOURITE("Убрать из избранного")
}

@Composable
private fun ManagerList(state: UiState, actions: UiActions, playlistId: String?, listScope: ChannelListScope?,
    back: () -> Unit, open: (ChannelListScope) -> Unit) {
    val root = listScope == null
    val categories = state.categories.filter { playlistId == null || it.playlistId == playlistId }
    val category = categories.find { it.name == listScope?.category }
    val preferences = state.managerPreferences.associateBy { it.channelId }
    val rows = if (root) categories.sortedWith(compareByDescending<Category> { it.pinned }.thenBy { it.order }).map {
        ManagedRow(it.name, it.name, it.hidden, it.pinned, "${it.count} каналов" +
            (if (it.pinned) " · Закреплена" else "") + (if (it.hidden) " · Категория скрыта" else ""), category = it)
    } else ChannelLists.ordered(ListManagement.channels(state.allChannels, listScope!!), listScope, state.managerPreferences).map { channel ->
        val hiddenCategory = categories.any { it.playlistId == channel.playlistId && it.name == channel.group && it.hidden }
        ManagedRow(channel.id, channel.name, channel.hidden || hiddenCategory, preferences[channel.id]?.pinned == true,
            listOfNotNull(channel.group.takeIf { listScope.kind != ChannelListKind.CATEGORY },
                "Избранное".takeIf { channel.favourite }, "Закреплён".takeIf { preferences[channel.id]?.pinned == true },
                "Канал скрыт".takeIf { channel.hidden }, "Категория скрыта".takeIf { hiddenCategory }).joinToString(" · "), channel = channel)
    }
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(ListVisibility.ALL) }
    var selecting by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var lastFocus by rememberSaveable { mutableStateOf<String?>(null) }
    var modeAtStart by remember { mutableStateOf(false) }
    var popupId by remember { mutableStateOf<String?>(null) }
    var detail by remember { mutableStateOf<ManagedRow?>(null) }
    var binding by remember { mutableStateOf(false) }
    var iconPicker by remember { mutableStateOf(false) }
    var epgQuery by remember { mutableStateOf("") }
    var batchMenu by remember { mutableStateOf(false) }
    var batch by remember { mutableStateOf<BatchAction?>(null) }
    var pending by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf<List<String>?>(null) }
    var orderMembers by remember { mutableStateOf<Map<String, Boolean>?>(null) }
    var moving by remember { mutableStateOf<String?>(null) }
    var beforeMove by remember { mutableStateOf<List<String>>(emptyList()) }
    var restore by remember { mutableIntStateOf(0) }
    var previousIds by remember { mutableStateOf<List<String>>(emptyList()) }
    val requesters = remember { mutableMapOf<String, FocusRequester>() }
    val listState = rememberLazyListState()
    val coroutine = rememberCoroutineScope()
    val matches = rows.filter { it.title.contains(query.trim(), ignoreCase = true) }
    val filtered = matches.filter { ListManagement.accepts(it.hidden, filter) }
    val byId = rows.associateBy { it.id }
    val displayed = draft?.mapNotNull(byId::get) ?: filtered
    val ids = displayed.map { it.id }
    val headerFocus = remember { FocusRequester() }
    val modeFocus = remember { FocusRequester() }
    val ready = root || state.managerScope == listScope && !state.managerLoading
    val busy = state.managerSaving
    val title = when (listScope?.kind) {
        null -> "Каналы и категории"
        ChannelListKind.ALL -> "Все каналы"
        ChannelListKind.FAVOURITES -> "Избранное"
        ChannelListKind.CATEGORY -> listScope.category
    }
    fun closeDetail() { detail = null; binding = false; iconPicker = false; restore++ }
    fun startOrder(id: String? = null) {
        draft = rows.map { it.id }; orderMembers = rows.associate { it.id to it.pinned }
        moving = id; beforeMove = draft.orEmpty(); selecting = false; selected = arrayListOf()
        detail = null; notice = null; restore++
    }
    fun endOrder() { draft = null; moving = null; orderMembers = null; restore++ }
    fun cancel() {
        when {
            moving != null -> { draft = beforeMove; moving = null; restore++ }
            draft != null -> endOrder()
            selecting -> { selecting = false; selected = arrayListOf(); restore++ }
            !root -> back()
        }
    }
    BackHandler(!busy && (moving != null || draft != null || selecting), ::cancel)
    BackHandler(busy) {}
    // A hidden item stays editable even after no longer matching the active filter.
    val currentDetail = detail?.let { cached ->
        if (root) categories.find { it.name == cached.id }?.let { cached.copy(category = it, hidden = it.hidden, pinned = it.pinned) }
        else state.allChannels.find { it.id == cached.id }?.let { cached.copy(channel = it, pinned = preferences[it.id]?.pinned == true) }
    }
    LaunchedEffect(rows.map { it.id to it.pinned }, busy) {
        val liveIds = rows.map { it.id }.toSet()
        selected = ArrayList(selected.filter { it in liveIds })
        if (!busy && draft != null && orderMembers != rows.associate { it.id to it.pinned }) {
            endOrder(); notice = "Список изменился. Несохранённый порядок отменён."
        }
        if (popupId != null && popupId !in liveIds) { popupId = null; restore++ }
        if (detail != null && currentDetail == null) { closeDetail(); notice = "Элемент больше не входит в каталог." }
    }
    LaunchedEffect(state.managerRevision) {
        if (pending != null) {
            when (pending) {
                "batch" -> { selecting = false; selected = arrayListOf(); batch = null; batchMenu = false }
                "order" -> endOrder()
                "binding" -> binding = false
            }
            pending = null; notice = "Изменения сохранены"; restore++
        }
    }
    LaunchedEffect(restore, ready, busy) {
        if (!busy && ready && detail == null && popupId == null) {
            withFrameNanos { }
            val target = if (lastFocus == null) null else ListManagement.focusAfterRemoval(lastFocus, previousIds, ids)
            if (target != null) {
                val index = displayed.indexOfFirst { it.id == target }
                listState.scrollToItem(index + 1)
                withFrameNanos { }; withFrameNanos { }
                runCatching { requesters[target]?.requestFocus() }
            } else runCatching { headerFocus.requestFocus() }
        }
    }
    fun mutate(command: ListMutation, purpose: String = "detail") { pending = purpose; actions.mutateList(command) }
    Column(Modifier.fillMaxSize().testTag("list_manager"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!ready) { Label("Загрузка списка…", color = Muted); return@Column }
        LazyColumn(Modifier.weight(1f).testTag("manager_list"), state = listState, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            item(key = "header") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!root) TvAction("К категориям", icon = Icons.Outlined.ArrowBack, enabled = !busy && draft == null, onClick = back)
                    SectionTitle(title, if (root) "OK — открыть · Удерживайте OK — действия со списком" else "OK — настройки канала · Удерживайте OK — действия")
                    if (category?.hidden == true) {
                        Label("Категория скрыта на главном экране. Все её каналы доступны здесь.", size = 12, color = Muted)
                        TvAction("Показать категорию", enabled = !busy && draft == null) { mutate(ListMutation.Categories(category.playlistId, setOf(category.name), hidden = false)) }
                    }
                    if (draft == null) {
                        Entry(if (root) "Найти категорию" else "Найти канал", query, { query = it; selected = arrayListOf() }, enabled = !busy)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            ListVisibility.entries.forEach { value ->
                                val name = when (value) { ListVisibility.ALL -> "Все"; ListVisibility.VISIBLE -> "Видимые"; ListVisibility.HIDDEN -> "Скрытые" }
                                TvAction("$name · ${matches.count { ListManagement.accepts(it.hidden, value) }}",
                                    (if (value == ListVisibility.ALL) Modifier.focusRequester(headerFocus) else Modifier).testTag("manager_filter_${value.name}"),
                                    selected = filter == value, enabled = !busy) { filter = value; selected = arrayListOf() }
                            }
                        }
                    } else {
                        Label(if (moving == null) "OK — выбрать строку для перемещения" else "↑ ↓ — переместить · OK — поставить · Назад — отменить перемещение", size = 12, color = Muted)
                    }
                    if (root && !selecting && draft == null && playlistId != null) {
                        SettingRow("Все каналы", "${state.allChannels.size} каналов · включая скрытые", modifier = Modifier.testTag("manager_all"), icon = Icons.Outlined.Tv) {
                            open(ChannelListScope(playlistId, ChannelListKind.ALL))
                        }
                        SettingRow("Избранное", "${state.allChannels.count { it.favourite }} каналов · включая скрытые", modifier = Modifier.testTag("manager_favourites"), icon = Icons.Outlined.FavoriteBorder) {
                            open(ChannelListScope(playlistId, ChannelListKind.FAVOURITES))
                        }
                        Label("Категории провайдера", size = 12, color = Muted)
                    }
                }
            }
            items(displayed, key = { it.id }) { row ->
                val requester = remember(row.id) { requesters.getOrPut(row.id) { FocusRequester() } }
                Box(Modifier.fillMaxWidth()) {
                    TvAction(row.title, Modifier.fillMaxWidth().focusRequester(requester).testTag("manager_row_${row.id}")
                        .onPreviewKeyEvent { event ->
                            if (moving != row.id) {
                                if ((selecting || draft != null) && event.key == Key.DirectionRight) {
                                    if (event.type == KeyEventType.KeyDown) runCatching { modeFocus.requestFocus() }
                                    true
                                } else false
                            } else when (event.key) {
                                Key.DirectionUp, Key.DirectionDown -> {
                                    if (!busy && event.type == KeyEventType.KeyDown) {
                                        draft = ChannelLists.moved(draft.orEmpty(), row.id, if (event.key == Key.DirectionUp) -1 else 1,
                                            rows.filter { it.pinned == row.pinned }.map { it.id }.toSet())
                                        coroutine.launch { listState.animateScrollToItem(draft.orEmpty().indexOf(row.id) + 1) }
                                    }; true
                                }
                                Key.DirectionLeft, Key.DirectionRight -> true
                                else -> false
                            }
                        }, enabled = !busy, selected = row.id in selected || moving == row.id, quiet = true,
                        onFocus = { lastFocus = row.id; previousIds = ids },
                        onLongClick = if (!selecting && draft == null && !busy) ({ lastFocus = row.id; previousIds = ids; popupId = row.id }) else null,
                        icon = if (selecting) { if (row.id in selected) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank }
                            else if (draft != null) Icons.Outlined.DragHandle else if (root) CategoryIcons.resolve(row.title, row.category?.iconOverride).vector() else if (row.hidden) Icons.Outlined.VisibilityOff else Icons.Outlined.Tv,
                        leadingContent = if (!root && !selecting && draft == null) ({ ChannelLogo(row.channel, state.settings.logos, 18, Modifier.size(30.dp)) }) else null,
                        textContent = { foreground -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Label(row.title, size = 14, bold = true, color = foreground)
                            if (row.subtitle.isNotBlank()) Label(row.subtitle, size = 12, color = foreground.copy(alpha = .8f))
                        } }) {
                        lastFocus = row.id; previousIds = ids
                        when {
                            selecting -> selected = ArrayList(if (row.id in selected) selected - row.id else selected + row.id)
                            draft != null -> { if (moving == row.id) moving = null else { beforeMove = draft.orEmpty(); moving = row.id } }
                            root -> { lastFocus = row.id; open(ChannelListScope(playlistId!!, ChannelListKind.CATEGORY, row.id)) }
                            else -> detail = row
                        }
                    }
                    if (popupId == row.id) {
                        val close: () -> Unit = { popupId = null; restore++ }
                        RowActionsPopup(row.title, row.pinned, close,
                            onPin = { close(); mutate(if (root) ListMutation.Categories(playlistId!!, setOf(row.id), pinned = !row.pinned)
                                else ListMutation.Pin(listScope!!, row.id, !row.pinned)) },
                            onMove = { popupId = null; startOrder(row.id) },
                            moveLabel = "Изменить порядок",
                            onSelect = { close(); selecting = true; selected = arrayListOf(row.id) },
                            hidden = row.category?.hidden ?: row.channel?.hidden ?: false,
                            onHide = { close(); mutate(if (root) ListMutation.Categories(playlistId!!, setOf(row.id), hidden = !row.hidden)
                                else ListMutation.Channels(setOf(row.id), hidden = !row.channel!!.hidden)) },
                            favourite = row.channel?.favourite == true,
                            onFavourite = if (root) null else ({ close(); mutate(ListMutation.Channels(setOf(row.id), favourite = !row.channel!!.favourite)) }),
                            iconOverride = row.category?.iconOverride,
                            onIcon = if (!root) null else ({ icon -> close(); mutate(ListMutation.Icon(playlistId!!, row.id, icon)) }),
                            onDetails = { popupId = null; detail = row })
                    }
                }
            }
            if (displayed.isEmpty()) item(key = "empty") {
                Label(if (filter == ListVisibility.HIDDEN) "Скрытых элементов нет" else "Ничего не найдено", Modifier.padding(12.dp), color = Muted)
                TvAction("Показать все") { filter = ListVisibility.ALL; query = "" }
            }
        }
        if (selecting || draft != null) {
            Column(Modifier.fillMaxWidth().onPreviewKeyEvent { event ->
                if (modeAtStart && event.key == Key.DirectionLeft && event.type == KeyEventType.KeyDown) { restore++; true } else false
            }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Label(if (selecting) "Выбрано: ${selected.size} · → — действия · Назад — отмена" else
                    if (moving != null) "↑ ↓ — переместить · OK — поставить" else "→ — сохранить порядок · Назад — отмена", size = 12, color = Muted)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (selecting) {
                        TvAction("Действия", (if (selected.isNotEmpty()) Modifier.focusRequester(modeFocus) else Modifier).testTag("manager_batch"), onFocus = { modeAtStart = true }, enabled = selected.isNotEmpty() && !busy) { batchMenu = true }
                        TvAction(if (ids.isNotEmpty() && selected.containsAll(ids)) "Снять все" else "Все найденные", (if (selected.isEmpty()) Modifier.focusRequester(modeFocus) else Modifier).testTag("manager_select_all"), onFocus = { modeAtStart = selected.isEmpty() }, enabled = !busy) {
                            selected = if (selected.containsAll(ids)) arrayListOf() else ArrayList(ids)
                        }
                        TvAction("Отмена выбора", onFocus = { modeAtStart = false }, enabled = !busy) { selecting = false; selected = arrayListOf(); restore++ }
                    } else {
                        TvAction("Сохранить порядок", Modifier.focusRequester(modeFocus).testTag("manager_save_order"), onFocus = { modeAtStart = true }, enabled = !busy && moving == null) {
                            val command = if (root) ListMutation.CategoryOrder(playlistId!!, draft!!) else ListMutation.ChannelOrder(listScope!!, draft!!)
                            mutate(command, "order")
                        }
                        TvAction("Отмена", onFocus = { modeAtStart = false }, enabled = !busy) { endOrder() }
                    }
                }
            }
        }
        if (busy) Label("Сохранение…", size = 12, color = Muted)
        (state.managerError ?: notice)?.let { Label(it, size = 12, color = Muted) }
    }
    if (currentDetail != null) {
        val row = currentDetail
        val channel = row.channel
        val cat = row.category
        val parent = channel?.let { c -> categories.find { it.playlistId == c.playlistId && it.name == c.group } }
        fun dismissCard() { if (!busy) { if (binding) binding = false else if (iconPicker) iconPicker = false else closeDetail() } }
        AirloomDialog(if (binding) "Привязка телепрограммы" else if (iconPicker) "Иконка категории" else row.title, ::dismissCard, animateDismiss = false, dismissEnabled = !busy) {
            if (binding && channel != null) {
                Entry("Найти в телепрограмме", epgQuery, { epgQuery = it; actions.findEpg(it) }, enabled = !busy)
                InitialFocus { TvAction("Автоматическая привязка", it, enabled = !busy) { mutate(ListMutation.Binding(channel.id, null), "binding") } }
                state.epgCandidates.forEach { epg -> TvAction(epg.name, Modifier.fillMaxWidth(), enabled = !busy) { mutate(ListMutation.Binding(channel.id, epg), "binding") } }
                if (state.epgCandidates.isEmpty()) Label("Совпадений нет", color = Muted)
            } else if (iconPicker && cat != null) {
                CompositionLocalProvider(LocalUiInteractive provides !busy) {
                    CategoryIconPicker(cat.iconOverride) { mutate(ListMutation.Icon(cat.playlistId, cat.name, it)); iconPicker = false }
                }
            } else if (channel != null) {
                Label(listOfNotNull("Канал скрыт".takeIf { channel.hidden }, "Категория скрыта".takeIf { parent?.hidden == true }).joinToString(" · ").ifBlank { "Канал видим" }, size = 12, color = Muted)
                InitialFocus { TvAction(if (channel.favourite) "Убрать из избранного" else "Добавить в избранное", it.fillMaxWidth(), enabled = !busy) { mutate(ListMutation.Channels(setOf(channel.id), favourite = !channel.favourite)) } }
                TvAction(if (channel.hidden) "Показать канал" else "Скрыть канал", Modifier.fillMaxWidth().testTag("manager_channel_visibility"), enabled = !busy) { mutate(ListMutation.Channels(setOf(channel.id), hidden = !channel.hidden)) }
                if (parent?.hidden == true) {
                    Label("Чтобы канал появился на главном экране, покажите также его категорию.", size = 12, color = Muted)
                    TvAction("Показать категорию", enabled = !busy) { mutate(ListMutation.Categories(parent.playlistId, setOf(parent.name), hidden = false)) }
                }
                TvAction(if (row.pinned) "Открепить в этом списке" else "Закрепить в этом списке", enabled = !busy) { mutate(ListMutation.Pin(listScope!!, channel.id, !row.pinned)) }
                TvAction("Изменить порядок", enabled = !busy && rows.any { it.id == channel.id }) { startOrder(channel.id) }
                TvAction("Привязка телепрограммы", enabled = !busy) { epgQuery = channel.name; binding = true; actions.findEpg(epgQuery) }
            } else if (cat != null) {
                Label(if (cat.hidden) "Категория скрыта. Её список можно открыть без восстановления." else "Категория видима", size = 12, color = Muted)
                InitialFocus { TvAction("Открыть каналы", it.fillMaxWidth(), enabled = !busy) { closeDetail(); open(ChannelListScope(cat.playlistId, ChannelListKind.CATEGORY, cat.name)) } }
                TvAction(if (cat.hidden) "Показать категорию" else "Скрыть категорию", enabled = !busy) { mutate(ListMutation.Categories(cat.playlistId, setOf(cat.name), hidden = !cat.hidden)) }
                TvAction(if (cat.pinned) "Открепить категорию" else "Закрепить категорию", enabled = !busy) { mutate(ListMutation.Categories(cat.playlistId, setOf(cat.name), pinned = !cat.pinned)) }
                TvAction("Изменить порядок", enabled = !busy) { startOrder(cat.name) }
                TvAction("Выбрать иконку", enabled = !busy) { iconPicker = true }
            }
            if (busy) Label("Сохранение…", size = 12)
            state.managerError?.let { Label(it, size = 12, color = Muted) }
        }
    }
    if (batchMenu) AirloomDialog("Действия · выбрано ${selected.size}", { if (!busy) { batchMenu = false; restore++ } }, animateDismiss = false, dismissEnabled = !busy) {
        BatchAction.entries.filter { !root || it == BatchAction.SHOW || it == BatchAction.HIDE }.forEachIndexed { index, action ->
            if (index == 0) InitialFocus { TvAction(action.title, it.fillMaxWidth(), enabled = !busy) { batch = action; batchMenu = false } }
            else TvAction(action.title, Modifier.fillMaxWidth(), enabled = !busy) { batch = action; batchMenu = false }
        }
    }
    batch?.let { action ->
        AirloomDialog("${action.title}: ${selected.size}", { if (!busy) { batch = null; restore++ } }, animateDismiss = false, dismissEnabled = !busy) {
            val inHiddenCategory = rows.count { it.id in selected && it.channel?.let { c -> categories.any { cat -> cat.playlistId == c.playlistId && cat.name == c.group && cat.hidden } } == true }
            Label(if (root) "Индивидуальное скрытие каналов сохранится." else if (action == BatchAction.SHOW && inHiddenCategory > 0)
                "$inHiddenCategory из выбранных находятся в скрытых категориях. Снимется только скрытие самих каналов; категории останутся скрытыми."
                else "Порядок и привязки телепрограммы сохранятся.", size = 13, color = Muted)
            InitialFocus { TvAction("Применить", it.testTag("manager_apply_batch"), enabled = !busy && selected.isNotEmpty()) {
                val hidden = when (action) { BatchAction.SHOW -> false; BatchAction.HIDE -> true; else -> null }
                val command = if (root) ListMutation.Categories(playlistId!!, selected.toSet(), hidden = hidden)
                else ListMutation.Channels(selected.toSet(), hidden = hidden, favourite = when (action) { BatchAction.FAVOURITE -> true; BatchAction.UNFAVOURITE -> false; else -> null })
                mutate(command, "batch")
            } }
            if (busy) Label("Сохранение…", size = 12)
            state.managerError?.let { Label(it, size = 12, color = Muted) }
        }
    }
}
