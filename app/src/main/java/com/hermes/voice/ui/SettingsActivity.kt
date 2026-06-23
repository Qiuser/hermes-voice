package com.hermes.voice.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.hermes.voice.databinding.ActivitySettingsBinding
import com.hermes.voice.network.ApiConfig
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class SettingsActivity : AppCompatActivity() {

    @Inject
    lateinit var apiConfig: ApiConfig

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.apply {
            title = "设置"
            setDisplayHomeAsUpEnabled(true)
        }

        loadCurrentConfig()
        setupSave()
    }

    private fun loadCurrentConfig() {
        binding.etApiUrl.setText(apiConfig.wsUrl)
        binding.etApiToken.setText(apiConfig.voiceToken)
        binding.etDeviceId.setText(apiConfig.deviceId)
    }

    private fun setupSave() {
        binding.btnSave.setOnClickListener {
            val url = binding.etApiUrl.text.toString().trim()
            val token = binding.etApiToken.text.toString().trim()
            val deviceId = binding.etDeviceId.text.toString().trim()

            if (url.isBlank() || token.isBlank() || deviceId.isBlank()) {
                Toast.makeText(this, "请填写完整", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (!url.startsWith("ws://") && !url.startsWith("wss://")) {
                Toast.makeText(this, "地址需要以 ws:// 或 wss:// 开头", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            apiConfig.wsUrl = url
            apiConfig.voiceToken = token
            apiConfig.deviceId = deviceId
            Toast.makeText(this, "配置已保存", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
