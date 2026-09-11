package com.taiba.aitextenhancer

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var tvApiKeyStatus: TextView
    private lateinit var tvAccessibilityStatus: TextView
    private lateinit var switchBubble: Switch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvApiKeyStatus = findViewById(R.id.tvApiKeyStatus)
        tvAccessibilityStatus = findViewById(R.id.tvAccessibilityStatus)
        switchBubble = findViewById(R.id.switchBubble)

        findViewById<Button>(R.id.btnOpenSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        findViewById<Button>(R.id.btnEnableAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        switchBubble.setOnCheckedChangeListener { _, isChecked ->
            Prefs.setBubbleEnabled(this, isChecked)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        val hasKey = Prefs.getApiKey(this).isNotBlank()
        tvApiKeyStatus.text = if (hasKey) "API key is set" else "API key not set"

        val enabled = isAccessibilityServiceEnabled()
        tvAccessibilityStatus.text = if (enabled) "Service is ON" else "Service is OFF"

        switchBubble.setOnCheckedChangeListener(null)
        switchBubble.isChecked = Prefs.isBubbleEnabled(this)
        switchBubble.setOnCheckedChangeListener { _, isChecked ->
            Prefs.setBubbleEnabled(this, isChecked)
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedComponent = ComponentName(this, TextEnhancerAccessibilityService::class.java)
        val enabledServices = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabledServices)
        while (splitter.hasNext()) {
            val componentName = ComponentName.unflattenFromString(splitter.next())
            if (componentName != null && componentName == expectedComponent) return true
        }
        return false
    }
}
