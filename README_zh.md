# Hermes Voice

[English](README.md) | 简体中文

面向 [Hermes Agent](https://github.com/NousResearch/hermes-agent) 的语音平台适配器 + Android 客户端 —— 让你戴着蓝牙耳机免手动交互。

## 这是什么？

Hermes Voice 让你可以直接用语音跟你的 Hermes Agent 对话——按一下蓝牙耳机按键、说话、听回复。适合开车、走路，或任何不方便打字的场景。

跟那些只是简单转发到 LLM API 的语音壁纸不同，Hermes Voice 以**平台适配器**的身份接入（跟 Telegram、Discord 适配器同级），因此能拿到完整的 Hermes 体验：

- ✅ 全部工具（terminal、file、web_search、delegate_task……）
- ✅ 持久记忆（Hindsight）
- ✅ Skills
- ✅ 会话管理 + 上下文压缩
- ✅ 指令确认（语音确认/拒绝）
- ✅ 后台任务完成通知
- ✅ 不可朗读内容（代码/链接）自动转发到飞书

## 架构

```
┌─────────────────────────────────────────────────────────┐
│                    Hermes Gateway                         │
│                                                          │
│  ┌────────────┐  ┌────────────┐  ┌──────────────────┐  │
│  │  飞书       │  │  Telegram  │  │  语音适配器       │  │
│  │  Adapter   │  │  Adapter   │  │  (plugin, 8650)  │  │
│  └─────┬──────┘  └─────┬──────┘  └────────┬─────────┘  │
│        └───────────────┼───────────────────┘             │
│                        ▼                                 │
│         Agent（工具 / skills / 记忆 / 审批）              │
└─────────────────────────────────────────────────────────┘
                         ▲ WebSocket
                         │
┌────────────────────────┼────────────────────────────────┐
│              Android App（Kotlin）                         │
│  蓝牙触发 → STT（讯飞）→ Agent → TTS 播报                 │
└─────────────────────────────────────────────────────────┘
```

## 组成部分

### Server：语音平台适配器（`adapter/`）

一个 Hermes 平台插件——不需要修改 Hermes 任何源码。把它放到 `~/.hermes/hermes-agent/plugins/platforms/voice/` 后重启 gateway 即可。

功能：
- 供 App 连接的 WebSocket 服务
- Token 鉴权 + 设备配对
- 实时流式回复（send_draft）
- 讯飞 STT 凭证代理（App 端不会接触到 API key）
- 代码/链接内容自动转发为飞书卡片消息
- 简洁回复模式的平台提示
- 服务端不做 TTS（由 App 本地处理）

### Client：Android App（`app/`）

原生 Kotlin Android 应用，负责语音交互。

功能：
- 蓝牙耳机按键触发
- 讯飞中文大模型 ASR（对 docker/nginx/jenkins 这类技术词汇识别效果很好）
- Android 原生 TTS，支持流式播放
- 前台服务，支持后台运行
- 唤醒词检测（可选，基于 Sherpa-ONNX）
- 对话历史记录（Room）
- 断线自动重连（指数退避）

## 快速开始

### Server 端

1. 把适配器复制到 Hermes 插件目录：
```bash
cp -r adapter/ ~/.hermes/hermes-agent/plugins/platforms/voice/
```

2. 在 `~/.hermes/.env` 中添加：
```bash
VOICE_TOKEN=$(openssl rand -hex 32)
# 可选：讯飞 STT 凭证（用于服务端鉴权代理）
XFYUN_APP_ID=your_app_id
XFYUN_API_KEY=your_api_key
XFYUN_API_SECRET=your_api_secret
```

3. 重启 gateway：
```bash
systemctl --user restart hermes-gateway.service
```

4. 验证：
```bash
curl http://localhost:8650/health
# {"status": "healthy", "platform": "voice", "connected_devices": 0}
```

### Client 端

1. 编译 APK：
```bash
cd app
./gradlew assembleDebug
```

2. 安装到手机上并配置：
   - Hermes 地址：`ws://your-hermes-host:8650/ws`
   - Voice Token：（上面生成的 token）

3. 首次连接会触发设备配对：
```bash
hermes pairing approve voice <CODE>
```

## WebSocket 协议

完整协议规范见 [hermes-voice-spec.md](hermes-voice-spec.md)，包括：
- 鉴权流程
- 消息格式（delta/end 流式）
- STT token 请求
- 审批交互
- 任务完成通知

## 配置

### Server（`.env`）

| 变量 | 是否必需 | 说明 |
|----------|----------|------|
| `VOICE_TOKEN` | 是 | App 连接鉴权 token |
| `VOICE_WS_PORT` | 否 | WebSocket 端口（默认 8650） |
| `VOICE_ALLOWED_DEVICES` | 否 | 设备白名单，`*` 表示允许全部 |
| `XFYUN_APP_ID` | 否 | 讯飞 App ID（用于 STT 代理） |
| `XFYUN_API_KEY` | 否 | 讯飞 API Key |
| `XFYUN_API_SECRET` | 否 | 讯飞 API Secret |

### Client（App 设置）

| 设置项 | 说明 |
|---------|------|
| Hermes 地址 | 语音适配器的 WS/WSS 地址 |
| Voice Token | 鉴权 token |
| TTS 语速 | 0.5 - 2.0 |
| 连续对话超时 | 3-10 秒 |

## 跟其他方案的区别

| 方案 | 工具/记忆/Skills | 实时流式 | 语音优先体验 |
|------|:------------------:|:------------------:|:--------------:|
| **Hermes Voice（本项目）** | ✅ 完整 | ✅ send_draft | ✅ 专为语音设计 |
| `/v1/chat/completions` API | ❌ 无 | ❌ 仅 SSE | ❌ 文本优先 |
| Dashboard `/api/ws` TUI | ✅ 完整 | ✅ | ❌ 终端界面 |
| Telegram 语音消息 | ✅ 完整 | ❌ 批量 | ❌ 需手动点按录音 |

## 参与贡献

欢迎贡献！以下方向尤其需要帮助：

- [ ] iOS 客户端
- [ ] 唤醒词模型训练（自定义热词）
- [ ] 多语言 STT 支持
- [ ] 语音活动检测（VAD）优化
- [ ] 其他 TTS 引擎（Edge TTS、Kokoro）
- [ ] 车载模式 UI（大按钮、极简信息）

## License

MIT
