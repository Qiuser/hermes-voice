"""Voice platform adapter for Hermes Agent.

Runs a WebSocket server that accepts connections from the Hermes Voice
Android app. The app sends STT-transcribed text, the adapter routes it
through the standard gateway message pipeline (tools, skills, memory,
approval, etc.), and streams the agent's reply back for TTS playback.

This adapter ships as a Hermes platform plugin under
``plugins/platforms/voice/``. The Hermes plugin loader scans the
directory at startup, calls :func:`register`, and the platform becomes
available through the platform_registry — no edits to core files required.

Configuration in .env::

    VOICE_TOKEN=<strong random secret>
    VOICE_WS_PORT=8650
    VOICE_ALLOWED_DEVICES=*
    VOICE_HOME_CHANNEL=default_device

Or in config.yaml::

    platforms:
      voice:
        enabled: true
        extra:
          port: 8650
          token: "..."
          allowed_devices: "*"

Protocol (App ↔ Adapter):

    App → Adapter:
      {"type":"auth","token":"...","device_id":"..."}
      {"type":"message","text":"..."}
      {"type":"approval_response","choice":"once|session|always|deny"}
      {"type":"command","cmd":"stop|new"}
      {"type":"pong"}

    Adapter → App:
      {"type":"auth_ok"}
      {"type":"auth_fail","reason":"..."}
      {"type":"delta","content":"..."}
      {"type":"end","finish_reason":"stop"}
      {"type":"tool_start","name":"...","description":"..."}
      {"type":"tool_end","name":"...","duration":0.0}
      {"type":"approval_request","command":"...","description":"..."}
      {"type":"task_complete","task":"...","success":true}
      {"type":"busy","message":"..."}
      {"type":"ping"}
      {"type":"error","message":"..."}
"""

import asyncio
import json
import logging
import os
import time
import uuid
from typing import Any, Dict, List, Optional

try:
    from aiohttp import web, WSMsgType
    AIOHTTP_AVAILABLE = True
except ImportError:
    AIOHTTP_AVAILABLE = False
    web = None  # type: ignore

from gateway.config import Platform, PlatformConfig
from gateway.platforms.base import (
    BasePlatformAdapter,
    MessageEvent,
    MessageType,
    SendResult,
)

logger = logging.getLogger(__name__)

# Defaults
DEFAULT_WS_PORT = 8650
MAX_MESSAGE_LENGTH = 2000  # Voice replies should be short

# Platform hint — don't mention "voice" to avoid triggering server-side TTS
VOICE_PLATFORM_HINT = (
    "You are on a voice platform (TTS output). Keep ALL replies short and speakable. "
    "Rules: "
    "1) Use plain spoken language, no markdown or formatting. "
    "2) Maximum 2-3 short sentences per reply. No exceptions. "
    "3) When content cannot be spoken aloud (code, URLs, commands, logs, configs), "
    "use send_to_feishu tool to send it, then say: sent to Feishu. "
    "4) For task completion reports (deployment results, build logs, status details), "
    "send the full report to Feishu via send_to_feishu, "
    "verbally only say the outcome: e.g. deploy succeeded, or deploy failed. "
    "5) NEVER read out deployment logs, build output, or technical details verbally. "
    "6) Confirmations: one sentence max. e.g. OK started, or done successfully."
)


def check_requirements() -> bool:
    """Return True if aiohttp is available."""
    return AIOHTTP_AVAILABLE


def validate_config(config: PlatformConfig) -> bool:
    """Return True if required config is present."""
    token = os.getenv("VOICE_TOKEN", "")
    if not token:
        extra = config.extra or {}
        token = extra.get("token", "")
    return bool(token)


def is_connected(config: PlatformConfig) -> bool:
    """Return True if the platform has credentials configured."""
    return validate_config(config)


def _env_enablement() -> Optional[dict]:
    """Seed PlatformConfig.extra from env vars."""
    token = os.getenv("VOICE_TOKEN", "")
    if not token:
        return None
    return {
        "token": token,
        "port": int(os.getenv("VOICE_WS_PORT", str(DEFAULT_WS_PORT))),
        "allowed_devices": os.getenv("VOICE_ALLOWED_DEVICES", "*"),
    }


