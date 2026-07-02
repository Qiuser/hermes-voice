# Hermes Voice — 技术规划文档

## 产品定位

Android 原生语音助手，通过蓝牙耳机按键触发，与 Hermes Agent 进行语音对话。
核心场景：开车/外出时免手动操作，语音吩咐 Hermes 执行任务。

**双端架构：**
- **服务端**：Voice Platform Adapter（Hermes 自定义平台插件）— ✅ 已完成
- **客户端**：Android APK — 开发中

---

## 服务端状态：✅ 已完成并部署

Voice Adapter 以 Hermes 插件形式运行，零源码修改，与飞书/微信 Adapter 平级。

### 部署信息

| 项目 | 值 |
|------|-----|
| 插件位置 | `~/.hermes/hermes-agent/plugins/platforms/voice/` |
| 监听端口 | `8650`（WebSocket） |
| 健康检查 | `http://<YOUR_HERMES_HOST>:8650/health` |
| 内网连接地址 | `ws://<YOUR_HERMES_HOST>:8650/ws` |
| 外网连接地址 | 需通过 nginx 反代暴露 WSS（待配置） |
| VOICE_TOKEN | `<YOUR_VOICE_TOKEN>` |
| 设备授权 | 首次连接需 pairing，已授权 `test_device` |

### 已验证的功能

- ✅ WebSocket 连接 + Token 鉴权
- ✅ 设备 pairing 授权机制（与飞书相同）
- ✅ 消息走完整 gateway agent 流程（tools/skills/memory/session/approval）
- ✅ 流式回复推送（delta → end）
- ✅ 心跳保活（25s 间隔）
- ✅ 语音模式 platform_hint（自动简短回复）
- ✅ Hindsight 记忆生效（platform=voice）
- ✅ session 独立（session_key: `agent:main:voice:dm:<device_id>`）

---

## 架构概览

```
┌─────────────────────────────────────────────────────────┐
│                    Hermes Gateway                         │
│                                                          │
│  ┌────────────┐  ┌────────────┐  ┌──────────────────┐  │
│  │  飞书       │  │  微信       │  │  Voice Adapter   │  │
│  │  Adapter   │  │  Adapter   │  │  (插件, 8650)    │  │
│  └─────┬──────┘  └─────┬──────┘  └────────┬─────────┘  │
│        │               │                   │             │
│        └───────────────┼───────────────────┘             │
│                        ▼                                 │
│  ┌─────────────────────────────────────────────────┐    │
│  │              Agent 完整流程                       │    │
│  │  tools / skills / memory / session / approval    │    │
│  └─────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────┘
                         ▲
                         │ WebSocket
                         │
┌────────────────────────┼────────────────────────────────┐
│                  Android App                              │
│                                                          │
│  ┌──────────┐   ┌──────────┐   ┌──────────┐            │
│  │Bluetooth │   │  Speech  │   │   TTS    │            │
│  │ Service  │   │Recognizer│   │  Engine  │            │
│  └────┬─────┘   └────┬─────┘   └────┬─────┘            │
│       │               │               │                  │
│       ▼               ▼               ▲                  │
│  ┌──────────────────────────────────────┐               │
│  │         VoiceSessionManager          │               │
│  │  状态机: 待命→听取→思考→播报→听取... │               │
│  └──────────────────┬───────────────────┘               │
│                     │                                    │
│                     ▼                                    │
│  ┌──────────────────────────────────────┐               │
│  │        VoiceWebSocketClient          │               │
│  │   连接 Voice Adapter (8650)          │               │
│  └──────────────────────────────────────┘               │
└─────────────────────────────────────────────────────────┘
```

---

## WebSocket 协议（App ↔ Voice Adapter）

### 连接地址

```
内网测试: ws://<YOUR_HERMES_HOST>:8650/ws
外网生产: wss://你的域名:端口/ws  (通过 nginx 反代)
```

### 完整消息协议

#### App → 服务端

