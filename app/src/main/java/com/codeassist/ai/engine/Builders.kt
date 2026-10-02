package com.codeassist.ai.engine

import java.io.File

/**
 * Project builders — Spec §10 flexible output types. Each builder scaffolds
 * a real, coherent project (actual files, not placeholders), runs checks,
 * and packages the artifact through the packaging tool.
 */
object Builders {

    private fun tool(run: RunRecord, ctx: ToolContext, name: String,
                     vararg args: Pair<String, Any?>): ToolResult =
        ToolRegistry.execute(ToolCall(name, mapOf(*args), runId = run.runId), ctx)

    private fun write(run: RunRecord, ctx: ToolContext, root: File, rel: String, content: String) {
        tool(run, ctx, "file_write", "path" to File(root, rel).absolutePath, "content" to content)
    }

    private fun step(run: RunRecord, title: String) {
        run.currentStep = title; EngineStore.upsertRun(run)
        EventBus.emit(run.runId, EventType.STEP_STARTED, title, EventStatus.ACTIVE)
    }

    private fun stepDone(run: RunRecord, title: String, detail: String = "") {
        EventBus.emit(run.runId, EventType.STEP_UPDATED, title, EventStatus.DONE, detail = detail)
    }

    // ------------------------------------------------------------------
    // BUILD_APP dispatcher
    // ------------------------------------------------------------------

    fun build(run: RunRecord, r: RoutedIntent, ctx: ToolContext, stop: StopCheck): Answer? {
        val topic = (r.entities["topic"] ?: run.userRequest)
            .replace(Regex("(?i)\\b(ek|mujhe|mere liye|banao|bana do|banana|please|create|make|build|app|website|game|for|a|an)\\b"), " ")
            .replace(Regex("\\s+"), " ")
            .trim().ifBlank { "My Project" }
        val slug = topic.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
            .ifBlank { "project" }.take(32)

        val root = ctx.projectRoot ?: File(ProjectSystem.projectsRoot(), slug)
        root.mkdirs()
        val pctx = ctx.copy(projectRoot = root)
        run.checkpoint["project_root"] = root.absolutePath
        EngineStore.upsertRun(run)

        val name = topic.replaceFirstChar { it.uppercase() }

        step(run, "Scaffolding project")
        when (r.outputType) {
            OutputType.ANDROID_APP -> androidProject(name, slug, run, pctx, root)
            OutputType.WEBSITE -> website(name, run, pctx, root)
            OutputType.GAME -> game(name, run, pctx, root)
            OutputType.IOS_APP -> iosProject(name, run, pctx, root)
            OutputType.BACKEND -> backend(name, run, pctx, root)
            else -> website(name, run, pctx, root)
        }
        stepDone(run, "Scaffolding project", "${root.walkTopDown().count { it.isFile }} files")
        if (stop(run)) return null

        // The deterministic scaffold is created by tools. All model-written implementation
        // now goes through the same Gemma -> contract -> Coder -> guard -> validation pipeline
        // as existing-project edits. This prevents a second unguarded write path.
        val helperImported = CoderHelperManager.state() != ModelState.NOT_IMPORTED
        if (!helperImported) {
            val typeLabel = when (r.outputType) {
                OutputType.ANDROID_APP -> "Android app project"
                OutputType.WEBSITE -> "website"
                OutputType.GAME -> "game project"
                OutputType.IOS_APP -> "iOS project"
                OutputType.BACKEND -> "backend project"
                else -> "project"
            }
            return Answer(
                "Your **$typeLabel** \"$name\" starter project is ready.\n\n" +
                    "• Stack: ${ProjectSystem.detectStack(root)}\n" +
                    "• Coder Helper: not imported, so no model-generated project edits were made.\n" +
                    "• Open the project and import the Coder Helper, then ask for the feature implementation.",
                artifacts = emptyList()
            )
        }

        step(run, "Implementing project with two-module pipeline")
        EventBus.emit(run.runId, EventType.STEP_UPDATED, "Two-module pipeline", EventStatus.INFO, detail = "Gemma plans; Coder writes code only")
        val result = CodingPipeline.run(run, pctx, stop)
        if (!result.ok) {
            return Answer(
                "Project scaffold ban gaya, lekin implementation pipeline complete nahi hui.\n\n" +
                    result.errors.joinToString("\n") { "• $it" },
                artifacts = emptyList()
            )
        }
        val typeLabel = when (r.outputType) {
            OutputType.ANDROID_APP -> "Android app project"
            OutputType.WEBSITE -> "website"
            OutputType.GAME -> "game project"
            OutputType.IOS_APP -> "iOS project"
            OutputType.BACKEND -> "backend project"
            else -> "project"
        }
        return Answer(
            "Your **$typeLabel** \"$name\" is ready.\n\n" +
                "• Stack: ${ProjectSystem.detectStack(root)}\n" +
                "• Two-module implementation + validation: PASS\n" +
                "• Final ZIP: ${result.packagePath?.let { File(it).name } ?: "in Artifacts"}",
            artifacts = listOfNotNull(result.packagePath)
        )
    }

