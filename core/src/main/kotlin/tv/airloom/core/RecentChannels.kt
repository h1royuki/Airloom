package tv.airloom.core

/** Most recently opened first; identifiers only, never stream addresses. */
object RecentChannels {
    const val LIMIT = 50
    fun normalize(ids: List<String>): List<String> = ids.filter { it.isNotBlank() }.distinct().take(LIMIT)
    fun opened(ids: List<String>, channelId: String): List<String> = normalize(listOf(channelId) + ids)
    fun available(ids: List<String>, channels: List<Channel>): List<Channel> {
        val byId = channels.associateBy { it.id }
        return normalize(ids).mapNotNull(byId::get)
    }
    /** Keeps the rows already shown in place: channels new to the list go on top, removed ones drop out. */
    fun keepingOrder(channels: List<Channel>, shown: List<String>): List<Channel> {
        val position = shown.withIndex().associate { it.value to it.index }
        val (known, added) = channels.partition { it.id in position }
        return added + known.sortedBy { position.getValue(it.id) }
    }
}
