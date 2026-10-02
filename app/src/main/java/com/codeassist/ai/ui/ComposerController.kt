package com.codeassist.ai.ui

import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import com.codeassist.ai.R
import com.codeassist.ai.data.AttachKind
import com.codeassist.ai.data.Attachment

/**
 * Wires the shared composer (view_composer.xml):
 *  - grows up to 5 lines, then scrolls internally (XML config)
 *  - 4000-char soft limit: counter appears near the limit, turns red and
 *    disables Send past the limit (typing itself is never blocked)
 *  - attachment chips with remove buttons
 */
class ComposerController(
    root: View,
    private val helper: AttachmentHelper,
    private val onSend: (String, List<Attachment>) -> Unit
) {
    companion object {
        const val MAX_CHARS = 4000
        const val COUNTER_SHOW_AT = 3400
    }

    val edit: EditText = root.findViewById(R.id.editMessage)
    private val btnSend: ImageButton = root.findViewById(R.id.btnSend)
    private val btnPlus: ImageButton = root.findViewById(R.id.btnPlus)
    private val btnTune: ImageButton = root.findViewById(R.id.btnTune)
    private val counter: TextView = root.findViewById(R.id.textCounter)
    private val attachScroll: HorizontalScrollView = root.findViewById(R.id.attachmentScroll)
    private val attachContainer: LinearLayout = root.findViewById(R.id.attachmentContainer)
    private val composerCard: View = root.findViewById(R.id.composerCard)
    val chipModel: TextView = root.findViewById(R.id.chipModel)
    val chipTools: TextView = root.findViewById(R.id.chipTools)

    var hint: String
        get() = edit.hint?.toString() ?: ""
        set(v) { edit.hint = v }

    var onPlusClick: (() -> Unit)? = null
    var onTuneClick: (() -> Unit)? = null
    var onStopClick: (() -> Unit)? = null

    /** Send / Stop are mutually clear (Spec §11 composer states). */
    enum class Mode { SEND, STOP }
    private var mode = Mode.SEND
    private var sending = false

    fun setMode(m: Mode) {
        if (mode == m) return
        mode = m
        btnSend.setImageResource(if (m == Mode.SEND) R.drawable.ic_send else R.drawable.ic_close)
        refresh()
    }

    init {
        btnPlus.setOnClickListener { onPlusClick?.invoke() }
        btnTune.setOnClickListener { onTuneClick?.invoke() }
        btnSend.setOnClickListener { if (mode == Mode.SEND) send() else onStopClick?.invoke() }

        edit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { refresh() }
        })
        edit.setOnFocusChangeListener { _, hasFocus ->
            composerCard.setBackgroundResource(
                if (hasFocus) R.drawable.composer_bg_focus else R.drawable.composer_bg
            )
        }
        helper.onChanged = { renderAttachments() }
        refresh()
    }

    private fun refresh() {
        val len = edit.text?.length ?: 0
        val over = len > MAX_CHARS
        if (len >= COUNTER_SHOW_AT) {
            counter.visibility = View.VISIBLE
            counter.text = "$len/$MAX_CHARS"
            counter.setTextColor(if (over) 0xFFFF7B72.toInt() else 0xFF5D6B7C.toInt())
        } else {
            counter.visibility = View.GONE
        }
        val hasContent = !edit.text.isNullOrBlank() || helper.current.isNotEmpty()
        val enabled = if (mode == Mode.STOP) true else hasContent && !over && !sending
        if (btnSend.isEnabled != enabled) {
            btnSend.isEnabled = enabled
            btnSend.animate().cancel()
            btnSend.animate().alpha(if (enabled) 1f else 0.5f).setDuration(140).start()
        }
    }

    private fun renderAttachments() {
        attachContainer.removeAllViews()
        attachScroll.visibility = if (helper.current.isEmpty()) View.GONE else View.VISIBLE
        val inflater = LayoutInflater.from(attachContainer.context)
        for (a in helper.current) {
            val chip = inflater.inflate(R.layout.item_attachment, attachContainer, false)
            chip.findViewById<TextView>(R.id.textAttachName).text = a.name
            chip.findViewById<TextView>(R.id.textAttachSize).text = AttachmentHelper.formatSize(a.size)
            val icon = when (a.kind) {
                AttachKind.IMAGE -> R.drawable.ic_image
                AttachKind.ZIP -> R.drawable.ic_zip
                AttachKind.FILE -> R.drawable.ic_file
            }
            chip.findViewById<android.widget.ImageView>(R.id.iconAttach).setImageResource(icon)
            chip.findViewById<ImageButton>(R.id.btnRemoveAttach).setOnClickListener {
                helper.remove(a)
            }
            attachContainer.addView(chip)
        }
        refresh()
    }

    private fun send() {
        if (sending) return
        val text = edit.text?.toString()?.trim() ?: ""
        if (text.isEmpty() && helper.current.isEmpty()) return
        if (text.length > MAX_CHARS) return
        sending = true
        refresh()
        onSend(text, helper.current.toList())
    }

    fun clearAfterSend() {
        edit.setText("")
        helper.clear()
        sending = false
        refresh()
    }

    fun finishSend(success: Boolean) {
        if (success) clearAfterSend() else { sending = false; refresh() }
    }

    fun setModelLabel(model: String) {
        chipModel.text = "$model  ▾"
    }
}
