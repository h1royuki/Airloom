package tv.airloom.playback

import tv.airloom.core.Channel
import tv.airloom.core.PlaybackRequest

/** UI-safe playback state. Error strings deliberately never contain provider URLs. */
data class PlaybackState(
    val request: PlaybackRequest? = null,
    val channel: Channel? = null,
    val title: String = "",
    val playWhenReady: Boolean = false,
    val recovery: PlaybackRecovery? = null,
    val reconnectAttempts: Int = 0,
    val restartRequired: Boolean = false,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val error: String? = null,
    val hasRenderedFrame: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val isSeekable: Boolean = false,
    val isLive: Boolean = true,
    val audioTracks: List<TrackOption> = emptyList(),
    val subtitleTracks: List<TrackOption> = emptyList(),
)

data class TrackOption(val id: String, val label: String, val selected: Boolean)

data class PlaybackRecovery(val attempt: Int, val maximum: Int)
