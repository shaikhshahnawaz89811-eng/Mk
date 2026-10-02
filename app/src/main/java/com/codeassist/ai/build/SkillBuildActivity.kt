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
import com.codeassist.ai.workspace.WorkspaceActivity

/** Simple, non-fake project-build transition screen required by the existing UI flow. */
class SkillBuildActivity : AppCompatActivity() {
    companion object { const val EXTRA_PROJECT_ID = "project_id" }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var steps: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        setContentView(R.layout.activity_skill_build)
        steps = findViewById(R.id.stepsContainer)
        findViewById<View>(R.id.errorCard).visibility = View.GONE
        val projectId = intent.getStringExtra(EXTRA_PROJECT_ID)
        if (projectId == null || Store.project(projectId) == null) { finish(); return }

        val titles = listOf(
            "✓ Project created",
            "✓ Workspace prepared",
            "✓ Ready for the two-module coding pipeline"
        )
        titles.forEachIndexed { index, title ->
            handler.postDelayed({ addStep(title) }, index * 360L)
        }
        handler.postDelayed({
            startActivity(Intent(this, WorkspaceActivity::class.java).putExtra(WorkspaceActivity.EXTRA_PROJECT_ID, projectId))
            finish()
        }, 1400L)
    }

    private fun addStep(text: String) {
        val tv = TextView(this).apply {
            this.text = text
            setTextColor(0xFF93A1B3.toInt())
            textSize = 12.5f
            setPadding(0, 5, 0, 5)
        }
        steps.addView(tv)
    }

    override fun onDestroy() { handler.removeCallbacksAndMessages(null); super.onDestroy() }
}
