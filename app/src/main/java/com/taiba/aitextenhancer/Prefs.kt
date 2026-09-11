package com.taiba.aitextenhancer

import android.content.Context

/**
 * Simple SharedPreferences. (Pehle EncryptedSharedPreferences use kar rahe the,
 * lekin Android Keystore first-run par kabhi kabhi slow/hang ho jata hai jo app
 * ko "stuck/empty" dikhata tha — is liye reliability ke liye simple prefs par
 * switch kar diya hai. API key sirf isi device par local rehti hai, kisi server
 * pe nahi jati siwaye seedha Gemini API call ke.)
 */
object Prefs {
    private const val FILE_NAME = "ate_prefs"
    const val DEFAULT_MODEL = "gemini-3.1-flash-lite"

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun getApiKey(context: Context): String =
        prefs(context).getString("apiKey", "") ?: ""

    fun setApiKey(context: Context, key: String) {
        prefs(context).edit().putString("apiKey", key).apply()
    }

    fun getModel(context: Context): String =
        prefs(context).getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL

    fun setModel(context: Context, model: String) {
        prefs(context).edit().putString("model", model).apply()
    }

    fun isBubbleEnabled(context: Context): Boolean =
        prefs(context).getBoolean("bubbleEnabled", true)

    fun setBubbleEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("bubbleEnabled", enabled).apply()
    }
}
