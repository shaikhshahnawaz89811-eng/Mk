package com.codeassist.ai.diagnostics

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.codeassist.ai.R
import com.codeassist.ai.engine.ModelLifecycle
import com.codeassist.ai.engine.SkillRegistry
import com.codeassist.ai.engine.Telemetry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Developer diagnostics — Spec §25. Read-only surface: run IDs, tool calls
 * with duration/status, skill activations, model lifecycle, retries,
 * Coder Helper status. Metadata only — no secrets, no private content.
 */
class DiagnosticsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_diagnostics)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnClear).setOnClickListener {
            Telemetry.clear(); render()
        }
        findViewById<View>(R.id.btnSelfTest).setOnClickListener { runSelfTest() }
        render()
    }

    /** One-tap answer to "is everything actually running?" (Spec §25/§28). */
    private fun runSelfTest() {
        val btn = findViewById<TextView>(R.id.btnSelfTest)
        val out = findViewById<TextView>(R.id.textSelfTest)
        btn.isEnabled = false; btn.alpha = 0.5f
        val sb = StringBuilder("Running…\n")
        out.text = sb.toString()
        Thread {
            val results = com.codeassist.ai.engine.SelfTest.runAll { c ->
                val mark = if (c.ok) "✓" else if (c.warn) "⚠" else "✗"
                val line = "$mark ${c.name} — ${c.detail}\n"
                runOnUiThread { out.append(line) }
            }
            runOnUiThread {
                val failed = results.count { !it.ok && !it.warn }
                val warned = results.count { it.warn }
                out.append("\n" + when {
                    failed == 0 && warned == 0 -> "All ${results.size} checks passed — everything is running."
                    failed == 0 -> "${results.size - warned} passed, $warned warning(s) — engine is fine."
                    else -> "$failed check(s) failed — see above."
                })
                btn.isEnabled = true; btn.alpha = 1f
                render()
            }
        }.start()
    }

    private fun render() {
        val gemma = ModelLifecycle.gemma()
        val coder = ModelLifecycle.coder()
        val summary = Telemetry.summary()
        findViewById<TextView>(R.id.textSummary).text = buildString {
            append("Gemma: ${gemma.state}  •  Coder Helper: ${coder.state}")
            if (coder.lastError.isNotBlank()) append(" (last: ${coder.lastError})")
            append("\nActive skills: ")
            append(SkillRegistry.activeSkills().joinToString(", ") { it.skill.id }.ifBlank { "none" })
            append("\nEvents: ")
            append(summary.entries.joinToString("  ") { "${it.key}=${it.value}" }.ifBlank { "none yet" })
        }

        val recycler = findViewById<RecyclerView>(R.id.recyclerDiag)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = DiagAdapter(Telemetry.entries().reversed())
    }

    class DiagAdapter(private val items: List<Telemetry.Entry>) :
        RecyclerView.Adapter<DiagAdapter.VH>() {

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val title: TextView = v.findViewById(android.R.id.text1)
            val sub: TextView = v.findViewById(android.R.id.text2)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(android.R.layout.simple_list_item_2, parent, false)
            v.findViewById<TextView>(android.R.id.text1).setTextColor(0xFFE6EBF2.toInt())
            v.findViewById<TextView>(android.R.id.text1).textSize = 13f
            v.findViewById<TextView>(android.R.id.text2).setTextColor(0xFF93A1B3.toInt())
            v.findViewById<TextView>(android.R.id.text2).textSize = 11f
            return VH(v)
        }

        override fun onBindViewHolder(h: VH, pos: Int) {
            val e = items[pos]
            val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(e.at))
            h.title.text = "[$time] ${e.kind}: ${e.label}"
            h.sub.text = buildString {
                append(e.status)
                if (e.durationMs > 0) append(" • ${e.durationMs} ms")
                if (e.detail.isNotBlank()) append(" • ${e.detail}")
            }
        }

        override fun getItemCount() = items.size
    }
}
