package clawberry.aiworm.cn.voice

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.io.File

/**
 * Disk cache for received TTS audio chunks, enabling replay of the last
 * agent voice response.
 *
 * Each turn is stored as a subdirectory under [root]:
 * ```
 * cacheDir/tts_replay/
 *   <turnId>/
 *     0000.bin      ← chunk 0 raw bytes (as received, format-agnostic)
 *     0001.bin      ← chunk 1
 *     meta.json     ← {"format": "wav", "chunkCount": 2}
 * ```
 *
 * Chunks are saved as-is — no decoding, transcoding, or concatenation.
 * Replay reads each chunk back and feeds it through the same [ProxyTtsAudioPlayer]
 * path used for the original playback.
 */
class TtsAudioCache(private val context: Context) {

    private val root = File(context.cacheDir, "tts_replay")

    // ── Write ────────────────────────────────────────────────────────────────

    /**
     * Create a per-turn subdirectory and write the initial [meta.json].
     * @param turnId  unique id for this turn (caller generates, e.g. UUID)
     * @param format  first chunk's format string ("wav" / "mp3" / "pcm_24000")
     */
    fun beginTurn(turnId: String, format: String): File {
        val dir = File(root, turnId).also { it.mkdirs() }
        File(dir, "meta.json").writeText(
            JSONObject().apply { put("format", format) }.toString()
        )
        return dir
    }

    /**
     * Write one chunk's raw bytes (base64-decoded) to a numbered file.
     * @param dir    directory from [beginTurn]
     * @param index  zero-based chunk index
     * @param bytes  raw audio bytes for this chunk
     */
    fun writeChunk(dir: File, index: Int, bytes: ByteArray) {
        File(dir, nameForIndex(index)).writeBytes(bytes)
    }

    /**
     * Finalise the turn: record [chunkCount] in meta.json.
     * @return absolute path to the turn directory
     */
    fun finishTurn(dir: File, chunkCount: Int): String {
        val meta = JSONObject(File(dir, "meta.json").readText())
        meta.put("chunkCount", chunkCount)
        File(dir, "meta.json").writeText(meta.toString())
        return dir.absolutePath
    }

    // ── Read ─────────────────────────────────────────────────────────────────

    /**
     * Read meta.json from a cached turn directory.
     * @return Pair(format, chunkCount), or null if the directory is missing / corrupt.
     */
    fun readMeta(dirPath: String): Pair<String, Int>? {
        return try {
            val meta = JSONObject(File(dirPath, "meta.json").readText())
            meta.optString("format", "") to meta.optInt("chunkCount", 0)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Read a single chunk from disk and return it as a base64-encoded string,
     * ready to pass straight to [ProxyTtsAudioPlayer.playRaw].
     */
    fun readChunkBase64(dirPath: String, index: Int): String? {
        val file = File(dirPath, nameForIndex(index))
        if (!file.exists()) return null
        return Base64.encodeToString(file.readBytes(), Base64.DEFAULT)
    }

    // ── Cleanup ──────────────────────────────────────────────────────────────

    /** Delete one turn's cached audio. */
    fun deleteTurn(turnId: String) {
        File(root, turnId).deleteRecursively()
    }

    /**
     * Keep at most [maxTurns] turns on disk, deleting the oldest first.
     * Call this before starting a new turn.
     */
    fun evictOldest(maxTurns: Int = 20) {
        val dirs = root.listFiles()?.sortedBy { it.lastModified() } ?: return
        for (dir in dirs.dropLast(maxTurns.coerceAtLeast(1))) {
            dir.deleteRecursively()
        }
    }

    // ── Internals ────────────────────────────────────────────────────────────

    private fun nameForIndex(index: Int) =
        index.toString().padStart(4, '0') + ".bin"
}
