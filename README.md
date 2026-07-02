# Hermes Voice

English | [简体中文](README_zh.md)

Voice platform adapter + Android client for [Hermes Agent](https://github.com/NousResearch/hermes-agent) — hands-free voice interaction via Bluetooth headset.

## What is this?

Hermes Voice lets you talk to your Hermes Agent by voice — press your Bluetooth headset button, speak, and hear the reply. It's designed for driving, walking, or any situation where you can't type.

Unlike simple voice wrappers that just proxy to an LLM API, Hermes Voice connects as a **first-class platform adapter** (like Telegram or Discord), giving you the full Hermes experience:

- ✅ All tools (terminal, file, web_search, delegate_task...)
- ✅ Persistent memory (Hindsight)
- ✅ Skills
- ✅ Session management + context compression
- ✅ Command approval (voice confirm/deny)
- ✅ Background task notifications
- ✅ Automatic forwarding of unspeakable content (code/URLs) to Feishu

## Architecture

```
┌─────────────────────────────────────────────────────────┐
│                    Hermes Gateway                         │
│                                                          │
│  ┌────────────┐  ┌────────────┐  ┌──────────────────┐  │
│  │  Feishu    │  │  Telegram  │  │  Voice Adapter   │  │
│  │  Adapter   │  │  Adapter   │  │  (plugin, 8650)  │  │
│  └─────┬──────┘  └─────┬──────┘  └────────┬─────────┘  │
│        └───────────────┼───────────────────┘             │
│                        ▼                                 │
│         Agent (tools / skills / memory / approval)       │
└─────────────────────────────────────────────────────────┘
                         ▲ WebSocket
                         │
┌────────────────────────┼────────────────────────────────┐
│              Android App (Kotlin)                         │
│  Bluetooth trigger → STT (Xunfei) → Agent → TTS reply   │
└─────────────────────────────────────────────────────────┘
```

## Components

### Server: Voice Platform Adapter (`adapter/`)

A Hermes platform plugin — zero modification to Hermes source code. Drop it into `~/.hermes/hermes-agent/plugins/platforms/voice/` and restart the gateway.

Features:
- WebSocket server for app connections
- Token authentication + device pairing
- Real-time streaming replies (send_draft)
- Xunfei STT credential proxy (app never sees API keys)
- Automatic forwarding of code/URLs to Feishu as card messages
- Brief-reply mode platform hint
- No server-side TTS (app handles locally)

### Client: Android App (`app/`)

Native Kotlin Android app for voice interaction.

Features:
- Bluetooth headset button trigger
- Xunfei Chinese ASR (大模型版, excellent for tech terms like docker/nginx/jenkins)
- Android native TTS with streaming playback
- Foreground Service for background operation
- Wake word detection (optional, Sherpa-ONNX)
- Conversation history (Room)
- Auto-reconnect with exponential backoff

## Quick Start

### Server Setup

1. Copy the adapter to your Hermes plugins:
```bash
cp -r adapter/ ~/.hermes/hermes-agent/plugins/platforms/voice/
```

2. Add to your `~/.hermes/.env`:
```bash
VOICE_TOKEN=$(openssl rand -hex 32)
# Optional: Xunfei STT credentials (for server-side auth proxy)
XFYUN_APP_ID=your_app_id
XFYUN_API_KEY=your_api_key
XFYUN_API_SECRET=your_api_secret
```

3. Restart gateway:
```bash
systemctl --user restart hermes-gateway.service
```

4. Verify:
```bash
curl http://localhost:8650/health
# {"status": "healthy", "platform": "voice", "connected_devices": 0}
```

### Client Setup

1. **Download model files** (required, ~350MB total):

```bash
cd app/src/main/assets

# 1. Offline STT - SenseVoice (fallback when Xunfei is unavailable)
mkdir -p sherpa-onnx && cd sherpa-onnx
wget https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2
tar xjf sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2 --strip-components=1 model.int8.onnx tokens.txt
rm sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2
wget -O silero_vad.onnx https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx
cd ..

# 2. Wake Word Detection - KWS (keyword: "小马")
mkdir -p sherpa-onnx-kws && cd sherpa-onnx-kws
wget https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01.tar.bz2
tar xjf sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01.tar.bz2 --strip-components=1
cp encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx encoder.onnx
cp decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx decoder.onnx
cp joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx joiner.onnx
rm -f *.tar.bz2 *.wav *epoch* test_wavs -rf
cd ..
# Note: keywords.txt is already in the repo

# 3. Offline TTS - Matcha Chinese+English
mkdir -p sherpa-onnx-tts && cd sherpa-onnx-tts
wget https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/matcha-icefall-zh-en.tar.bz2
tar xjf matcha-icefall-zh-en.tar.bz2 --strip-components=1
rm matcha-icefall-zh-en.tar.bz2
wget -O vocos.onnx https://github.com/k2-fsa/sherpa-onnx/releases/download/vocoder-models/vocos-16khz-univ.onnx
cd ..
```

2. **Download native libraries** (required):

```bash
cd app/src/main/jniLibs
wget https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.3/sherpa-onnx-v1.13.3-android.tar.bz2
tar xjf sherpa-onnx-v1.13.3-android.tar.bz2
# Keep only arm64-v8a (or whichever arch you need)
mv jniLibs/arm64-v8a .
rm -rf jniLibs sherpa-onnx-v1.13.3-android.tar.bz2
cd ..
```

3. Build the APK:
```bash
cd app
./gradlew assembleDebug
```

4. Install on your phone and configure:
   - Hermes address: `ws://your-hermes-host:8650/ws`
   - Voice Token: (the token you generated above)

5. First connection will trigger device pairing:
```bash
hermes pairing approve voice <CODE>
```

## WebSocket Protocol

See [hermes-voice-spec.md](hermes-voice-spec.md) for the complete protocol specification including:
- Authentication flow
- Message format (delta/end streaming)
- STT token request
- Approval interaction
- Task completion notifications

## Configuration

### Server (`.env`)

| Variable | Required | Description |
|----------|----------|-------------|
| `VOICE_TOKEN` | Yes | Authentication token for app connections |
| `VOICE_WS_PORT` | No | WebSocket port (default: 8650) |
| `VOICE_ALLOWED_DEVICES` | No | Device allowlist, `*` for all |
| `XFYUN_APP_ID` | No | Xunfei App ID (for STT proxy) |
| `XFYUN_API_KEY` | No | Xunfei API Key |
| `XFYUN_API_SECRET` | No | Xunfei API Secret |

### Client (App Settings)

| Setting | Description |
|---------|-------------|
| Hermes Address | WS/WSS URL to Voice Adapter |
| Voice Token | Authentication token |
| TTS Speed | 0.5 - 2.0 |
| Continuous Dialog Timeout | 3-10 seconds |

## How it differs from other approaches

| Approach | Tools/Memory/Skills | Real-time streaming | Voice-first UX |
|----------|:------------------:|:------------------:|:--------------:|
| **Hermes Voice (this)** | ✅ Full | ✅ send_draft | ✅ Designed for it |
| `/v1/chat/completions` API | ❌ None | ❌ SSE only | ❌ Text-first |
| Dashboard `/api/ws` TUI | ✅ Full | ✅ | ❌ Terminal UI |
| Telegram voice messages | ✅ Full | ❌ Batch | ❌ Tap to record |

## Model Files

The Android app requires offline AI models for STT, TTS, and wake word detection. These are **not** included in the repository due to their size (~350MB total). See the Client Setup section above for download instructions.

| Model | Directory | Size | Source |
|-------|-----------|------|--------|
| SenseVoice (offline STT) | `assets/sherpa-onnx/` | ~230MB | [k2-fsa/sherpa-onnx asr-models](https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models) |
| Silero VAD | `assets/sherpa-onnx/` | ~630KB | [k2-fsa/sherpa-onnx asr-models](https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models) |
| KWS Zipformer (wake word) | `assets/sherpa-onnx-kws/` | ~5MB | [k2-fsa/sherpa-onnx kws-models](https://github.com/k2-fsa/sherpa-onnx/releases/tag/kws-models) |
| Matcha TTS (zh+en) | `assets/sherpa-onnx-tts/` | ~75MB | [k2-fsa/sherpa-onnx tts-models](https://github.com/k2-fsa/sherpa-onnx/releases/tag/tts-models) |
| Vocos vocoder (16kHz) | `assets/sherpa-onnx-tts/` | ~52MB | [k2-fsa/sherpa-onnx vocoder-models](https://github.com/k2-fsa/sherpa-onnx/releases/tag/vocoder-models) |
| Sherpa-ONNX JNI libs | `jniLibs/arm64-v8a/` | ~30MB | [k2-fsa/sherpa-onnx v1.13.3](https://github.com/k2-fsa/sherpa-onnx/releases/tag/v1.13.3) |

**Expected file structure after setup:**

```
app/src/main/
├── assets/
│   ├── sherpa-onnx/           # Offline STT (SenseVoice)
│   │   ├── model.int8.onnx   # 229MB
│   │   ├── tokens.txt         # 309KB
│   │   └── silero_vad.onnx   # 629KB
│   ├── sherpa-onnx-kws/       # Wake word detection
│   │   ├── encoder.onnx       # 4.6MB
│   │   ├── decoder.onnx       # 177KB
│   │   ├── joiner.onnx        # 64KB
│   │   ├── tokens.txt         # 1.6KB
│   │   └── keywords.txt       # (in repo)
│   └── sherpa-onnx-tts/       # Offline TTS (Matcha zh-en)
│       ├── model-steps-3.onnx # 73MB
│       ├── vocos.onnx         # 52MB (vocos-16khz-univ.onnx renamed)
│       ├── lexicon.txt        # 1.4MB
│       ├── tokens.txt         # 21KB
│       ├── espeak-ng-data/    # English phoneme data
│       ├── phone-zh.fst       # 87KB
│       ├── date-zh.fst        # 58KB
│       └── number-zh.fst      # 63KB
└── jniLibs/
    └── arm64-v8a/
        ├── libonnxruntime.so  # 25MB
        └── libsherpa-onnx-jni.so # 4.5MB
```

**Notes:**
- The TTS model outputs at 16kHz (this is correct — `matcha-icefall-zh-en` is paired with `vocos-16khz-univ.onnx`, not the 22kHz variant)
- The offline STT is only used as a fallback when Xunfei cloud STT is unavailable
- KWS model is very lightweight (~5MB) and runs continuously with minimal CPU impact

## Contributing

Contributions welcome! Areas that need help:

- [ ] iOS client
- [ ] Wake word model training (custom hotword)
- [ ] Multi-language STT support
- [ ] Voice activity detection improvements
- [ ] Alternative TTS engines (Edge TTS, Kokoro)
- [ ] Car mode UI (large buttons, minimal info)

## License

MIT — see [LICENSE](LICENSE).

The vendored Sherpa-ONNX Kotlin bindings under
`app/src/main/java/com/k2fsa/sherpa/onnx/` are Copyright (c) Xiaomi
Corporation and remain licensed under
[Apache License 2.0](app/src/main/java/com/k2fsa/sherpa/onnx/LICENSE-APACHE-2.0),
not MIT.