    // ------------------------------------------------------------------
    // Website
    // ------------------------------------------------------------------

    private fun website(name: String, run: RunRecord, ctx: ToolContext, root: File) {
        write(run, ctx, root, "index.html", """<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>$name</title>
    <link rel="stylesheet" href="styles.css">
</head>
<body>
    <header class="header">
        <div class="logo">$name</div>
        <nav aria-label="Main">
            <ul class="nav-links">
                <li><a href="#features">Features</a></li>
                <li><a href="#about">About</a></li>
                <li><a href="#contact">Contact</a></li>
            </ul>
        </nav>
    </header>

    <main>
        <section class="hero">
            <h1>$name</h1>
            <p>A fast, responsive, accessible site — generated on-device.</p>
            <a class="cta" href="#features">Get started</a>
        </section>

        <section id="features" class="features">
            <article class="card"><h2>Fast</h2><p>No frameworks, no bloat. Plain HTML/CSS/JS.</p></article>
            <article class="card"><h2>Responsive</h2><p>Mobile-first layout that adapts to any screen.</p></article>
            <article class="card"><h2>Accessible</h2><p>Semantic markup and proper contrast by default.</p></article>
        </section>

        <section id="about" class="about">
            <h2>About</h2>
            <p>$name — built as a starting point you can extend. Edit freely.</p>
        </section>

        <section id="contact" class="contact">
            <h2>Contact</h2>
            <form id="contactForm">
                <label>Email <input type="email" required placeholder="you@example.com"></label>
                <label>Message <textarea required placeholder="Hello…"></textarea></label>
                <button type="submit">Send</button>
            </form>
        </section>
    </main>

    <footer><p>© $name — generated locally.</p></footer>
    <script src="script.js"></script>
</body>
</html>
""")
        write(run, ctx, root, "styles.css", """:root {
    --bg: #0f141b; --panel: #161d27; --text: #e6ebf2; --muted: #93a1b3; --accent: #6ec1ff;
}
* { margin: 0; padding: 0; box-sizing: border-box; }
body { font-family: system-ui, sans-serif; background: var(--bg); color: var(--text); line-height: 1.6; }

.header {
    display: flex; justify-content: space-between; align-items: center;
    padding: 16px 24px; background: var(--panel); position: sticky; top: 0;
}
.logo { font-weight: 700; font-size: 1.25rem; }
.nav-links { display: flex; gap: 20px; list-style: none; }
.nav-links a { color: var(--muted); text-decoration: none; }
.nav-links a:hover { color: var(--accent); }

.hero { text-align: center; padding: 96px 24px; }
.hero h1 { font-size: 2.75rem; margin-bottom: 12px; }
.hero p { color: var(--muted); margin-bottom: 28px; }
.cta {
    background: var(--accent); color: #08131f; padding: 12px 28px;
    border-radius: 999px; text-decoration: none; font-weight: 600;
}
.features { display: grid; grid-template-columns: repeat(auto-fit, minmax(240px, 1fr)); gap: 16px; padding: 48px 24px; }
.card { background: var(--panel); border-radius: 16px; padding: 24px; }
.card h2 { margin-bottom: 8px; font-size: 1.1rem; }
.card p { color: var(--muted); }
.about, .contact { padding: 48px 24px; max-width: 720px; margin: 0 auto; }
form { display: grid; gap: 16px; }
label { display: grid; gap: 6px; color: var(--muted); }
input, textarea {
    background: var(--panel); border: 1px solid #2a3646; color: var(--text);
    border-radius: 10px; padding: 12px; font: inherit;
}
button { background: var(--accent); border: 0; border-radius: 10px; padding: 12px; font-weight: 600; cursor: pointer; }
footer { text-align: center; color: var(--muted); padding: 32px; }

@media (max-width: 768px) {
    .header { flex-direction: column; gap: 8px; }
    .hero { padding: 64px 16px; }
    .hero h1 { font-size: 2rem; }
}
@media (max-width: 480px) {
    .nav-links { flex-direction: column; align-items: center; gap: 12px; }
}
""")
        write(run, ctx, root, "script.js", """// progressive enhancement — the site works without JS too
document.getElementById('contactForm')?.addEventListener('submit', (e) => {
    e.preventDefault();
    const btn = e.target.querySelector('button');
    btn.textContent = 'Sent ✓';
    btn.disabled = true;
});
""")
        write(run, ctx, root, "README.md", """# $name

Static website — no build step. Open `index.html` in a browser.

## Structure
- `index.html` — semantic markup, responsive meta
- `styles.css` — mobile-first CSS with breakpoints at 768px / 480px
- `script.js` — progressive enhancement only
""")
    }

