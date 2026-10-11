package tv.airloom.core

enum class ChannelListKind { ALL, FAVOURITES, CATEGORY }
data class ChannelListScope(val playlistId: String, val kind: ChannelListKind, val category: String = "")
data class ChannelListPreference(val channelId: String, val pinned: Boolean = false, val position: Int? = null)
sealed interface ChannelSelection {
    data object All : ChannelSelection
    data object Favourites : ChannelSelection
    data object Recent : ChannelSelection
    data class Category(val playlistId: String, val name: String) : ChannelSelection

    companion object {
        fun legacy(name: String, playlistId: String = ""): ChannelSelection = when (name) {
            "Все каналы" -> All
            "Избранное" -> Favourites
            "Последние" -> Recent
            else -> Category(playlistId, name)
        }
    }
}

val ChannelSelection.label: String get() = when (this) {
    ChannelSelection.All -> "Все каналы"
    ChannelSelection.Favourites -> "Избранное"
    ChannelSelection.Recent -> "Последние"
    is ChannelSelection.Category -> name
}

object ChannelLists {
    fun scope(playlistId: String?, selection: ChannelSelection): ChannelListScope? = playlistId?.let {
        when (selection) {
            ChannelSelection.Recent -> null
            ChannelSelection.All -> ChannelListScope(it, ChannelListKind.ALL)
            ChannelSelection.Favourites -> ChannelListScope(it, ChannelListKind.FAVOURITES)
            is ChannelSelection.Category -> ChannelListScope(selection.playlistId.ifBlank { it }, ChannelListKind.CATEGORY, selection.name)
        }
    }

    fun scope(playlistId: String?, category: String): ChannelListScope? = playlistId?.let {
        when (category) {
            "Последние" -> null
            "Все каналы" -> ChannelListScope(it, ChannelListKind.ALL)
            "Избранное" -> ChannelListScope(it, ChannelListKind.FAVOURITES)
            else -> ChannelListScope(it, ChannelListKind.CATEGORY, category)
        }
    }
    fun ordered(channels: List<Channel>, scope: ChannelListScope?, preferences: List<ChannelListPreference>): List<Channel> {
        val byId = preferences.associateBy { it.channelId }
        return channels.sortedWith(compareByDescending<Channel> { byId[it.id]?.pinned == true }
            .thenBy { byId[it.id]?.position == null }
            .thenBy { byId[it.id]?.position ?: if (scope?.kind == ChannelListKind.FAVOURITES) it.favouriteOrder else it.order }
            .thenBy { it.number })
    }
    fun moved(ids: List<String>, id: String, delta: Int, eligible: Set<String>): List<String> {
        val positions = ids.indices.filter { ids[it] in eligible }
        val current = positions.indexOfFirst { ids[it] == id }
        if (current < 0) return ids
        val target = current + delta
        if (target !in positions.indices) return ids
        return ids.toMutableList().apply { val a = positions[current]; val b = positions[target]; this[a] = ids[b]; this[b] = ids[a] }
    }
}
