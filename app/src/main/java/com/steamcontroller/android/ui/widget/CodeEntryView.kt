package com.steamcontroller.android.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.InputType
import android.text.TextPaint
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import com.steamcontroller.android.ui.theme.C
import com.steamcontroller.android.ui.theme.FocusFx
import com.steamcontroller.android.ui.theme.Type

/**
 * Six-digit pairing code, TV-first: ←/→ move between cells, ↑/↓ roll the digit like a combination
 * lock, number keys type and advance, select opens the on-screen keyboard (which also works on a
 * phone). [onComplete] fires when the sixth digit is entered.
 */
class CodeEntryView(context: Context) : View(context) {
    private val d = resources.displayMetrics.density
    private val n = 6
    private val digits = IntArray(n) { -1 }
    private var cursor = 0
    var onComplete: (() -> Unit)? = null
    var onChange: (() -> Unit)? = null
    var error = false
        set(v) { field = v; invalidate() }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val txt = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Type.medium(context)
        textSize = 26f * resources.displayMetrics.scaledDensity
        fontFeatureSettings = Type.TABULAR
        textAlign = Paint.Align.CENTER
    }
    private val r = RectF()
    private val cellW = 44f * d
    private val cellH = 56f * d
    private val gap = 8f * d
    private val pad = 4f * d

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        FocusFx.attach(this, 1f) { invalidate() }
    }

    val code: String get() = if (digits.all { it >= 0 }) digits.joinToString("") else ""

    fun clear() { digits.fill(-1); cursor = 0; error = false; invalidate() }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension((n * cellW + (n - 1) * gap + 2 * pad).toInt(), (cellH + 2 * pad).toInt())

    override fun onDraw(c: Canvas) {
        for (i in 0 until n) {
            val x = pad + i * (cellW + gap)
            r.set(x, pad, x + cellW, pad + cellH)
            val active = isFocused && i == cursor
            if (active) {
                p.style = Paint.Style.STROKE; p.strokeWidth = d
                for (k in 3 downTo 1) {
                    p.color = C.alpha(C.accent, floatArrayOf(0.30f, 0.18f, 0.10f)[k - 1])
                    c.drawRoundRect(r.left - k * d, r.top - k * d, r.right + k * d, r.bottom + k * d, 10f * d + k * d, 10f * d + k * d, p)
                }
            }
            p.style = Paint.Style.FILL
            p.color = if (active) C.focusFill else C.surface2
            c.drawRoundRect(r, 10f * d, 10f * d, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = d
            p.color = when {
                active -> C.accent
                error -> C.alpha(C.danger, 0.7f)
                else -> C.line
            }
            c.drawRoundRect(r.left + d / 2, r.top + d / 2, r.right - d / 2, r.bottom - d / 2, 10f * d, 10f * d, p)
            val v = digits[i]
            txt.color = if (v < 0) C.text3 else C.text
            c.drawText(if (v < 0) "·" else DIGITS[v], r.centerX(), r.centerY() + txt.textSize * 0.36f, txt)
        }
    }

    private fun type(v: Int) {
        digits[cursor] = v
        error = false
        onChange?.invoke()
        if (cursor < n - 1) cursor++ else if (digits.all { it >= 0 }) onComplete?.invoke()
        invalidate()
    }

    private fun backspace() {
        if (digits[cursor] < 0 && cursor > 0) cursor--
        digits[cursor] = -1
        onChange?.invoke()
        invalidate()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> { type(keyCode - KeyEvent.KEYCODE_0); return true }
            in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> { type(keyCode - KeyEvent.KEYCODE_NUMPAD_0); return true }
            KeyEvent.KEYCODE_DEL -> { backspace(); return true }
            KeyEvent.KEYCODE_DPAD_LEFT -> if (cursor > 0) { cursor--; invalidate(); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (cursor < n - 1) { cursor++; invalidate(); return true }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                val dir = if (keyCode == KeyEvent.KEYCODE_DPAD_UP) 1 else -1
                val cur = digits[cursor].coerceAtLeast(if (dir > 0) -1 else 0)
                digits[cursor] = ((cur + dir) % 10 + 10) % 10
                error = false
                onChange?.invoke()
                invalidate()
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_A -> return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_BUTTON_A) {
            if (digits.all { it >= 0 }) onComplete?.invoke() else showKeyboard()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_UP) {
            val i = ((e.x - pad) / (cellW + gap)).toInt().coerceIn(0, n - 1)
            cursor = i
            requestFocus()
            showKeyboard()
            invalidate()
            performClick()
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    private fun showKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        @Suppress("DEPRECATION")
        imm.showSoftInput(this, InputMethodManager.SHOW_FORCED)
    }

    override fun onCheckIsTextEditor() = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_NUMBER
        outAttrs.imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        return object : BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
                for (ch in text) if (ch in '0'..'9') type(ch - '0')
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                repeat(beforeLength.coerceAtLeast(1)) { backspace() }
                return true
            }

            override fun performEditorAction(actionCode: Int): Boolean {
                if (digits.all { it >= 0 }) onComplete?.invoke()
                return true
            }
        }
    }

    private companion object {
        val DIGITS = Array(10) { it.toString() }
    }
}
