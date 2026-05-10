package clawberry.aiworm.cn.voice

import android.content.Context
import clawberry.aiworm.cn.AppLanguage
import clawberry.aiworm.cn.asr.VoiceRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/**
 * Handles voice-print enrollment with the FunASR + CAM++ speaker-identification service.
 *
 * API base: http://apicn.aiworm.cn:8811
 *
 * Flow:
 *  1. [checkRegistered]  — GET /api/v1/speakers/{userId} — returns true if already enrolled
 *  2. [startRecording]   — opens mic via [VoiceRecorder]
 *  3. [stopAndRegister]  — stops recording, POSTs WAV to /api/v1/speakers/register
 *  4. [deleteVoicePrint] — DELETE /api/v1/speakers/{userId}
 *
 * All network calls run on [Dispatchers.IO].  The recording itself runs on the calling
 * coroutine (start/stop are cheap; the actual capture thread is managed by [VoiceRecorder]).
 *
 * This class is intentionally standalone — it has no reference to any ViewModel or Runtime.
 */
class SpeakerRegistrationManager(private val context: Context) {

    // ── Constants ────────────────────────────────────────────────────────────────────────────
    companion object {
        const val BASE_URL = "http://apicn.aiworm.cn:8811"
        const val MIN_RECORD_SEC = 3
        const val MAX_RECORD_SEC = 10

        /** Reading prompts shown to the user while recording, by language. */
        val PROMPT_EN = listOf(
            "Please read clearly: The quick brown fox jumps over the lazy dog.",
            "Please read clearly: My voice is my password, please verify me.",
            "Please read clearly: Open sesame, let me through the door.",
        )
        val PROMPT_ZH = listOf(
            "请清晰朗读：今天天气真不错，我喜欢在阳光下散步。",
            "请清晰朗读：我的声音就是我的密码，请验证我的身份。",
            "请清晰朗读：人工智能助手帮助我解决了很多问题。",
        )
    }

    // ── State ────────────────────────────────────────────────────────────────────────────────
    enum class State {
        Idle,          // nothing happening
        Checking,      // querying server for existing registration
        ReadyToRecord, // user can press "Start Recording"
        Recording,     // mic is open, capturing
        Uploading,     // sending WAV to server
        Registered,    // successfully enrolled
        Error,         // last operation failed
    }

