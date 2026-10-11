package tv.airloom.app

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File

/** Generates a tiny colour-only clip locally. No provider footage or external media is used. */
internal fun syntheticVideo(file: File, frameCount: Int = 12) {
    val width = 160
    val height = 96
    val encoder = MediaCodec.createEncoderByType("video/avc")
    val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    var started = false
    try {
        val format = MediaFormat.createVideoFormat("video/avc", width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 24)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()
        val frame = ByteArray(width * height * 3 / 2) { if (it < width * height) 100 else 128.toByte() }
        val info = MediaCodec.BufferInfo()
        var track = -1
        var input = 0
        var ended = false
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!ended && System.nanoTime() < deadline) {
            if (input <= frameCount) {
                val index = encoder.dequeueInputBuffer(10_000)
                if (index >= 0) {
                    val buffer = encoder.getInputBuffer(index)!!
                    buffer.clear()
                    if (input < frameCount) buffer.put(frame)
                    encoder.queueInputBuffer(index, 0, if (input < frameCount) frame.size else 0,
                        input * 1_000_000L / 24, if (input == frameCount) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                    input++
                }
            }
            val output = encoder.dequeueOutputBuffer(info, 10_000)
            when {
                output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    track = muxer.addTrack(encoder.outputFormat)
                    muxer.start()
                    started = true
                }
                output >= 0 -> {
                    val buffer = encoder.getOutputBuffer(output)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                        check(started)
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        muxer.writeSampleData(track, buffer, info)
                    }
                    ended = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    encoder.releaseOutputBuffer(output, false)
                }
            }
        }
        check(ended && started) { "Synthetic encoder did not finish" }
    } finally {
        runCatching { encoder.stop() }
        encoder.release()
        if (started) muxer.stop()
        muxer.release()
    }
}
