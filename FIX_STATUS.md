# CodeAssistAI v2.2 — FIX STATUS / BEFORE→AFTER / SAFE-FIX REPORT

> **Single source of truth for this repair pass.** This file was created before the repair and then updated as each implementation item was completed.
>
> Status legend: `[x]` = implemented and source/static-verified; `[~]` = implemented but runtime/device/CI verification is still required here; `[ ]` = not completed.
>
> **Important verification rule:** this pass did not fake a successful Android build. The local environment cannot download the Gradle 8.7 distribution, so Android Gradle compilation/test/lint/packaging are recorded as externally verifiable rather than falsely marked PASS.

## 1. Repair scope

The repair is based on the attached research document **`MJ_2_Module_Coding_Pipeline_Research.pdf`**. Its required separation is Gemma = planning/analyzing/reviewing, Coder = code-only implementation, deterministic tools/validator = execution/truth checking, and checkpoints = recovery. The document also requires a structured Implementation Contract before Coder work. See the source document's role and contract sections.

The target workflow is:

```text
USER
  |
  v
INPUT ROUTER
  |
  +--> text
  +--> image
  +--> PDF
  +--> ZIP
  +--> code
  +--> log
  +--> reference project
  |
  v
WORKSPACE / PROJECT MANAGER
  |
  +--> safe extraction
  +--> project detection
  +--> provenance
  +--> file tree
  +--> Git/checkpoints
  +--> indexes
  |
  v
PROJECT INTELLIGENCE
  |
  +--> search / grep
  +--> symbol index / LSP when available
  +--> dependency scan
  +--> baseline build/test evidence
  +--> project instructions
  |
  v
GEMMA = PLAN + ANALYZE + REVIEW
  |
  v
IMPLEMENTATION CONTRACT
  |
  v
CODER = CODE ONLY
  |
  v
PATCH GUARD
  |
  v
APPLY PATCH
  |
  v
EXECUTION / VALIDATION
  |
  +--> compile/build when executable in the selected environment
  +--> tests
  +--> lint
  +--> static analysis
  +--> runtime checks
  |
  v
GEMMA REVIEW
  |
  +--> FAIL -> correction plan -> CODER -> re-test
  |
  +--> PASS -> final scan
  |
  v
FINAL ZIP / DELIVERY
```

The separation above follows the attached PDF's complete recommended pipeline, including Input Router, Workspace Manager, Project Intelligence, Gemma contract generation, Coder, Patch Guard, execution/validation, review, final scan and delivery.

---

# 2. Problem register — BEFORE → AFTER

## Runtime / model loading

### P01 — Gemma 4 E4B runtime mismatch
- **Before:** `[x]` Problem confirmed from source + screen.
- **Symptom:** `LOADED` + `Real inference: NOT active` + native `RET_CHECK / model Error building tflit`.
- **Before cause:** `.litertlm` Gemma was wired to the old MediaPipe `tasks-genai`/`LlmInference` path.
- **After:** `[x]` Real Gemma runtime is isolated in `LiteRtLmRuntime` and the project dependency is LiteRT-LM.
- **Safe fix:** Runtime selection is behind `GemmaRuntime` + `RuntimeFactory`; UI/lifecycle call sites keep using the same lifecycle entry points.
- **Runtime verification:** `[~]` Device inference still needs one real import/load/probe on the target phone.

### P02 — `.litertlm` extension was lost during import
- **Before:** imported model could be stored as `$modelId.model`.
- **After:** `[x]` validated extension is preserved; Gemma is stored as `.litertlm`, Coder Helper as `.gguf` or `.litertlm`.
- **Safe fix:** import uses temporary `.importing` storage, validates bytes, then replaces the final file only after the copy is complete. A previous file is staged as `.previous` until replacement succeeds.

### P03 — Failed real runtime was marked `LOADED`
- **Before:** runtime init could fail but lifecycle state could still become `LOADED`.
- **After:** `[x]` `LOADED` is written only after `runtime.load()` and `runtime.isRealModel == true`. Failure becomes `ERROR` and preserves the actual reason.
- **Safe fix:** same `ModelState` enum/UI contract; only the transition gate was hardened.

