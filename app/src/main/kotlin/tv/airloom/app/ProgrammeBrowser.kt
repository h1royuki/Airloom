package tv.airloom.app

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import tv.airloom.core.*

/** Null date follows today, including a midnight rollover. No browsing state is persisted to disk. */
data class ProgrammeBrowserState(
    val channelId: String? = null,
    val date: LocalDate? = null,
    val loadedDate: LocalDate? = null,
    val programmes: List<Programme> = emptyList(),
    val selected: Programme? = null,
    val load: ContentLoad = ContentLoad(LoadPhase.LOADING),
    val positionRevision: Int = 0,
)

internal data class ProgrammeProgress(val fraction: Float, val elapsedMinutes: Long, val remainingMinutes: Long)

internal object ProgrammeBrowserPolicy {
    fun hasArchive(channel: Channel): Boolean = channel.catchup.days > 0 && when (channel.catchup.mode.lowercase(java.util.Locale.ROOT)) {
        "flussonic", "shift" -> true
        "default", "append" -> channel.catchup.source.isNotBlank()
        else -> false
    }
    fun visible(channel: Channel, programmes: List<Programme>, now: Long): List<Programme> = programmes.filter {
        it.end > it.start && (it.end > now || CatchupResolver.resolve(PlaybackRequest.Archive(channel, it), now) is CatchupResult.Available)
    }

    fun progress(programme: Programme, now: Long): ProgrammeProgress {
        val duration = (programme.end - programme.start).coerceAtLeast(1)
        val elapsed = (now - programme.start).coerceIn(0, duration)
        return ProgrammeProgress((elapsed.toDouble() / duration).toFloat(), elapsed / 60_000, (duration - elapsed + 59_999) / 60_000)
    }
    fun today(now: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    fun earliest(channel: Channel, now: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate =
        today(now - (if (hasArchive(channel)) channel.catchup.days.coerceIn(0, 365) else 0).toLong() * 86_400_000L, zone)
    fun window(date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Pair<Long, Long> =
        date.atStartOfDay(zone).toInstant().toEpochMilli() to date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    fun same(a: Programme?, b: Programme?): Boolean = a != null && b != null && a.sourceId == b.sourceId && a.id == b.id
    fun select(rows: List<Programme>, previous: Programme?, now: Long, date: LocalDate, zone: ZoneId): Programme? =
        rows.firstOrNull { same(it, previous) } ?: if (previous != null) rows.minByOrNull { kotlin.math.abs(it.start - previous.start) }
        else if (date == today(now, zone)) rows.firstOrNull { it.start <= now && it.end > now }
            ?: rows.firstOrNull { it.start > now } ?: rows.lastOrNull()
        else rows.firstOrNull()
    fun status(channel: Channel, programme: Programme, now: Long): String {
        if (programme.start >= now) return "Далее"
        return when (val result = CatchupResolver.resolve(PlaybackRequest.Archive(channel, programme), now)) {
            is CatchupResult.Available -> if (programme.end > now) "С начала" else "Архив"
            is CatchupResult.Unavailable -> result.reason
        }
    }
}
