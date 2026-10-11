package tv.airloom.data

import kotlinx.coroutines.flow.*
import tv.airloom.core.*

/** Main-safe catalogue projections; unchanged staging rows do not rebuild UI models. */
internal class CatalogueReader(private val dao: AirloomDao, private val runtime: AirloomRuntime) {
    fun channels(playlistId: String?, includeHidden: Boolean) = combine(dao.channels(playlistId).distinctUntilChanged(), dao.preferences().distinctUntilChanged(), dao.bindings().distinctUntilChanged(), dao.currentEpgChannels().distinctUntilChanged(), dao.providerProfile().distinctUntilChanged()) { channels, prefs, bindings, epg, provider ->
        val p = prefs.associateBy { it.channelId }; val b = bindings.associateBy { it.channelId }
        val icons = epg.associate { (it.sourceId to it.id) to it.icon }
        val profile = provider?.model()
        val rewrite = profile?.let(ProviderEndpoints::streamRewriter)
        channels.map { row ->
            val binding = b[row.id]
            val channel = row.model(p[row.id], binding).copy(epgLogo = binding?.let { icons[it.sourceId to it.epgChannelId] }.orEmpty())
            rewrite?.let { transform -> channel.copy(
                url = transform(channel.url),
                catchup = channel.catchup.copy(source = transform(channel.catchup.source)))
            } ?: channel
        }.filter { includeHidden || !it.hidden }.sortedWith(compareBy<Channel> { it.order }.thenBy { it.number })
    }.flowOn(runtime.computation)
    fun categories(playlistId: String?) = combine(dao.channels(playlistId).distinctUntilChanged(), dao.categoryPreferences().distinctUntilChanged()) { channels, prefs ->
        val p = prefs.associateBy { it.playlistId to it.name }
        channels.groupBy { it.playlistId to it.category }.map { (key, values) ->
            Category(key.first, key.second, values.size, p[key]?.hidden ?: false, p[key]?.position ?: values.minOf { it.number }, p[key]?.pinned ?: false, p[key]?.iconOverride)
        }.sortedBy { it.order }
    }.flowOn(runtime.computation)
    fun programmes(channelIds: List<String>, start: Long, end: Long): Flow<Map<String, List<Programme>>> {
        if (channelIds.isEmpty()) return flowOf(emptyMap())
        // UI supplies a visible viewport; chunking also keeps each query below SQLite's bind limit.
        val visibleSet = channelIds.toSet()
        val chunks = channelIds.distinct().chunked(400).map { dao.programmes(it, start, end).distinctUntilChanged() }
        return combine(combine(chunks) { it.flatMap { rows -> rows }.distinctBy { row -> Triple(row.sourceId, row.generation, row.id) } }, dao.bindings()) { values, bindings ->
            val lookup = values.groupBy { it.sourceId to it.channelId }
            bindings.filter { it.channelId in visibleSet }.associate { b -> b.channelId to lookup[b.sourceId to b.epgChannelId].orEmpty().map { it.model() }.sortedBy { it.start } }
        }.flowOn(runtime.computation)
    }
}
