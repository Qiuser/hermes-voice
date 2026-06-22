# Hermes Voice — 技术规划文档

## 产品定位

Android 原生语音助手，通过蓝牙耳机按键触发，与 Hermes Agent 进行语音对话。
核心场景：开车/外出时免手动操作，语音吩咐 Hermes 执行任务。

**双端架构：**
- **服务端**：Voice Platform Adapter（Hermes 自定义平台插件）
- **客户端**：Android APK

---

## 架构概览

```
┌─────────────────────────────────────────────────────────┐
│                    Hermes Gateway                         │
│                                                          │
│  ┌────────────┐  ┌────────────┐  ┌──────────────────┐  │
│  │  飞书       │  │  微信       │  │  Voice Adapter   │  │
│  │  Adapter   │  │  Adapter   │  │  (自定义平台)     │  │
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
                         │ WebSocket (WSS)
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
│  │   连接 Voice Adapter 的 WS 端口      │               │
│  └──────────────────────────────────────┘               │
└─────────────────────────────────────────────────────────┘
```

**Voice Adapter 与飞书/微信 Adapter 平级，是 gateway 的一等公民。**
App 不 hack dashboard 的内部协议，而是通过自己的 adapter 走标准消息流程。

---

## 两端职责划分

### 服务端：Voice Platform Adapter

| 职责 | 说明 |
|------|------|
| WebSocket 服务 | 监听端口，接受 App 连接 |
| 鉴权 | Token 验证，拒绝非法连接 |
| 消息转换 | App 文字 → MessageEvent → gateway 消息流程 |
| 响应推送 | Agent 回复 → WebSocket 推给 App |
| 语音模式注入 | 识别 voice 平台，自动注入简短回复 system prompt |
| 审批转发 | 审批请求 → 推给 App，App 回复 → 转发给 agent |
| 任务通知 | 后台 delegation 完成 → 推给 App |
| Session 管理 | 每个 App 设备一个 session_key |

### 客户端：Android APK

| 职责 | 说明 |
|------|------|
| 蓝牙按键监听 | 触发对话 |
| STT 语音识别 | 语音转文字 |
| WebSocket 通信 | 发文字、收回复、收事件 |
| TTS 播报 | 流式文字转语音播放 |
| 审批交互 | 语音确认允许/拒绝 |
| 通知展示 | 后台任务完成通知 |
| 对话记录 | 本地存储历史 |
| 后台常驻 | Foreground Service |

---

## 服务端：Voice Adapter 设计

### 文件位置

```
~/.hermes/hermes-agent/gateway/platforms/voice.py
```

或作为插件：

```
~/.hermes/hermes-agent/hermes_plugins/voice_platform/
├── __init__.py
├── adapter.py          # VoiceAdapter(BasePlatformAdapter)
├── protocol.py         # WebSocket 消息协议定义
└── plugin.yaml         # 插件声明
```

### 核心实现

```python
class VoiceAdapter(BasePlatformAdapter):
    """语音平台适配器 — 通过 WebSocket 与 Android App 通信。"""
    
    platform = Platform("voice")
    supports_code_blocks = False  # 语音场景不用代码块
    
    async def connect(self) -> bool:
        """启动 WebSocket 服务器，监听 App 连接。"""
        # 复用 gateway 的 asyncio 事件循环
        # 在配置的端口启动 WS 服务（如 8650）
        # 等待 App 连接
        pass
    
    async def disconnect(self) -> None:
        """关闭 WS 服务器，断开所有客户端。"""
        pass
    
    async def send(self, chat_id, content, reply_to=None, metadata=None) -> SendResult:
        """将 agent 回复推送给 App。"""
        # chat_id = device_id（设备标识）
        # content = agent 的文字回复
        # 通过 WebSocket 发送: {"type":"reply","content":"好的，开始部署了"}
        pass
    
    async def handle_client_message(self, device_id: str, text: str):
        """收到 App 发来的文字，构造 MessageEvent 进入 gateway 流程。"""
        event = MessageEvent(
            text=text,
            message_type=MessageType.TEXT,
            source=SessionSource(
                platform=Platform("voice"),
                chat_id=device_id,
                chat_type="dm",
                user_id=device_id,
            ),
        )
        await self.handle_message(event)
```

### WebSocket 协议（服务端 ↔ App）