    // ------------------------------------------------------------------
    // Android (Gradle) — real, assembleDebug-ready structure (Spec §17)
    // ------------------------------------------------------------------

    private fun androidProject(name: String, slug: String, run: RunRecord, ctx: ToolContext, root: File) {
        val pkg = "com.generated.${slug.replace("-", "")}"
        write(run, ctx, root, "settings.gradle", """pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement { repositories { google(); mavenCentral() } }
rootProject.name = "$name"
include(":app")
""")
        write(run, ctx, root, "build.gradle", """plugins {
    id 'com.android.application' version '8.5.2' apply false
    id 'org.jetbrains.kotlin.android' version '1.9.24' apply false
}
""")
        write(run, ctx, root, "gradle.properties", """org.gradle.jvmargs=-Xmx2048m
android.useAndroidX=true
kotlin.code.style=official
""")
        write(run, ctx, root, "app/build.gradle", """plugins {
    id 'com.android.application'
    id 'org.jetbrains.kotlin.android'
}

android {
    namespace '$pkg'
    compileSdk 34

    defaultConfig {
        applicationId "$pkg"
        minSdk 26
        targetSdk 34
        versionCode 1
        versionName "1.0"
    }
    compileOptions {
        sourceCompatibility JavaVersion.VERSION_17
        targetCompatibility JavaVersion.VERSION_17
    }
}

dependencies {
    implementation 'androidx.core:core-ktx:1.13.1'
    implementation 'androidx.appcompat:appcompat:1.7.0'
    implementation 'com.google.android.material:material:1.12.0'
}
""")
        write(run, ctx, root, "app/src/main/AndroidManifest.xml", """<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application
        android:allowBackup="true"
        android:label="@string/app_name"
        android:theme="@style/Theme.AppCompat.DayNight.NoActionBar"
        android:supportsRtl="true">
        <activity android:name=".MainActivity" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
""")
        val path = pkg.replace(".", "/")
        write(run, ctx, root, "app/src/main/java/$path/MainActivity.kt", """package $pkg

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViewById<TextView>(R.id.title).text = getString(R.string.app_name)
    }
}
""")
        write(run, ctx, root, "app/src/main/res/values/strings.xml", """<resources>
    <string name="app_name">$name</string>
</resources>
""")
        write(run, ctx, root, "app/src/main/res/layout/activity_main.xml", """<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:gravity="center"
    android:orientation="vertical"
    android:padding="24dp">

    <TextView
        android:id="@+id/title"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:textSize="24sp"
        android:textStyle="bold" />
</LinearLayout>
""")
        write(run, ctx, root, "README.md", """# $name

Android app (Kotlin, views). Build the APK:

```
./gradlew assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`
""")
    }

    // ------------------------------------------------------------------
    // Game (HTML5 canvas — runnable anywhere)
    // ------------------------------------------------------------------

