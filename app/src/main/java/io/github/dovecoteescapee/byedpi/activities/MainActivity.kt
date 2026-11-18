package io.github.dovecoteescapee.byedpi.activities

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import io.github.dovecoteescapee.byedpi.R
import io.github.dovecoteescapee.byedpi.data.*
import io.github.dovecoteescapee.byedpi.databinding.ActivityMainBinding
import io.github.dovecoteescapee.byedpi.services.ServiceManager
import io.github.dovecoteescapee.byedpi.services.appStatus
import io.github.dovecoteescapee.byedpi.utility.*

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    data class TvPreset(val title: String, val args: String)

    companion object {
        private val TAG: String = MainActivity::class.java.simpleName

        val TV_PRESETS = listOf(
            TvPreset("1/5: Базовая (Split + OOB)", "-Ku -a1 -An -o1 -At,r,s -d1"),
            TvPreset("2/5: Агрессивный TLS + SNI", "-Ku -a1 -An -s1 -o1 -At,r,s -d1 -f-1 -r1+s -As"),
            TvPreset("3/5: Десинхронизация TTL=3", "-Ku -a1 -An -o1 -At,r,s -t3 -f-1 -r1+s"),
            TvPreset("4/5: Модифицированный Disorder", "-Ku -a1 -An -d1 -s1+s -At,r,s"),
            TvPreset("5/5: Максимальный обход", "-Ku -a1 -An -o1 -d1 -s1 -f-1 -t4 -At,r,s -As")
        )
    }

    private val vpnRegister =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == RESULT_OK) {
                binding.adbHintText.visibility = View.GONE
                ServiceManager.start(this, Mode.VPN)
            } else {
                showSonyVpnHint()
                Toast.makeText(this, "Нет разрешения на VPN", Toast.LENGTH_LONG).show()
                updateStatus()
            }
        }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            when (intent.action) {
                STARTED_BROADCAST, STOPPED_BROADCAST -> updateStatus()
                FAILED_BROADCAST -> {
                    Toast.makeText(context, "Ошибка запуска туннеля", Toast.LENGTH_SHORT).show()
                    updateStatus()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // При первом запуске включаем режим аргументов командной строки и ставим 1-й пресет
        initTvDefaultsIfNeeded()

        val intentFilter = IntentFilter().apply {
            addAction(STARTED_BROADCAST)
            addAction(STOPPED_BROADCAST)
            addAction(FAILED_BROADCAST)
        }

        @SuppressLint("UnspecifiedRegisterReceiverFlag")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, intentFilter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(receiver, intentFilter)
        }

        binding.statusButton.setOnClickListener {
            val (status, _) = appStatus
            when (status) {
                AppStatus.Halted -> start()
                AppStatus.Running -> stop()
            }
        }

        binding.presetButton.setOnClickListener {
            cyclePreset()
        }

        binding.ytOnlyButton.setOnClickListener {
            toggleYoutubeOnly()
        }

        binding.autostartButton.setOnClickListener {
            toggleAutostart()
        }

        binding.settingsButton.setOnClickListener {
            val (status, _) = appStatus
            if (status == AppStatus.Halted) {
                startActivity(Intent(this, SettingsActivity::class.java))
            } else {
                Toast.makeText(this, "Сначала отключите туннель", Toast.LENGTH_SHORT).show()
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        binding.statusButton.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        updateTvControls()
        updateStatus()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(receiver)
    }

    private fun initTvDefaultsIfNeeded() {
        val prefs = getPreferences()
        if (!prefs.getBoolean("tv_initialized", false)) {
            prefs.edit()
                .putBoolean("tv_initialized", true)
                .putBoolean("byedpi_enable_cmd_settings", true)
                .putInt("tv_preset_index", 0)
                .putString("byedpi_cmd_args", TV_PRESETS[0].args)
                .putBoolean("tv_only_youtube", true)
                .putBoolean("tv_autostart", false)
                .putBoolean("ipv6_enable", false)
                .apply()
        }
    }

    private fun cyclePreset() {
        val prefs = getPreferences()
        val nextIndex = (prefs.getInt("tv_preset_index", 0) + 1) % TV_PRESETS.size
        val preset = TV_PRESETS[nextIndex]

        prefs.edit()
            .putBoolean("byedpi_enable_cmd_settings", true)
            .putInt("tv_preset_index", nextIndex)
            .putString("byedpi_cmd_args", preset.args)
            .apply()

        updateTvControls()

        // Если VPN уже запущен — перезапускаем его с новой стратегией
        if (appStatus.first == AppStatus.Running) {
            Toast.makeText(this, "Применяю стратегию ${nextIndex + 1}...", Toast.LENGTH_SHORT).show()
            stop()
            Handler(Looper.getMainLooper()).postDelayed({ start() }, 600)
        }
    }

    private fun toggleYoutubeOnly() {
        val prefs = getPreferences()
        val newValue = !prefs.getBoolean("tv_only_youtube", true)
        prefs.edit().putBoolean("tv_only_youtube", newValue).apply()
        updateTvControls()

        if (appStatus.first == AppStatus.Running) {
            stop()
            Handler(Looper.getMainLooper()).postDelayed({ start() }, 600)
        }
    }

    private fun toggleAutostart() {
        val prefs = getPreferences()
        val newValue = !prefs.getBoolean("tv_autostart", false)
        prefs.edit().putBoolean("tv_autostart", newValue).apply()
        updateTvControls()
    }

    private fun updateTvControls() {
        val prefs = getPreferences()
        val index = prefs.getInt("tv_preset_index", 0).coerceIn(0, TV_PRESETS.lastIndex)
        val preset = TV_PRESETS[index]

        binding.presetButton.text = "Стратегия ${preset.title}"
        binding.presetArgsText.text = preset.args

        val ytOnly = prefs.getBoolean("tv_only_youtube", true)
        binding.ytOnlyButton.text = if (ytOnly) {
            "Трафик: Только YouTube и SmartTube"
        } else {
            "Трафик: Весь телевизор"
        }

        val autostart = prefs.getBoolean("tv_autostart", false)
        binding.autostartButton.text = if (autostart) {
            "Автозапуск при включении ТВ: ВКЛ"
        } else {
            "Автозапуск при включении ТВ: ВЫКЛ"
        }
    }

    private fun showSonyVpnHint() {
        binding.adbHintText.visibility = View.VISIBLE
        binding.adbHintText.text =
            "Если окно запроса VPN не открылось на Sony Bravia, выполните с ПК по ADB:\n" +
            "adb shell appops set $packageName ACTIVATE_VPN allow"
    }

    private fun start() {
        try {
            val intentPrepare = VpnService.prepare(this)
            if (intentPrepare != null) {
                vpnRegister.launch(intentPrepare)
            } else {
                binding.adbHintText.visibility = View.GONE
                ServiceManager.start(this, Mode.VPN)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to prepare VPN", e)
            showSonyVpnHint()
        }
    }

    private fun stop() {
        ServiceManager.stop(this)
    }

    private fun updateStatus() {
        val (status, _) = appStatus
        val preferences = getPreferences()
        val proxyIp = preferences.getStringNotNull("byedpi_proxy_ip", "127.0.0.1")
        val proxyPort = preferences.getStringNotNull("byedpi_proxy_port", "1080")
        binding.proxyAddress.text = "Локальный прокси: $proxyIp:$proxyPort"

        when (status) {
            AppStatus.Halted -> {
                binding.statusText.text = "ОТКЛЮЧЕНО"
                binding.statusText.setTextColor(Color.parseColor("#F87171"))
                binding.statusButton.text = "ВКЛЮЧИТЬ YOUTUBE"
                binding.statusButton.isEnabled = true
            }

            AppStatus.Running -> {
                binding.statusText.text = "РАБОТАЕТ"
                binding.statusText.setTextColor(Color.parseColor("#4ADE80"))
                binding.statusButton.text = "ОТКЛЮЧИТЬ"
                binding.statusButton.isEnabled = true
            }
        }
    }
}
