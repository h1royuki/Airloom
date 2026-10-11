package tv.airloom.playback

import androidx.media3.ui.PlayerView
import kotlinx.coroutines.flow.StateFlow
import tv.airloom.core.PlaybackRequest

/** Foreground playback is replaceable in state and navigation tests. */
interface PlaybackController {
    val state: StateFlow<PlaybackState>
    fun attachView(view: PlayerView)
    fun detachView(view: PlayerView)
    fun configure(settings: tv.airloom.core.PlaybackNetworkSettings) {}
    fun play(request: PlaybackRequest)
    fun retry()
    fun pause()
    fun resume()
    fun seekBy(milliseconds: Long)
    fun seekTo(milliseconds: Long)
    fun goLive()
    fun stop()
    fun selectAudio(id: String)
    fun selectSubtitle(id: String?)
    fun release()
}
