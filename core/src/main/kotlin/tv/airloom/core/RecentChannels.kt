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
}
