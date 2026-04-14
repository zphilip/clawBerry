package clawberry.aiworm.cn.ui.chat

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import clawberry.aiworm.cn.asr.VoiceRecorder
import clawberry.aiworm.cn.ui.mobileAccent
import clawberry.aiworm.cn.ui.mobileCallout
import clawberry.aiworm.cn.ui.mobileCaption1
import clawberry.aiworm.cn.ui.mobileCardSurface
import clawberry.aiworm.cn.ui.mobileBorder
import clawberry.aiworm.cn.ui.mobileTextSecondary
import clawberry.aiworm.cn.ui.mobileTextTertiary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shows a playable audio clip bar for the captured PCM from the mic.
 *
 * Displays a waveform thumbnail sampled from the raw PCM bytes, a play/pause
 * button (backed by [AudioTrack] for raw PCM playback — no file I/O needed),
 * the clip duration, and optionally a spinning indicator while ASR is running.
 *
 * This widget is shown inside the composer while [isTranscribing] is true,
 * giving the user immediate feedback that their clip was captured and playback
 * is available while the server transcribes it.
 */
@Composable
internal fun VoiceClipBar(
    pcm: ByteArray,
    isTranscribing: Boolean,
    modifier: Modifier = Modifier,
) {
    var isPlaying by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var currentTrack by remember { mutableStateOf<AudioTrack?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            currentTrack?.stop()
            currentTrack?.release()
            currentTrack = null
        }
    }

    // Duration label (mm:ss)
    val totalSecs = if (pcm.size >= 2) pcm.size / (VoiceRecorder.SAMPLE_RATE * 2) else 0
    val durationLabel = "%d:%02d".format(totalSecs / 60, totalSecs % 60)

    // Sample 28 amplitude points for the waveform thumbnail
    val waveform = remember(pcm) {
        if (pcm.size < 4) FloatArray(28) { 0.2f }
        else {
            val samplesTotal = pcm.size / 2
            FloatArray(28) { i ->
                val sampleIdx = (i.toLong() * samplesTotal / 28).toInt().coerceIn(0, samplesTotal - 1)
                val b0 = pcm[sampleIdx * 2].toInt() and 0xFF
                val b1 = pcm[sampleIdx * 2 + 1].toInt()
                val raw = ((b1 shl 8) or b0).toShort()
                (kotlin.math.abs(raw.toInt()) / 32768f).coerceIn(0.06f, 1f)
            }
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, mobileBorder, RoundedCornerShape(12.dp))
            .background(mobileCardSurface, RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // ── Play / Pause button ─────────────────────────────────────────────
        Surface(
            onClick = {
                if (isPlaying) {
                    currentTrack?.pause()
                    currentTrack?.release()
                    currentTrack = null
                    isPlaying = false
                } else {
                    scope.launch(Dispatchers.IO) {
                        val minBuf = AudioTrack.getMinBufferSize(
                            VoiceRecorder.SAMPLE_RATE,
                            AudioFormat.CHANNEL_OUT_MONO,
                            AudioFormat.ENCODING_PCM_16BIT,
                        )
                        val track = AudioTrack.Builder()
                            .setAudioAttributes(
                                AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_MEDIA)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                    .build()
                            )
                            .setAudioFormat(
                                AudioFormat.Builder()
                                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                    .setSampleRate(VoiceRecorder.SAMPLE_RATE)
                                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                    .build()
                            )
                            .setBufferSizeInBytes(maxOf(minBuf, pcm.size))
                            .setTransferMode(AudioTrack.MODE_STATIC)
                            .build()
                        track.write(pcm, 0, pcm.size)
                        withContext(Dispatchers.Main) {
                            currentTrack = track
                            isPlaying = true
                        }
                        track.play()
                        // Poll until playback finishes
                        while (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                            Thread.sleep(80)
                        }
                        withContext(Dispatchers.Main) {
                            isPlaying = false
                            currentTrack = null
                        }
                        track.release()
                    }
                }
            },
            modifier = Modifier.size(36.dp),
            shape = RoundedCornerShape(18.dp),
            color = mobileAccent,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        // ── Waveform bars ───────────────────────────────────────────────────
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            waveform.forEach { amp ->
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .height((amp * 22f + 3f).dp)
                        .background(
                            if (isPlaying) mobileAccent else mobileTextTertiary,
                            RoundedCornerShape(2.dp),
                        )
                )
            }
        }

        // ── Duration ────────────────────────────────────────────────────────
        Text(
            text = durationLabel,
            style = mobileCaption1,
            color = mobileTextSecondary,
        )

        // ── Transcribing spinner ─────────────────────────────────────────────
        if (isTranscribing) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
                color = mobileAccent,
            )
        }
    }
}