```json
// === App → 服务端 ===

// 鉴权（连接后第一条消息）
{"type": "auth", "token": "xxx", "device_id": "device_abc123"}

// 发送消息
{"type": "message", "text": "发下信封管理后台"}

// 审批回复
{"type": "approval_response", "choice": "once"}

// 命令
{"type": "command", "cmd": "stop"}
{"type": "command", "cmd": "new"}

// === 服务端 → App ===

// 鉴权结果
{"type": "auth_ok"}
{"type": "auth_fail", "reason": "invalid token"}

// Agent 回复（流式）
{"type": "delta", "content": "好的，"}
{"type": "delta", "content": "开始部署了"}
{"type": "end", "finish_reason": "stop"}

// 工具执行状态
{"type": "tool_start", "name": "terminal", "description": "执行部署脚本"}
{"type": "tool_end", "name": "terminal", "duration": 2.3}

// 审批请求
{"type": "approval_request", "command": "curl | bash", "description": "高危管道命令"}

// 后台任务通知
{"type": "task_complete", "task": "部署 envo-admin", "success": true}

// Agent 忙碌
{"type": "busy", "message": "正在执行任务，消息已排队"}

// 心跳
{"type": "ping"}  →  {"type": "pong"}
```

### 配置

```yaml
# ~/.hermes/config.yaml 或 .env
VOICE_ENABLED=true
VOICE_WS_PORT=8650
VOICE_TOKEN=<自定义强密码>
VOICE_ALLOWED_DEVICES=*  # 或指定设备ID列表
```

### 语音模式 System Prompt 注入

Voice Adapter 在构造 MessageEvent 时添加 metadata：

```python
event.metadata = {"voice_mode": True}
```

Gateway 的 agent 初始化检测到 `platform == "voice"` 时，自动在 system prompt 末尾追加：

```
[语音模式] 当前用户通过语音与你对话，请遵循以下规则：
1. 每次回复不超过3句话，总字数控制在80字以内
2. 不使用 markdown 格式、代码块、列表符号
3. 用口语化表达，像面对面说话一样
4. 关键信息先说，细节可以说"详细信息我发飞书给你"
5. 确认类回复尽量一句话，如"好的，开始部署了"
6. 如果内容确实很多，主动分段，先说摘要，问用户要不要听详细的
```

---

## 客户端：Android App 设计

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
| 唤醒词(可选) | Picovoice Porcupine | 离线、低功耗 |

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
│   ├── VoiceWebSocketClient.kt   # WebSocket 客户端（连 Voice Adapter）
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

### VoiceSessionManager（状态机）

```
状态流转:
IDLE → [蓝牙按键触发] → LISTENING
LISTENING → [识别完成] → THINKING
LISTENING → [超时5秒] → IDLE
THINKING → [收到首个delta] → SPEAKING
THINKING → [超时/错误] → IDLE
SPEAKING → [播报完成] → LISTENING (连续对话)
SPEAKING → [用户打断] → LISTENING
LISTENING → [静默5秒] → IDLE

特殊状态:
SPEAKING → [收到approval_request] → APPROVAL_LISTENING
APPROVAL_LISTENING → [识别到允许/拒绝] → 发送回复 → SPEAKING(继续播报)
```

### TTS 流式播报

```kotlin
// 策略：收到一句话就播，不等全部返回
// 按标点符号（。！？，；）分句
// 每分出一句 → TextToSpeech.speak(queue=QUEUE_ADD)
// 最后一句播完 → 回调通知 SessionManager
```

---

## 安全方案

### 传输安全
- Voice Adapter WS 端口走 WSS（通过 nginx 反代加 TLS）
- 外网地址：`wss://hmvoice.q.wdnmd.wang:端口`

### 认证
- App 连接时发送 `{"type":"auth","token":"xxx","device_id":"xxx"}`
- Token 由用户在 Hermes config 中设置（VOICE_TOKEN）
- device_id 用于标识设备，可限制允许的设备列表
- Token 存储在 Android Keystore（硬件加密）

### 本地安全
- 设置页需指纹/面部识别才能进入
- Token 不明文写 SharedPreferences
- 对话历史本地加密存储（可选）

### 防滥用
- 连续认证失败 5 次 → 锁定 30 秒
- 单设备同时只能一个 WebSocket 连接

---

## 蓝牙按键处理

