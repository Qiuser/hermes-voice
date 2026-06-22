package com.hermes.voice.util

object Constants {
    const val VOICE_SYSTEM_PROMPT = """[语音模式] 当前用户通过语音与你对话，请遵循以下规则：
1. 每次回复不超过3句话，总字数控制在80字以内
2. 不使用 markdown 格式、代码块、列表符号
3. 用口语化表达，像面对面说话一样
4. 关键信息先说，细节可以说"详细信息我发飞书给你"
5. 确认类回复尽量一句话，如"好的，开始部署了"
6. 如果内容确实很多，主动分段，先说摘要，问用户要不要听详细的"""

    const val LISTENING_TIMEOUT_MS = 5000L
    const val API_TIMEOUT_MS = 10000L
    const val MAX_CONSECUTIVE_ERRORS = 5
    const val ERROR_LOCKOUT_MS = 30000L
}
