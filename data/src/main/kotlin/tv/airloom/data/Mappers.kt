package tv.airloom.data

import tv.airloom.core.*
internal fun ProviderProfileEntity.model() = ProviderProfile(accessKey, baseOrigin, playlistOrigin, epgOrigin, logoOrigin, streamOrigin, enumValueOf(epgVariant))
internal fun ProviderProfile.entity() = ProviderProfileEntity(accessKey = key, baseOrigin = baseOrigin, playlistOrigin = playlistOrigin, epgOrigin = epgOrigin, logoOrigin = logoOrigin, streamOrigin = streamOrigin, epgVariant = epgVariant.name)

internal fun PlaylistEntity.model() = PlaylistSource(id, name, location, SourceKind.valueOf(kind), userAgent, referer, lastSuccess, lastError, channelCount)
internal fun PlaylistSource.entity() = PlaylistEntity(id, name, location, kind.name, userAgent, referer, lastSuccess, lastError, channelCount)
internal fun EpgSourceEntity.model() = EpgSource(id, playlistId, location, SourceKind.valueOf(kind), offsetMinutes, lastSuccess, lastError, programmeCount)
internal fun EpgSource.entity(generation: String) = EpgSourceEntity(id, playlistId, location, kind.name, offsetMinutes, lastSuccess, lastError, programmeCount, generation)
internal fun Channel.entity() = ChannelEntity(id, playlistId, name, url, group, tvgId, tvgName, logo, number, catchup.mode, catchup.source, catchup.days, catchup.correctionSeconds, userAgent, referer)
internal fun ChannelEntity.model(p: PreferenceEntity? = null, b: BindingEntity? = null) = Channel(id, playlistId, name, url, category, tvgId, tvgName, logo, number, CatchupSpec(catchupMode, catchupSource, catchupDays, correctionSeconds), userAgent, referer, p?.favourite ?: false, p?.hidden ?: false, p?.position ?: number, p?.favouritePosition ?: number, b?.sourceId, b?.epgChannelId)
internal fun ProgrammeEntity.model() = Programme(id, sourceId, channelId, title, start, end, description, category, catchupId)
internal fun Programme.entity(generation: String) = ProgrammeEntity(sourceId, generation, id, channelId, title, start, end, description, category, catchupId)

/** Reconcile only unique fallback identities; duplicate provider IDs never merge channels. */
internal fun reconcileChannels(incoming: List<Channel>, previous: List<Channel>): List<Channel> {
    val byUrl = previous.groupBy { it.url }.mapValues { ArrayDeque(it.value) }
    val key: (Channel) -> Triple<String, String, String> = { Triple(it.tvgId, it.name, it.group) }
    val fallback = previous.groupBy(key)
    val incomingCounts = incoming.groupingBy(key).eachCount()
    val used = mutableSetOf<String>()
    return incoming.map { channel ->
        val exact = byUrl[channel.url]?.firstOrNull { it.id !in used }
        val candidates = fallback[key(channel)].orEmpty()
        val unique = candidates.singleOrNull()?.takeIf { incomingCounts[key(channel)] == 1 && it.id !in used }
        val id = (exact ?: unique)?.id ?: channel.id.takeUnless { it in used } ?: java.util.UUID.randomUUID().toString()
        used += id
        channel.copy(id = id)
    }
}
private val epgNamePunctuation = Regex("[^\\p{L}\\p{N}]")
internal fun normalizeEpgName(name: String) = name.lowercase(java.util.Locale.ROOT).replace(epgNamePunctuation, "")

/** Index once per rematch; candidate normalization never runs in the channel lookup loop. */
internal class EpgMatcher(candidates: List<EpgChannel>) {
    private val byId = candidates.groupBy { it.id }
    private val byName = candidates.groupBy { normalizeEpgName(it.name) }

    fun binding(channel: Channel): EpgBinding? {
        val exact = if (channel.tvgId.isBlank()) emptyList() else byId[channel.tvgId].orEmpty()
        val match = if (exact.isNotEmpty()) exact.singleOrNull() else {
            val names = listOf(channel.tvgName, channel.name).filter { it.isNotBlank() }.map(::normalizeEpgName).toSet()
            names.flatMap { byName[it].orEmpty() }.distinctBy { it.sourceId to it.id }.singleOrNull()
        }
        return match?.let { EpgBinding(channel.id, it.sourceId, it.id) }
    }
}
internal fun automaticBinding(channel: Channel, candidates: List<EpgChannel>): EpgBinding? = EpgMatcher(candidates).binding(channel)
