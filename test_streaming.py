#!/usr/bin/env python3
"""
FunASR WebSocket Streaming (Online) ASR Test
Tests the 2-pass server running on port 10095.

Modes:
  - offline   : send full audio, get one final result
  - online    : streaming only (partial results, no final correction)
  - 2pass     : streaming online + offline correction pass (best quality)
"""

import ssl
import json
import time
import wave
import threading
import subprocess
import tempfile
import os
import sys
from queue import Queue, Empty
from websocket import create_connection, ABNF

# ─── Server profiles ────────────────────────────────────────────────────────
SERVERS = {
    "local": {
        "host"   : "127.0.0.1",
        "port"   : 10095,
        "use_ssl": False,          # no cert configured on local server
    },
    "remote": {
        "host"   : "asr.aiworm.cn",
        "port"   : 443,            # standard HTTPS/WSS port
        "use_ssl": True,           # domain → try wss first, fallback to ws on 80
    },
}

CHUNK_SIZE     = [0, 10, 5]     # [0, chunk_ms/10, lookahead] – matches server default
CHUNK_INTERVAL = 10             # ms per step
# ─────────────────────────────────────────────────────────────────────────────

def mp3_to_wav_bytes(mp3_path: str) -> bytes:
    """Convert mp3 → 16 kHz mono 16-bit PCM WAV bytes using ffmpeg."""
    with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as tmp:
        tmp_path = tmp.name
    try:
        subprocess.run(
            [
                "ffmpeg", "-y", "-i", mp3_path,
                "-ar", "16000", "-ac", "1", "-sample_fmt", "s16",
                tmp_path,
            ],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            check=True,
        )
        with wave.open(tmp_path, "rb") as wf:
            return wf.readframes(wf.getnframes())
    finally:
        os.unlink(tmp_path)


def load_audio(path: str) -> bytes:
    """Load audio file → raw PCM bytes (16 kHz / 16-bit / mono)."""
    if path.endswith(".mp3"):
        return mp3_to_wav_bytes(path)
    with wave.open(path, "rb") as wf:
        assert wf.getframerate() == 16000, "WAV must be 16 kHz"
        assert wf.getsampwidth() == 2,     "WAV must be 16-bit"
        assert wf.getnchannels() == 1,     "WAV must be mono"
        return wf.readframes(wf.getnframes())


def run_test(audio_path: str, mode: str = "2pass",
             host: str = "127.0.0.1", port: int = 10095, use_ssl: bool = False):
    """
    Send audio to the FunASR 2-pass server and print results.

    mode: 'offline' | 'online' | '2pass'
    """
    is_remote = host != "127.0.0.1"
    final_wait = 15 if is_remote else 8   # remote needs more time for offline pass
    print(f"\n{'='*60}")
    print(f"  Mode   : {mode}")
    print(f"  Audio  : {os.path.basename(audio_path)}")
    print(f"  Server : {'wss' if use_ssl else 'ws'}://{host}:{port}")
    print(f"{'='*60}")

    # ── Build WebSocket URI ────────────────────────────────────────────────
    uri = f"{'wss' if use_ssl else 'ws'}://{host}:{port}"
    ws = None
    if use_ssl:
        ssl_ctx = ssl.SSLContext()
        ssl_ctx.check_hostname = False
        ssl_ctx.verify_mode = ssl.CERT_NONE
        try:
            ws = create_connection(uri, ssl=ssl_ctx, sslopt={"cert_reqs": ssl.CERT_NONE})
        except Exception:
            # SSL failed – retry without SSL on standard HTTP port 80
            fallback_uri = f"ws://{host}:80"
            print(f"  ⚠️  wss:// failed, retrying as {fallback_uri}")
            ws = create_connection(fallback_uri)
    else:
        ws = create_connection(uri)

    msg_queue: Queue = Queue()
    partial_results = []
    final_result    = None

    # ── Background receiver thread ─────────────────────────────────────────
    def _recv():
        try:
            while True:
                raw = ws.recv()
                if not raw:
                    continue
                msg = json.loads(raw)
                msg_queue.put(msg)
        except Exception:
            pass   # connection closed

    t = threading.Thread(target=_recv, daemon=True)
    t.start()

    # ── Send initial config message ────────────────────────────────────────
    config = {
        "mode"                    : mode,
        "chunk_size"              : CHUNK_SIZE,
        "chunk_interval"          : CHUNK_INTERVAL,
        "encoder_chunk_look_back" : 4,
        "decoder_chunk_look_back" : 1,
        "wav_name"                : os.path.splitext(os.path.basename(audio_path))[0],
        "is_speaking"             : True,
    }
    ws.send(json.dumps(config))

    # ── Stream audio in chunks ─────────────────────────────────────────────
    audio_bytes = load_audio(audio_path)
    stride      = int(60 * CHUNK_SIZE[1] / CHUNK_INTERVAL / 1000 * 16000 * 2)
    chunk_num   = (len(audio_bytes) - 1) // stride + 1

    print(f"  Audio  : {len(audio_bytes)/32000:.2f}s  |  {chunk_num} chunks  |  stride={stride}B")
    print()

    t_start = time.time()
    for i in range(chunk_num):
        chunk = audio_bytes[i * stride : (i + 1) * stride]
        ws.send(chunk, ABNF.OPCODE_BINARY)

        # Drain any messages that arrived
        while True:
            try:
                msg = msg_queue.get_nowait()
                _handle_msg(msg, partial_results, mode)
            except Empty:
                break

        time.sleep(CHUNK_INTERVAL / 1000.0)   # pace == real-time

    # ── Signal end-of-speech ──────────────────────────────────────────────
    ws.send(json.dumps({"is_speaking": False}))

    # ── Wait for final offline-pass result ───────────────────────────────────
    deadline = time.time() + final_wait
    while time.time() < deadline:
        try:
            msg = msg_queue.get(timeout=0.2)
            final_result = _handle_msg(msg, partial_results, mode)
            if final_result:
                break
        except Empty:
            pass

    ws.close()

    elapsed = time.time() - t_start
    print(f"\n  ⏱  Total elapsed : {elapsed:.2f}s")
    if final_result:
        print(f"  ✅ Final result  : {final_result}")
    elif partial_results:
        print(f"  ✅ Last partial  : {partial_results[-1]}")
    else:
        print("  ⚠️  No result received")

    return final_result or (partial_results[-1] if partial_results else None)