### P04 — `ERROR` state guard was inconsistent
- **Before:** `canLoad(ERROR)` and blocking behavior were inconsistent.
- **After:** `[x]` `ERROR` is recoverable for load/re-import/delete paths, and retry is normalized before loading.
- **Safe fix:** pure guard logic plus lifecycle retry handling; no UI navigation contract changed.

### P18 — GGUF was accepted without a matching runtime
- **Before:** file validation could accept `.gguf` even without a runnable GGUF engine.
- **After:** `[x]` runtime format is explicit: LiteRT-LM handles `.litertlm`; `LlamaCppRuntime` handles `.gguf`; unsupported formats never become `LOADED`.
- **Safe fix:** format check happens before the real runtime is attached.

### P23 — Persisted `LOADED` state could survive process death without a native handle
- **Before:** Android process recreation could leave SharedPreferences saying `LOADED` while `RuntimeFactory` was empty.
- **After:** `[x]` startup reconciliation resets stale `LOADED`/`BUSY`/transitional states to `IMPORTED_UNLOADED` (or `NOT_IMPORTED` if the file is gone), then a real load/probe is required again.
- **Safe fix:** no model bytes are deleted; only stale runtime state is corrected.

### P24 — Coder mode could silently fall back to Gemma
- **Before:** `preferCoder=true` still selected Gemma when Coder was unavailable.
- **After:** `[x]` Coder mode is strict: `preferCoder=true` selects only the Coder Helper. No Gemma fallback is allowed for implementation generation.
- **Safe fix:** preserves Gemma's planner/reviewer role and prevents model-role leakage.

### P25 — Coder load failure did not report Gemma restore failure
- **Before:** a failed Coder load attempted to restore Gemma but did not surface a restore failure clearly.
- **After:** `[x]` handoff reports both failures when applicable and aborts safely rather than pretending the system is ready.

---

# 3. Two-module architecture fixes

### P05 — Implementation Contract missing
- **Before:** Coder could receive broad natural-language context.
- **After:** `[x]` added `ImplementationContract` with:
  - `task_id`
  - `mode`
  - `goal`
  - `requirements`
  - `acceptance_criteria`
  - `files_allowed_to_modify`
  - `files_to_create`
  - `protected_files`
  - `relevant_symbols`
  - `architecture_decisions`
  - `dependencies`
  - `test_plan`
  - `constraints`
  - `source_provenance`
- **Safe fix:** Coder receives only contract-authorized files and bounded context.

### P17 — Gemma/Coder handoff was not guaranteed
- **Before:** no single failure-aware coordinator guaranteed the full unload/load/reload order.
- **After:** `[x]` `CoderHelperManager` uses a single synchronized handoff path:

```text
Gemma READY
   |
   v
safe Gemma unload (only when idle)
   |
   v
Coder load + real probe
   |
   v
Coder BUSY
   |
   v
Coder finish / error
   |
   v
Gemma restore + real probe
   |
   v
Coder release
```

- **Safe fix:** Gemma is never unloaded while busy; Coder is never released before the requested Gemma restoration step completes/fails explicitly.

### Role lock
- **Gemma:** WHAT + WHY + WHERE + HOW TO VERIFY.
- **Coder:** WRITE THE REQUIRED CODE / PATCH only.
- **Coder restrictions:** no independent redesign, no web research, no unrelated file edits, no unauthorized deletes, no random dependencies, no requirement changes, no GitHub push.
- **Status:** `[x]` enforced by prompt, contract, strict Coder selection and patch guard.

---

# 4. Patch safety / non-destructive editing

### P06 — Patch Guard missing
- **Before:** model-generated file output could reach write operations without a complete contract-aware preflight.
- **After:** `[x]` `PatchGuard.validate()` validates the **whole proposed patch before any write**.
- **Checks:** allowed paths, protected paths, path traversal, duplicate paths, file-size bound, conflict markers, JSON/XML syntax shape, dependency additions.
- **Safe fix:** all blocks must pass first; only then are files written atomically.

### P07 — Delete protection incomplete
- **Before:** generic delete tool was too permissive for an AI coding workflow.
- **After:** `[x]` delete is blocked by default. Explicit `allow_delete=true`, reason and impact are required by the file tool; model-generated Coder patch output is also rejected when it contains delete directives.
- **Safe fix:** deletion cannot happen as an accidental side effect of an implementation patch.