class VoiceClient:
    """Represents a single connected voice app client."""

    def __init__(self, ws, device_id: str):
        self.ws = ws
        self.device_id = device_id
        self.connected_at = time.time()
        self.last_activity = time.time()

    async def send_json(self, data: dict) -> bool:
        """Send a JSON message to this client. Returns False if failed."""
        try:
            await self.ws.send_json(data)
            return True
        except Exception as e:
            logger.debug("Failed to send to device %s: %s", self.device_id, e)
            return False


class VoiceAdapter(BasePlatformAdapter):
    """Voice platform adapter — WebSocket server for Android voice app."""

    supports_code_blocks = False
    REQUIRES_EDIT_FINALIZE = False

    def supports_draft_streaming(self, chat_type=None, metadata=None) -> bool:
        """Voice adapter supports real-time streaming via WebSocket."""
        return True

    async def send_draft(self, chat_id: str, draft_id: int, content: str, metadata=None) -> "SendResult":
        """Stream partial content to the voice app in real-time.

        Called by gateway as agent generates tokens. `content` is cumulative
        (grows each call). We track what was already sent and push only the new part.
        Strips the streaming cursor character (▉) which is for visual platforms only.
        """
        client = self._clients.get(chat_id)
        if not client:
            return SendResult(success=True)

        # Strip streaming cursor character
        content = content.rstrip(" \u2589")

        # Track last sent position per chat_id+draft_id
        key = f"{chat_id}:{draft_id}"
        if not hasattr(self, "_draft_offsets"):
            self._draft_offsets = {}
        last_offset = self._draft_offsets.get(key, 0)
        new_content = content[last_offset:]

        if new_content:
            await client.send_json({"type": "delta", "content": new_content})
            self._draft_offsets[key] = len(content)

        return SendResult(success=True)

    def _should_auto_tts_for_chat(self, chat_id: str) -> bool:
        """Voice platform never does server-side TTS — App handles TTS locally."""
        return False

    async def play_tts(self, *args, **kwargs) -> "SendResult":
        """Disabled — voice app does TTS on device."""
        return SendResult(success=True)

    async def send_voice(self, *args, **kwargs) -> "SendResult":
        """Disabled — voice app does TTS on device."""
        return SendResult(success=True)

    def __init__(self, config: PlatformConfig):
        super().__init__(config, Platform("voice"))
        extra = config.extra or {}
        self._token = extra.get("token") or os.getenv("VOICE_TOKEN", "")
        self._port = int(extra.get("port") or os.getenv("VOICE_WS_PORT", str(DEFAULT_WS_PORT)))
        self._allowed_devices = self._parse_allowed_devices(
            extra.get("allowed_devices") or os.getenv("VOICE_ALLOWED_DEVICES", "*")
        )
        self._app: Optional[web.Application] = None
        self._runner: Optional[web.AppRunner] = None
        self._site: Optional[web.TCPSite] = None
        # Connected clients: device_id -> VoiceClient
        self._clients: Dict[str, VoiceClient] = {}
        # Pending approval responses: approval_id -> asyncio.Future
        self._pending_approvals: Dict[str, asyncio.Future] = {}

    @staticmethod
    def _parse_allowed_devices(value: str) -> set:
        """Parse allowed devices config into a set."""
        if not value or value.strip() == "*":
            return {"*"}
        return {d.strip() for d in value.split(",") if d.strip()}

    def _is_device_allowed(self, device_id: str) -> bool:
        """Check if a device is in the allowlist."""
        if "*" in self._allowed_devices:
            return True
        return device_id in self._allowed_devices

    async def connect(self, *, is_reconnect: bool = False) -> bool:
        """Start the WebSocket server.

        ``is_reconnect`` is part of the BasePlatformAdapter.connect contract
        (see gateway/platforms/base.py): False on a cold first boot, True
        when the reconnect watcher is re-establishing a platform that
        dropped after an outage. Voice has no server-side update queue to
        preserve across reconnects, so the flag is accepted but unused.
        """
        if not AIOHTTP_AVAILABLE:
            logger.warning("[Voice] aiohttp not available")
            return False

        if not self._token:
            logger.error("[Voice] VOICE_TOKEN not set — refusing to start")
            return False

        try:
            self._app = web.Application()
            self._app.router.add_get("/ws", self._handle_websocket)
            self._app.router.add_get("/health", self._handle_health)

            self._runner = web.AppRunner(self._app)
            await self._runner.setup()
            self._site = web.TCPSite(self._runner, "0.0.0.0", self._port)
            await self._site.start()

            self._mark_connected()
            logger.info(
                "[Voice] WebSocket server listening on ws://0.0.0.0:%d/ws",
                self._port,
            )
            # Start heartbeat task
            asyncio.create_task(self._heartbeat_loop())
            return True

        except Exception as e:
            logger.error("[Voice] Failed to start: %s", e, exc_info=True)
            return False

    async def disconnect(self) -> None:
        """Stop the WebSocket server and close all client connections."""
        self._mark_disconnected()
        # Close all client connections
        for client in list(self._clients.values()):
            try:
                await client.ws.close()
            except Exception:
                pass
        self._clients.clear()

        if self._site:
            await self._site.stop()
            self._site = None
        if self._runner:
            await self._runner.cleanup()
            self._runner = None
        self._app = None
        logger.info("[Voice] Disconnected")

    async def send(
        self,
        chat_id: str,
        content: str,
        reply_to: Optional[str] = None,
        metadata: Optional[Dict[str, Any]] = None,
    ) -> SendResult:
        """Send final content to the voice app client.

        Uses metadata['notify'] to distinguish agent replies from system notifications:
        - notify=True → agent reply (send as delta or end if already streamed)
        - notify absent → system notification (send as 'system' type, not TTS'd)
        """
        client = self._clients.get(chat_id)
        if not client:
            return SendResult(success=False, error=f"Device {chat_id} not connected")

        content = (content or "").strip()
        if not content:
            return SendResult(success=True, message_id=str(uuid.uuid4()))

        is_agent_reply = bool((metadata or {}).get("notify"))

        # Check if this content was already streamed via send_draft
        already_streamed = False
        if hasattr(self, "_draft_offsets"):
            for key in list(self._draft_offsets.keys()):
                if key.startswith(f"{chat_id}:"):
                    offset = self._draft_offsets.pop(key)
                    if offset > 0:
                        already_streamed = True

        if already_streamed:
            # Content was streamed — just send end marker
            await client.send_json({"type": "end", "finish_reason": "stop"})
        elif is_agent_reply:
            # Non-streamed agent reply — send as delta + end
            sentences = self._split_sentences(content)
            for sentence in sentences:
                ok = await client.send_json({"type": "delta", "content": sentence})
                if not ok:
                    return SendResult(success=False, error="WebSocket send failed")
            await client.send_json({"type": "end", "finish_reason": "stop"})
        else:
            # System notification — send as system type, App won't TTS this
            await client.send_json({"type": "system", "content": content[:300]})

        return SendResult(success=True, message_id=str(uuid.uuid4()))

    async def get_chat_info(self, chat_id: str) -> Dict[str, Any]:
        """Return info about a connected voice client."""
        client = self._clients.get(chat_id)
        if client:
            return {
                "name": f"Voice:{chat_id}",
                "type": "dm",
                "device_id": chat_id,
                "connected_since": client.connected_at,
            }
        return {"name": f"Voice:{chat_id}", "type": "dm"}

    # ──────────────────────────────────────────────────────────────────────
    # WebSocket handlers
    # ──────────────────────────────────────────────────────────────────────

    async def _handle_health(self, request) -> "web.Response":
        """Health check endpoint."""
        return web.json_response({
            "status": "healthy",
            "platform": "voice",
            "connected_devices": len(self._clients),
        })

    async def _handle_websocket(self, request) -> "web.WebSocketResponse":
        """Handle a new WebSocket connection from the voice app."""
        ws = web.WebSocketResponse(heartbeat=30)
        await ws.prepare(request)

        device_id = None
        authenticated = False

        try:
            # Wait for auth message (timeout 10s)
            try:
                msg = await asyncio.wait_for(ws.receive(), timeout=10.0)
            except asyncio.TimeoutError:
                await ws.send_json({"type": "auth_fail", "reason": "auth timeout"})
                await ws.close()
                return ws

            if msg.type != WSMsgType.TEXT:
                await ws.close()
                return ws

            try:
                data = json.loads(msg.data)
            except json.JSONDecodeError:
                await ws.send_json({"type": "auth_fail", "reason": "invalid json"})
                await ws.close()
                return ws

            # Verify auth
            if data.get("type") != "auth":
                await ws.send_json({"type": "auth_fail", "reason": "expected auth message"})
                await ws.close()
                return ws

            token = data.get("token", "")
            device_id = data.get("device_id", "").strip()

            if not device_id:
                await ws.send_json({"type": "auth_fail", "reason": "device_id required"})
                await ws.close()
                return ws

            if token != self._token:
                logger.warning("[Voice] Auth failed for device %s", device_id)
                await ws.send_json({"type": "auth_fail", "reason": "invalid token"})
                await ws.close()
                return ws

            if not self._is_device_allowed(device_id):
                await ws.send_json({"type": "auth_fail", "reason": "device not allowed"})
                await ws.close()
                return ws

            # Auth success
            authenticated = True

            # Close existing connection for same device (single session)
            if device_id in self._clients:
                old_client = self._clients[device_id]
                try:
                    await old_client.ws.close()
                except Exception:
                    pass

            client = VoiceClient(ws, device_id)
            self._clients[device_id] = client
            await ws.send_json({"type": "auth_ok"})
            logger.info("[Voice] Device %s connected from %s", device_id, request.remote)

            # Message loop
            async for msg in ws:
                if msg.type == WSMsgType.TEXT:
                    client.last_activity = time.time()
                    await self._handle_client_message(client, msg.data)
                elif msg.type == WSMsgType.ERROR:
                    logger.warning("[Voice] WS error from %s: %s", device_id, ws.exception())
                    break

        except Exception as e:
            logger.error("[Voice] Connection error for device %s: %s", device_id, e, exc_info=True)
        finally:
            if device_id and device_id in self._clients:
                del self._clients[device_id]
                logger.info("[Voice] Device %s disconnected", device_id)

        return ws

    async def _handle_client_message(self, client: VoiceClient, raw: str) -> None:
        """Process a message from a connected voice app client."""
        try:
            data = json.loads(raw)
        except json.JSONDecodeError:
            await client.send_json({"type": "error", "message": "invalid json"})
            return

        msg_type = data.get("type", "")

        if msg_type == "message":
            text = data.get("text", "").strip()
            if not text:
                return
            await self._process_user_message(client, text)

        elif msg_type == "approval_response":
            choice = data.get("choice", "deny")
            approval_id = data.get("approval_id", "")
            if approval_id and approval_id in self._pending_approvals:
                future = self._pending_approvals.pop(approval_id)
                if not future.done():
                    future.set_result(choice)

        elif msg_type == "command":
            cmd = data.get("cmd", "")
            if cmd == "stop":
                await self._process_user_message(client, "/stop")
            elif cmd == "new":
                await self._process_user_message(client, "/new")

        elif msg_type == "pong":
            pass  # heartbeat response, nothing to do

        elif msg_type == "request_stt_token":
            await self._handle_stt_token_request(client)

    async def _process_user_message(self, client: VoiceClient, text: str) -> None:
        if not text or not text.strip():
            return
        """Route user message through the gateway message pipeline."""
        source = self.build_source(
            chat_id=client.device_id,
            chat_name=f"Voice:{client.device_id}",
            chat_type="dm",
            user_id=client.device_id,
            user_name=client.device_id,
        )
        event = MessageEvent(
            text=text,
            message_type=MessageType.TEXT,
            source=source,
            message_id=str(uuid.uuid4()),
        )
        await self.handle_message(event)

    # ──────────────────────────────────────────────────────────────────────
    # Approval support
    # ──────────────────────────────────────────────────────────────────────

    async def send_exec_approval(
        self,
        chat_id: str,
        command: str,
        session_key: str,
        description: str = "dangerous command",
        metadata: Optional[Dict[str, Any]] = None,
    ) -> SendResult:
        """Send approval request to voice app and wait for response."""
        client = self._clients.get(chat_id)
        if not client:
            return SendResult(success=False, error="Device not connected")

        approval_id = str(uuid.uuid4())
        ok = await client.send_json({
            "type": "approval_request",
            "approval_id": approval_id,
            "command": command[:500],
            "description": description,
        })
        if not ok:
            return SendResult(success=False, error="Send failed")

        return SendResult(success=True, message_id=approval_id)

    # ──────────────────────────────────────────────────────────────────────
    # Utility
    # ──────────────────────────────────────────────────────────────────────

    @staticmethod
    def _split_sentences(text: str) -> List[str]:
        """Split text into sentences for streaming TTS."""
        import re
        # Split on Chinese/English sentence boundaries
        parts = re.split(r'([。！？；\n.!?;])', text)
        sentences = []
        current = ""
        for part in parts:
            current += part
            if part and part[-1] in "。！？；.!?;\n":
                if current.strip():
                    sentences.append(current.strip())
                current = ""
        if current.strip():
            sentences.append(current.strip())
        return sentences if sentences else [text]

    # ──────────────────────────────────────────────────────────────────────
    # STT token generation (讯飞)
    # ──────────────────────────────────────────────────────────────────────

    async def _handle_stt_token_request(self, client: VoiceClient) -> None:
        """Generate a signed 讯飞 WebSocket URL and return to the App."""
        import base64
        import hashlib
        import hmac as hmac_mod
        from datetime import datetime as dt
        from wsgiref.handlers import format_date_time
        from time import mktime
        from urllib.parse import urlencode

        api_key = os.getenv("XFYUN_API_KEY", "")
        api_secret = os.getenv("XFYUN_API_SECRET", "")

        if not api_key or not api_secret:
            await client.send_json({
                "type": "stt_token",
                "provider": "xfyun",
                "url": "",
                "error": "XFYUN_API_KEY or XFYUN_API_SECRET not configured"
            })
            return

        try:
            host = "iat.xf-yun.com"
            path = "/v1"
            now = dt.now()
            date = format_date_time(mktime(now.timetuple()))

            signature_origin = f"host: {host}\ndate: {date}\nGET {path} HTTP/1.1"
            signature_sha = hmac_mod.new(
                api_secret.encode(), signature_origin.encode(), hashlib.sha256
            ).digest()
            signature = base64.b64encode(signature_sha).decode()

            authorization_origin = (
                f'api_key="{api_key}", algorithm="hmac-sha256", '
                f'headers="host date request-line", signature="{signature}"'
            )
            authorization = base64.b64encode(authorization_origin.encode()).decode()

            params = {"authorization": authorization, "date": date, "host": host}
            url = f"wss://{host}{path}?{urlencode(params)}"

            await client.send_json({
                "type": "stt_token",
                "provider": "xfyun",
                "url": url,
                "app_id": os.getenv("XFYUN_APP_ID", ""),
                "expires_in": 300,
            })
        except Exception as e:
            logger.warning("[Voice] Failed to generate STT token: %s", e)
            await client.send_json({
                "type": "stt_token",
                "provider": "xfyun",
                "url": "",
                "error": str(e),
            })

    async def _heartbeat_loop(self, interval: float = 25.0) -> None:
        """Send periodic ping to all connected clients."""
        while self._running:
            await asyncio.sleep(interval)
            for client in list(self._clients.values()):
                try:
                    await client.send_json({"type": "ping"})
                except Exception:
                    pass

    # ──────────────────────────────────────────────────────────────────────
    # Standalone sender (for cron/notifications when gateway not co-resident)
    # ──────────────────────────────────────────────────────────────────────