```json
// 1. 鉴权（连接后必须第一条发送，10秒超时）
{"type": "auth", "token": "<YOUR_VOICE_TOKEN>", "device_id": "my_phone_001"}

// 2. 发送消息（文字输入或讯飞识别后的文字）
{"type": "message", "text": "发下信封管理后台"}

// 3. 请求 STT 临时凭据
{"type": "request_stt_token"}

// 4. 审批回复
{"type": "approval_response", "approval_id": "uuid-xxx", "choice": "once"}
// choice 可选值: "once" | "session" | "always" | "deny"

// 5. 命令
{"type": "command", "cmd": "stop"}   // 中断当前任务
{"type": "command", "cmd": "new"}    // 开新会话

// 6. 心跳回复
{"type": "pong"}
```

#### 服务端 → App

```json
// 1. 鉴权结果
{"type": "auth_ok"}
{"type": "auth_fail", "reason": "invalid token"}

// 2. Agent 流式回复
{"type": "delta", "content": "好的，"}
{"type": "delta", "content": "开始部署了。"}
{"type": "end", "finish_reason": "stop"}
// App 收到 delta 立刻送入 TTS 播放，收到 end 标记回复结束

// 3. 工具执行状态（可选展示）
{"type": "tool_start", "name": "terminal", "description": "执行部署脚本"}
{"type": "tool_end", "name": "terminal", "duration": 2.3}

// 4. 审批请求
{"type": "approval_request", "approval_id": "uuid-xxx", "command": "curl -s xxx | bash", "description": "高危管道命令"}
// App 收到后 TTS 播报"需要执行xxx命令，是否允许？"，然后 STT 识别用户回复

// 5. 后台任务完成通知
{"type": "task_complete", "task": "部署 envo-admin", "success": true}

// 6. Agent 忙碌（消息已排队）
{"type": "busy", "message": "正在执行任务，消息已排队"}

// 7. 心跳（25秒间隔）
{"type": "ping"}
// App 收到后回复 {"type": "pong"}

// 8. 系统消息（不播报，可选显示在 UI）
{"type": "system", "content": "💾 Self-improvement review: Memory updated"}
// App 收到 system 类型不做 TTS，可选在界面底部静默展示或直接忽略

// 9. STT 临时凭据响应
{"type": "stt_token", "provider": "xfyun", "url": "wss://iat-api.xfyun.cn/v2/iat?authorization=xxx&date=xxx&host=iat-api.xfyun.cn", "expires_in": 300}
// App 拿到 url 后直接连讯飞 WebSocket 做流式识别
// 如果凭据生成失败：
{"type": "stt_token", "provider": "xfyun", "url": "", "error": "API key not configured"}

// 10. 错误
{"type": "error", "message": "invalid json"}
```

### 连接生命周期

```
App 启动 → WebSocket 连接 → 发送 auth → 收到 auth_ok → 就绪
                                        → 收到 auth_fail → 断开，提示用户

就绪后:
  用户按蓝牙键 → 录音 → 发送 audio（base64 PCM）→ 收到 stt_result → 等待 delta/end → TTS 播报
  收到 ping → 回复 pong
  收到 approval_request → TTS 播报 → STT 识别 → 发送 approval_response
  收到 task_complete → 通知栏 + TTS

STT 降级策略:
  网络正常 → 发送 audio 到服务端识别（准确率高，支持中英混合）
  网络异常/超时 → 退回本地 SenseVoice 识别（纯中文可用）

断线:
  WebSocket 断开 → 指数退避重连（1s→2s→4s→8s→max 30s）
  重连后重新发送 auth
```

### 首次设备授权流程

```
1. App 连接并 auth 成功
2. 发送第一条消息
3. 服务端返回 pairing code（如 "LFUWKFYZ"）
4. 用户需要在 Hermes 侧执行: hermes pairing approve voice <code>
5. 授权后，后续消息正常走 agent 流程
```