    private fun game(name: String, run: RunRecord, ctx: ToolContext, root: File) {
        write(run, ctx, root, "index.html", """<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>$name</title>
    <style>
        body { margin: 0; background: #0f141b; display: grid; place-items: center; height: 100vh; }
        canvas { background: #161d27; border-radius: 12px; touch-action: none; max-width: 96vw; }
    </style>
</head>
<body>
    <canvas id="game" width="480" height="640"></canvas>
    <script src="game.js"></script>
</body>
</html>
""")
        write(run, ctx, root, "game.js", """// $name — tiny arcade loop (60fps), keyboard + touch
const cv = document.getElementById('game');
const ctx = cv.getContext('2d');

const player = { x: 240, y: 580, w: 40, h: 16, speed: 5 };
const ball = { x: 240, y: 300, r: 8, dx: 3, dy: -3 };
let score = 0;

const keys = {};
addEventListener('keydown', (e) => keys[e.key] = true);
addEventListener('keyup', (e) => keys[e.key] = false);
cv.addEventListener('touchmove', (e) => {
    const t = e.touches[0];
    const rect = cv.getBoundingClientRect();
    player.x = (t.clientX - rect.left) * (cv.width / rect.width);
    e.preventDefault();
}, { passive: false });

function update() {
    if (keys['ArrowLeft']) player.x -= player.speed;
    if (keys['ArrowRight']) player.x += player.speed;
    player.x = Math.max(player.w / 2, Math.min(cv.width - player.w / 2, player.x));

    ball.x += ball.dx; ball.y += ball.dy;
    if (ball.x < ball.r || ball.x > cv.width - ball.r) ball.dx *= -1;
    if (ball.y < ball.r) ball.dy *= -1;
    if (ball.y > cv.height - 30 - ball.r &&
        Math.abs(ball.x - player.x) < player.w) { ball.dy = -Math.abs(ball.dy); score++; }
    if (ball.y > cv.height + 20) { ball.x = 240; ball.y = 300; ball.dy = -3; score = 0; }
}

function draw() {
    ctx.clearRect(0, 0, cv.width, cv.height);
    ctx.fillStyle = '#6ec1ff';
    ctx.fillRect(player.x - player.w / 2, cv.height - 30, player.w, player.h);
    ctx.beginPath(); ctx.arc(ball.x, ball.y, ball.r, 0, Math.PI * 2);
    ctx.fillStyle = '#ffd166'; ctx.fill();
    ctx.fillStyle = '#e6ebf2'; ctx.font = '20px system-ui';
    ctx.fillText('Score: ' + score, 16, 30);
}

(function loop() { update(); draw(); requestAnimationFrame(loop); })();
""")
        write(run, ctx, root, "README.md", "# $name\n\nHTML5 canvas game. Open `index.html`. Arrow keys or touch.\n")
    }

    // ------------------------------------------------------------------
    // iOS (SwiftUI skeleton)
    // ------------------------------------------------------------------

    private fun iosProject(name: String, run: RunRecord, ctx: ToolContext, root: File) {
        write(run, ctx, root, "Package.swift", """// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "$name",
    platforms: [.iOS(.v16)],
    targets: [.target(name: "$name", path: "Sources")]
)
""")
        write(run, ctx, root, "Sources/App.swift", """import SwiftUI

@main
struct ${name.replace(" ", "")}App: App {
    var body: some Scene {
        WindowGroup { ContentView() }
    }
}
""")
        write(run, ctx, root, "Sources/ContentView.swift", """import SwiftUI

struct ContentView: View {
    var body: some View {
        VStack(spacing: 16) {
            Text("$name").font(.largeTitle).bold()
            Text("Generated on-device — extend me.").foregroundStyle(.secondary)
        }
        .padding()
    }
}
""")
        write(run, ctx, root, "README.md", "# $name\n\nSwiftUI app skeleton. Open in Xcode 15+.\n")
    }

    // ------------------------------------------------------------------
    // Backend
    // ------------------------------------------------------------------

