package tv.airloom.playback

import android.content.Context
import android.os.SystemClock
import androidx.media3.exoplayer.DefaultLoadControl
import kotlinx.coroutines.Job
import tv.airloom.core.PlaybackNetworkSettings
import javax.net.ssl.SSLException
import java.io.IOException
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.session.MediaSession
import androidx.media3.ui.PlayerView
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import tv.airloom.core.CatchupResolver
import tv.airloom.core.CatchupResult
import tv.airloom.core.PlaybackRequest

/**
 * One foreground player for fullscreen playback, reused across channel changes and seeks.
 * Constructing/attaching this controller never starts a network request. All commands are called
 * on the main thread. The Activity stops it when going into the background and releases on destroy.
 */
@UnstableApi
class AirloomPlayer(context: Context, private val runtime: tv.airloom.core.AirloomRuntime = tv.airloom.core.AirloomRuntime()) : PlaybackController {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(PlaybackState())
    override val state: StateFlow<PlaybackState> = mutableState.asStateFlow()
    private var attachedView: PlayerView? = null
    private var released = false
    private var archiveOffsetMs = 0L
    private var configured = PlaybackNetworkSettings()
    private var activeSettings = configured
    // Settings baked into the current player's buffers and HTTP client.
    private var builtWith = activeSettings
    private val recovery = RecoveryPolicy()
    private var reconnectJob: Job? = null
    @Volatile private var generation = 0L
    private var wantsPlay = false
    private var recovering = false
    private var recoveryPosition = 0L
    private var recoverySeekable = false
    @Volatile private var segmentFailure: Throwable? = null
    private var httpClient = newHttpClient()

