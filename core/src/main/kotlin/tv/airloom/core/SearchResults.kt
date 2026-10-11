package tv.airloom.core

data class SearchLiveResult(val channel: Channel, val programme: Programme? = null)
data class SearchResults(val live: List<SearchLiveResult>, val archive: List<Pair<Channel, Programme>>)

object SearchResultBuilder {
    fun build(query: String, channels: List<Channel>, programmeHits: List<Pair<Channel, Programme>>,
              current: Map<String, List<Programme>>, now: Long): SearchResults {
        if (query.isBlank()) return SearchResults(emptyList(), emptyList())
        val allowed = channels.filterNot { it.hidden }.associateBy { it.id }
        val hits = programmeHits.filter { it.first.id in allowed && it.second.end > it.second.start }
        val liveChannels = (channels.filter { !it.hidden && it.name.contains(query, true) } +
            hits.filter { (_, p) -> p.start <= now && p.end > now }.map { allowed.getValue(it.first.id) })
            .distinctBy { it.id }.sortedWith(compareBy<Channel> { it.order }.thenBy { it.number }).take(100)
        val live = liveChannels.map { channel ->
            SearchLiveResult(channel, current[channel.id].orEmpty().firstOrNull { it.start <= now && it.end > now }
                ?: hits.firstOrNull { it.first.id == channel.id && it.second.start <= now && it.second.end > now }?.second)
        }
        val archive = hits.filter { (channel, programme) -> programme.end <= now &&
            CatchupResolver.resolve(PlaybackRequest.Archive(allowed.getValue(channel.id), programme), now) is CatchupResult.Available }
            .distinctBy { Triple(it.first.id, it.second.sourceId, it.second.id) }
            .sortedByDescending { it.second.start }.take(100)
        return SearchResults(live, archive)
    }
}
