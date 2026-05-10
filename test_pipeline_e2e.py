"""
End-to-end tests for the FunASR + 3D-Speaker Pipeline API.

Coverage
--------
Stage 1  – Speaker registration (CAM++ voiceprint)
Stage 3  – Speaker verification
Stage 4  – Plain ASR transcription
Stage 3+4 – Targeted (speaker-gated) transcription

Runs against a live container.  Set the API base URL via:
    E2E_BASE_URL=http://localhost:8811   (default)

Audio fixtures use the example WAV files bundled with the CAM++ model under
the shared models volume.  Override via:
    E2E_AUDIO_DIR=/path/to/wavs

Usage
-----
# from the project root:
python -m pytest tests/test_pipeline_e2e.py -v

# or via the existing runner:
python tests/run_test.py --test_dir tests --pattern test_pipeline_e2e.py
"""

from __future__ import annotations

import io
import os
import struct
import time
import unittest
import urllib.error
import urllib.parse
import urllib.request

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------

BASE_URL: str = os.environ.get("E2E_BASE_URL", "http://localhost:8811").rstrip("/")

# Directory containing speaker example WAVs shipped with the CAM++ model
_DEFAULT_AUDIO_DIR = (
    "/Jupiter/service-FunASR/funasr-runtime-resources/models"
    "/iic/speech_campplus_sv_zh-cn_16k-common/examples"
)
AUDIO_DIR: str = os.environ.get("E2E_AUDIO_DIR", _DEFAULT_AUDIO_DIR)

# Unique IDs used throughout the suite – cleaned up in tearDownClass
_TEST_USER_ID = "e2e_test_speaker"
_TEST_USER_ID2 = "e2e_test_speaker2"

# ---------------------------------------------------------------------------
# Minimal multipart/form-data helpers (stdlib only)
# ---------------------------------------------------------------------------

def _build_multipart(fields: dict, files: dict | None = None):
    """
    Build a multipart/form-data body from *fields* (str values) and *files*
    (mapping of field_name → (filename, bytes)).

    Returns (body_bytes, content_type_header_value).
    """
    boundary = b"e2eboundary1234567890"
    parts = []

    for name, value in fields.items():
        parts.append(
            b"--" + boundary + b"\r\n"
            b'Content-Disposition: form-data; name="' + name.encode() + b'"\r\n\r\n'
            + str(value).encode() + b"\r\n"
        )

    if files:
        for field_name, (filename, data) in files.items():
            parts.append(
                b"--" + boundary + b"\r\n"
                b'Content-Disposition: form-data; name="' + field_name.encode()
                + b'"; filename="' + filename.encode() + b'"\r\n'
                b"Content-Type: audio/wav\r\n\r\n"
                + data + b"\r\n"
            )

    body = b"".join(parts) + b"--" + boundary + b"--\r\n"
    content_type = "multipart/form-data; boundary=" + boundary.decode()
    return body, content_type


def _get(path: str, timeout: int = 10) -> dict:
    url = BASE_URL + path
    req = urllib.request.Request(url)
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        import json
        return json.loads(resp.read().decode())


def _post(path: str, fields: dict | None = None, files: dict | None = None,
          timeout: int = 90) -> dict:
    import json
    body, ct = _build_multipart(fields or {}, files)
    url = BASE_URL + path
    req = urllib.request.Request(url, data=body, method="POST")
    req.add_header("Content-Type", ct)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return json.loads(resp.read().decode())
    except urllib.error.HTTPError as exc:
        raise AssertionError(
            f"POST {path} → HTTP {exc.code}: {exc.read().decode()}"
        ) from exc


def _delete(path: str, timeout: int = 10) -> dict:
    import json
    url = BASE_URL + path
    req = urllib.request.Request(url, method="DELETE")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return json.loads(resp.read().decode())
    except urllib.error.HTTPError as exc:
        raise AssertionError(
            f"DELETE {path} → HTTP {exc.code}: {exc.read().decode()}"
        ) from exc