def _handle_msg(msg: dict, partial_results: list, mode: str):
    """Print and track one server message. Returns text if it is a final result."""
    text = msg.get("text", "")
    is_final = msg.get("is_final", False)
    wav_name = msg.get("wav_name", "")
    mode_tag = msg.get("mode", "")

    if not text:
        return None

    if is_final or mode_tag == "offline":
        tag = "FINAL  (offline-pass)" if mode == "2pass" else "FINAL"
        print(f"  [{tag}]  {text}")
        return text
    else:
        partial_results.append(text)
        print(f"  [partial]            {text}")
        return None


# ─── Main ─────────────────────────────────────────────────────────────────────
if __name__ == "__main__":
    import argparse

    parser = argparse.ArgumentParser(description="FunASR WebSocket streaming test")
    parser.add_argument("audio",   nargs="?", help="Custom audio file path")
    parser.add_argument("--server", choices=list(SERVERS.keys()) + ["all"],
                        default="all",
                        help="Which server profile to test (default: all)")
    parser.add_argument("--mode",   choices=["offline", "online", "2pass", "all"],
                        default="2pass",
                        help="ASR mode to test (default: 2pass)")
    args = parser.parse_args()

    # Audio files
    test_files = (
        [args.audio] if args.audio else
        [
            "/workspace/models/SenseVoiceSmall/example/zh.mp3",
            "/workspace/models/SenseVoiceSmall/example/en.mp3",
        ]
    )

    # Server profiles to run
    server_keys = list(SERVERS.keys()) if args.server == "all" else [args.server]

    # Modes to run
    modes = ["offline", "online", "2pass"] if args.mode == "all" else [args.mode]

    print("FunASR 2-Pass Streaming Test")
    print("Server is the C++ funasr-wss-server-2pass (Paraformer ONNX)")
    print(f"Servers  : {server_keys}")
    print(f"Modes    : {modes}")

    for srv_key in server_keys:
        srv = SERVERS[srv_key]
        print(f"\n{'#'*60}")
        print(f"  SERVER PROFILE : {srv_key}  ({srv['host']}:{srv['port']})")
        print(f"{'#'*60}")

        for audio_path in test_files:
            if not os.path.exists(audio_path):
                print(f"⚠️  Skipping missing file: {audio_path}")
                continue

            for mode in modes:
                try:
                    run_test(
                        audio_path,
                        mode=mode,
                        host=srv["host"],
                        port=srv["port"],
                        use_ssl=srv["use_ssl"],
                    )
                except Exception as e:
                    print(f"  ❌ [{srv_key}] mode={mode}: {e}")
                time.sleep(0.5)

    print("\n\nDone.")