    data class UiState(
        val state: State = State.Idle,
        val isRegistered: Boolean = false,
        val userName: String = "",
        val prompt: String = "",
        val recordedSeconds: Int = 0,
        val error: String? = null,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState

    // ── Private ──────────────────────────────────────────────────────────────────────────────
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val recorder = VoiceRecorder()

    // ── Public API ───────────────────────────────────────────────────────────────────────────

    /**
     * Returns a random reading prompt appropriate for [language].
     */
    fun promptFor(language: AppLanguage): String =
        if (language == AppLanguage.ChineseSimplified) PROMPT_ZH.random() else PROMPT_EN.random()

    /**
     * Query the server for whether [userId] already has a voice-print registered.
     * Updates [uiState] accordingly.
     */
    suspend fun checkRegistered(userId: String, userName: String, language: AppLanguage) {
        _uiState.value = _uiState.value.copy(
            state = State.Checking,
            userName = userName,
            prompt = promptFor(language),
            error = null,
        )
        val registered = withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder()
                    .url("$BASE_URL/api/v1/speakers/")
                    .get()
                    .build()
                val body = http.newCall(req).execute().use { it.body?.string() ?: "[]" }
                val arr = org.json.JSONArray(body)
                (0 until arr.length()).any { arr.getJSONObject(it).optString("user_id") == userId }
            }.getOrElse { false }
        }
        _uiState.value = _uiState.value.copy(
            state = State.ReadyToRecord,
            isRegistered = registered,
        )
    }

    /**
     * Open the microphone and begin capturing.  Call this from a coroutine launched
     * in a scope that outlives the recording (e.g. the ViewModel scope).
     */
    fun startRecording() {
        if (_uiState.value.state == State.Recording) return
        recorder.start()
        _uiState.value = _uiState.value.copy(state = State.Recording, recordedSeconds = 0)
    }

    /** Update the elapsed-seconds counter displayed in the UI. */
    fun tickRecordingSecond() {
        val cur = _uiState.value
        if (cur.state != State.Recording) return
        _uiState.value = cur.copy(recordedSeconds = cur.recordedSeconds + 1)
    }

    /**
     * Stop capturing, convert PCM to WAV, and POST to the server.
     * [userId] and [userName] are used for the voiceprint profile.
     */
    suspend fun stopAndRegister(userId: String, userName: String) {
        if (_uiState.value.state != State.Recording) return
        val pcm = recorder.stop()

        if (VoiceRecorder.isSilent(pcm, minBytes = VoiceRecorder.SAMPLE_RATE * 2 * MIN_RECORD_SEC)) {
            _uiState.value = _uiState.value.copy(
                state = State.Error,
                error = "Recording was too short or too quiet. Please try again.",
            )
            return
        }

        _uiState.value = _uiState.value.copy(state = State.Uploading, error = null)

        val result = withContext(Dispatchers.IO) {
            runCatching {
                val wav = pcmToWav(pcm, VoiceRecorder.SAMPLE_RATE)
                val body = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("user_id", userId)
                    .addFormDataPart("name", userName)
                    .addFormDataPart(
                        "audio", "voiceprint.wav",
                        wav.toRequestBody("audio/wav".toMediaType()),
                    )
                    .build()
                val req = Request.Builder()
                    .url("$BASE_URL/api/v1/speakers/register")
                    .post(body)
                    .build()
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) error("Server error ${resp.code}")
                    val json = JSONObject(resp.body?.string() ?: "{}")
                    json.optString("user_id")
                }
            }
        }

        result.fold(
            onSuccess = {
                _uiState.value = _uiState.value.copy(
                    state = State.Registered,
                    isRegistered = true,
                    error = null,
                )
            },
            onFailure = { e ->
                _uiState.value = _uiState.value.copy(
                    state = State.Error,
                    error = e.message ?: "Upload failed",
                )
            },
        )
    }

    /**
     * Cancel an in-progress recording without uploading.
     */
    fun cancelRecording() {
        if (_uiState.value.state == State.Recording) {
            recorder.release()
        }
        _uiState.value = _uiState.value.copy(state = State.ReadyToRecord, recordedSeconds = 0)
    }

    /**
     * Delete the voice-print from the server.
     */
    suspend fun deleteVoicePrint(userId: String) {
        _uiState.value = _uiState.value.copy(state = State.Uploading, error = null)
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder()
                    .url("$BASE_URL/api/v1/speakers/$userId")
                    .delete()
                    .build()
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) error("Server error ${resp.code}")
                }
            }
        }
        result.fold(
            onSuccess = {
                _uiState.value = _uiState.value.copy(
                    state = State.ReadyToRecord,
                    isRegistered = false,
                    error = null,
                )
            },
            onFailure = { e ->
                _uiState.value = _uiState.value.copy(
                    state = State.Error,
                    error = e.message ?: "Delete failed",
                )
            },
        )
    }

    /** Reset to idle so the card can be dismissed / re-shown cleanly. */
    fun reset() {
        if (_uiState.value.state == State.Recording) recorder.release()
        _uiState.value = UiState()
    }

    // ── PCM → WAV ────────────────────────────────────────────────────────────────────────────

    private fun pcmToWav(pcm: ByteArray, sampleRate: Int): ByteArray {
        val channels = 1
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val dataSize = pcm.size
        val out = ByteArrayOutputStream(44 + dataSize)
        val dos = DataOutputStream(out)
        fun writeInt32LE(v: Int) = dos.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())
        fun writeInt16LE(v: Int) = dos.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array())
        dos.writeBytes("RIFF")
        writeInt32LE(36 + dataSize)
        dos.writeBytes("WAVEfmt ")
        writeInt32LE(16)
        writeInt16LE(1)             // PCM
        writeInt16LE(channels)
        writeInt32LE(sampleRate)
        writeInt32LE(byteRate)
        writeInt16LE(blockAlign)
        writeInt16LE(bitsPerSample)
        dos.writeBytes("data")
        writeInt32LE(dataSize)
        dos.write(pcm)
        return out.toByteArray()
    }
}