def _read_wav(filename: str) -> bytes:
    path = os.path.join(AUDIO_DIR, filename)
    if not os.path.exists(path):
        raise unittest.SkipTest(f"Audio fixture not found: {path}")
    with open(path, "rb") as fh:
        return fh.read()


def _make_silence_wav(duration_sec: float = 2.0, sample_rate: int = 16000) -> bytes:
    """Generate a minimal valid WAV containing near-silence (very faint noise)."""
    n_samples = int(duration_sec * sample_rate)
    pcm = bytes(n_samples * 2)  # 16-bit zeros = silence
    data_size = len(pcm)
    buf = io.BytesIO()
    buf.write(b"RIFF")
    buf.write(struct.pack("<I", 36 + data_size))
    buf.write(b"WAVEfmt ")
    buf.write(struct.pack("<IHHIIHH", 16, 1, 1, sample_rate, sample_rate * 2, 2, 16))
    buf.write(b"data")
    buf.write(struct.pack("<I", data_size))
    buf.write(pcm)
    return buf.getvalue()


# ---------------------------------------------------------------------------
# Test suite
# ---------------------------------------------------------------------------

class TestAPIHealth(unittest.TestCase):
    """Basic connectivity and model-load checks."""

    def test_root_returns_service_info(self):
        data = _get("/")
        self.assertIn("service", data)
        self.assertIn("FunASR", data["service"])

    def test_status_both_models_loaded(self):
        data = _get("/status")
        self.assertTrue(data.get("speaker_model_loaded"),
                        "CAM++ speaker model not loaded")
        self.assertTrue(data.get("asr_model_loaded"),
                        "FunASR ASR model not loaded")

    def test_status_schema(self):
        data = _get("/status")
        self.assertIn("speaker_model_loaded", data)
        self.assertIn("asr_model_loaded", data)