### Protected paths
- `[x]` `.git`, `.mj`, generated/build directories, wrapper files and `local.properties` remain protected from model-driven project edits.
- `[x]` Model storage and credential state are outside the normal project-write root.

### Atomic file mutation
- `[x]` accepted Coder blocks are written through temporary `.mjpatch` files and moved into place only after the entire patch has passed validation.
- `[x]` temporary files are cleaned in `finally` blocks.

---

# 5. Existing-project and workspace intelligence

### P08 — Existing-project baseline incomplete
- **After:** `[x]` before model editing the pipeline collects:
  - project stack
  - file tree
  - source index
  - Git status
  - current Git diff names
  - README/project instructions
  - build files
  - dependencies
  - existing tests
  - LSP availability status
  - deterministic baseline validation evidence
- **Safe fix:** baseline is read-only; it happens before the initial mutation checkpoint.

### P09 — ZIP scan/index incomplete
- **After:** `[x]` ZIP flow now performs safe extraction, project-root detection, source indexing and scan summary with file count, extensions, suspicious binaries, generated/ignored paths and largest files.
- **Safety:** zip-slip protection, 50,000-entry limit, 1 GiB extracted-size limit, temporary staging before promotion to the destination.
- **Ignored/generated directories:** `.git`, `.gradle`, `build`, `node_modules`, `dist`, `out`, `target`, `venv`, `.venv`, `__pycache__`, test/build caches, coverage, `bin`, `obj`, `.scannerwork` and related generated paths.

### P13 — Reference project isolation incomplete
- **After:** `[x]` reference ZIPs are stored in a separate read-only tree and are compared against the target without copying files into the target.
- **Comparison output:** same file patterns, reference-only patterns, target-only patterns, stack comparison and compatibility classification (`COMPATIBLE`, `COMPATIBLE_WITH_DIFFERENT_LAYOUT`, `INCOMPATIBLE_STACKS`, `UNKNOWN`).
- **Safe fix:** Patch Guard only authorizes target-project paths; reference roots are never patch destinations.

### P14 — LSP / structured code intelligence
- **After:** `[x]` `LspBridge` provides a trusted-project configuration path plus availability probing and a deterministic symbol-index/reference fallback when a language server is unavailable.
- **Important limitation:** a language server binary is not bundled for every language; the app uses the LSP integration point only when the project explicitly provides a trusted `lsp_command`. The fallback keeps navigation/search deterministic when no LSP is available.

---

# 6. Image / PDF / log input fixes

### P10 — Image workflow not wired to structured Gemma vision analysis
- **After:** `[x]` image attachments can be sent through `generateWithImage()` with a structured UI-analysis prompt covering layout, components, spacing, typography, colors, icons, states, interactions and implementation requirements.
- **Safe fix:** analysis is read-only; code mutation still requires the Implementation Contract and Patch Guard.
- **Device vision verification:** `[~]` requires a real multimodal Gemma model on the phone.

### P11 — PDF workflow missing page-image analysis/provenance
- **After:** `[x]` PDF pipeline now:
  - extracts text when possible
  - derives best-effort headings/table-like/code-like evidence
  - renders up to 12 pages into run-local images
  - records source/page provenance
  - passes the first rendered pages to Gemma vision when available
- **Safe fix:** source PDF remains read-only and rendered evidence lives under the run workspace, not the target project.
- **Device PDF/vision verification:** `[~]` runtime not executed in this environment.

### P12 — Log/error workflow only partially structured
- **After:** `[x]` log parsing records errors, warnings, stack lines, timestamps, threads, processes and repeated failures.
- **Safe fix:** log analysis is read-only and its result becomes planning evidence rather than executable instructions.

### P26 — Untrusted-input boundary
- **Status:** `[x]` existing `InjectionGuard` remains the boundary for untrusted web/file content; PDF/image/log evidence is described to the model as source data, not higher-priority instructions.

---

# 7. Deterministic execution / validation