注意：也可以预先设置 `VOICE_ALLOWED_DEVICES=*` 跳过 pairing 限制。

---

## App 端开发指南

### 技术栈

| 组件 | 选型 | 理由 |
|------|------|------|
| 语言 | Kotlin | 原生性能，硬件控制最强 |
| 最低 API | Android 10 (API 29) | 覆盖 95%+ 设备 |
| STT | 讯飞语音听写 Android SDK | 中文识别精准、支持离线、流式实时 |
| TTS | Android TextToSpeech | 免费、离线、即时 |
| 网络 | OkHttp 4.x WebSocket | 成熟稳定 |
| 后台 | Foreground Service (TYPE_MEDIA_PLAYBACK) | 保活 + 蓝牙监听 |
| 蓝牙 | MediaSession + MediaButtonReceiver | 标准媒体按键拦截 |
| DI | Hilt | 官方推荐，简洁 |
| 架构 | MVVM + Clean Architecture | 分层清晰 |
| 本地存储 | Room (SQLite) | 对话历史 |

### 项目结构

```
app/src/main/java/com/hermes/voice/
├── HermesVoiceApp.kt              # Application + Hilt 入口
├── MainActivity.kt                # 主界面
│
├── service/
│   ├── VoiceService.kt            # Foreground Service 核心
│   └── BootReceiver.kt            # 开机自启（可选）
│
├── audio/
│   ├── SpeechRecognizerManager.kt # STT 封装
│   ├── TtsManager.kt             # TTS 封装（流式播报）
│   └── AudioFocusManager.kt      # 音频焦点管理
│
├── bluetooth/
│   ├── BluetoothMediaReceiver.kt  # 蓝牙媒体按键接收
│   └── BluetoothStateMonitor.kt   # 蓝牙连接状态监控
│
├── network/
│   ├── VoiceWebSocketClient.kt   # WebSocket 客户端
│   ├── ProtocolModels.kt         # 协议消息数据类
│   ├── ConnectionManager.kt      # 自动重连 + 心跳
│   └── ApiConfig.kt              # URL、Token 配置
│
├── session/
│   ├── VoiceSessionManager.kt     # 对话状态机
│   ├── VoiceSession.kt           # 单次会话数据
│   └── SessionState.kt           # 状态枚举
│
├── approval/
│   └── VoiceApprovalHandler.kt    # 审批语音交互
│
├── notification/
│   ├── NotificationHelper.kt      # 通知栏管理
│   └── TaskNotificationHandler.kt # 后台任务完成通知
│
├── ui/
│   ├── MainViewModel.kt          # 主界面 ViewModel
│   ├── HistoryActivity.kt        # 对话记录列表
│   ├── HistoryDetailActivity.kt  # 对话记录详情
│   ├── SettingsActivity.kt       # 设置页
│   └── SetupGuideActivity.kt    # 首次使用引导
│
├── di/
│   └── AppModule.kt              # Hilt 依赖注入模块
│
├── data/
│   ├── ConversationDao.kt        # Room DAO
│   ├── ConversationEntity.kt     # 对话记录实体
│   ├── MessageEntity.kt          # 单条消息实体
│   └── AppDatabase.kt            # Room 数据库
│
└── util/
    ├── Constants.kt              # 常量
    └── Logger.kt                 # 日志工具
```

### VoiceSessionManager 状态机

```
IDLE → [蓝牙按键触发] → LISTENING
LISTENING → [识别完成] → THINKING
LISTENING → [超时5秒] → IDLE
THINKING → [收到首个delta] → SPEAKING
THINKING → [超时/错误] → IDLE
SPEAKING → [播报完成] → LISTENING (连续对话)
SPEAKING → [用户打断] → LISTENING
LISTENING → [静默5秒] → IDLE

审批特殊状态:
SPEAKING → [收到approval_request] → APPROVAL_LISTENING
APPROVAL_LISTENING → [识别到允许/拒绝] → 发送回复 → SPEAKING
```

