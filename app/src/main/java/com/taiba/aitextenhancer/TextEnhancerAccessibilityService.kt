package com.taiba.aitextenhancer

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class TextEnhancerAccessibilityService : AccessibilityService() {

    private var overlayManager: OverlayManager? = null
    private val selfPackageName: String by lazy { applicationContext.packageName }

    private val handler = Handler(Looper.getMainLooper())
    private var pendingHide: Runnable? = null

    // Har app ka accessibility event alag tareeqe se aata hai (kayi apps focus event
    // reliably nahi bhejte) — is liye event ke sath sath ek halka sa poll bhi rakha hai
    // taake icon "kabhi show hi nahi hota" wala masla na ho.
    private val pollRunnable = object : Runnable {
        override fun run() {
            if (Prefs.isBubbleEnabled(this@TextEnhancerAccessibilityService)) {
                checkFocus()
            }
            handler.postDelayed(this, 800)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        overlayManager = OverlayManager(this)
        handler.post(pollRunnable)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // Humara apna overlay panel (bubble/toolbar/custom-prompt/chat) khud bhi
        // accessibility events generate karta hai jab uske andar EditText focus hoti hai.
        // Pehle ye "dusri app khul gayi" samajh kar khud ko band kar deta tha — is liye
        // apni khud ki app ke events ko yahan hi ignore kar dete hain.
        if (event.packageName == selfPackageName) return

        if (!Prefs.isBubbleEnabled(this)) {
            overlayManager?.hideBubbleAndMenus()
            return
        }

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                // NOTE: hum ab yahan package-change par khud panel band nahi karte.
                // Wajah: jab custom-prompt/chat ka EditText tap hota hai to on-screen
                // keyboard khulta hai, jo ek bilkul ALAG app package (e.g. Gboard) se
                // window-state event bhejta hai — pehle code ye "app switch ho gayi"
                // samajh kar turant panel band kar deta tha. Ab close sirf explicit
                // user action (X button ya menu select) se hi hota hai — zyada reliable.
                checkFocus()
            }
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> checkFocus()
            else -> { /* ignore */ }
        }
    }

    /**
     * Bubble dikhana/chhupana yahan decide hota hai. Jab tak toolbar/custom-prompt
     * khula hai, bilkul react nahi karte — warna beech me hi menu band ho jata tha
     * (content-changed event bohat baar fire hoti hai, usse false-negative aata tha).
     */
    private fun checkFocus() {
        val overlay = overlayManager ?: return
        if (overlay.isMenuOpen()) return

        pendingHide?.let { handler.removeCallbacks(it) }
        val node = getFocusedEditableNode()
        if (node != null) {
            overlay.showBubble()
        } else {
            // Turant hide na karein — 400ms wait, kahin ye sirf ek pal ka false-negative na ho
            val r = Runnable {
                if (overlayManager?.isMenuOpen() != true && getFocusedEditableNode() == null) {
                    overlayManager?.hideBubbleOnly()
                }
            }
            pendingHide = r
            handler.postDelayed(r, 400)
        }
    }

    override fun onInterrupt() { /* no-op */ }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        overlayManager?.destroy()
        overlayManager = null
    }

    /** Currently focused, editable node dhoondta hai (agar koi ho) — humari apni overlay
     *  UI ki fields (chat input, custom prompt input) ko kabhi target nahi karte. */
    fun getFocusedEditableNode(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        if (root.packageName == selfPackageName) return null
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return null
        if (focused.packageName == selfPackageName) return null
        return if (focused.isEditable) focused else null
    }

    fun getFocusedNodeText(): String {
        val node = getFocusedEditableNode() ?: return ""
        return node.text?.toString() ?: ""
    }

    /**
     * Focused field me text set karta hai. Pehle ACTION_SET_TEXT try karta hai
     * (zyada tar native EditText/Compose fields is se kaam kar jate hain).
     * Agar wo fail ho to clipboard-paste fallback (select all + paste) try karta hai —
     * kuch WebView / custom text field is se bhi kaam kar jate hain, lekin guarantee nahi.
     */
    fun setFocusedNodeText(newText: String): Boolean {
        val node = getFocusedEditableNode() ?: return false

        val directArgs = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
        }
        val directSuccess = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, directArgs)
        if (directSuccess) return true

        // Fallback: clipboard + select-all + paste
        return try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("ate", newText))

            val currentLen = node.text?.length ?: 0
            val selectArgs = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, currentLen)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selectArgs)
            node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        } catch (_: Exception) {
            false
        }
    }
}
