package com.hermes.voice

import android.Manifest
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.inputmethod.EditorInfo
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import com.hermes.voice.databinding.ActivityMainBinding
import com.hermes.voice.service.VoiceService
import com.hermes.voice.session.SessionState
import com.hermes.voice.ui.MainViewModel
import com.hermes.voice.ui.SettingsActivity
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: MainViewModel
    private var micPulseAnimator: AnimatorSet? = null
    private var micAnimatedState: SessionState? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (!allGranted) {
            Toast.makeText(this, "需要录音和蓝牙权限才能正常工作", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        viewModel = ViewModelProvider(this)[MainViewModel::class.java]

        requestPermissions()
        setupUI()
        observeState()
        startVoiceService()
        checkBatteryOptimization()
    }

    override fun onResume() {
        super.onResume()
        viewModel.checkConfig()
    }

    private fun checkBatteryOptimization() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            AlertDialog.Builder(this)
                .setTitle("关闭电池优化")
                .setMessage("为保证语音助手在后台稳定运行，请允许 Hermes Voice 不受电池优化限制。")
                .setPositiveButton("去设置") { _, _ ->
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                }
                .setNegativeButton("稍后", null)
                .show()
        }
    }

    private fun startVoiceService() {
        val intent = Intent(this, VoiceService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun requestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.BLUETOOTH_CONNECT
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun setupUI() {
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.btnStartSession.setOnClickListener {
            viewModel.toggleVoiceSession()
        }

        binding.btnSend.setOnClickListener {
            sendMessage()
        }

        binding.etInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendMessage()
                true
            } else false
        }
    }

    private fun sendMessage() {
        val text = binding.etInput.text.toString().trim()
        if (text.isBlank()) return
        binding.etInput.text?.clear()
        viewModel.sendMessage(text)
    }

    private fun updateMicVisual(state: SessionState) {
        when (state) {
            SessionState.IDLE -> {
                micAnimatedState = null
                stopMicPulse()
                binding.btnStartSession.setImageResource(R.drawable.mic_orb)
            }
            SessionState.APPROVAL_WAITING -> {
                binding.btnStartSession.setImageResource(R.drawable.mic_orb_approval)
                startMicPulse(state, durationMs = 760L, scale = 1.09f)
            }
            SessionState.LISTENING -> {
                binding.btnStartSession.setImageResource(R.drawable.mic_orb_active)
                startMicPulse(state, durationMs = 680L, scale = 1.10f)
            }
            SessionState.THINKING -> {
                binding.btnStartSession.setImageResource(R.drawable.mic_orb_active)
                startMicPulse(state, durationMs = 1100L, scale = 1.05f)
            }
            SessionState.SPEAKING -> {
                binding.btnStartSession.setImageResource(R.drawable.mic_orb_active)
                startMicPulse(state, durationMs = 900L, scale = 1.07f)
            }
        }
    }

    private fun startMicPulse(state: SessionState, durationMs: Long, scale: Float) {
        if (micPulseAnimator?.isStarted == true && micAnimatedState == state) return
        stopMicPulse()
        micAnimatedState = state
        val scaleX = ObjectAnimator.ofFloat(binding.btnStartSession, "scaleX", 1f, scale).apply {
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            duration = durationMs
            interpolator = AccelerateDecelerateInterpolator()
        }
        val scaleY = ObjectAnimator.ofFloat(binding.btnStartSession, "scaleY", 1f, scale).apply {
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            duration = durationMs
            interpolator = AccelerateDecelerateInterpolator()
        }
        val alpha = ObjectAnimator.ofFloat(binding.btnStartSession, "alpha", 0.9f, 1f).apply {
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            duration = durationMs
            interpolator = AccelerateDecelerateInterpolator()
        }
        micPulseAnimator = AnimatorSet().apply {
            playTogether(scaleX, scaleY, alpha)
            start()
        }
    }

    private fun stopMicPulse() {
        micPulseAnimator?.cancel()
        micPulseAnimator = null
        binding.btnStartSession.scaleX = 1f
        binding.btnStartSession.scaleY = 1f
        binding.btnStartSession.alpha = 1f
    }

    override fun onDestroy() {
        stopMicPulse()
        super.onDestroy()
    }

    private fun observeState() {
        viewModel.sessionState.observe(this) { state ->
            when (state) {
                SessionState.IDLE -> {
                    binding.tvStatus.text = "点击或说「你好小马」开始对话"
                    binding.tvStatus.setTextColor(getColor(R.color.text_secondary))
                }
                SessionState.LISTENING -> {
                    binding.tvStatus.text = "听取中..."
                    binding.tvStatus.setTextColor(getColor(R.color.primary))
                }
                SessionState.THINKING -> {
                    binding.tvStatus.text = "思考中..."
                    binding.tvStatus.setTextColor(getColor(R.color.primary))
                }
                SessionState.SPEAKING -> {
                    binding.tvStatus.text = "播报中..."
                    binding.tvStatus.setTextColor(getColor(R.color.accent))
                }
                SessionState.APPROVAL_WAITING -> {
                    binding.tvStatus.text = "审批中... 请说允许或拒绝"
                    binding.tvStatus.setTextColor(getColor(R.color.warning))
                }
            }
            updateMicVisual(state)
            binding.btnSend.isEnabled = state == SessionState.IDLE
        }

        viewModel.chatLog.observe(this) { msg ->
            binding.tvLastMessage.text = msg
            binding.scrollView.post {
                binding.scrollView.fullScroll(android.view.View.FOCUS_DOWN)
            }
        }

        viewModel.configValid.observe(this) { valid ->
            binding.btnSend.isEnabled = valid
            binding.etInput.isEnabled = valid
            binding.btnStartSession.isEnabled = valid
            if (!valid) {
                binding.tvStatus.text = "请先配置连接信息"
            }
        }

        viewModel.connectionStatus.observe(this) { status ->
            binding.tvConnection.text = status
            val isConnected = status == "已连接"
            binding.tvConnection.setTextColor(
                getColor(if (isConnected) R.color.status_connected else R.color.status_disconnected)
            )
            binding.statusDot.setBackgroundResource(
                if (isConnected) R.drawable.status_dot_green else R.drawable.status_dot_red
            )
        }
    }
}
