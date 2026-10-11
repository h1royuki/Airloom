package tv.airloom.app

import tv.airloom.core.Channel
import tv.airloom.core.Category

internal object UiPolicies {
    fun autoHide(playing: Boolean, buffering: Boolean, channelPanel: Boolean,
                 timelineFocused: Boolean, hasError: Boolean): Boolean =
        playing && !buffering && !channelPanel && !timelineFocused && !hasError

    fun draftSeek(position: Long, delta: Long, duration: Long): Long =
        (position + delta).coerceIn(0, duration.coerceAtLeast(0))

    const val NEXT_PROGRAMME_LEAD_MS = 5 * 60_000L
    /** How long the notice of the next programme stays over the video. */
    const val NEXT_NOTICE_MS = 8_000L

    /** The next programme is announced only during the last minutes of the current one. */
    fun announcesNext(end: Long, now: Long): Boolean = end - now <= NEXT_PROGRAMME_LEAD_MS

    const val EPG_WINDOW_STEP = 10
    private const val EPG_WINDOW_SIZE = 40

    /**
     * Channel ids to load EPG for: a padded window around the viewport that moves once per
     * [EPG_WINDOW_STEP] rows, so stepping through a list does not start a query on every row.
     */
    fun epgWindow(rows: List<Channel>, firstVisibleIndex: Int): List<String> {
        if (firstVisibleIndex < 0 || rows.isEmpty()) return emptyList()
        val from = ((firstVisibleIndex / EPG_WINDOW_STEP - 1) * EPG_WINDOW_STEP).coerceIn(0, rows.lastIndex)
        return rows.subList(from, (from + EPG_WINDOW_SIZE).coerceAtMost(rows.size)).map { it.id }
    }

    fun lastChannel(channels: List<Channel>, categories: List<Category>, id: String?): Channel? {
        val hiddenGroups = categories.filter { it.hidden }.map { it.playlistId to it.name }.toSet()
        return channels.firstOrNull { it.id == id && !it.hidden && (it.playlistId to it.group) !in hiddenGroups }
    }
}