### P15 — Validation was not deterministic enough
- **After:** `[x]` added `ExecutionEngine` with controlled project-stack validation.
- **Non-Android stacks:** safe standard commands are selected for Python, Rust, Go and Node/TypeScript when the project/toolchain allows them.
- **Android:** on-device pipeline performs structural/static Gradle checks rather than starting a Gradle daemon, matching the current mobile delivery policy. Real Android unit-test/lint/assemble verification is delegated to CI/external SDK verification.
- **Truth rule:** a model statement is never treated as proof of a successful command.

### P26 — CI lint was allowed to fail silently
- **Before:** workflow used `lintDebug --continue || true`.
- **After:** `[x]` CI now fails on unit-test/lint/build errors instead of masking them.
- **CI order:** structural verifier -> unit tests -> lint -> debug build -> unsigned release build -> artifact upload.

---

# 8. Checkpoints / rollback / final delivery

### P16 — Checkpoints were logical markers, not real rollback
- **After:** `[x]` `CheckpointManager` stores filesystem snapshots + SHA-256 manifest.
- **Checkpoint sequence:**
  - `initial`
  - `plan-approved`
  - `phase-1`
  - `post-coder-*`
  - `post-build-*`
  - `post-test-*`
  - `phase-2`
  - `final`
- **Safe fix:** snapshots exclude generated/system directories; restore deletes only restorable workspace content and copies the checkpoint back.

### P19 — Final ZIP was not gated
- **After:** `[x]` final delivery is reached only after:

```text
Coder patch
   ↓
Patch Guard
   ↓
Apply
   ↓
Deterministic validation
   ↓
Gemma review
   ↓
PASS
   ↓
Final scan
   ↓
Final checkpoint
   ↓
ZIP
```

- **Failure behavior:** bounded retries restore `initial`; failed patches are not left behind as the final state.
- **Package report:** `[x]` external **Delivery Manifest** is stored outside the project so packaging does not dirty the user's source tree.

---

# 9. Documentation / bootstrap / compile hygiene

### P20 — README behavior did not match backend guarantees
- **After:** `[x]` README now describes the guarded two-module pipeline and does not claim the app is using an unguarded direct `file_write` model path.

### P21 — Central Engine bootstrap reference was missing
- **Before:** source referenced `Engine.init(...)` without an implementation.
- **After:** `[x]` added `Engine.init(Context)` to initialize store, sandbox, credential store, tool registry, model lifecycle and run recovery.

### P22 — Missing `SkillBuildActivity` / `GlowArcView` classes
- **Before:** manifest/layout/project dialog referenced classes that were absent from the source tree.
- **After:** `[x]` added both classes so those source references resolve.

### Additional compile hygiene
- `[x]` fixed the Builders call to the actual `step()` signature.
- `[x]` fixed Coder handoff result initialization so the `finally` branch cannot reference an uninitialized result.
- `[x]` checked for legacy MediaPipe references in `app/src/main`: none remain.

---

# 10. Implementation order — final state

- [x] A. Create repair/status document.
- [x] B. Replace incorrect Gemma runtime with LiteRT-LM integration.
- [x] C. Preserve `.litertlm` model files and make runtime/state truthful.
- [x] D. Fix model state guards, startup reconciliation and error handling.
- [x] E. Implement safe Gemma ↔ Coder handoff.
- [x] F. Add Implementation Contract and contract persistence.
- [x] G. Add Patch Guard and delete protection.
- [x] H. Add project baseline/index/provenance/reference isolation.
- [x] I. Add ZIP/PDF/image/log intake pieces.
- [x] J. Add LSP integration point + deterministic symbol/reference fallback.
- [x] K. Add deterministic execution/validation engine.
- [x] L. Add real filesystem checkpoints/rollback.
- [x] M. Add Gemma review -> Coder repair -> re-test loop.
- [x] N. Gate final scan/ZIP on PASS.
- [x] O. Update README, CI and this status file.
- [x] P. Run offline structural/static verification.
- [~] Q. Perform target-phone real Gemma/Coder runtime verification.
- [~] R. Execute GitHub Actions Android unit-test/lint/build verification.

---

# 11. Files changed in this repair pass