    private fun backend(name: String, run: RunRecord, ctx: ToolContext, root: File) {
        write(run, ctx, root, "package.json", """{
  "name": "${name.lowercase().replace(" ", "-")}",
  "version": "1.0.0",
  "private": true,
  "scripts": { "start": "node server.js", "test": "node --test" }
}
""")
        write(run, ctx, root, "server.js", """// $name — zero-dependency Node API
const http = require('http');

const routes = {
    'GET /health': (req, res) => send(res, 200, { status: 'ok' }),
    'GET /api/items': (req, res) => send(res, 200, { items: [] }),
    'POST /api/items': (req, res) => {
        let body = '';
        req.on('data', (c) => body += c);
        req.on('end', () => send(res, 201, { created: JSON.parse(body || '{}') }));
    },
};

function send(res, code, obj) {
    res.writeHead(code, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify(obj));
}

http.createServer((req, res) => {
    const handler = routes[`${'$'}{req.method} ${'$'}{req.url}`];
    if (handler) handler(req, res);
    else send(res, 404, { error: 'not found' });
}).listen(8080, () => console.log('$name listening on :8080'));
""")
        write(run, ctx, root, "schema.sql", """CREATE TABLE IF NOT EXISTS items (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    created_at TEXT DEFAULT CURRENT_TIMESTAMP
);
""")
        write(run, ctx, root, "README.md", "# $name\n\nNode API skeleton: `npm start`, then `curl localhost:8080/health`.\n")
    }

    // ------------------------------------------------------------------
    // Media / animation pipeline — Spec §10 (scenes, frames, motion, render)
    // ------------------------------------------------------------------

    fun media(run: RunRecord, r: RoutedIntent, ctx: ToolContext, stop: StopCheck): Answer? {
        val topic = r.entities["topic"] ?: run.userRequest.take(60)
        val slug = topic.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(28)
            .ifBlank { "animation" }
        val root = ctx.projectRoot ?: File(ProjectSystem.projectsRoot(), slug)
        root.mkdirs()
        val pctx = ctx.copy(projectRoot = root)

        step(run, "Planning scenes")
        write(run, pctx.toCtx(), root, "scenes.json", """{
  "title": "$topic",
  "fps": 30,
  "scenes": [
    { "id": 1, "name": "Intro", "duration_s": 3, "motion": "fade-in + slow zoom" },
    { "id": 2, "name": "Main action", "duration_s": 6, "motion": "keyframed translate/scale" },
    { "id": 3, "name": "Outro", "duration_s": 2, "motion": "fade-out" }
  ]
}
""")
        stepDone(run, "Planning scenes", "3 scenes")
        if (stop(run)) return null

        step(run, "Creating motion spec")
        write(run, pctx.toCtx(), root, "motion/intro.json", """{
  "scene": 1,
  "keyframes": [
    { "t": 0.0, "opacity": 0, "scale": 0.9 },
    { "t": 1.0, "opacity": 1, "scale": 1.0, "easing": "easeOutCubic" }
  ]
}
""")
        write(run, pctx.toCtx(), root, "motion/main.json", """{
  "scene": 2,
  "keyframes": [
    { "t": 0.0, "x": -100 },
    { "t": 0.5, "x": 0, "easing": "spring" },
    { "t": 1.0, "x": 100, "easing": "easeInOut" }
  ]
}
""")
        write(run, pctx.toCtx(), root, "render_plan.md", """# Render plan — $topic

- Timeline: 3 scenes, 11 s total @30fps (330 frames)
- Output: 1080x1920 (mobile) mp4
- Captions: `captions.srt`

Rendering video on-device isn't possible in this build — the project bundles
the full scene/motion/caption spec so any renderer (FFmpeg, Remotion) can
execute it.
""")
        write(run, pctx.toCtx(), root, "captions.srt", """1
00:00:00,000 --> 00:00:03,000
$topic

2
00:00:03,000 --> 00:00:09,000
Generated scene plan — ready for a renderer.
""")
        stepDone(run, "Creating motion spec")
        if (stop(run)) return null

        step(run, "Packaging")
        val pkg = tool(run, pctx.toCtx(), "package", "name" to "$slug.zip")
        stepDone(run, "Packaging")
        return Answer(
            "Animation project **$topic** is planned and packaged.\n\n" +
                "• scenes.json — 3 scenes, timings, motion\n" +
                "• motion/*.json — keyframes + easing\n" +
                "• captions.srt + render_plan.md\n\n" +
                "Bundle: ${pkg.output}",
            pkg.filesChanged
        )
    }

    private fun ToolContext.toCtx(): ToolContext = this
}
