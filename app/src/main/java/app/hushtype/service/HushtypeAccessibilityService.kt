package app.hushtype.service

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import app.hushtype.HushtypeApp
import app.hushtype.core.DictationContext
import app.hushtype.data.BubbleMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Finds the text field you're typing in, shows the floating bubble next to it, and types the
 * dictation into it. Android only lets accessibility services do this for other apps.
 */
class HushtypeAccessibilityService : AccessibilityService(), TextTarget {
    private lateinit var bubble: BubbleController
    private val app get() = HushtypeApp.instance

    override fun onServiceConnected() {
        super.onServiceConnected()
        running.value = true
        bubble = BubbleController(this, app.dictation, app.settings)
        app.dictation.target = this
        refresh()
    }

    override fun onDestroy() {
        running.value = false
        if (app.dictation.target === this) app.dictation.target = null
        if (::bubble.isInitialized) bubble.destroy()
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        when (event?.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED,
            AccessibilityEvent.TYPE_VIEW_CLICKED -> refresh()
        }
    }

    /** Decides whether the bubble should show, and keeps it clear of the keyboard. */
    private fun refresh() {
        if (!::bubble.isInitialized) return
        val focus = runCatching { rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()
        val editing = focus?.isEditable == true && !focus.isPassword
        val keyboardTop = keyboardTop()
        val mode = app.settings.bubbleMode
        val inOwnApp = runCatching { rootInActiveWindow?.packageName == packageName }.getOrDefault(false)
        // In Hushtype itself the bubble shows only in text fields.
        val show = editing || (!inOwnApp && (mode == BubbleMode.ALWAYS || keyboardTop != null))
        bubble.update(visible = show, keyboardTop = keyboardTop)
    }

    private fun keyboardTop(): Int? = runCatching {
        windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }?.let { window ->
            Rect().also { window.getBoundsInScreen(it) }.top
        }
    }.getOrNull()

    // region TextTarget

    override fun capture(): FieldSnapshot? {
        val node = focusedField() ?: return null
        val text = currentText(node)
        var start = node.textSelectionStart
        var end = node.textSelectionEnd
        if (start < 0 || end < 0 || start > text.length || end > text.length) {
            start = text.length
            end = text.length
        }
        if (start > end) start = end.also { end = start }
        val pkg = node.packageName?.toString()
        val context = DictationContext(
            appName = pkg?.let(::appLabel),
            packageName = pkg,
            textBefore = text.substring(0, start),
            textAfter = text.substring(end),
            selectedText = text.substring(start, end),
        )
        return FieldSnapshot(Field(node, start, end), context, start, node.isPassword)
    }

    override fun insert(snapshot: FieldSnapshot?, text: String): Boolean {
        val field = snapshot?.handle as? Field
        val node = field?.node?.takeIf { it.refresh() && it.isEditable } ?: focusedField() ?: return false
        val current = currentText(node)
        // Use the selection from when dictation started if the field still looks the same.
        val sameField = field != null && field.node == node && field.end <= current.length
        var start = if (sameField) field!!.start else node.textSelectionStart.takeIf { it in 0..current.length } ?: current.length
        var end = if (sameField) field!!.end else node.textSelectionEnd.takeIf { it in 0..current.length } ?: current.length
        if (start > end) start = end.also { end = start }

        val updated = current.substring(0, start) + text + current.substring(end)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, updated)
        }
        if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            val cursor = start + text.length
            node.performAction(
                AccessibilityNodeInfo.ACTION_SET_SELECTION,
                Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
                },
            )
            return true
        }
        // Some apps ignore "set text"; pasting works in most of those.
        copy(text)
        return node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
    }

    override fun read(snapshot: FieldSnapshot): String? {
        val node = (snapshot.handle as? Field)?.node ?: return null
        if (!node.refresh()) return null
        return currentText(node)
    }

    override fun copy(text: String) {
        getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Hushtype", text))
    }

    // endregion

    private class Field(val node: AccessibilityNodeInfo, val start: Int, val end: Int)

    private fun focusedField(): AccessibilityNodeInfo? = runCatching {
        rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }
            ?: windows.firstNotNullOfOrNull { window ->
                window.root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }
            }
    }.getOrNull()

    /** The field's text, without the grey hint an empty field shows. */
    private fun currentText(node: AccessibilityNodeInfo): String =
        if (node.isShowingHintText) "" else node.text?.toString().orEmpty()

    private fun appLabel(pkg: String): String? = runCatching {
        @Suppress("DEPRECATION") packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrNull()

    companion object {
        private val running = MutableStateFlow(false)
        /** Whether Android has the service switched on and connected. */
        val isRunning: StateFlow<Boolean> = running.asStateFlow()
    }
}
