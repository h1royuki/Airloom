package tv.airloom.core

/** Commands contain stable catalogue identities, never stream addresses. */
sealed interface ListMutation {
    data class Channels(val ids: Set<String>, val hidden: Boolean? = null, val favourite: Boolean? = null) : ListMutation
    data class Categories(val playlistId: String, val names: Set<String>, val hidden: Boolean? = null, val pinned: Boolean? = null) : ListMutation
    data class Pin(val scope: ChannelListScope, val id: String, val pinned: Boolean) : ListMutation
    data class ChannelOrder(val scope: ChannelListScope, val ids: List<String>) : ListMutation
    data class CategoryOrder(val playlistId: String, val names: List<String>) : ListMutation
    data class Icon(val playlistId: String, val name: String, val icon: String?) : ListMutation
    data class Binding(val id: String, val epg: EpgChannel?) : ListMutation
}

enum class ListVisibility { ALL, VISIBLE, HIDDEN }
object ListManagement {
    fun hidden(channel: Channel, categories: List<Category>): Boolean = channel.hidden || categories.any {
        it.playlistId == channel.playlistId && it.name == channel.group && it.hidden
    }
    fun accepts(hidden: Boolean, visibility: ListVisibility) = when (visibility) {
        ListVisibility.ALL -> true
        ListVisibility.VISIBLE -> !hidden
        ListVisibility.HIDDEN -> hidden
    }
    fun channels(channels: List<Channel>, scope: ChannelListScope): List<Channel> = channels.filter {
        it.playlistId == scope.playlistId && when (scope.kind) {
            ChannelListKind.ALL -> true
            ChannelListKind.FAVOURITES -> it.favourite
            ChannelListKind.CATEGORY -> it.group == scope.category
        }
    }
    fun sameMembers(before: List<String>, after: List<String>) = before.size == before.toSet().size && before.toSet() == after.toSet()
    fun focusAfterRemoval(id: String?, previous: List<String>, current: List<String>): String? {
        if (id in current) return id
        val index = previous.indexOf(id).coerceAtLeast(0)
        return previous.drop(index + 1).firstOrNull { it in current }
            ?: previous.take(index).lastOrNull { it in current } ?: current.firstOrNull()
    }
}
