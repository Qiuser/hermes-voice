package com.hermes.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.inputmethod.EditorInfo
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

    private fun observeState() {
        viewModel.sessionState.observe(this) { state ->
            binding.tvStatus.text = state.displayName
            binding.btnStartSession.text = if (state.isActive) "结束对话" else "语音对话"
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
        }
    }
}