class TestSpeakerRegistration(unittest.TestCase):
    """Stage 1 – CAM++ voiceprint registration."""

    @classmethod
    def setUpClass(cls):
        cls.spk1_a = _read_wav("speaker1_a_cn_16k.wav")
        cls.spk1_b = _read_wav("speaker1_b_cn_16k.wav")
        cls.spk2_a = _read_wav("speaker2_a_cn_16k.wav")

    @classmethod
    def tearDownClass(cls):
        for uid in (_TEST_USER_ID, _TEST_USER_ID2):
            try:
                _delete(f"/api/v1/speakers/{uid}")
            except Exception:
                pass

    def setUp(self):
        # Ensure a clean slate before each test
        for uid in (_TEST_USER_ID, _TEST_USER_ID2):
            try:
                _delete(f"/api/v1/speakers/{uid}")
            except Exception:
                pass

    # --- Registration --------------------------------------------------

    def test_register_new_speaker(self):
        resp = _post(
            "/api/v1/speakers/register",
            fields={"user_id": _TEST_USER_ID, "name": "E2E Speaker"},
            files={"audio": ("speaker1_a_cn_16k.wav", self.spk1_a)},
        )
        self.assertEqual(resp["user_id"], _TEST_USER_ID)
        self.assertEqual(resp["name"], "E2E Speaker")
        self.assertEqual(resp["embedding_dim"], 192)
        self.assertFalse(resp["updated"])
        self.assertIn("created_at", resp)

    def test_register_second_speaker(self):
        _post(
            "/api/v1/speakers/register",
            fields={"user_id": _TEST_USER_ID2, "name": "E2E Speaker2"},
            files={"audio": ("speaker2_a_cn_16k.wav", self.spk2_a)},
        )
        speakers = _get("/api/v1/speakers/")
        ids = [s["user_id"] for s in speakers]
        self.assertIn(_TEST_USER_ID2, ids)

    def test_register_updates_existing_speaker(self):
        # First registration
        _post(
            "/api/v1/speakers/register",
            fields={"user_id": _TEST_USER_ID, "name": "Old Name"},
            files={"audio": ("speaker1_a_cn_16k.wav", self.spk1_a)},
        )
        # Update with different audio
        resp = _post(
            "/api/v1/speakers/register",
            fields={"user_id": _TEST_USER_ID, "name": "New Name"},
            files={"audio": ("speaker1_b_cn_16k.wav", self.spk1_b)},
        )
        self.assertTrue(resp["updated"])
        self.assertEqual(resp["name"], "New Name")

    def test_register_returns_192_dim_embedding(self):
        resp = _post(
            "/api/v1/speakers/register",
            fields={"user_id": _TEST_USER_ID, "name": "Dim Test"},
            files={"audio": ("speaker1_a_cn_16k.wav", self.spk1_a)},
        )
        self.assertEqual(resp["embedding_dim"], 192)

    # --- List ----------------------------------------------------------

    def test_list_speakers(self):
        _post(
            "/api/v1/speakers/register",
            fields={"user_id": _TEST_USER_ID, "name": "List Test"},
            files={"audio": ("speaker1_a_cn_16k.wav", self.spk1_a)},
        )
        speakers = _get("/api/v1/speakers/")
        self.assertIsInstance(speakers, list)
        ids = [s["user_id"] for s in speakers]
        self.assertIn(_TEST_USER_ID, ids)
        # Each item has required fields
        for s in speakers:
            self.assertIn("user_id", s)
            self.assertIn("name", s)
            self.assertIn("created_at", s)

    # --- Delete --------------------------------------------------------

    def test_delete_existing_speaker(self):
        _post(
            "/api/v1/speakers/register",
            fields={"user_id": _TEST_USER_ID, "name": "Delete Test"},
            files={"audio": ("speaker1_a_cn_16k.wav", self.spk1_a)},
        )
        resp = _delete(f"/api/v1/speakers/{_TEST_USER_ID}")
        self.assertIn("deleted", resp.get("message", "").lower())
        # Confirm gone from list
        speakers = _get("/api/v1/speakers/")
        ids = [s["user_id"] for s in speakers]
        self.assertNotIn(_TEST_USER_ID, ids)

    def test_delete_nonexistent_speaker_returns_404(self):
        with self.assertRaises(AssertionError) as ctx:
            _delete("/api/v1/speakers/no_such_user_xyz")
        self.assertIn("404", str(ctx.exception))