### TTS 流式播报策略

```kotlin
// 收到 delta 消息后，按标点符号分句
// 每分出一句完整的话 → TextToSpeech.speak(queue=QUEUE_ADD)
// 收到 end → 标记最后一句
// 最后一句播完 → 回调通知 SessionManager → 进入下一轮 LISTENING

// 分句标点: 。！？；.!?;\n
// 缓冲区: 攒字符直到遇到标点，然后一起送 TTS
```

### VoiceWebSocketClient 关键实现

```kotlin
class VoiceWebSocketClient(
    private val config: ApiConfig,
    private val onDelta: (String) -> Unit,
    private val onEnd: () -> Unit,
    private val onApproval: (ApprovalRequest) -> Unit,
    private val onTaskComplete: (TaskComplete) -> Unit,
    private val onBusy: (String) -> Unit,
    private val onConnectionChange: (Boolean) -> Unit,
) {
    private var webSocket: WebSocket? = null
    private var authenticated = false

    fun connect() {
        val request = Request.Builder()
            .url(config.wsUrl)  // ws://<YOUR_HERMES_HOST>:8650/ws
            .build()
        webSocket = okHttpClient.newWebSocket(request, listener)
    }

    fun sendMessage(text: String) {
        send("""{"type":"message","text":"$text"}""")
    }

    fun sendApprovalResponse(approvalId: String, choice: String) {
        send("""{"type":"approval_response","approval_id":"$approvalId","choice":"$choice"}""")
    }

    fun sendCommand(cmd: String) {
        send("""{"type":"command","cmd":"$cmd"}""")
    }

    // WebSocket Listener 中处理:
    // onMessage → 解析 JSON → 按 type 分发到对应回调
    // onClosed/onFailure → 触发重连
}
```

---

## 安全方案

### 传输安全
- 内网测试: `ws://` (明文，仅限开发)
- 生产环境: `wss://` (通过 nginx 反代加 TLS)

### 认证
- VOICE_TOKEN 认证 WebSocket 连接
- Token 存储在 Android Keystore（硬件加密）
- 首次配置通过 app 设置页输入

### 设备管理
- 每个 device_id 同时只能一个连接（后连接踢掉前连接）
- 可通过 `VOICE_ALLOWED_DEVICES` 限制允许的设备
- 或通过 pairing 机制逐个授权

---

## 设置项

| 设置 | 默认值 | 说明 |
|------|--------|------|
| Hermes 地址 | (用户配置) | WS/WSS URL |
| Voice Token | (用户配置) | 认证 token |
| TTS 语速 | 1.0 | 0.5 - 2.0 |
| TTS 音色 | 系统默认 | 可选系统已安装引擎 |
| 连续对话超时 | 5秒 | 3-10秒 |
| 任务通知方式 | 通知+语音 | 仅通知/通知+语音/静默 |
| 对话历史保留 | 7天 | 1/7/30天/永久 |

---

## 开发顺序

### ✅ 阶段一：服务端 Voice Adapter（已完成）
- 插件实现，自动被 gateway 发现加载
- WebSocket 服务监听 8650
- 鉴权 + pairing + 消息路由
- 流式回复推送
- 语音模式 platform_hint

### 阶段二：App 基础对话
1. **PR1: 项目骨架** — ✅ 已完成
2. **PR2: WebSocket 通信层** — 连接 Voice Adapter，收发消息，自动重连
3. **PR3: 语音层** — STT + TTS + 状态机
4. **PR4: 蓝牙 + Service** — Foreground Service + 蓝牙按键

### 阶段三：完善功能
- 审批语音交互
- 后台任务通知
- 对话历史
- 连续对话 + 打断

### 阶段四：后台稳定性
- 保活策略
- 电池优化豁免引导
- 蓝牙状态管理
- 音频焦点

### 阶段五：语音唤醒（可选）
- Picovoice Porcupine 集成

---

## 依赖列表

