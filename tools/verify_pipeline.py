#!/usr/bin/env python3
"""Offline structural verification for CodeAssistAI's two-module pipeline."""
from pathlib import Path
import json
import re
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
errors=[]; warnings=[]

def read(path):
    return (ROOT/path).read_text(encoding="utf-8", errors="ignore")

def require(cond, msg):
    if not cond: errors.append(msg)

# CI
workflow=read(Path('.github/workflows/android-build.yml'))
require('android-actions/setup-android@v4' in workflow, 'CI must use setup-android@v4')
require('accept-android-sdk-licenses: true' in workflow, 'CI must enable setup-android non-interactive license acceptance')
require('log-accepted-android-sdk-licenses: false' in workflow, 'CI should suppress license text output')
require('sdkmanager' in workflow and '--install' in workflow and '--licenses' not in workflow, 'CI must install SDK packages without invoking the interactive sdkmanager --licenses flow')
require('cmdline-tools-version: 15859902' in workflow, 'CI command-line tools must be pinned to 22.0/15859902')
require('platforms;android-34' in workflow and 'build-tools;34.0.0' in workflow and 'platform-tools' in workflow, 'CI exact SDK packages missing')
require('./gradlew --no-daemon --stacktrace testDebugUnitTest' in workflow, 'CI unit-test task missing')
require('./gradlew --no-daemon --stacktrace lintDebug' in workflow, 'CI lint task missing')
require('./gradlew --no-daemon --stacktrace assembleDebug' in workflow, 'CI debug build task missing')
require('./gradlew --no-daemon --stacktrace assembleRelease' in workflow, 'CI release build task missing')
require('actions/cache@v4' in workflow and 'actions/upload-artifact@v4' in workflow, 'CI artifact/cache actions missing')
# Ensure no second workflow silently reintroduces the failing setup.
for wf in (ROOT/'.github/workflows').rglob('*.yml'):
    txt=wf.read_text(encoding='utf-8',errors='ignore')
    require('android-actions/setup-android@v3' not in txt, f'old setup-android@v3 remains in {wf.relative_to(ROOT)}')
    
# Toolchain
root_build=read(Path('build.gradle')); wrap=read(Path('gradle/wrapper/gradle-wrapper.properties'))
require("com.android.application' version '8.5.2'" in root_build, 'AGP 8.5.2 missing')
require("org.jetbrains.kotlin.android' version '2.4.20'" in root_build, 'KGP 2.4.20 missing')
require('gradle-8.7-bin.zip' in wrap, 'Gradle 8.7 wrapper missing')
require('.kotlin/' in read(Path('.gitignore')), '.kotlin/ must be ignored')

# Runtime
build=read(Path('app/build.gradle'))
require('com.google.ai.edge.litertlm:litertlm-android:0.17.1' in build, 'LiteRT-LM dependency missing')
require('dev.ffmpegkit-maintained:llama-android:0.1.1' in build, 'GGUF Coder dependency missing')
require('tasks-genai' not in build and 'LlmInference' not in ''.join(p.read_text(errors='ignore') for p in (ROOT/'app/src/main').rglob('*.kt')), 'legacy MediaPipe runtime references remain')
runtime=read(Path('app/src/main/java/com/codeassist/ai/engine/Runtime.kt'))
require('Backend.CPU()' in runtime and 'Backend.GPU()' in runtime, 'LiteRT-LM backend constructors missing')
require('visionBackend = Backend.CPU()' in runtime, 'LiteRT-LM vision backend not configured')
require('initialize()' in runtime and 'createConversation()' in runtime and 'runtimeReady = true' in runtime and 'probe' in runtime, 'Gemma runtime readiness gate incomplete')
require('class LlamaCppRuntime' in runtime and 'Llama.loadModel' in runtime and 'Llama.complete' in runtime, 'GGUF Coder runtime incomplete')
ml=read(Path('app/src/main/java/com/codeassist/ai/engine/ModelLifecycle.kt'))
require('n.endsWith(".litertlm")' in ml and 'n.endsWith(".gguf")' in ml, 'slot-specific model extension checks missing')
require('LITERTLM' in ml and 'GGUF' in ml, 'model header validation missing')
require('verifyManifest(file, file.name, rec.format)' in ml, 'load path does not revalidate model container before runtime start')
require('if (!runtime.isRealModel)' in ml, 'LOADED state is not gated on real inference')

# Two-module handoff / pipeline
llm=read(Path('app/src/main/java/com/codeassist/ai/engine/Llm.kt'))
require('val primary = if (preferCoder) coder() else gemma()' in llm, 'strict coder selection missing')
rt=runtime
require('synchronized(handoffLock)' in rt and 'restoreGemmaBlocking' in rt, 'Gemma/Coder handoff protection missing')
pipeline=read(Path('app/src/main/java/com/codeassist/ai/engine/CodingPipeline.kt'))
for n in ['ImplementationContract','PatchGuard','CheckpointManager','ExecutionEngine','GemmaCodingPlanner.review','finalScan','safeExtract','provenance=source=','READ ONLY']:
    require(n in pipeline, f'pipeline invariant missing: {n}')
require('Delete operations are blocked' in pipeline, 'delete guard missing')
require('Unexpected changed paths after patch' in pipeline, 'final changed-path guard missing')
require('PDF_EXTRACT_ERROR' in pipeline, 'PDF extraction error evidence missing')

