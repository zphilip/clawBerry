package clawberry.aiworm.cn.voice

/**
 * Supported local TTS models bundled under app/src/main/assets/.
 *
 * [id]          = asset directory name
 * [displayName] = shown in Settings
 * [maxSpeakers] = number of distinct speaker IDs (0-indexed). 1 means only sid=0.
 *
 * Note: [MatchaZhEn] requires `matcha-icefall-zh-en/vocos-16khz-univ.onnx` in assets.
 * Download from: https://github.com/k2-fsa/sherpa-onnx/releases/download/vocoder-models/vocos-16khz-univ.onnx
 */
enum class TtsModel(
    val id: String,
    val displayName: String,
    val maxSpeakers: Int,
) {
    /** matcha-icefall-zh-en — 1 speaker, needs vocos-16khz-univ.onnx vocoder */
    MatchaZhEn("matcha-icefall-zh-en", "Matcha ZH+EN", 1),

    /** sherpa-onnx-vits-zh-ll — 5 speakers (0–4) */
    VitsZhLl("sherpa-onnx-vits-zh-ll", "VITS ZH-LL", 5),

    /** vits-zh-hf-fanchen-C — 187 speakers (0–186) */
    VitsFanchenC("vits-zh-hf-fanchen-C", "VITS Fanchen-C", 187),
    ;

    companion object {
        val DEFAULT = MatchaZhEn
        fun fromId(id: String): TtsModel = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}