    private fun newHttpClient() = OkHttpClient.Builder()
        .connectTimeout(activeSettings.connectTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
        .readTimeout(activeSettings.readTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
        .followRedirects(true).followSslRedirects(true).build()

    // Media3 error logging includes full stream URLs. Never expose credentials.
    private fun newPlayer(): ExoPlayer {
        Log.setLogLevel(Log.LOG_LEVEL_OFF)
        return ExoPlayer.Builder(appContext).setLoadControl(DefaultLoadControl.Builder()
            .setBufferDurationsMs(activeSettings.bufferMs, activeSettings.bufferMs,
                activeSettings.startBufferMs, activeSettings.resumeBufferMs)
            .setTargetBufferBytes(64 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(false).build()).build().apply {
            setAudioAttributes(androidx.media3.common.AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            setHandleAudioBecomingNoisy(true)
        }
    }
    var player: ExoPlayer = newPlayer()
        private set
    private var session = MediaSession.Builder(appContext, player).build()
    private fun newListener(token: Long) = object : Player.Listener {
        private fun current() = !released && token == generation
        override fun onEvents(player: Player, events: Player.Events) {
            if (!current()) return
            publish()
        }
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!current() || state.value.error != null) return
            wantsPlay = playWhenReady
            if (!playWhenReady) {
                reconnectJob?.cancel(); reconnectJob = null
                recovery.resetTimers()
            } else if (recovering) scheduleReconnect(0)
            publish()
        }
        override fun onPlayerError(error: PlaybackException) {
            if (current()) handleFailure(userMessage(error), retryable(error))
        }
        override fun onTracksChanged(tracks: Tracks) {
            if (!current()) return
            mutableState.update { it.copy(audioTracks = tracks(C.TRACK_TYPE_AUDIO), subtitleTracks = tracks(C.TRACK_TYPE_TEXT)) }
            publish()
        }
        override fun onRenderedFirstFrame() {
            if (current()) mutableState.update { it.copy(hasRenderedFrame = true) }
        }
    }
    private var listener = newListener(generation)

    override fun configure(settings: PlaybackNetworkSettings) { configured = settings.normalized() }

    private fun cancelRecovery() {
        generation++
        reconnectJob?.cancel()
        reconnectJob = null
        recovering = false
        recovery.resetTimers()
    }

    private fun retryable(error: PlaybackException): Boolean {
        val causes = generateSequence<Throwable>(error) { it.cause }.take(20).toList()
        val http = causes.filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()
        return PlaybackFailurePolicy.retryable(error.errorCode, http?.responseCode,
            causes.any { it is SSLException }, causes.any { it is IOException }, causes.any { it === segmentFailure })
    }

    private fun finalFailure(message: String, restartRequired: Boolean = false) {
        reconnectJob?.cancel()
        reconnectJob = null
        val wasRecovering = recovering
        recovering = false
        wantsPlay = false
        val position = if (state.value.error != null) state.value.positionMs else if (wasRecovering) recoveryPosition else player.currentPosition.coerceAtLeast(0) + archiveOffsetMs
        mutableState.update { it.copy(positionMs = position, error = message, isPlaying = false, isBuffering = false,
            playWhenReady = false, recovery = null, reconnectAttempts = recovery.attempts,
            restartRequired = restartRequired) }
        player.stop()
    }

    private fun handleFailure(message: String, retryable: Boolean) {
        if (released || state.value.request == null || recovering) return
        recoveryPosition = player.currentPosition.coerceAtLeast(0) + archiveOffsetMs
        recoverySeekable = recoverySeekable || player.isCurrentMediaItemSeekable
        if (!retryable) { finalFailure(message); return }
        val request = state.value.request!!
        if (request is PlaybackRequest.Archive && recoveryPosition > 0 &&
            !canServerSeek(request) && !recoverySeekable) {
            finalFailure("Поток архива не поддерживает продолжение с места остановки.", true)
            return
        }
        val delayMs = recovery.nextDelay(activeSettings)
        if (delayMs == null) { finalFailure(message); return }
        recovering = true
        player.stop()
        mutableState.update { it.copy(error = null, isPlaying = false, isBuffering = false,
            positionMs = recoveryPosition, recovery = PlaybackRecovery(recovery.attempts, activeSettings.reconnectAttempts),
            reconnectAttempts = recovery.attempts, playWhenReady = wantsPlay) }
        scheduleReconnect(delayMs)
    }

    private fun scheduleReconnect(delayMs: Long) {
        if (!wantsPlay || !recovering || reconnectJob != null) return
        val token = generation
        val job = scope.launch(start = CoroutineStart.LAZY) {
            delay(delayMs)
            if (released || generation != token || !wantsPlay || !recovering) return@launch
            reconnectJob = null
            val request = state.value.request ?: return@launch
            playFrom(request, if (request is PlaybackRequest.Archive) recoveryPosition else 0,
                localSeek = request is PlaybackRequest.Archive && !canServerSeek(request) && recoverySeekable,
                automatic = true)
        }
        reconnectJob = job
        job.start()
    }

    private fun watchdog() {
        if (released || state.value.request == null || state.value.error != null || recovering) return
        val expired = recovery.tick(SystemClock.elapsedRealtime(), wantsPlay,
            player.playbackState == Player.STATE_BUFFERING, player.isPlaying,
            player.currentPosition, activeSettings.bufferingTimeoutMs)
        if (expired) handleFailure("Источник слишком долго не начинает воспроизведение.", true)
        else if (player.isPlaying) mutableState.update { it.copy(recovery = null, reconnectAttempts = recovery.attempts) }
    }

    init {
        player.addListener(listener)
        scope.launch {
            while (isActive) {
                if (mutableState.value.request != null) { publish(); watchdog() }
                delay(500)
            }
        }
    }

    /** PlayerView defaults to SurfaceView, supported on API 30; native controls are replaced by TV UI. */
    override fun attachView(view: PlayerView) {
        if (released) return
        if (attachedView !== view) {
            attachedView?.player = null
            attachedView = view
        }
        view.useController = false
        view.isFocusable = false
        view.isFocusableInTouchMode = false
        view.setKeepContentOnPlayerReset(false)
        view.player = player
    }

    override fun detachView(view: PlayerView) {
        if (attachedView === view) { view.player = null; attachedView = null }
    }

    override fun play(request: PlaybackRequest) {
        activeSettings = configured
        recovery.reset()
        recoverySeekable = false
        playFrom(request, 0)
    }

    private fun playFrom(request: PlaybackRequest, offsetMs: Long, localSeek: Boolean = false, automatic: Boolean = false,
                         fresh: Boolean = false) {
        if (released) return
        // A healthy player only swaps its source: building a player and releasing a decoder on every
        // channel change blocks the main thread. After a failure, during recovery, or when buffer or
        // timeout settings changed, playback still starts from a new player and connection pool.
        val rebuild = fresh || automatic || recovering || state.value.error != null || builtWith != activeSettings
        cancelRecovery()
        wantsPlay = true
        player.removeListener(listener)
        val trackParameters = player.trackSelectionParameters.buildUpon().clearOverrides().build()
        if (rebuild) {
            attachedView?.player = null
            session.release()
            player.release()
            httpClient.dispatcher.cancelAll()
            httpClient.connectionPool.evictAll()
            httpClient.dispatcher.executorService.shutdown()
            httpClient = newHttpClient()
            player = newPlayer()
            builtWith = activeSettings
            session = MediaSession.Builder(appContext, player).build()
        } else {
            // Reset while no listener is attached, so the new one never sees the previous stream ending.
            player.stop()
            player.clearMediaItems()
        }
        player.trackSelectionParameters = trackParameters
        listener = newListener(generation)
        player.addListener(listener)
        if (rebuild) attachedView?.player = player
        segmentFailure = null
        val resolvedRequest = if (request is PlaybackRequest.Archive && offsetMs > 0 && !localSeek)
            PlaybackPolicy.requestAt(request, offsetMs) else request
        val result = CatchupResolver.resolve(resolvedRequest)
        archiveOffsetMs = if (localSeek) 0 else offsetMs
        mutableState.value = PlaybackState(
            request = request,
            channel = request.channel,
            title = if (request is PlaybackRequest.Archive) request.programme.title else request.channel.name,
            isLive = request is PlaybackRequest.Live,
            playWhenReady = true,
            positionMs = offsetMs,
            reconnectAttempts = recovery.attempts,
            recovery = if (automatic) PlaybackRecovery(recovery.attempts, activeSettings.reconnectAttempts) else null,
        )
        if (result is CatchupResult.Unavailable) {
            wantsPlay = false
            mutableState.update { it.copy(error = result.reason, playWhenReady = false) }
            return
        }
        result as CatchupResult.Available
        val network = OkHttpDataSource.Factory(httpClient)
            .setDefaultRequestProperties(result.stream.headers)
        val sourceGeneration = generation
        val sourceFactory = DefaultMediaSourceFactory(DefaultDataSource.Factory(appContext, network))
            .setLoadErrorHandlingPolicy(BoundedRetryPolicy(result.stream.url) { exception -> if (generation == sourceGeneration) segmentFailure = exception })
        val item = MediaItem.Builder()
            .setUri(result.stream.url)
            .setMediaId(request.channel.id)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(state.value.title).setArtist(request.channel.name).build())
            .build()
        val mediaSource = sourceFactory.createMediaSource(item)
        if (request is PlaybackRequest.Archive) player.setMediaSource(mediaSource, if (localSeek) offsetMs else 0L)
        else player.setMediaSource(mediaSource)
        player.prepare()
        player.play()
        publish()
    }

    override fun retry() {
        if (released) return
        val request = state.value.request ?: return
        val position = state.value.positionMs
        val localSeek = player.isCurrentMediaItemSeekable || recoverySeekable
        activeSettings = configured
        recovery.reset()
        if (request is PlaybackRequest.Archive && position > 0) {
            if (canServerSeek(request)) playFrom(request, PlaybackPolicy.clampArchivePosition(position, archiveDuration(request)), fresh = true)
            else if (localSeek) playFrom(request, position, localSeek = true, fresh = true)
            else finalFailure("Поток архива не поддерживает продолжение с места остановки.", true)
        } else playFrom(request, 0, fresh = true)
    }
    override fun pause() {
        if (released) return
        wantsPlay = false
        reconnectJob?.cancel(); reconnectJob = null
        recovery.resetTimers()
        player.pause()
        publish()
    }
    override fun resume() {
        if (released || state.value.error != null) return
        wantsPlay = true
        if (recovering) scheduleReconnect(0) else if (player.mediaItemCount > 0) player.play()
        publish()
    }

    override fun seekBy(milliseconds: Long) = seekTo(state.value.positionMs + milliseconds)
    override fun seekTo(milliseconds: Long) {
        if (released) return
        val request = state.value.request ?: return
        if (recovering || state.value.error != null) return
        recovery.resetTimers()
        if (request is PlaybackRequest.Archive) {
            val target = PlaybackPolicy.clampArchivePosition(milliseconds, archiveDuration(request))
            val localTarget = target - archiveOffsetMs
            if (player.isCurrentMediaItemSeekable && localTarget >= 0 &&
                (player.duration == C.TIME_UNSET || localTarget <= player.duration)) player.seekTo(localTarget)
            else if (canServerSeek(request)) {
                val resumeAfterSeek = player.playWhenReady
                playFrom(request, target)
                if (!resumeAfterSeek) pause()
            }
        } else if (player.isCurrentMediaItemSeekable) {
            val duration = player.duration
            val target = if (duration > 0 && duration != C.TIME_UNSET) milliseconds.coerceIn(0, duration)
                else milliseconds.coerceAtLeast(0)
            player.seekTo(target)
        }
        publish()
    }

    override fun goLive() {
        if (released) return
        val request = state.value.request ?: return
        if (request is PlaybackRequest.Archive) play(PlaybackRequest.Live(request.channel))
        else if (recovering || state.value.error != null) play(request)
        else {
            wantsPlay = true
            recovery.resetTimers()
            player.seekToDefaultPosition()
            player.play()
            publish()
        }
    }

    /** Drop active playback so returning from the background requires another explicit channel OK. */
    override fun stop() {
        if (released) return
        cancelRecovery()
        recovery.reset()
        wantsPlay = false
        player.stop()
        player.clearMediaItems()
        archiveOffsetMs = 0
        mutableState.value = PlaybackState()
    }

    override fun selectAudio(id: String) = selectTrack(id, C.TRACK_TYPE_AUDIO)
    override fun selectSubtitle(id: String?) {
        if (released) return
        if (id == null) {
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
        } else selectTrack(id, C.TRACK_TYPE_TEXT)
    }

    private fun selectTrack(id: String, type: Int) {
        if (released) return
        val indices = id.split(':').mapNotNull(String::toIntOrNull)
        if (indices.size != 2) return
        val group = player.currentTracks.groups.getOrNull(indices[0]) ?: return
        val index = indices[1]
        if (group.type != type || index !in 0 until group.length || !group.isTrackSupported(index)) return
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(type, false).clearOverridesOfType(type)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index)).build()
    }

    override fun release() {
        if (released) return
        cancelRecovery()
        released = true
        scope.cancel()
        attachedView?.player = null
        attachedView = null
        player.removeListener(listener)
        session.release()
        player.release()
        httpClient.dispatcher.cancelAll()
        httpClient.connectionPool.evictAll()
        httpClient.dispatcher.executorService.shutdown()
    }

    private fun publish() {
        if (released) return
        mutableState.update {
            it.copy(
                isPlaying = player.isPlaying,
                isBuffering = !recovering && it.error == null && player.playbackState == Player.STATE_BUFFERING,
                playWhenReady = wantsPlay,
                positionMs = if (recovering || it.error != null) it.positionMs else player.currentPosition.coerceAtLeast(0) + archiveOffsetMs,
                durationMs = (it.request as? PlaybackRequest.Archive)?.let(::archiveDuration)
                    ?: player.duration.takeIf { value -> value > 0 && value != C.TIME_UNSET } ?: 0,
                isSeekable = player.mediaItemCount > 0 && (player.isCurrentMediaItemSeekable ||
                    (it.request as? PlaybackRequest.Archive)?.let(::canServerSeek) == true),
            )
        }
    }

    private fun archiveDuration(request: PlaybackRequest.Archive): Long =
        PlaybackPolicy.availableDuration(request, runtime.now())

    private fun canServerSeek(request: PlaybackRequest.Archive): Boolean = PlaybackPolicy.canSeekOnServer(request)

    private fun tracks(type: Int): List<TrackOption> = buildList {
        player.currentTracks.groups.forEachIndexed { groupIndex, group ->
            if (group.type == type) {
                for (index in 0 until group.length) {
                    if (!group.isTrackSupported(index)) continue
                    val format = group.getTrackFormat(index)
                    val language = format.language?.takeUnless { it == "und" }?.let {
                        Locale.forLanguageTag(it).getDisplayLanguage(Locale.forLanguageTag("ru"))
                    }
                    val label = format.label?.takeIf(String::isNotBlank) ?: language?.takeIf(String::isNotBlank)
                        ?: if (type == C.TRACK_TYPE_AUDIO) "Аудио ${size + 1}" else "Субтитры ${size + 1}"
                    val channels = if (type == C.TRACK_TYPE_AUDIO && format.channelCount > 0) " · ${format.channelCount} кан." else ""
                    add(TrackOption("$groupIndex:$index", label + channels, group.isTrackSelected(index)))
                }
            }
        }
    }

    private fun userMessage(error: PlaybackException): String {
        if (generateSequence<Throwable>(error) { it.cause }.take(20).any { it is SSLException })
            return "Не удалось проверить защищённое соединение с источником. Проверьте дату и время телевизора."
        return when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "Нет соединения с источником. Проверьте сеть и повторите."
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "Источник отклонил запрос. Проверьте доступ к каналу или архиву."
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "Канал или запись не найдены у провайдера."
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES -> "Телевизор не поддерживает этот формат видео или звука."
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED -> "Источник вернул неподдерживаемый или повреждённый поток."
            else -> "Не удалось воспроизвести канал. Повторите попытку."
        }
    }

    /** Fatal HTTP requests never loop; transient failures get at most three automatic retries. */
    private class BoundedRetryPolicy(private val sourceUrl: String, private val segmentError: (Throwable) -> Unit) : DefaultLoadErrorHandlingPolicy(3) {
        override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
            if (generateSequence<Throwable>(loadErrorInfo.exception) { it.cause }.take(20).any { it is SSLException }) return C.TIME_UNSET
            val status = (loadErrorInfo.exception as? HttpDataSource.InvalidResponseCodeException)?.responseCode
            val segment = loadErrorInfo.mediaLoadData.dataType == C.DATA_TYPE_MEDIA &&
                loadErrorInfo.loadEventInfo.dataSpec.uri.toString() != sourceUrl
            if (segment) segmentError(loadErrorInfo.exception)
            if (loadErrorInfo.errorCount > 3 || (status != null && !RecoveryPolicy.retryableHttp(status, segment))) return C.TIME_UNSET
            return super.getRetryDelayMsFor(loadErrorInfo).let { delay ->
                if (delay == C.TIME_UNSET) delay else maxOf(delay, 500L * loadErrorInfo.errorCount)
            }
        }
    }
}
