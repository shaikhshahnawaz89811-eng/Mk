package com.codeassist.ai.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.fragment.app.Fragment
import com.codeassist.ai.R
import com.codeassist.ai.data.Store

class SettingsFragment : Fragment() {

    private lateinit var container: LinearLayout

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        Store.init(requireContext())
        container = view.findViewById(R.id.settingsContainer)
        view.findViewById<View>(R.id.btnMenu).setOnClickListener {
            (activity as? com.codeassist.ai.MainActivity)?.openDrawer()
        }
        build()
    }

    private fun build() {
        container.removeAllViews()

        section("Appearance")
        card {
            valueRow(R.drawable.ic_moon, "Theme", "Dark") { anchor, tv ->
                popup(anchor, listOf("Dark"), "Dark") { tv.text = it }
            }
            switchRow(R.drawable.ic_sparkle, "Glass Effect", Store.glassEffect) {
                Store.glassEffect = it
                Toast.makeText(requireContext(), if (it) "Glass cards on" else "Flat cards on", Toast.LENGTH_SHORT).show()
            }
            valueRow(R.drawable.ic_text, "Text Size", Store.textSize) { anchor, tv ->
                popup(anchor, listOf("Small", "Medium", "Large"), Store.textSize) {
                    Store.textSize = it; tv.text = it
                }
            }
        }

        section("Assistant Engine")
        card {
            val gemma = com.codeassist.ai.engine.ModelLifecycle.gemma()
            valueRow(R.drawable.ic_sparkle, "Gemma 4 E4B IT",
                gemma.state.name.replace('_', ' ')) { _, _ ->
                (activity as? com.codeassist.ai.MainActivity)?.let { act ->
                    act.supportFragmentManager.popBackStackImmediate(null,
                        androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)
                    act.supportFragmentManager.beginTransaction()
                        .replace(com.codeassist.ai.R.id.fragmentContainer,
                            com.codeassist.ai.models.ModelsFragment())
                        .addToBackStack("tab").commit()
                }
            }
            valueRow(R.drawable.ic_shield, "Permission Mode", Store.permissionMode) { anchor, tv ->
                // Spec §13 permission modes — hard boundaries always enforced
                popup(anchor, listOf("Ask when needed", "Auto", "Skip approvals"),
                    Store.permissionMode) {
                    Store.permissionMode = it; tv.text = it
                    com.codeassist.ai.engine.RiskEngine.approvalMode = when (it) {
                        "Auto" -> com.codeassist.ai.engine.ApprovalMode.AUTO
                        "Skip approvals" -> com.codeassist.ai.engine.ApprovalMode.SKIP_APPROVALS
                        else -> com.codeassist.ai.engine.ApprovalMode.ASK_WHEN_NEEDED
                    }
                }
            }
            valueRow(R.drawable.ic_tune, "Thinking / Effort", Store.effort) { anchor, tv ->
                popup(anchor, listOf("Fast", "Balanced", "Deep"), Store.effort) {
                    Store.effort = it; tv.text = it
                }
            }
        }

        section("Credentials")
        card {
            val creds = com.codeassist.ai.engine.CredentialStore.list()
            if (creds.isEmpty()) {
                valueRow(R.drawable.ic_data, "No credentials", "Add") { _, _ ->
                    credentialDialog(null)
                }
            } else {
                creds.forEach { c ->
                    valueRow(R.drawable.ic_data, c.credentialId,
                        "${c.provider} • ${c.status}") { _, _ -> credentialDialog(c) }
                }
            }
        }

        section("Notifications")
        card {
            switchRow(R.drawable.ic_bell, "Push Notifications", Store.pushNotifications) {
                Store.pushNotifications = it
            }
            switchRow(R.drawable.ic_sound, "Sound", Store.sound) {
                Store.sound = it
            }
        }

        section("Privacy & Security")
        card {
            valueRow(R.drawable.ic_data, "Data Usage", Store.dataUsage) { anchor, tv ->
                popup(anchor, listOf("Keep in app", "Share anonymously"), Store.dataUsage) {
                    Store.dataUsage = it; tv.text = it
                }
            }
            actionRow(R.drawable.ic_delete, "Clear Chat History") {
                AlertDialog.Builder(requireContext())
                    .setTitle("Clear chat history")
                    .setMessage("This removes all chats and messages from this device.")
                    .setPositiveButton("Clear") { _, _ ->
                        Store.clearHistory()
                        Toast.makeText(requireContext(), "History cleared", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }

        section("App Behavior")
        card {
            switchRow(R.drawable.ic_save, "Auto Save Projects", Store.autoSave) {
                Store.autoSave = it
            }
            switchRow(R.drawable.ic_history, "Open Last Project", Store.openLastProject) {
                Store.openLastProject = it
            }
        }

        val version = TextView(requireContext()).apply {
            text = "CodeAssistAI v1.0 · all data stays on this device"
            setTextColor(0xFF5D6B7C.toInt())
            textSize = 11.5f
            gravity = android.view.Gravity.CENTER
            setPadding(0, 40, 0, 8)
        }
        container.addView(version)
    }

    private fun section(title: String) {
        val tv = TextView(requireContext()).apply {
            text = title
            setTextColor(0xFF5D6B7C.toInt())
            textSize = 12.5f
            letterSpacing = 0.05f
            setPadding(4, 34, 0, 16)
        }
        container.addView(tv)
    }

    /** Add / revoke a credential — secret goes only to the encrypted store (Spec §16). */
    private fun credentialDialog(existing: com.codeassist.ai.engine.CredentialRef?) {
        val ctx = requireContext()
        val providerIn = android.widget.EditText(ctx).apply {
            hint = "Provider (e.g. openai, google, github)"
            setText(existing?.provider ?: "")
            setPadding(48, 24, 48, 8)
        }
        val keyIn = android.widget.EditText(ctx).apply {
            hint = "API key / token"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setPadding(48, 8, 48, 24)
        }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(providerIn); addView(keyIn)
        }
        val b = AlertDialog.Builder(ctx)
            .setTitle(if (existing == null) "Add credential" else existing.credentialId)
            .setMessage("Encrypted with the Android Keystore. The assistant only ever sees the credential_id.")
            .setView(box)
            .setNegativeButton("Cancel", null)
        if (existing == null) {
            b.setPositiveButton("Save") { _, _ ->
                val p = providerIn.text.toString().trim()
                val k = keyIn.text.toString().trim()
                if (p.isNotBlank() && k.length >= 8) {
                    com.codeassist.ai.engine.CredentialStore.connect(p, k)
                    build()
                } else Toast.makeText(ctx, "Provider + key (8+ chars) required", Toast.LENGTH_SHORT).show()
            }
        } else {
            keyIn.visibility = View.GONE
            providerIn.isEnabled = false
            b.setPositiveButton("Revoke") { _, _ ->
                com.codeassist.ai.engine.CredentialStore.revoke(existing.credentialId)
                build()
            }
        }
        b.show()
    }

    private var activeCard: LinearLayout? = null

    private fun card(content: () -> Unit) {
        val card = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(if (Store.glassEffect) R.drawable.glass_card else R.drawable.glass_card_solid)
        }
        container.addView(card)
        activeCard = card
        content()
        activeCard = null
    }

    private fun inflateRow(): View {
        return LayoutInflater.from(requireContext()).inflate(R.layout.item_setting_row, activeCard, false)
    }

    private fun addRow(v: View) {
        val card = activeCard ?: return
        if (card.childCount > 0) {
            val d = View(requireContext())
            d.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
                marginStart = dp(58)
            }
            d.setBackgroundResource(R.drawable.divider)
            card.addView(d)
        }
        card.addView(v)
    }

    private fun switchRow(icon: Int, title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        val row = inflateRow()
        row.findViewById<ImageView>(R.id.rowIcon).setImageResource(icon)
        row.findViewById<TextView>(R.id.rowTitle).text = title
        row.findViewById<TextView>(R.id.rowValue).visibility = View.GONE
        row.findViewById<ImageView>(R.id.rowChevron).visibility = View.GONE
        val sw = row.findViewById<SwitchCompat>(R.id.rowSwitch)
        sw.visibility = View.VISIBLE
        sw.isChecked = checked
        sw.setOnCheckedChangeListener { _, b -> onChange(b) }
        row.setOnClickListener { sw.toggle() }
        addRow(row)
    }

    private fun valueRow(icon: Int, title: String, value: String, onClick: (View, TextView) -> Unit) {
        val row = inflateRow()
        row.findViewById<ImageView>(R.id.rowIcon).setImageResource(icon)
        row.findViewById<TextView>(R.id.rowTitle).text = title
        val tv = row.findViewById<TextView>(R.id.rowValue)
        tv.text = value
        row.setOnClickListener { onClick(it, tv) }
        addRow(row)
    }

    private fun actionRow(icon: Int, title: String, onClick: () -> Unit) {
        val row = inflateRow()
        row.findViewById<ImageView>(R.id.rowIcon).apply {
            setImageResource(icon)
            setColorFilter(0xFFFF7B72.toInt())
        }
        row.findViewById<TextView>(R.id.rowTitle).text = title
        row.findViewById<TextView>(R.id.rowValue).visibility = View.GONE
        row.setOnClickListener { onClick() }
        addRow(row)
    }

    private fun popup(anchor: View, options: List<String>, current: String, onPick: (String) -> Unit) {
        val menu = PopupMenu(requireContext(), anchor)
        options.forEachIndexed { i, o ->
            val label = if (o == current) "✓  $o" else "    $o"
            menu.menu.add(0, i, i, label)
        }
        menu.setOnMenuItemClickListener {
            onPick(options[it.itemId]); true
        }
        menu.show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
