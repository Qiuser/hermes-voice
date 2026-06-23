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
| 健康检查 | `http://192.168.50.18:8650/health` |
| 内网连接地址 | `ws://192.168.50.18:8650/ws` |
| 外网连接地址 | 需通过 nginx 反代暴露 WSS（待配置） |
| VOICE_TOKEN | `70665a739889a0a1ea761728eb4162919a15754e1ee33380fb6a36be5c85889b` |
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
内网测试: ws://192.168.50.18:8650/ws
外网生产: wss://你的域名:端口/ws  (通过 nginx 反代)
```

### 完整消息协议

#### App → 服务端

```json
// 1. 鉴权（连接后必须第一条发送，10秒超时）
{"type": "auth", "token": "70665a739889a0a1ea761728eb4162919a15754e1ee33380fb6a36be5c85889b", "device_id": "my_phone_001"}

// 2. 发送消息（文字输入）
{"type": "message", "text": "发下信封管理后台"}

// 3. 发送语音（服务端 STT 识别）
{"type": "audio", "format": "pcm", "sample_rate": 16000, "data": "<base64 编码的 PCM 音频>"}
// 服务端收到后调用在线 STT API 识别，识别完成后：
//   - 返回 {"type": "stt_result", "text": "重启 nginx 服务"} 给 App 确认
//   - 然后自动将识别文字作为用户消息进入 agent 流程
//   - 如果识别失败返回 {"type": "stt_result", "text": "", "error": "识别失败"}

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

// 9. 语音识别结果（服务端 STT 返回）
{"type": "stt_result", "text": "重启 nginx 服务"}
// App 收到后在 chatLog 显示"你: xxx"，然后等待 agent 回复（delta/end）
// 如果识别失败：
{"type": "stt_result", "text": "", "error": "识别失败"}
// App 收到后 TTS 播报错误提示

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
| STT | Android SpeechRecognizer | 免费、离线可用、延迟低 |
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
            .url(config.wsUrl)  // ws://192.168.50.18:8650/ws
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

1. 服务端已部署运行，App 可直接连 `ws://192.168.50.18:8650/ws` 测试
2. 首次连接某个新 device_id 会触发 pairing，需要在服务器上 `hermes pairing approve voice <code>`
3. 或设置 `VOICE_ALLOWED_DEVICES=*` 跳过 pairing（测试用）
4. Agent 回复可能需要 5-15 秒（取决于是否调工具），App 的超时设长一些（建议 60s）
5. `platform_hint` 已生效，Hermes 对 voice 平台自动简短回复
6. Android 14+ 要求 Foreground Service 声明 `foregroundServiceType="mediaPlayback"`
7. 蓝牙按键与音乐播放器竞争时需合理管理 MediaSession 优先级
8. 生产环境必须走 WSS（nginx 反代加 TLS），不要裸 WS 暴露到公网
9. **服务端 STT**：App 录音完成后将 PCM 音频 base64 编码通过 `{"type":"audio",...}` 发给服务端，服务端负责调用在线 STT API（如 Azure Speech / 讯飞）识别后返回 `stt_result`。本地 SenseVoice 作为离线降级方案保留。

---

## 服务端 STT 方案说明（待实现）

### 背景

本地离线 STT 模型（SenseVoice、Paraformer）对中文语境中夹杂的英文技术术语（nginx、jenkins、docker 等）识别能力极差。需要服务端接入在线 STT API 来解决。

### 协议流程

```
App 录音完成
  ↓
App 发送: {"type": "audio", "format": "pcm", "sample_rate": 16000, "data": "<base64>"}
  ↓
服务端收到 audio 消息
  ↓
服务端调用在线 STT API（Azure/讯飞/Google）
  ↓
识别成功 → 服务端返回: {"type": "stt_result", "text": "重启 nginx 服务"}
         → 服务端自动将 text 作为用户消息进入 agent 流程
         → 后续正常走 delta/end 回复
  ↓
识别失败 → 服务端返回: {"type": "stt_result", "text": "", "error": "识别失败"}
```

### 音频格式

| 参数 | 值 |
|------|------|
| 格式 | PCM（原始采样，无压缩头） |
| 采样率 | 16000 Hz |
| 位深 | 16-bit signed integer |
| 声道 | 单声道（mono） |
| 编码 | base64 |
| 典型大小 | 5 秒音频 ≈ 160KB PCM ≈ 213KB base64 |

### 服务端实现要点

1. Voice Adapter 收到 `type: "audio"` 后，base64 解码得到 PCM 字节流
2. 调用在线 STT API：
   - 推荐 Azure Speech（中英混合 code-switching 效果最好）
   - 或讯飞实时语音转写（中文最强）
   - 设置语言为 `zh-CN`，开启 code-switching / 中英混合模式
3. 识别结果返回给 App（`stt_result`），同时自动构造 MessageEvent 进入 agent 流程
4. 超时处理：如果 STT API 10 秒没返回，返回 error

### App 端降级策略

```
正常模式（有网络）:
  录音 → 发 audio 到服务端 → 等 stt_result → 显示 + 等 agent 回复

降级模式（WebSocket 断开/超时）:
  录音 → 本地 SenseVoice 识别 → 发 message 文字 → 等 agent 回复
```

### 推荐的在线 STT API

| API | 中英混合 | 延迟 | 免费额度 |
|-----|---------|------|---------|
| Azure Speech | ✅ 很好 | 1-2s | 5 小时/月 |
| 讯飞实时转写 | ✅ 好 | <1s | 有免费额度 |
| Google Speech | ✅ 好 | 1-2s | 60 分钟/月 |
| 阿里云 ASR | ✅ 好 | <1s | 有免费额度 |