```text
.github/workflows/android-build.yml
README.md
FIX_STATUS.md
app/build.gradle
app/src/main/java/com/codeassist/ai/build/GlowArcView.kt                 (new)
app/src/main/java/com/codeassist/ai/build/SkillBuildActivity.kt          (new)
app/src/main/java/com/codeassist/ai/engine/Builders.kt
app/src/main/java/com/codeassist/ai/engine/CodingPipeline.kt
app/src/main/java/com/codeassist/ai/engine/Engine.kt                    (new)
app/src/main/java/com/codeassist/ai/engine/EngineStore.kt
app/src/main/java/com/codeassist/ai/engine/Executors.kt
app/src/main/java/com/codeassist/ai/engine/Llm.kt
app/src/main/java/com/codeassist/ai/engine/ModelLifecycle.kt
app/src/main/java/com/codeassist/ai/engine/PipelineTools.kt
app/src/main/java/com/codeassist/ai/engine/Runtime.kt
app/src/main/java/com/codeassist/ai/engine/Security.kt
app/src/main/java/com/codeassist/ai/engine/SelfTest.kt
app/src/main/java/com/codeassist/ai/engine/Skills.kt
app/src/main/java/com/codeassist/ai/engine/States.kt
app/src/main/java/com/codeassist/ai/engine/ToolDocs.kt
app/src/main/java/com/codeassist/ai/engine/ToolImpls.kt
app/src/main/java/com/codeassist/ai/engine/ToolProject.kt
app/src/main/java/com/codeassist/ai/models/ModelsFragment.kt
app/src/main/res/layout/fragment_models.xml
app/src/test/java/com/codeassist/ai/CodingPipelineTest.kt
app/src/test/java/com/codeassist/ai/CodingPipelineTest.kt
 tools/verify_pipeline.py                                                (new)
```

> The exact project diff remains available from the fixed ZIP. The list above is the implementation surface intentionally touched; unrelated UI/navigation code was not redesigned.

---

# 12. Verification performed

## Offline structural verification

**Command:**

```text
python3 tools/verify_pipeline.py
```

**Result:** `PASS`

Checks passed:

```text
Kotlin sources inspected: 42
Legacy MediaPipe runtime references: 0
LiteRT-LM + GGUF runtime wiring: present
Implementation Contract + Patch Guard + checkpoint + review loop: present
XML/JSON structural parse: passed
```

## Resource/config parsing

- `[x]` Android XML resource files parse successfully.
- `[x]` JSON files in the source tree parse successfully.
- `[x]` manifest-referenced project Activity classes were checked; the previously missing classes were added.

## Kotlin syntax-oriented check

A raw `kotlinc` source pass cannot be a complete Android build because AndroidX, Gson, LiteRT-LM and llama runtime dependencies are not on the bare JVM classpath. The run was therefore used only as a syntax/error hygiene signal.

- `[x]` no syntax-oriented `expecting`, `unexpected tokens`, `illegal escape` or `unclosed` diagnostics were found.
- `[x]` the one independent Builders signature error was fixed and rechecked.
- `[~]` unresolved Android/third-party symbols are expected from the dependency-free JVM invocation and are not treated as successful Android compilation.

### P27 — GitHub Actions Android SDK license prompt / setup failure
- **Before:** `[x]` Failure confirmed from CI log: `android-actions/setup-android@v3` reached `sdkmanager --licenses` and stopped at an interactive prompt with `6 of 7 SDK package licenses not accepted`.
- **After:** `[x]` Workflow now uses `android-actions/setup-android@v4`; its automatic license acceptance is disabled so the workflow controls the input explicitly.
- **Fix:** `yes | sdkmanager --licenses` accepts licenses non-interactively, then the exact required SDK packages (`platform-tools`, `platforms;android-34`, `build-tools;34.0.0`) are installed explicitly.
- **Additional guard:** `[x]` a toolchain verification step checks Java, `sdkmanager`, `adb`, Android 34 platform and `aapt2` before Gradle starts.
- **Why this fixes the observed failure:** the CI job no longer depends on an interactive `sdkmanager --licenses` prompt. The current `setup-android` action documents explicit license controls and v4 is the current action line.
- **Remote CI verification:** `[~]` requires the workflow to be pushed/run on GitHub; this environment cannot execute GitHub-hosted Actions.

## Android Gradle verification

Attempted:

