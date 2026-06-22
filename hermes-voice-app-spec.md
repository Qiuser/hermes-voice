# Hermes Voice App — 技术规划文档

## 产品定位

Android 原生语音助手 app，通过蓝牙耳机按键触发，与 Hermes Agent 进行语音对话。
核心场景：开车/外出时免手动操作，语音吩咐 Hermes 执行任务。

---

## 架构概览

```
┌─────────────────────────────────────────────────┐
│                Android App                       │
│                                                  │
│  ┌──────────┐   ┌──────────┐   ┌──────────┐    │
│  │Bluetooth │   │  Speech  │   │   TTS    │    │
│  │ Service  │   │Recognizer│   │  Engine  │    │
│  └────┬─────┘   └────┬─────┘   └────┬─────┘    │
│       │               │               │         │
│       ▼               ▼               ▲         │
│  ┌──────────────────────────────────────┐       │
│  │         VoiceSessionManager          │       │
│  │  状态机: 待命→听取→思考→播报→听取... │       │
│  └──────────────────┬───────────────────┘       │
│                     │                            │
│                     ▼                            │
│  ┌──────────────────────────────────────┐       │
│  │          HermesApiClient             │       │
│  │  OkHttp SSE streaming + Auth        │       │
│  └──────────────────┬───────────────────┘       │
└─────────────────────┼───────────────────────────┘
                      │ HTTPS + Bearer Token
                      ▼
              ┌───────────────┐
              │ Hermes API    │
              │ (外网地址)     │
              └───────────────┘
```

---

## 技术栈

| 组件 | 选型 | 理由 |
|------|------|------|
| 语言 | Kotlin | 原生性能，硬件控制最强 |
| 最低 API | Android 10 (API 29) | 覆盖 95%+ 设备 |
| STT | Android SpeechRecognizer | 免费、离线可用、延迟低 |
| TTS | Android TextToSpeech | 免费、离线、即时 |
| 网络 | OkHttp 4.x + SSE | 流式响应，成熟稳定 |
| 后台 | Foreground Service (TYPE_MEDIA_PLAYBACK) | 保活 + 蓝牙监听 |
| 蓝牙 | MediaSession + MediaButtonReceiver | 标准媒体按键拦截 |
| DI | Hilt | 官方推荐，简洁 |
| 架构 | MVVM + Clean Architecture | 分层清晰 |
| 本地存储 | Room (SQLite) | 对话历史 |
| 唤醒词(可选) | Picovoice Porcupine | 离线、低功耗 |

---

## 项目结构

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
├── api/
│   ├── HermesApiClient.kt         # Hermes API 通信（SSE streaming）
│   ├── HermesApiModels.kt         # 请求/响应数据类
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
│   ├── SettingsActivity.kt       # 设置页
│   └── SetupGuideActivity.kt    # 首次使用引导（电池优化等）
│
├── di/
│   └── AppModule.kt              # Hilt 依赖注入模块
│
├── data/
│   ├── ConversationDao.kt        # Room DAO
│   ├── ConversationEntity.kt     # 对话记录实体
│   └── AppDatabase.kt            # Room 数据库
│
└── util/
    ├── Constants.kt              # 常量
    └── Logger.kt                 # 日志工具
```

---

## 核心模块设计

### 1. VoiceService（Foreground Service）

```kotlin
// 生命周期
onCreate → 启动前台通知 + 初始化 MediaSession + 注册蓝牙监听
onStartCommand → 返回 START_STICKY
onDestroy → 释放资源

// 职责
- 保持 app 进程存活
- 监听蓝牙媒体按键
- 管理 VoiceSessionManager 生命周期
- 发送状态到 UI（LiveData / Flow）
```

### 2. VoiceSessionManager（状态机）

```
状态流转:
IDLE → [蓝牙按键触发] → LISTENING
LISTENING → [识别完成] → THINKING
LISTENING → [超时5秒] → IDLE
THINKING → [收到首个token] → SPEAKING
THINKING → [超时/错误] → IDLE
SPEAKING → [播报完成] → LISTENING (连续对话)
SPEAKING → [用户打断] → LISTENING
LISTENING → [静默5秒] → IDLE
```

### 3. HermesApiClient（流式通信）

```kotlin
// 请求格式（OpenAI 兼容）
POST /v1/chat/completions
Headers: Authorization: Bearer <API_SERVER_KEY>
Body: {
  "model": "hermes-agent",
  "stream": true,
  "messages": [
    {"role": "system", "content": "[语音模式] 回复规则：..."},
    {"role": "user", "content": "用户说的话"}
  ]
}

