package tv.airloom.playback

import tv.airloom.core.PlaybackRequest

/** Pure policy shared by the player and local tests; all programme times use epoch milliseconds. */
internal object PlaybackPolicy {
    fun availableDuration(request: PlaybackRequest.Archive, now: Long): Long =
        (minOf(request.programme.end, now) - request.programme.start).coerceAtLeast(0)

    fun clampArchivePosition(position: Long, duration: Long): Long =
        position.coerceIn(0, (duration - 1_000).coerceAtLeast(0))

    fun requestAt(request: PlaybackRequest.Archive, offsetMs: Long): PlaybackRequest.Archive =
        request.copy(programme = request.programme.copy(start = request.programme.start + offsetMs))

    /** ID-only links use native seeking: replacing a timestamp would not change their content. */
    fun canSeekOnServer(request: PlaybackRequest.Archive): Boolean {
        val spec = request.channel.catchup
        val template = spec.source
        return (spec.mode.equals("flussonic", true) && template.isBlank()) ||
            (spec.mode.equals("shift", true) && template.isBlank()) ||
            listOf("{utc}", "{start}", "{offset}").any(template::contains) ||
            listOf("{H}", "{M}", "{S}").all(template::contains)
    }

}