```text
./gradlew :app:compileDebugKotlin --offline --stacktrace
```

Result: **BLOCKED BY ENVIRONMENT** because the Gradle wrapper distribution was not present locally and the environment could not resolve `services.gradle.org`.

Therefore this file does **not** claim Android build/test/lint success.

## Target-device checks still required

```text
1. Import real Gemma 4 E4B IT .litertlm
2. Load
3. Confirm Models screen says real inference ON only after probe
4. Send a normal prompt and receive a real Gemma response
5. Import Coder Helper GGUF
6. Run a coding task
7. Observe Gemma -> Coder -> Gemma handoff
8. Trigger a guarded patch and verify allowed files only
9. Verify validation/review/retry/rollback behavior
10. Download final ZIP and inspect Delivery Manifest
```

---

# 13. How each fix avoids breaking existing work

### Existing files
No automatic destructive cleanup was introduced. Existing project content is protected by path checks and an initial checkpoint before the first Coder mutation.

### Existing model import
Old legacy `$modelId.model` records are migrated to the slot-appropriate extension instead of being deleted. Imported bytes are hashed and integrity-checked.

### Existing UI
The screen remains the same model/lifecycle surface. The important UI correction is that `LOADED` is now reserved for a genuinely working runtime. No new desktop-style UI or unrelated controls were added as part of this repair.

### Model handoff
The handoff is serialized by one lock and uses lifecycle APIs instead of direct native-handle manipulation from UI code. A Coder failure cannot silently cause Gemma to disappear from the state machine.

### Code edits
The Coder cannot mutate arbitrary files. Contract -> Patch Guard -> atomic apply is the mandatory path. On failed validation/review, the initial snapshot is restored before the next attempt.

### Reference inputs
Reference projects stay read-only. Their patterns become evidence for Gemma; they are not copied wholesale over the current project.

### Generated/build folders
Normal indexing and checkpoints ignore build/generated caches so they do not pollute context or rollback.

### Delivery
The final ZIP is created outside the source tree, and its manifest is also outside the source tree, so delivery bookkeeping does not create unrelated source changes.

---

# 14. Final architectural state

```text
                    +----------------------+
USER / ATTACHMENTS ->|    INPUT ROUTER      |
                    +----------+-----------+
                               |
                               v
                    +----------------------+
                    | WORKSPACE MANAGER    |
                    | safe ZIP / indexes   |
                    | provenance / refs    |
                    +----------+-----------+
                               |
                               v
                    +----------------------+
                    | PROJECT INTELLIGENCE |
                    | search / symbols     |
                    | deps / baseline      |
                    | instructions / LSP   |
                    +----------+-----------+
                               |
                               v
                    +----------------------+
                    |       GEMMA          |
                    | plan / analyze /     |
                    | decide / review      |
                    +----------+-----------+
                               |
                               v
                    +----------------------+
                    | IMPLEMENTATION       |
                    | CONTRACT             |
                    +----------+-----------+
                               |
                               v
                    +----------------------+
                    |       CODER          |
                    |     CODE ONLY        |
                    +----------+-----------+
                               |
                               v
                    +----------------------+
                    |     PATCH GUARD      |
                    +----------+-----------+
                               |
                               v
                    +----------------------+
                    | ATOMIC APPLY         |
                    +----------+-----------+
                               |
                               v
                    +----------------------+
                    | EXECUTION /         |
                    | VALIDATION          |
                    +----------+-----------+
                               |
                               v
                    +----------------------+
                    |   GEMMA REVIEW       |
                    +----+-------------+---+
                         |             |
                      FAIL             PASS
                         |             |
                         v             v
                   CORRECTION       FINAL SCAN
                       |               |
                       v               v
                    CODER             ZIP
```

## Final role definition

```text
GEMMA       = Brain / Planner / Analyzer / Reviewer
CODER       = Code Writer / Patch Generator
TOOLS       = Eyes + Hands + Execution
VALIDATOR   = Truth Checker
CHECKPOINT  = Recovery
SKILLS      = On-demand Expertise
```

This is the final design target implemented in source. The remaining `[~]` items are verification events that require the actual Android/Gradle/device environment; they are intentionally not represented as successful build/runtime results in this file.