async def _standalone_send(
    pconfig: Any,
    chat_id: str,
    message: str,
    *,
    thread_id: Optional[str] = None,
    media_files: Optional[list] = None,
    force_document: bool = False,
) -> dict:
    """Standalone send — not supported for voice (requires live WS)."""
    return {"error": "Voice platform requires a live WebSocket connection"}


# ──────────────────────────────────────────────────────────────────────────
# Plugin registration
# ──────────────────────────────────────────────────────────────────────────


def register(ctx) -> None:
    """Plugin entry point — called by the Hermes plugin system at startup."""
    ctx.register_platform(
        name="voice",
        label="Voice",
        adapter_factory=lambda cfg: VoiceAdapter(cfg),
        check_fn=check_requirements,
        validate_config=validate_config,
        is_connected=is_connected,
        required_env=["VOICE_TOKEN"],
        install_hint="aiohttp is already a Hermes dependency",
        env_enablement_fn=_env_enablement,
        cron_deliver_env_var="VOICE_HOME_CHANNEL",
        standalone_sender_fn=_standalone_send,
        allowed_users_env="VOICE_ALLOWED_DEVICES",
        allow_all_env="VOICE_ALLOWED_DEVICES",
        max_message_length=MAX_MESSAGE_LENGTH,
        emoji="🎙️",
        pii_safe=True,
        allow_update_command=False,
        platform_hint=VOICE_PLATFORM_HINT,
    )

    # Register send_to_feishu tool
    ctx.register_tool(
        name="send_to_feishu",
        toolset="terminal",
        schema=SEND_TO_FEISHU_SCHEMA,
        handler=_handle_send_to_feishu,
        check_fn=lambda: bool(__import__("os").getenv("FEISHU_HOME_CHANNEL") or __import__("os").getenv("FEISHU_APP_ID")),
        emoji="📨",
    )