class TestSpeakerVerification(unittest.TestCase):
    """Stage 3 – Cosine-similarity speaker verification."""

    @classmethod
    def setUpClass(cls):
        cls.spk1_a = _read_wav("speaker1_a_cn_16k.wav")
        cls.spk1_b = _read_wav("speaker1_b_cn_16k.wav")
        cls.spk2_a = _read_wav("speaker2_a_cn_16k.wav")

        # Register speaker 1 once for the whole class
        _post(
            "/api/v1/speakers/register",
            fields={"user_id": _TEST_USER_ID, "name": "Verify Test"},
            files={"audio": ("speaker1_a_cn_16k.wav", cls.spk1_a)},
        )

    @classmethod
    def tearDownClass(cls):
        try:
            _delete(f"/api/v1/speakers/{_TEST_USER_ID}")
        except Exception:
            pass

    def test_verify_same_speaker_returns_high_similarity(self):
        resp = _post(
            f"/api/v1/speakers/{_TEST_USER_ID}/verify",
            fields={"threshold": "0.50"},
            files={"audio": ("speaker1_b_cn_16k.wav", self.spk1_b)},
        )
        self.assertIn("similarity", resp)
        self.assertGreater(resp["similarity"], 0.50,
                           "Same speaker should have similarity > 0.50")

    def test_verify_different_speaker_returns_lower_similarity(self):
        resp_same = _post(
            f"/api/v1/speakers/{_TEST_USER_ID}/verify",
            fields={"threshold": "0.50"},
            files={"audio": ("speaker1_b_cn_16k.wav", self.spk1_b)},
        )
        resp_diff = _post(
            f"/api/v1/speakers/{_TEST_USER_ID}/verify",
            fields={"threshold": "0.50"},
            files={"audio": ("speaker2_a_cn_16k.wav", self.spk2_a)},
        )
        self.assertGreater(
            resp_same["similarity"], resp_diff["similarity"],
            "Same-speaker similarity should exceed cross-speaker similarity"
        )

    def test_verify_matched_flag_true_when_above_threshold(self):
        resp = _post(
            f"/api/v1/speakers/{_TEST_USER_ID}/verify",
            fields={"threshold": "0.50"},
            files={"audio": ("speaker1_b_cn_16k.wav", self.spk1_b)},
        )
        self.assertTrue(resp["matched"])

    def test_verify_matched_flag_false_when_below_threshold(self):
        # Use an impossibly high threshold to force mismatch
        resp = _post(
            f"/api/v1/speakers/{_TEST_USER_ID}/verify",
            fields={"threshold": "0.9999"},
            files={"audio": ("speaker1_b_cn_16k.wav", self.spk1_b)},
        )
        self.assertFalse(resp["matched"])

    def test_verify_response_schema(self):
        resp = _post(
            f"/api/v1/speakers/{_TEST_USER_ID}/verify",
            fields={"threshold": "0.70"},
            files={"audio": ("speaker1_b_cn_16k.wav", self.spk1_b)},
        )
        for key in ("user_id", "name", "similarity", "matched", "threshold"):
            self.assertIn(key, resp)
        self.assertIsInstance(resp["similarity"], float)
        self.assertIsInstance(resp["matched"], bool)
        self.assertEqual(resp["threshold"], 0.70)

    def test_verify_unregistered_speaker_returns_404(self):
        with self.assertRaises(AssertionError) as ctx:
            _post(
                "/api/v1/speakers/no_such_user_xyz/verify",
                fields={"threshold": "0.70"},
                files={"audio": ("speaker1_a_cn_16k.wav", self.spk1_a)},
            )
        self.assertIn("404", str(ctx.exception))


class TestASRTranscription(unittest.TestCase):
    """Stage 4 – Plain Paraformer ASR transcription."""

    @classmethod
    def setUpClass(cls):
        cls.spk1_a = _read_wav("speaker1_a_cn_16k.wav")
        cls.spk1_b = _read_wav("speaker1_b_cn_16k.wav")

    def test_transcribe_returns_non_empty_text(self):
        resp = _post(
            "/api/v1/asr/transcribe",
            files={"audio": ("speaker1_a_cn_16k.wav", self.spk1_a)},
        )
        self.assertIn("text", resp)
        self.assertGreater(len(resp["text"].strip()), 0,
                           "Expected non-empty transcription for speech audio")

    def test_transcribe_contains_chinese_characters(self):
        resp = _post(
            "/api/v1/asr/transcribe",
            files={"audio": ("speaker1_a_cn_16k.wav", self.spk1_a)},
        )
        has_chinese = any("\u4e00" <= ch <= "\u9fff" for ch in resp["text"])
        self.assertTrue(has_chinese, f"Expected Chinese text, got: {resp['text']!r}")

    def test_transcribe_duration_is_positive(self):
        resp = _post(
            "/api/v1/asr/transcribe",
            files={"audio": ("speaker1_a_cn_16k.wav", self.spk1_a)},
        )
        self.assertGreater(resp["duration_sec"], 0.0)

    def test_transcribe_second_clip(self):
        resp = _post(
            "/api/v1/asr/transcribe",
            files={"audio": ("speaker1_b_cn_16k.wav", self.spk1_b)},
        )
        self.assertGreater(len(resp["text"].strip()), 0)

    def test_transcribe_response_schema(self):
        resp = _post(
            "/api/v1/asr/transcribe",
            files={"audio": ("speaker1_a_cn_16k.wav", self.spk1_a)},
        )
        self.assertIn("text", resp)
        self.assertIn("duration_sec", resp)
        self.assertIsInstance(resp["text"], str)
        self.assertIsInstance(resp["duration_sec"], float)

    def test_transcribe_silence_returns_empty_or_short_text(self):
        silence = _make_silence_wav(duration_sec=2.0)
        resp = _post(
            "/api/v1/asr/transcribe",
            files={"audio": ("silence.wav", silence)},
        )
        # Silence should produce empty text or very short output
        self.assertIsInstance(resp["text"], str)
        self.assertLess(len(resp["text"].strip()), 10,
                        f"Expected silence to yield little/no text, got: {resp['text']!r}")