# Keyboard -> backend
models=read(Path('app/src/main/java/com/codeassist/ai/data/Models.kt'))
attach=read(Path('app/src/main/java/com/codeassist/ai/ui/AttachmentStore.kt'))
home=read(Path('app/src/main/java/com/codeassist/ai/home/HomeFragment.kt'))
composer=read(Path('app/src/main/java/com/codeassist/ai/ui/ComposerController.kt'))
require('localPath: String?' in models, 'staged attachment path missing')
require('MAX_FILE_BYTES' in attach and 'MAX_IMAGE_BYTES' in attach and 'cleanupStaged' in attach, 'attachment copy limits/cleanup missing')
require('stagedPaths' in home and 'composer.finishSend(false)' in home, 'attachment failure recovery missing')
require('startRun(text, stagedAttachments, c, branchOf)' in home and 'composer.clearAfterSend()' in home, 'send/run handoff missing')
require('m.attachments' in home, 'retry does not reuse attachments')
require('onSend(text, helper.current.toList())' in composer, 'composer send handoff missing')
require('if (chat == null && existingChat == null)' not in home, 'obsolete first-send view gate remains')
require('uiStillTargetsSend' in home and 'Persist the target chat/message/run first' in home, 'send lifecycle protection missing')
require('clearAfterSend' in composer and 'finishSend(success' in composer, 'composer state API missing')
execs=read(Path('app/src/main/java/com/codeassist/ai/engine/Executors.kt'))
require('val hasProjectArchive' in execs and 'CodingPipeline.run(run, ctx.copy(projectRoot = found), stop)' in execs, 'ZIP-only request is not allowed through pipeline before root detection')
require('Intent.CODING_TASK -> coding(run, r, ctx, stop, interrupt)' in execs, 'coding intents must never bypass the two-module coding pipeline')
require('Coder Helper is required for this code-only task' in execs and 'else {\n            Llm.generate(CODE_SYSTEM, prompt)' not in execs, 'Coder-unavailable path must not fall back to Gemma code generation')
require('Coder Helper could not complete this code-only task' in execs, 'coder failure message incorrectly falls back to Gemma readiness text')
require('helper crash -> keep code task failed' in read(Path('app/src/main/java/com/codeassist/ai/engine/Errors.kt')), 'helper crash still claims Gemma fallback')
require('Content.Text(prompt)' in runtime and 'Content.ImageFile(image.absolutePath)' in runtime and runtime.index('Content.Text(prompt)') < runtime.index('Content.ImageFile(image.absolutePath)'), 'multimodal content order must be text before media')

# Run state/persistence
orch=read(Path('app/src/main/java/com/codeassist/ai/engine/Orchestrator.kt'))
states=read(Path('app/src/main/java/com/codeassist/ai/engine/States.kt'))
store=read(Path('app/src/main/java/com/codeassist/ai/engine/EngineStore.kt'))
require('if (!resumed && run.state == RunState.STOP_REQUESTED)' in orch, 'queued stop handling missing')
require('RunState.QUEUED -> to == RunState.UNDERSTANDING || to == RunState.STOP_REQUESTED' in states, 'queued stop transition missing')
require('// Never consume a pending interruption before proving' in orch, 'resume interruption guard missing')
require('putIfAbsent' in orch and 'Duplicate execution suppressed' in orch, 'duplicate execution guard missing')
require('resume_mode' in store and 'snapshot["snapshot"]' in store or 'checkpoint["snapshot"]' in store, 'checkpoint state missing')
require('storeLock' in store, 'engine store synchronization missing')

# XML/JSON
for pth in (ROOT/'app/src/main/res').rglob('*.xml'):
    try: ET.parse(pth)
    except Exception as e: errors.append(f'invalid XML {pth.relative_to(ROOT)}: {e}')
for pth in ROOT.rglob('*.json'):
    if any(x in {'build','.gradle','node_modules','out','dist'} for x in pth.parts): continue
    try: json.loads(pth.read_text(encoding='utf-8',errors='ignore'))
    except Exception as e: errors.append(f'invalid JSON {pth.relative_to(ROOT)}: {e}')

manifest=read(Path('app/src/main/AndroidManifest.xml'))
require('android:name="libvndksupport.so" android:required="false"' in manifest and 'android:name="libOpenCL.so" android:required="false"' in manifest, 'optional LiteRT-LM GPU native libraries missing')
for fq in re.findall(r'android:name="([.A-Za-z0-9_]+)"', manifest):
    if fq.startswith('.'): fq='com.codeassist.ai'+fq
    if fq.startswith('com.'):
        kt=ROOT/'app/src/main/java'/Path(fq.replace('.','/')+'.kt')
        java=ROOT/'app/src/main/java'/Path(fq.replace('.','/')+'.java')
        require(kt.exists() or java.exists(), f'manifest class missing: {fq}')

if errors:
    print('FAIL')
    for e in errors: print('-',e)
    sys.exit(1)
print('PASS')
print(f'Kotlin sources inspected: {len(list((ROOT/"app/src/main").rglob("*.kt")))}')
print('CI workflow invariants: PASS')
print('LiteRT-LM + GGUF runtime wiring: PASS')
print('Keyboard → attachment staging → router → orchestrator → coding pipeline: PASS')
print('Implementation Contract + Patch Guard + checkpoint/review loop: PASS')
print('XML/JSON structural parse: PASS')
