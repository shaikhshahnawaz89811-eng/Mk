package com.codeassist.ai.ui

import android.app.Dialog
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import androidx.fragment.app.DialogFragment
import com.codeassist.ai.R

class AttachSheet(private val helper: AttachmentHelper) : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val d = Dialog(requireContext(), R.style.SheetDialog)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        return d
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val v = inflater.inflate(R.layout.dialog_attach, container, false)
        v.findViewById<View>(R.id.optImage).setOnClickListener { helper.openImages(); dismiss() }
        v.findViewById<View>(R.id.optZip).setOnClickListener { helper.openZip(); dismiss() }
        v.findViewById<View>(R.id.optFile).setOnClickListener { helper.openFiles(); dismiss() }
        return v
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
        }
    }
}