class TestTargetedTranscription(unittest.TestCase):
    """Stage 3 + 4 – Speaker-gated transcription (full pipeline)."""

    @classmethod
    def setUpClass(cls):
        cls.spk1_a = _read_wav("speaker1_a_cn_16k.wav")
        cls.spk1_b = _read_wav("speaker1_b_cn_16k.wav")
        cls.spk2_a = _read_wav("speaker2_a_cn_16k.wav")

        _post(
            "/api/v1/speakers/register",
            fields={"user_id": _TEST_USER_ID, "name": "Targeted Test"},
            files={"audio": ("speaker1_a_cn_16k.wav", cls.spk1_a)},
        )

    @classmethod
    def tearDownClass(cls):
        try:
            _delete(f"/api/v1/speakers/{_TEST_USER_ID}")
        except Exception:
            pass

    # --- Matched path ------------------------------------------------

    def test_targeted_same_speaker_low_threshold_produces_transcript(self):
        resp = _post(
            "/api/v1/asr/transcribe/targeted",
            fields={"user_id": _TEST_USER_ID, "threshold": "0.55"},
            files={"audio": ("speaker1_b_cn_16k.wav", self.spk1_b)},
        )
        self.assertTrue(resp["speaker_matched"])
        self.assertGreater(len(resp["text"].strip()), 0,
                           "Expected transcript when speaker is matched")

    def test_targeted_transcript_contains_chinese_when_matched(self):
        resp = _post(
            "/api/v1/asr/transcribe/targeted",
            fields={"user_id": _TEST_USER_ID, "threshold": "0.55"},
            files={"audio": ("speaker1_b_cn_16k.wav", self.spk1_b)},
        )
        if resp["speaker_matched"]:
            has_chinese = any("\u4e00" <= ch <= "\u9fff" for ch in resp["text"])
            self.assertTrue(has_chinese, f"Got: {resp['text']!r}")

    def test_targeted_similarity_value_is_in_range(self):
        resp = _post(
            "/api/v1/asr/transcribe/targeted",
            fields={"user_id": _TEST_USER_ID, "threshold": "0.55"},
            files={"audio": ("speaker1_b_cn_16k.wav", self.spk1_b)},
        )
        self.assertGreaterEqual(resp["similarity"], 0.0)
        self.assertLessEqual(resp["similarity"], 1.0)

    # --- Rejected path -----------------------------------------------

    def test_targeted_high_threshold_rejects_and_returns_empty_text(self):
        resp = _post(
            "/api/v1/asr/transcribe/targeted",
            fields={"user_id": _TEST_USER_ID, "threshold": "0.9999"},
            files={"audio": ("speaker1_b_cn_16k.wav", self.spk1_b)},
        )
        self.assertFalse(resp["speaker_matched"])
        self.assertEqual(resp["text"], "")
        self.assertEqual(resp["duration_sec"], 0.0)

    def test_targeted_different_speaker_not_matched(self):
        resp = _post(
            "/api/v1/asr/transcribe/targeted",
            fields={"user_id": _TEST_USER_ID, "threshold": "0.80"},
            files={"audio": ("speaker2_a_cn_16k.wav", self.spk2_a)},
        )
        # Distinct speakers should not match at a reasonable threshold
        self.assertFalse(resp["speaker_matched"],
                         f"Expected cross-speaker rejection; similarity={resp['similarity']}")

    # --- Error paths -------------------------------------------------

    def test_targeted_unknown_user_returns_404(self):
        with self.assertRaises(AssertionError) as ctx:
            _post(
                "/api/v1/asr/transcribe/targeted",
                fields={"user_id": "no_such_user_xyz", "threshold": "0.70"},
                files={"audio": ("speaker1_a_cn_16k.wav", self.spk1_a)},
            )
        self.assertIn("404", str(ctx.exception))

    # --- Response schema ---------------------------------------------

    def test_targeted_response_schema(self):
        resp = _post(
            "/api/v1/asr/transcribe/targeted",
            fields={"user_id": _TEST_USER_ID, "threshold": "0.55"},
            files={"audio": ("speaker1_b_cn_16k.wav", self.spk1_b)},
        )
        for key in ("text", "duration_sec", "speaker_matched", "similarity", "threshold"):
            self.assertIn(key, resp, f"Missing key: {key}")
        self.assertIsInstance(resp["text"], str)
        self.assertIsInstance(resp["duration_sec"], float)
        self.assertIsInstance(resp["speaker_matched"], bool)
        self.assertIsInstance(resp["similarity"], float)
        self.assertAlmostEqual(resp["threshold"], 0.55, places=4)


