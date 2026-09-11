package com.taiba.aitextenhancer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Extension's content.js + background.js as the Android equivalent:
 * floating bubble -> expandable toolbar -> enhance modes / custom prompt / chat,
 * all built on the AccessibilityService's WindowManager overlay windows.
 */
class OverlayManager(private val service: TextEnhancerAccessibilityService) {

    private val windowManager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val inflater = LayoutInflater.from(service)
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Main)

    private var bubbleView: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null

    private var toolbarView: View? = null
    private var customPromptView: View? = null
    private var chatView: View? = null

    private val chatHistory = mutableListOf<ChatMessage>()
    private var chatAdapter: ChatAdapter? = null

    // ---------------- Bubble ----------------

    fun showBubble() {
        if (bubbleView != null) return
        val view = inflater.inflate(R.layout.overlay_bubble, null)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 0
        params.y = 600

        attachDragAndClick(view, params)

        try {
            windowManager.addView(view, params)
            bubbleView = view
            bubbleParams = params
        } catch (_: Exception) {
            // overlay add failed on some OEM — silently ignore
        }
    }

    private fun attachDragAndClick(view: View, params: WindowManager.LayoutParams) {
        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false

        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (abs(dx) > 8 || abs(dy) > 8) moved = true
                    params.x = initialX + dx
                    params.y = initialY + dy
                    try {
                        windowManager.updateViewLayout(view, params)
                    } catch (_: Exception) { }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    // Directly call the action here instead of routing through
                    // performClick()/OnClickListener — that indirection was the likely
                    // cause of "sometimes needs a second tap to respond".
                    if (!moved) toggleToolbar()
                    true
                }
                else -> false
            }
        }
    }

    // ---------------- Toolbar menu ----------------

    private fun toggleToolbar() {
        if (toolbarView != null) {
            hideMenusOnly()
            return
        }
        val view = inflater.inflate(R.layout.overlay_toolbar, null)

        view.findViewById<TextView>(R.id.menuImprove).setOnClickListener { runEnhance(EnhanceMode.IMPROVE) }
        view.findViewById<TextView>(R.id.menuGrammar).setOnClickListener { runEnhance(EnhanceMode.GRAMMAR) }
        view.findViewById<TextView>(R.id.menuProfessional).setOnClickListener { runEnhance(EnhanceMode.PROFESSIONAL) }
        view.findViewById<TextView>(R.id.menuFriendly).setOnClickListener { runEnhance(EnhanceMode.FRIENDLY) }
        view.findViewById<TextView>(R.id.menuShorten).setOnClickListener { runEnhance(EnhanceMode.SHORTEN) }
        view.findViewById<TextView>(R.id.menuExpand).setOnClickListener { runEnhance(EnhanceMode.EXPAND) }
        view.findViewById<TextView>(R.id.menuCustom).setOnClickListener {
            hideMenusOnly()
            showCustomPromptInput()
        }
        view.findViewById<TextView>(R.id.menuChat).setOnClickListener {
            hideMenusOnly()
            showChat()
        }
        view.findViewById<ImageView>(R.id.btnCloseToolbar).setOnClickListener { hideMenusOnly() }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        dockAboveKeyboard(view, params, defaultMargin = 140)

        try {
            windowManager.addView(view, params)
            toolbarView = view
        } catch (_: Exception) { }
    }

    private fun showCustomPromptInput() {
        val view = inflater.inflate(R.layout.overlay_custom_prompt, null)
        val etPrompt = view.findViewById<EditText>(R.id.etCustomPrompt)

        view.findViewById<ImageView>(R.id.btnCancelPrompt).setOnClickListener { hideMenusOnly() }
        view.findViewById<TextView>(R.id.btnApplyPrompt).setOnClickListener {
            val prompt = etPrompt.text.toString()
            hideMenusOnly()
            if (prompt.isNotBlank()) {
                runEnhance(EnhanceMode.CUSTOM, prompt)
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, // focusable so the keyboard can appear for this field
            PixelFormat.TRANSLUCENT
        )
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        dockAboveKeyboard(view, params, defaultMargin = 140)

        try {
            windowManager.addView(view, params)
            customPromptView = view
        } catch (_: Exception) { }
    }

    /**
     * Docks a panel to the bottom-center of the screen, and keeps it sitting just
     * above the on-screen keyboard once one appears (classic visible-frame technique).
     */
    private fun dockAboveKeyboard(view: View, params: WindowManager.LayoutParams, defaultMargin: Int) {
        params.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        params.x = 0
        params.y = defaultMargin

        view.viewTreeObserver.addOnGlobalLayoutListener {
            try {
                val rect = Rect()
                view.getWindowVisibleDisplayFrame(rect)
                val screenHeight = view.resources.displayMetrics.heightPixels
                val keypadHeight = screenHeight - rect.bottom
                val newY = if (keypadHeight > screenHeight * 0.15) keypadHeight + 16 else defaultMargin
                if (params.y != newY) {
                    params.y = newY
                    windowManager.updateViewLayout(view, params)
                }
            } catch (_: Exception) { }
        }
    }

    // ---------------- Enhance flow ----------------

    private fun runEnhance(mode: EnhanceMode, customPrompt: String? = null) {
        hideMenusOnly()
        val text = service.getFocusedNodeText()
        if (text.isBlank()) {
            toast("The text field is empty")
            return
        }
        toast("Enhancing…")
        scope.launch {
            GeminiClient.enhance(service.applicationContext, text, mode, customPrompt) { event ->
                when (event) {
                    is StreamEvent.Done -> {
                        val applied = service.setFocusedNodeText(event.fullText)
                        if (!applied) {
                            copyToClipboard(event.fullText)
                            toast("Couldn't apply automatically — result copied, please paste it")
                        }
                    }
                    is StreamEvent.Error -> toast(errorMessage(event))
                    else -> { /* deltas ignored for field-enhance, only the final text is needed */ }
                }
            }
        }
    }

    // ---------------- Chat ----------------

    private fun showChat() {
        if (chatView != null) return
        val view = inflater.inflate(R.layout.overlay_chat, null)
        val rv = view.findViewById<RecyclerView>(R.id.rvChat)
        val etInput = view.findViewById<EditText>(R.id.etChatInput)
        val btnSend = view.findViewById<ImageView>(R.id.btnSendChat)
        val btnClose = view.findViewById<ImageView>(R.id.btnCloseChat)
        val header = view.findViewById<View>(R.id.chatHeader)

        val adapter = ChatAdapter(chatHistory)
        chatAdapter = adapter
        rv.layoutManager = LinearLayoutManager(service)
        rv.adapter = adapter

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, // focusable so the keyboard works
            PixelFormat.TRANSLUCENT
        )
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        params.gravity = Gravity.CENTER

        attachDragOnly(header, view, params)

        btnClose.setOnClickListener { hideChat() }
        btnSend.setOnClickListener {
            val msg = etInput.text.toString().trim()
            if (msg.isEmpty()) return@setOnClickListener
            etInput.setText("")
            // adapter and chatHistory point to the same list, so add in one place only
            adapter.addMessage(ChatMessage("user", msg))
            rv.scrollToPosition(chatHistory.size - 1)
            sendChat()
        }

        try {
            windowManager.addView(view, params)
            chatView = view
        } catch (_: Exception) { }
    }

    private fun sendChat() {
        val adapter = chatAdapter ?: return
        // snapshot the history before adding the "…" placeholder
        val historyForApi = chatHistory.toList()
        adapter.addMessage(ChatMessage("assistant", "…")) // same list as chatHistory, placeholder is now last
        var streamed = ""
        scope.launch {
            GeminiClient.chat(service.applicationContext, historyForApi) { event ->
                when (event) {
                    is StreamEvent.Delta -> {
                        streamed += event.text
                        adapter.updateLast(streamed)
                    }
                    is StreamEvent.Done -> adapter.updateLast(event.fullText)
                    is StreamEvent.Error -> adapter.updateLast(errorMessage(event))
                }
            }
        }
    }

    private fun attachDragOnly(handle: View, container: View, params: WindowManager.LayoutParams) {
        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f
        handle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - touchX).toInt()
                    params.y = initialY + (event.rawY - touchY).toInt()
                    try {
                        windowManager.updateViewLayout(container, params)
                    } catch (_: Exception) { }
                    true
                }
                else -> false
            }
        }
    }

    private fun hideChat() {
        chatView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) { }
        }
        chatView = null
        chatAdapter = null
    }

    // ---------------- Visibility helpers ----------------

    fun hideMenusOnly() {
        toolbarView?.let { try { windowManager.removeView(it) } catch (_: Exception) { } }
        toolbarView = null
        customPromptView?.let { try { windowManager.removeView(it) } catch (_: Exception) { } }
        customPromptView = null
    }

    fun hideBubbleOnly() {
        hideMenusOnly()
        bubbleView?.let { try { windowManager.removeView(it) } catch (_: Exception) { } }
        bubbleView = null
        bubbleParams = null
    }

    fun hideBubbleAndMenus() {
        hideBubbleOnly()
        hideChat()
    }

    fun isMenuOpen(): Boolean = toolbarView != null || customPromptView != null

    fun destroy() {
        hideBubbleAndMenus()
        job.cancel()
    }

    private fun toast(msg: String) {
        Toast.makeText(service, msg, Toast.LENGTH_SHORT).show()
    }

    private fun copyToClipboard(text: String) {
        val cm = service.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("ate", text))
    }

    private fun errorMessage(e: StreamEvent.Error): String = when (e.code) {
        "NO_API_KEY" -> "Please set your Gemini API key in Settings first"
        "EMPTY_TEXT" -> "The text field is empty"
        "EMPTY_PROMPT" -> "Custom prompt is empty"
        "EMPTY_RESPONSE" -> "Model returned an empty response (${e.message})"
        else -> "Error: ${e.message}"
    }
}
