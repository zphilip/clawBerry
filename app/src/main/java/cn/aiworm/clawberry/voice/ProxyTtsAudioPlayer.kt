package clawberry.aiworm.cn.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaPlayer
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import kotlin.math.max

internal class ProxyTtsAudioPlayer(
  private val context: Context,
) {
  private val lock = Mutex()
  private var mediaPlayer: MediaPlayer? = null
  private var audioTrack: AudioTrack? = null

  suspend fun play(
    audioBase64: String,
    format: String?,
    onBeforeSpeak: suspend () -> Unit,
    onAfterSpeak: suspend () -> Unit,
  ) {
    val decoded = runCatching { Base64.decode(audioBase64, Base64.DEFAULT) }.getOrNull() ?: return
    if (decoded.isEmpty()) return
    val normalized = format.orEmpty().trim().lowercase()

    onBeforeSpeak()
    try {
      lock.withLock {
        stopInternal()
        if (normalized.startsWith("pcm")) {
          playPcmInternal(decoded, parseSampleRate(normalized))
        } else {
          playEncodedInternal(decoded, pickExtension(normalized, decoded))
        }
      }
    } finally {
      onAfterSpeak()
    }
  }

  /**
   * Play a single audio chunk without lifecycle callbacks.
   * Used by the streaming TTS queue in the ViewModels so chunks play back-to-back.
   */
  suspend fun playRaw(audioBase64: String, format: String?) {
    val decoded = runCatching { Base64.decode(audioBase64, Base64.DEFAULT) }.getOrElse {
      Log.w("ProxyTtsAudio", "[playRaw] base64 decode failed b64len=${audioBase64.length}", it)
      return
    }
    if (decoded.isEmpty()) {
      Log.w("ProxyTtsAudio", "[playRaw] decoded to empty bytes b64len=${audioBase64.length}")
      return
    }
    val normalized = format.orEmpty().trim().lowercase()
    lock.withLock {
      stopInternal()
      if (normalized.startsWith("pcm")) {
        val sampleRate = parseSampleRate(normalized)
        val durationMs = decoded.size * 1000L / 2 / sampleRate
        Log.d("ProxyTtsAudio", "[playRaw] PCM ${decoded.size}B sampleRate=$sampleRate durationMs≈${durationMs}")
        playPcmInternal(decoded, sampleRate)
      } else {
        val ext = pickExtension(normalized, decoded)
        Log.d("ProxyTtsAudio", "[playRaw] encoded ${decoded.size}B format='$normalized' ext=$ext")
        playEncodedInternal(decoded, ext)
      }
    }
    Log.d("ProxyTtsAudio", "[playRaw] done")
  }

  suspend fun stop() {
    lock.withLock {
      stopInternal()
    }
  }

  suspend fun release() {
    stop()
  }

  private fun stopInternal() {
    mediaPlayer?.runCatching {
      stop()
      release()
    }
    mediaPlayer = null

    audioTrack?.runCatching {
      stop()
      flush()
      release()
    }
    audioTrack = null
  }

  private suspend fun playPcmInternal(bytes: ByteArray, sampleRate: Int) {
    val minBuffer = AudioTrack.getMinBufferSize(
      sampleRate,
      AudioFormat.CHANNEL_OUT_MONO,
      AudioFormat.ENCODING_PCM_16BIT,
    )
    if (minBuffer <= 0) return
    val track = AudioTrack.Builder()
      .setAudioAttributes(
        AudioAttributes.Builder()
          .setUsage(AudioAttributes.USAGE_MEDIA)
          .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
          .build(),
      )
      .setAudioFormat(
        AudioFormat.Builder()
          .setSampleRate(sampleRate)
          .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
          .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
          .build(),
      )
      .setBufferSizeInBytes(max(minBuffer, bytes.size))
      .setTransferMode(AudioTrack.MODE_STREAM)
      .build()
    if (track.state != AudioTrack.STATE_INITIALIZED) {
      track.release()
      return
    }

    audioTrack = track
    track.play()

    var offset = 0
    while (offset < bytes.size) {
      val wrote = track.write(bytes, offset, bytes.size - offset, AudioTrack.WRITE_BLOCKING)
      if (wrote <= 0) break
      offset += wrote
    }

    val frames = bytes.size / 2L
    val durationMs = (frames * 1000L) / sampleRate
    delay(durationMs + 80L)

    track.stop()
    track.flush()
    track.release()
    if (audioTrack === track) audioTrack = null
  }

  private suspend fun playEncodedInternal(bytes: ByteArray, ext: String) {
    val tempFile = File.createTempFile("proxy_tts_", ".${ext}", context.cacheDir)
    tempFile.writeBytes(bytes)
    try {
      val player = MediaPlayer()
      player.setAudioAttributes(
        AudioAttributes.Builder()
          .setUsage(AudioAttributes.USAGE_MEDIA)
          .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
          .build(),
      )
      mediaPlayer = player
      val finished = kotlinx.coroutines.CompletableDeferred<Unit>()
      player.setOnPreparedListener { it.start() }
      player.setOnCompletionListener { finished.complete(Unit) }
      player.setOnErrorListener { _, _, _ ->
        finished.complete(Unit)
        true
      }
      player.setDataSource(tempFile.absolutePath)
      player.prepareAsync()
      finished.await()
      player.stop()
      player.release()
      if (mediaPlayer === player) mediaPlayer = null
    } finally {
      tempFile.delete()
    }
  }

  private fun parseSampleRate(format: String): Int {
    val matched = Regex("pcm[_-]?(\\d+)").find(format)
    val parsed = matched?.groupValues?.getOrNull(1)?.toIntOrNull()
    return parsed?.takeIf { it in 8000..96000 } ?: 24000
  }

  private fun pickExtension(format: String, bytes: ByteArray): String {
    if (format.contains("mp3") || format.contains("mpeg")) return "mp3"
    if (format.contains("wav")) return "wav"
    // WAV: RIFF header
    if (bytes.size >= 4 && bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte()) {
      return "wav"
    }
    // MP3: ID3v2 tag header
    if (bytes.size >= 3 && bytes[0] == 'I'.code.toByte() && bytes[1] == 'D'.code.toByte() && bytes[2] == '3'.code.toByte()) {
      return "mp3"
    }
    // MP3: MPEG frame sync word — first byte 0xFF, second byte upper 3 bits = 111
    if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && (bytes[1].toInt() and 0xE0) == 0xE0) {
      return "mp3"
    }
    // Default to mp3 — WAV would have been caught by magic bytes above
    return "mp3"
  }
}