class TestEndToEndPipeline(unittest.TestCase):
    """
    Full 4-stage integration tests simulating an Android client workflow:

      Stage 1: register voiceprint → Stage 3: verify identity
             → Stage 4: transcribe if matched
    """

    @classmethod
    def setUpClass(cls):
        cls.spk1_a = _read_wav("speaker1_a_cn_16k.wav")
        cls.spk1_b = _read_wav("speaker1_b_cn_16k.wav")
        cls.spk2_a = _read_wav("speaker2_a_cn_16k.wav")

    @classmethod
    def tearDownClass(cls):
        for uid in (_TEST_USER_ID, _TEST_USER_ID2):
            try:
                _delete(f"/api/v1/speakers/{uid}")
            except Exception:
                pass

    def setUp(self):
        for uid in (_TEST_USER_ID, _TEST_USER_ID2):
            try:
                _delete(f"/api/v1/speakers/{uid}")
            except Exception:
                pass

    def test_full_pipeline_register_verify_transcribe(self):
        # --- Stage 1: register ---
        reg = _post(
            "/api/v1/speakers/register",
            fields={"user_id": _TEST_USER_ID, "name": "Pipeline User"},
            files={"audio": ("speaker1_a_cn_16k.wav", self.spk1_a)},
        )
        self.assertEqual(reg["embedding_dim"], 192)
        self.assertFalse(reg["updated"])

        # --- Stage 3: verify identity ---
        verify = _post(
            f"/api/v1/speakers/{_TEST_USER_ID}/verify",
            fields={"threshold": "0.50"},
            files={"audio": ("speaker1_b_cn_16k.wav", self.spk1_b)},
        )
        self.assertTrue(verify["matched"],
                        f"Stage 3 failed: similarity={verify['similarity']}")

        # --- Stage 4 (via targeted): transcribe if matched ---
        transcript = _post(
            "/api/v1/asr/transcribe/targeted",
            fields={"user_id": _TEST_USER_ID, "threshold": "0.50"},
            files={"audio": ("speaker1_b_cn_16k.wav", self.spk1_b)},
        )
        self.assertTrue(transcript["speaker_matched"])
        self.assertGreater(len(transcript["text"].strip()), 0)

    def test_pipeline_rejects_impostor(self):
        # Register speaker 1
        _post(
            "/api/v1/speakers/register",
            fields={"user_id": _TEST_USER_ID, "name": "Legitimate User"},
            files={"audio": ("speaker1_a_cn_16k.wav", self.spk1_a)},
        )

        # Impostor (speaker 2) tries targeted transcription
        resp = _post(
            "/api/v1/asr/transcribe/targeted",
            fields={"user_id": _TEST_USER_ID, "threshold": "0.80"},
            files={"audio": ("speaker2_a_cn_16k.wav", self.spk2_a)},
        )
        self.assertFalse(resp["speaker_matched"],
                         "Impostor should be rejected at threshold=0.80")
        self.assertEqual(resp["text"], "", "No transcript should be returned for impostor")

    def test_pipeline_two_speakers_isolated(self):
        # Register two distinct speakers
        _post(
            "/api/v1/speakers/register",
            fields={"user_id": _TEST_USER_ID, "name": "Speaker One"},
            files={"audio": ("speaker1_a_cn_16k.wav", self.spk1_a)},
        )
        _post(
            "/api/v1/speakers/register",
            fields={"user_id": _TEST_USER_ID2, "name": "Speaker Two"},
            files={"audio": ("speaker2_a_cn_16k.wav", self.spk2_a)},
        )

        # Speaker 1's audio should not match speaker 2's profile (high threshold)
        resp12 = _post(
            f"/api/v1/speakers/{_TEST_USER_ID2}/verify",
            fields={"threshold": "0.80"},
            files={"audio": ("speaker1_b_cn_16k.wav", self.spk1_b)},
        )
        self.assertFalse(resp12["matched"],
                         f"Cross-speaker match should fail: sim={resp12['similarity']}")

        # Speaker 1's audio should match speaker 1's profile (lower threshold)
        resp11 = _post(
            f"/api/v1/speakers/{_TEST_USER_ID}/verify",
            fields={"threshold": "0.50"},
            files={"audio": ("speaker1_b_cn_16k.wav", self.spk1_b)},
        )
        self.assertTrue(resp11["matched"],
                        f"Same-speaker match should succeed: sim={resp11['similarity']}")

    def test_pipeline_update_and_re_verify(self):
        # Initial registration with clip A
        _post(
            "/api/v1/speakers/register",
            fields={"user_id": _TEST_USER_ID, "name": "Update Test"},
            files={"audio": ("speaker1_a_cn_16k.wav", self.spk1_a)},
        )
        sim_before = _post(
            f"/api/v1/speakers/{_TEST_USER_ID}/verify",
            fields={"threshold": "0.50"},
            files={"audio": ("speaker1_b_cn_16k.wav", self.spk1_b)},
        )["similarity"]

        # Re-register with clip B (update voiceprint)
        upd = _post(
            "/api/v1/speakers/register",
            fields={"user_id": _TEST_USER_ID, "name": "Update Test"},
            files={"audio": ("speaker1_b_cn_16k.wav", self.spk1_b)},
        )
        self.assertTrue(upd["updated"])

        # Re-verify: embedding now anchored to clip B, so clip B similarity should be 1.0
        sim_after = _post(
            f"/api/v1/speakers/{_TEST_USER_ID}/verify",
            fields={"threshold": "0.50"},
            files={"audio": ("speaker1_b_cn_16k.wav", self.spk1_b)},
        )["similarity"]
        # Self-similarity must be > original cross-utterance similarity
        self.assertGreater(sim_after, sim_before,
                           "After re-registration with clip B, clip B should score higher")

    def test_pipeline_delete_removes_from_targeted(self):
        _post(
            "/api/v1/speakers/register",
            fields={"user_id": _TEST_USER_ID, "name": "Delete Pipeline"},
            files={"audio": ("speaker1_a_cn_16k.wav", self.spk1_a)},
        )
        _delete(f"/api/v1/speakers/{_TEST_USER_ID}")

        # After deletion, targeted transcription should 404
        with self.assertRaises(AssertionError) as ctx:
            _post(
                "/api/v1/asr/transcribe/targeted",
                fields={"user_id": _TEST_USER_ID, "threshold": "0.70"},
                files={"audio": ("speaker1_b_cn_16k.wav", self.spk1_b)},
            )
        self.assertIn("404", str(ctx.exception))


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------

if __name__ == "__main__":
    print(f"API base URL : {BASE_URL}")
    print(f"Audio dir    : {AUDIO_DIR}")
    print()

    # Quick connectivity check before running full suite
    try:
        _get("/status")
    except Exception as exc:
        print(f"ERROR: Cannot reach API at {BASE_URL} — {exc}")
        print("Start the container first:  docker compose up -d")
        raise SystemExit(1)

    unittest.main(verbosity=2)