// 流式响应处理
SSE event → 解析 delta.content → 送入 TTS 缓冲区
[DONE] → 标记回复结束
```

### 4. TTS 流式播报

```kotlin
// 策略：收到一句话就播，不等全部返回
// 按标点符号（。！？，；）分句
// 每分出一句 → TextToSpeech.speak(queue=QUEUE_ADD)
// 最后一句播完 → 回调通知 SessionManager
```

### 5. 语音模式 System Prompt

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

## 安全方案

### 传输安全
- 所有通信走 HTTPS（TLS 1.3）
- 证书校验不跳过（不允许自签名，除非用户明确配置）

### 认证
- API_SERVER_KEY 作为 Bearer Token
- 存储在 Android Keystore（硬件加密）
- 首次配置时通过 app 内输入或扫码导入

### 本地安全
- 设置页需指纹/面部识别才能进入
- Token 不明文写 SharedPreferences
- 对话历史本地加密存储（可选）

### 防滥用
- 连续错误 5 次 → 锁定 30 秒
- 网络请求带设备指纹

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
| Hermes 超时(>10s) | TTS "服务响应超时" → 退出对话 |
| Hermes 不可达 | TTS "服务离线" → 退出对话 |
| 流式中断 | 播报已收到的内容 + TTS "回复不完整" |
| Token 失效 | TTS "认证失败，请在app中重新配置" |

---

## 审批交互（语音场景）

```
Hermes 返回审批请求 →
  TTS: "需要执行 curl 管道命令，是否允许？说'允许'、'拒绝'或'允许一次'"
  → 进入 STT
  → 识别关键词: 允许/拒绝/允许一次/本次会话/永久
  → 匹配后调审批 API
  → TTS: "已允许" / "已拒绝"
```

---

## 后台任务通知

```
Hermes 推送完成通知 → 
  通知栏: "✅ 部署 envo-admin 完成"
  如果设置了语音通知 → TTS 播报
  点击通知 → 进入 app 查看详情
```

---

## 设置项

| 设置 | 默认值 | 说明 |
|------|--------|------|
| Hermes API 地址 | (用户配置) | HTTPS URL |
| API Token | (用户配置) | Bearer Token |
| TTS 语速 | 1.0 | 0.5 - 2.0 |
| TTS 音色 | 系统默认 | 可选系统已安装的引擎 |
| 连续对话超时 | 5秒 | 3-10秒 |
| 任务通知方式 | 通知+语音 | 仅通知/通知+语音/静默 |
| 唤醒词 | 关闭 | 开启后持续监听 |
| 对话历史保留 | 7天 | 1/7/30天/永久 |

---

## 依赖列表（build.gradle.kts 关键依赖）

```kotlin
// Android 核心
implementation("androidx.core:core-ktx:1.13.1")
implementation("androidx.lifecycle:lifecycle-service:2.8.3")
implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.3")

// Hilt DI
implementation("com.google.dagger:hilt-android:2.51.1")
kapt("com.google.dagger:hilt-compiler:2.51.1")

// 网络 (OkHttp SSE)
implementation("com.squareup.okhttp3:okhttp:4.12.0")
implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")

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

## 开发顺序建议

### 阶段一拆分为4个 PR：

1. **PR1: 项目骨架**
   - 项目初始化、Hilt 配置、基础 UI
   - ApiConfig 设置页（输入 URL + Token）
   
2. **PR2: API 通信层**
   - HermesApiClient（SSE streaming）
   - 语音模式 system prompt
   - 文字对话验证（先用输入框测）

3. **PR3: 语音层**
   - SpeechRecognizerManager
   - TtsManager（流式分句播报）
   - VoiceSessionManager 状态机
   - 集成测试：说话→回复→播报

4. **PR4: 蓝牙 + Service**
   - VoiceService (Foreground)
   - BluetoothMediaReceiver
   - 锁屏/后台可用验证

---

## 注意事项

1. Android 14+ 要求 Foreground Service 声明 `foregroundServiceType="mediaPlayback"`
2. `SpeechRecognizer` 有些设备不支持离线，需要 fallback 方案
3. TTS 首次使用可能需要下载语音包，要处理初始化等待
4. 蓝牙按键与系统音乐播放器冲突时，需要合理管理 MediaSession 优先级
5. OkHttp SSE 需要在子线程处理，用 Coroutines + Flow 分发到 UI
6. 语音识别结果可能不准，关键操作（审批）需要二次确认