```kotlin
// MediaSession 方式（推荐，兼容性好）
MediaSession + MediaSessionCompat.Callback

// 按键映射
KEYCODE_HEADSETHOOK (单击) → 触发/结束对话
KEYCODE_MEDIA_PLAY_PAUSE → 同上
长按 → 可选：唤醒词替代

// 注意事项
- 需要声明 MediaSession active 才能收到按键
- 与音乐播放器竞争按键需要合理设置优先级
- 锁屏时 Service 内的 MediaSession 才能收到
```

---

## 网络异常处理

| 场景 | 处理 |
|------|------|
| 无网络 | TTS "网络不可用" → 退出对话 |
| WebSocket 断开 | 自动重连（指数退避：1s→2s→4s→8s→最大30s） |
| 重连成功 | TTS "已重新连接" |
| 认证失败 | TTS "认证失败，请在app中重新配置" |
| Agent 超时(>15s) | TTS "服务响应超时" → 退出对话 |

---

## 审批交互（语音场景）

```
服务端推送 {"type":"approval_request",...} →
  App TTS: "需要执行 curl 管道命令，是否允许？说'允许'、'拒绝'或'允许一次'"
  → 进入 STT
  → 识别关键词: 允许/拒绝/允许一次/本次会话/永久
  → 发送 {"type":"approval_response","choice":"once"}
  → 等待服务端确认
  → TTS: "已允许"
```

---

## 后台任务通知

```
服务端推送 {"type":"task_complete","task":"部署 envo-admin","success":true} →
  通知栏: "✅ 部署 envo-admin 完成"
  如果设置了语音通知 + 蓝牙已连接 → TTS 播报
  点击通知 → 进入 app 查看详情
```

---

## 设置项

| 设置 | 默认值 | 说明 |
|------|--------|------|
| Hermes 地址 | (用户配置) | WSS URL，如 wss://hmvoice.q.wdnmd.wang:端口 |
| Voice Token | (用户配置) | 连接认证 token |
| TTS 语速 | 1.0 | 0.5 - 2.0 |
| TTS 音色 | 系统默认 | 可选系统已安装的引擎 |
| 连续对话超时 | 5秒 | 3-10秒 |
| 任务通知方式 | 通知+语音 | 仅通知/通知+语音/静默 |
| 唤醒词 | 关闭 | 开启后持续监听 |
| 对话历史保留 | 7天 | 1/7/30天/永久 |

---

## 依赖列表

### 服务端（Python，Voice Adapter）

```
无额外依赖 — 使用 Hermes 已有的 asyncio + websockets/aiohttp
```

### 客户端（Android，build.gradle.kts）

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

// 唤醒词（阶段五）
// implementation("ai.picovoice:porcupine-android:3.0.2")
```

---

## 开发顺序

### 阶段一：服务端 Voice Adapter

1. **实现 VoiceAdapter** — 继承 BasePlatformAdapter
2. **WebSocket 服务** — 监听端口、鉴权、消息收发
3. **接入 gateway** — 注册为 Platform("voice")，处理 MessageEvent
4. **语音模式 prompt** — platform==voice 时注入简短回复规则
5. **测试** — 用 wscat/浏览器 WebSocket 客户端验证消息流通

### 阶段二：App 基础对话

1. **PR1: 项目骨架** — Hilt、基础 UI、设置页（URL + Token）
2. **PR2: WebSocket 通信层** — 连接 Voice Adapter、收发消息、自动重连
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

## 注意事项

1. **服务端优先开发** — App 依赖 Voice Adapter，先把服务端跑通
2. Voice Adapter 的 WS 端口需要通过 nginx 反代暴露到外网（加 TLS）
3. session_key 格式：`agent:main:voice:dm:<device_id>`，与飞书隔离
4. Hindsight 记忆里 platform=voice，可按平台区分来源
5. Android 14+ 要求 Foreground Service 声明 `foregroundServiceType="mediaPlayback"`
6. `SpeechRecognizer` 有些设备不支持离线，需要 fallback 方案
7. TTS 首次使用可能需要下载语音包，要处理初始化等待
8. 蓝牙按键与系统音乐播放器冲突时，需要合理管理 MediaSession 优先级
9. 语音识别结果可能不准，关键操作（审批）需要二次确认
10. Voice Adapter 可参考 `api_server` adapter 的实现方式（同样是 HTTP/WS 服务型适配器）
