package com.hermes.voice.session

enum class SessionState(val displayName: String, val isActive: Boolean) {
    IDLE("待命", false),
    LISTENING("听取中...", true),
    THINKING("思考中...", true),
    SPEAKING("播报中...", true),
    APPROVAL_WAITING("审批中...", true),
    PAIRING_WAITING("等待授权...", true);
}