### 服务端
无额外依赖 — 使用 aiohttp（Hermes 已有）。

### 客户端（build.gradle.kts）

```kotlin
// Android 核心
implementation("androidx.core:core-ktx:1.13.1")
implementation("androidx.lifecycle:lifecycle-service:2.8.3")
implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.3")

// Hilt DI
implementation("com.google.dagger:hilt-android:2.51.1")
kapt("com.google.dagger:hilt-compiler:2.51.1")

// 网络 (OkHttp WebSocket)
implementation("com.squareup.okhttp3:okhttp:4.12.0")

// JSON
implementation("com.google.code.gson:gson:2.11.0")

// Room (对话历史)
implementation("androidx.room:room-runtime:2.6.1")
implementation("androidx.room:room-ktx:2.6.1")
kapt("androidx.room:room-compiler:2.6.1")

// MediaSession (蓝牙按键)
implementation("androidx.media:media:1.7.0")

// Coroutines
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
```

---

## 注意事项

1. 服务端已部署运行，App 可直接连 `ws://<YOUR_HERMES_HOST>:8650/ws` 测试
2. 首次连接某个新 device_id 会触发 pairing，需要在服务器上 `hermes pairing approve voice <code>`
3. 或设置 `VOICE_ALLOWED_DEVICES=*` 跳过 pairing（测试用）
4. Agent 回复可能需要 5-15 秒（取决于是否调工具），App 的超时设长一些（建议 60s）
5. `platform_hint` 已生效，Hermes 对 voice 平台自动简短回复
6. Android 14+ 要求 Foreground Service 声明 `foregroundServiceType="mediaPlayback"`
7. 蓝牙按键与音乐播放器竞争时需合理管理 MediaSession 优先级
8. 生产环境必须走 WSS（nginx 反代加 TLS），不要裸 WS 暴露到公网
9. **STT 策略**：优先使用在线 STT（讯飞），App 直接调用；离线时降级为本地 SenseVoice

---

## 在线 STT 方案（讯飞实时语音转写）

### 背景

本地离线 STT 模型（SenseVoice、Paraformer）对中文语境中夹杂的英文技术术语（nginx、jenkins、docker 等）识别能力极差。采用讯飞在线 STT API 解决，App 端直接调用，服务端负责鉴权。

### 方案架构

```
App 按蓝牙键 → 录音
  ↓
App 向 Voice Adapter 请求 STT 临时凭据
  ↓
Voice Adapter 用讯飞 AppID/APIKey/APISecret 生成签名 URL → 返回给 App
  ↓
App 直接连讯飞 WebSocket（wss://iat-api.xfyun.cn/v2/iat?authorization=xxx&...）
  ↓
App 边录音边流式发送音频到讯飞 → 讯飞实时返回识别结果
  ↓
App 界面实时显示识别文字
  ↓
识别完成 → App 发送 {"type": "message", "text": "重启 nginx"} 给 Voice Adapter
  ↓
正常走 agent 流程 → delta/end 回复 → TTS 播报
```

### 协议扩展

#### App → 服务端

```json
// 请求 STT 临时凭据（App 启动时或凭据过期时）
{"type": "request_stt_token"}
```

#### 服务端 → App

```json
// 返回讯飞签名 URL（有效期几分钟）
{
  "type": "stt_token",
  "provider": "xfyun",
  "url": "wss://iat-api.xfyun.cn/v2/iat?authorization=xxx&date=xxx&host=iat-api.xfyun.cn",
  "expires_in": 300
}

// 凭据生成失败
{"type": "stt_token", "provider": "xfyun", "url": "", "error": "API key not configured"}
```

### 服务端实现要点

1. 在 Hermes 配置中添加讯飞凭据（✅ 已配置）：
```yaml
XFYUN_APP_ID=<YOUR_XFYUN_APP_ID>
XFYUN_API_KEY=（已配置在服务器 .env）
XFYUN_API_SECRET=（已配置在服务器 .env）
```