# ──────────────────────────────────────────────────────────────────────────
# send_to_feishu tool — lets the voice agent push detailed content to Feishu
# ──────────────────────────────────────────────────────────────────────────

SEND_TO_FEISHU_SCHEMA = {
    "name": "send_to_feishu",
    "description": (
        "Send a detailed message to the user's Feishu (飞书) chat. "
        "Use this when the user is on voice mode and you need to send long content, "
        "code snippets, links, or formatted text that is too long to speak aloud. "
        "The user will receive it in their Feishu DM."
    ),
    "parameters": {
        "type": "object",
        "properties": {
            "content": {
                "type": "string",
                "description": "The message content to send to Feishu. Can include markdown.",
            },
        },
        "required": ["content"],
    },
}


def _handle_send_to_feishu(args, **kw):
    """Tool handler: send content to user's Feishu chat."""
    import asyncio
    import json
    import os

    content = args.get("content", "").strip()
    if not content:
        return json.dumps({"error": "content is required"})

    chat_id = os.getenv("FEISHU_HOME_CHANNEL", "")
    if not chat_id:
        # Try config.yaml
        try:
            from hermes_cli.config import cfg_get, load_config
            cfg = load_config()
            chat_id = cfg_get(cfg, "FEISHU_HOME_CHANNEL", default="")
        except Exception:
            pass

    if not chat_id:
        return json.dumps({"error": "FEISHU_HOME_CHANNEL not configured"})

    try:
        from gateway.run import _gateway_runner_ref
        from gateway.config import Platform
        runner = _gateway_runner_ref()
        if runner is None:
            return json.dumps({"error": "Gateway runner not available"})

        adapter = runner.adapters.get(Platform.FEISHU)
        if adapter is None:
            return json.dumps({"error": "Feishu adapter not connected"})

        # Schedule the async send on the gateway event loop
        from agent.async_utils import safe_schedule_threadsafe
        import threading

        loop = None
        for attr in ("_gateway_loop", "_loop", "loop"):
            loop = getattr(runner, attr, None)
            if loop is not None:
                break
        if loop is None:
            # Try asyncio running loop
            try:
                loop = asyncio.get_event_loop()
            except Exception:
                pass

        if loop is None:
            return json.dumps({"error": "No event loop available"})

        # Build a Feishu interactive card with markdown for proper rendering
        import json as _json
        _card = {
            "config": {"wide_screen_mode": True},
            "header": {
                "title": {"content": "📋 来自语音对话", "tag": "plain_text"},
                "template": "blue",
            },
            "elements": [
                {"tag": "markdown", "content": content},
            ],
        }
        _card_payload = _json.dumps(_card, ensure_ascii=False)

        async def _send_card():
            return await adapter._feishu_send_with_retry(
                chat_id=chat_id,
                msg_type="interactive",
                payload=_card_payload,
                reply_to=None,
                metadata=None,
            )

        future = safe_schedule_threadsafe(_send_card(), loop)
        if future is not None:
            result = future.result(timeout=30)
            if hasattr(result, "success") and result.success:
                return json.dumps({"result": "Message sent to Feishu successfully."})
            else:
                err = getattr(result, "error", "unknown error")
                return json.dumps({"error": f"Feishu send failed: {err}"})
        return json.dumps({"error": "Failed to schedule send"})
    except Exception as e:
        return json.dumps({"error": f"send_to_feishu failed: {e}"})


