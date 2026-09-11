package com.taiba.aitextenhancer

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    private val models = listOf(
        "gemini-3.1-flash-lite",
        "gemini-3.1-flash",
        "gemini-2.5-flash",
        "gemini-2.5-flash-lite"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        title = getString(R.string.settings_title)

        val etApiKey = findViewById<EditText>(R.id.etApiKey)
        val spinnerModel = findViewById<Spinner>(R.id.spinnerModel)

        etApiKey.setText(Prefs.getApiKey(this))

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, models)
        spinnerModel.adapter = adapter
        val currentModel = Prefs.getModel(this)
        val idx = models.indexOf(currentModel)
        if (idx >= 0) spinnerModel.setSelection(idx)

        findViewById<Button>(R.id.btnSave).setOnClickListener {
            Prefs.setApiKey(this, etApiKey.text.toString().trim())
            Prefs.setModel(this, spinnerModel.selectedItem as String)
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
