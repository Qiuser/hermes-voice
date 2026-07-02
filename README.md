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

1. Build the APK:
```bash
cd app
./gradlew assembleDebug
```

2. Install on your phone and configure:
   - Hermes address: `ws://your-hermes-host:8650/ws`
   - Voice Token: (the token you generated above)

3. First connection will trigger device pairing:
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

## Contributing

Contributions welcome! Areas that need help:

- [ ] iOS client
- [ ] Wake word model training (custom hotword)
- [ ] Multi-language STT support
- [ ] Voice activity detection improvements
- [ ] Alternative TTS engines (Edge TTS, Kokoro)
- [ ] Car mode UI (large buttons, minimal info)

## License

MIT