2. Voice Adapter 收到 `request_stt_token` 后：
   - 用 API_KEY + API_SECRET 按讯飞签名算法生成鉴权 URL
   - 签名有效期设为 5 分钟
   - 返回完整的 WebSocket URL 给 App

3. 讯飞签名算法（Python 示例）：
```python
import base64, hashlib, hmac, time
from urllib.parse import urlencode
from datetime import datetime
from wsgiref.handlers import format_date_time
from time import mktime

def generate_xfyun_url(api_key, api_secret):
    url = "wss://iat-api.xfyun.cn/v2/iat"
    now = datetime.now()
    date = format_date_time(mktime(now.timetuple()))
    
    signature_origin = f"host: iat-api.xfyun.cn\ndate: {date}\nGET /v2/iat HTTP/1.1"
    signature_sha = hmac.new(
        api_secret.encode(), signature_origin.encode(), hashlib.sha256
    ).digest()
    signature = base64.b64encode(signature_sha).decode()
    
    authorization_origin = (
        f'api_key="{api_key}", algorithm="hmac-sha256", '
        f'headers="host date request-line", signature="{signature}"'
    )
    authorization = base64.b64encode(authorization_origin.encode()).decode()
    
    params = {"authorization": authorization, "date": date, "host": "iat-api.xfyun.cn"}
    return f"{url}?{urlencode(params)}"
```

### App 端实现要点

1. 启动时或凭据过期时发送 `request_stt_token` 获取签名 URL
2. 录音开始时连接讯飞 WebSocket
3. 边录音边发送音频帧（PCM 16kHz 16bit 单声道，每次发 1280 字节 = 40ms）
4. 讯飞返回的识别结果实时显示在界面
5. 录音结束后发送结束帧，等待最终识别结果
6. 最终结果作为 `{"type": "message", "text": "xxx"}` 发给 Voice Adapter

### 讯飞 WebSocket 协议（中文识别大模型版）

接口地址：`wss://iat.xf-yun.com/v1`（通过 stt_token 返回的 url 已包含鉴权参数）

```json
// App → 讯飞（首帧，带 header + parameter + payload）
{
  "header": {
    "app_id": "<YOUR_XFYUN_APP_ID>",
    "status": 0
  },
  "parameter": {
    "iat": {
      "domain": "slm",
      "language": "zh_cn",
      "accent": "mandarin",
      "eos": 6000,
      "dwa": "wpgs",
      "result": {
        "encoding": "utf8",
        "compress": "raw",
        "format": "json"
      }
    }
  },
  "payload": {
    "audio": {
      "encoding": "raw",
      "sample_rate": 16000,
      "channels": 1,
      "bit_depth": 16,
      "seq": 1,
      "status": 0,
      "audio": "<base64 PCM 1280字节>"
    }
  }
}

// App → 讯飞（中间帧，不需要 parameter）
{
  "header": {"app_id": "<YOUR_XFYUN_APP_ID>", "status": 1},
  "payload": {
    "audio": {
      "encoding": "raw",
      "sample_rate": 16000,
      "channels": 1,
      "bit_depth": 16,
      "seq": 2,
      "status": 1,
      "audio": "<base64 PCM>"
    }
  }
}

// App → 讯飞（末帧）
{
  "header": {"app_id": "<YOUR_XFYUN_APP_ID>", "status": 2},
  "payload": {
    "audio": {
      "encoding": "raw",
      "sample_rate": 16000,
      "channels": 1,
      "bit_depth": 16,
      "seq": 999,
      "status": 2,
      "audio": ""
    }
  }
}

// 讯飞 → App（识别结果，text 为 base64 编码的 JSON）
{
  "header": {"code": 0, "message": "success", "sid": "xxx", "status": 1},
  "payload": {
    "result": {
      "text": "<base64 编码的 JSON>",
      "seq": 1,
      "status": 1
    }
  }
}
// header.status=2 时为最终结果

// text base64 解码后的结构：
// {"sn":1, "ls":false, "ws":[{"bg":0, "cw":[{"w":"重启"}]}, {"bg":0, "cw":[{"w":"nginx"}]}]}
// 拼接所有 ws[].cw[].w 得到完整文字

// 动态修正（dwa=wpgs 时）:
// {"pgs":"apd", ...}  → 追加到之前结果
// {"pgs":"rpl", "rg":[2,5], ...}  → 替换第2到第5个结果
```

