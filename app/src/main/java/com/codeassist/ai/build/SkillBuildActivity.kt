package com.codeassist.ai.build

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.codeassist.ai.R
import com.codeassist.ai.data.Store
import com.codeassist.ai.engine.SkillRegistry
import com.codeassist.ai.workspace.WorkspaceActivity

/**
 * Project creation hand-off screen. It prepares the coding skill catalog and
 * then enters the real project workspace; it does not pretend to build code.
 */
class SkillBuildActivity : AppCompatActivity() {
    companion object { const val EXTRA_PROJECT_ID = "project_id" }

    private val handler = Handler(Looper.getMainLooper())
    private var projectId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_skill_build)
        projectId = intent.getStringExtra(EXTRA_PROJECT_ID)
        if (projectId.isNullOrBlank() || Store.project(projectId) == null) {
            finish()
            return
        }
        runPreparation()
    }

    private fun runPreparation() {
        val steps = findViewById<LinearLayout>(R.id.stepsContainer)
        val error = findViewById<View>(R.id.errorCard)
        val arc = findViewById<GlowArcView>(R.id.glowArc)
        val retry = findViewById<View>(R.id.btnRetry)

        steps.removeAllViews()
        error.visibility = View.GONE
        retry.setOnClickListener { runPreparation() }

        val labels = listOf(
            "Project created",
            "Loading coding skills",
            "Preparing workspace"
        )
        labels.forEachIndexed { index, label ->
            val tv = TextView(this).apply {
                text = "•  $label"
                setTextColor(0xFF93A1B3.toInt())
                textSize = 12.5f
                setPadding(0, 6, 0, 6)
            }
            steps.addView(tv)
            handler.postDelayed({
                tv.setTextColor(0xFF6EC1FF.toInt())
                arc.setProgress((index + 1) / labels.size.toFloat())
            }, index * 220L)
        }

        handler.postDelayed({
            runCatching {
                SkillRegistry.activate(listOf("coding", "android"), "project-build:$projectId")
            }.onFailure {
                error.visibility = View.VISIBLE
                return@postDelayed
            }
            val id = projectId ?: return@postDelayed
            startActivity(Intent(this, WorkspaceActivity::class.java)
                .putExtra(WorkspaceActivity.EXTRA_PROJECT_ID, id))
            finish()
        }, 800L)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
