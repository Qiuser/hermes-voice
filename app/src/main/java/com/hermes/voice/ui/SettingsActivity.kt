package com.hermes.voice.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.hermes.voice.api.ApiConfig
import com.hermes.voice.databinding.ActivitySettingsBinding
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
        binding.etApiUrl.setText(apiConfig.apiUrl)
        binding.etApiToken.setText(apiConfig.apiToken)
    }

    private fun setupSave() {
        binding.btnSave.setOnClickListener {
            val url = binding.etApiUrl.text.toString().trim()
            val token = binding.etApiToken.text.toString().trim()

            if (url.isBlank() || token.isBlank()) {
                Toast.makeText(this, "请填写完整的 API 地址和 Token", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (!url.startsWith("https://") && !url.startsWith("http://")) {
                Toast.makeText(this, "API 地址需要以 https:// 开头", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            apiConfig.apiUrl = url
            apiConfig.apiToken = token
            Toast.makeText(this, "配置已保存", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