**关键参数说明：**
- `domain: "slm"` — 使用大模型引擎（区别于标准版的 "iat"）
- `dwa: "wpgs"` — 开启动态修正，识别过程中会修正前面的错误
- `eos: 6000` — 静音 6 秒后自动停止识别
- `app_id` — 从 stt_token 响应中获取
- 每帧发送 1280 字节 PCM，间隔 40ms

### 讯飞中文识别大模型限制

- **单次最长 60 秒**（超时自动断开，App 需在 60s 内完成录音）
- 音频格式：PCM 16kHz 16bit 单声道（encoding=raw）或 MP3（encoding=lame）
- 每帧数据：1280 字节，间隔 40ms
- 签名 URL 有效期 5 分钟（过期需重新请求 `request_stt_token`）
- 支持中英混合识别（docker、nginx、jenkins 等技术术语可正确识别）
- 支持 202 种方言免切换

### App 端系统消息过滤

服务端无法完美区分 agent 正常回复和 gateway 后台系统通知（如 "💾 Self-improvement review: Memory updated"），
因为两者走相同的流式推送路径。App 端收到 delta 时需自行过滤：

```kotlin
// 不播报的内容（系统通知关键词）
val systemPatterns = listOf(
    "Self-improvement review",
    "Memory updated",
    "User profile updated",
    "Skill library updated",
    "File-mutation verifier",
    "No home channel",
)
fun shouldTts(content: String): Boolean {
    return systemPatterns.none { content.contains(it) }
}
```

### 降级策略

```
正常模式（WebSocket 连接正常 + 有 stt_token）:
  录音 → 讯飞在线识别 → 显示文字 → 发 message → 等 agent 回复

降级模式（WebSocket 断开 / stt_token 获取失败 / 讯飞连接超时）:
  录音 → 本地 SenseVoice 离线识别 → 显示文字 → 发 message → 等 agent 回复
```

### 推荐的在线 STT API

| API | 中英混合 | 延迟 | 免费额度 | 推荐 |
|-----|---------|------|---------|------|
| 讯飞实时转写 | ✅ 好 | <1s | 有免费额度 | ⭐ 首选 |
| Azure Speech | ✅ 很好 | 1-2s | 5 小时/月 | 备选 |
| 阿里云 ASR | ✅ 好 | <1s | 有免费额度 | 备选 |

---

## 待实现功能

### 语音唤醒（开关控制）

- 唤醒词："小马在吗"
- 使用 Sherpa-ONNX Keyword Spotting（中文模型 sherpa-onnx-kws-zipformer-wenetspeech-3.3M）
- 模型仅 3.3MB，极轻量
- 开启后麦克风常驻监听，检测到唤醒词后触发语音对话（等效于按蓝牙键）
- 默认关闭，设置页开关控制

唤醒词 keywords.txt 格式（需用 text2token 工具生成）：
```
x iǎo m ǎ z ài m a @小马在吗
```

### 自动连续对话（开关控制）

- TTS 播报完成后是否自动监听下一句
- 开启：播报完 → 提示音 → 自动监听 5 秒 → 超时退出
- 关闭：播报完 → 直接回到待命状态
- 默认开启

### 设置页新增项

| 设置 | 默认值 | 说明 |
|------|--------|------|
| 语音唤醒 | 关闭 | 开启后持续监听唤醒词"小马在吗" |
| 自动连续对话 | 开启 | TTS 播完后自动监听下一句 |
