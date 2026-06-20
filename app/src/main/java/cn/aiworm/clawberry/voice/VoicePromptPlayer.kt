package clawberry.aiworm.cn.voice

import android.content.Context
import android.media.MediaPlayer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Plays short pre-baked voice prompts from app assets.
 *
 * Place audio files (MP3/WAV) in app/src/main/assets/:
 *   voice_thinking.mp3   — played on a "thought" message.create frame
 *   voice_tool_calls.mp3 — played on a "tool_calls" message.create / tool_call frame
 *   voice_done.mp3       — played after the final TTS audio finishes
 *
 * Missing asset files are silently ignored (MediaPlayer returns null / throws gracefully).
 *
 * Thread-safety:
 *   play()  — suspend, serialised by [lock]; safe to call from any coroutine
 *   stop()  — non-suspend, safe to call from any thread; unblocks an in-flight play()
 */
class VoicePromptPlayer(private val context: Context) {

  private val lock = Mutex()

  private data class ActivePlayback(
    val player: MediaPlayer,
    val done: CompletableDeferred<Unit>,
  )

  /** Non-null while a prompt is playing. Written under [lock] but read from stop() directly. */
  @Volatile private var active: ActivePlayback? = null

  companion object {
    const val VOICE_THINKING   = "voice_thinking.mp3"
    const val VOICE_TOOL_CALLS = "voice_tool_calls.mp3"
    const val VOICE_DONE       = "voice_done.mp3"
  }

  /**
   * Play the named asset and suspend until playback completes or [stop] is called.
   * If the asset does not exist or MediaPlayer fails, returns silently.
   */
  suspend fun play(assetName: String) {
    val afd = runCatching { context.assets.openFd(assetName) }.getOrNull() ?: return
    lock.withLock {
      cancelActive()
      val done = CompletableDeferred<Unit>()
      val mp: MediaPlayer
      try {
        mp = MediaPlayer()
        mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
        runCatching { afd.close() }
        mp.prepare()
        mp.setOnCompletionListener { done.complete(Unit) }
        mp.setOnErrorListener { _, _, _ -> done.complete(Unit); true }
      } catch (e: Exception) {
        runCatching { afd.close() }
        return@withLock
      }
      active = ActivePlayback(mp, done)
      mp.start()
      done.await()
      if (active?.player === mp) active = null
      mp.runCatching { release() }
    }
  }

  /** Stop any current playback immediately (non-blocking, safe from any thread). */
  fun stop() {
    val pb = active ?: return
    active = null
    pb.done.complete(Unit)
    pb.player.runCatching { stop(); release() }
  }

  private fun cancelActive() {
    val pb = active ?: return
    active = null
    pb.done.complete(Unit)
    pb.player.runCatching { stop(); release() }
  }
}
