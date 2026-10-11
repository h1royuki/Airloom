package tv.airloom.data

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import tv.airloom.core.*
import java.util.PriorityQueue

private const val SEARCH_PAGE = 200
private const val SEARCH_LIMIT = 100
private const val DAY_MS = 86_400_000L

/**
 * "Contains [text], ignoring case" as a GLOB pattern. SQLite folds case for ASCII only, so each cased
 * letter becomes a set of its forms and the database rejects non-matching titles itself.
 */
internal fun globContaining(text: String): String = buildString(text.length * 4 + 2) {
    append('*')
    for (char in text) {
        val lower = char.lowercaseChar()
        val upper = char.uppercaseChar()
        when {
            lower != upper -> { append('[').append(lower).append(upper); if (char != lower && char != upper) append(char); append(']') }
            char == '*' || char == '?' || char == '[' -> append('[').append(char).append(']')
            else -> append(char)
        }
    }
    append('*')
}

/** Each source is scanned through its ordered index; a merge preserves the global latest-first limit. */
internal class ProgrammeSearch(private val dao: AirloomDao, private val runtime: AirloomRuntime) {
    private class ChangedGeneration : Exception()
    private data class Cursor(val source: String, val generation: String, var page: List<ProgrammeEntity> = emptyList(), var index: Int = 0)

    suspend fun search(query: String, playlistId: String?, now: Long, channels: List<Channel>): List<Pair<Channel, Programme>> =
        withContext(runtime.computation) {
            val text = query.take(100)
            val pattern = globContaining(text)
            val byEpg = channels.groupBy { it.epgSourceId to it.epgChannelId }
            if (byEpg.isEmpty()) return@withContext emptyList()
            // Nothing older than the longest archive can be played, so the scan stops there.
            // Two days is the floor: it keeps a long programme that is still on air.
            val earliest = now - maxOf(2, channels.maxOf { it.catchup.days }) * DAY_MS
            repeat(3) {
                val generations = dao.allEpgSources().associate { it.id to it.generation }
                val queue = PriorityQueue<Cursor>(compareByDescending<Cursor> { it.page[it.index].start }
                    .thenBy { it.source }.thenBy { it.page[it.index].id })
                suspend fun load(cursor: Cursor, first: Boolean = false) {
                    currentCoroutineContext().ensureActive()
                    val last = cursor.page.lastOrNull()
                    if (!first && cursor.page.size < SEARCH_PAGE) return
                    cursor.page = dao.searchPage(pattern, playlistId, now, earliest, cursor.source, cursor.generation,
                        last?.start ?: Long.MAX_VALUE, last?.id.orEmpty(), SEARCH_PAGE)
                    cursor.index = 0
                    if (dao.allEpgSources().associate { it.id to it.generation } != generations) throw ChangedGeneration()
                    if (cursor.page.isNotEmpty()) queue += cursor
                }
                try {
                    generations.forEach { (source, generation) -> load(Cursor(source, generation), first = true) }
                    val accepted = linkedMapOf<Triple<String, String, String>, Pair<Channel, Programme>>()
                    while (queue.isNotEmpty() && accepted.size < SEARCH_LIMIT) {
                        currentCoroutineContext().ensureActive()
                        val cursor = queue.remove()
                        val row = cursor.page[cursor.index]
                        // The pattern is a prefilter; Kotlin's comparison stays the rule for what matches.
                        if (row.title.contains(text, ignoreCase = true)) {
                            val programme = row.model()
                            for (channel in byEpg[row.sourceId to row.channelId].orEmpty()) {
                                if (row.start <= now && row.end > now ||
                                    CatchupResolver.resolve(PlaybackRequest.Archive(channel, programme), now) is CatchupResult.Available) {
                                    accepted[Triple(channel.id, row.sourceId, row.id)] = channel to programme
                                    if (accepted.size >= SEARCH_LIMIT) break
                                }
                            }
                        }
                        cursor.index++
                        if (cursor.index < cursor.page.size) queue += cursor else if (accepted.size < SEARCH_LIMIT) load(cursor)
                    }
                    return@withContext accepted.values.toList()
                } catch (_: ChangedGeneration) { /* Restart on a coherent generation. */ }
            }
            throw AirloomFailure(FailureKind.OPERATION)
        }
}
