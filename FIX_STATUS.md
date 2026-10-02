# CodeAssistAI v2.2 — Full Re-Audit / Fix Status

Date: 2026-10-02
Scope: keyboard/input → attachment staging → intent router → orchestrator → Gemma → Implementation Contract → Coder Helper → Patch Guard → apply → validation → review → ZIP, plus Android GitHub Actions workflow.
Basis: `MJ_2_Module_Coding_Pipeline_Research.pdf` and current source audit.

Legend: [x] fixed in this source ZIP, [~] locally inspected but requires remote/device verification, [ ] not part of this source.

## Critical issues fixed

| ID | Problem found | Status | Fix implemented |
|---|---|---|---|
| P01 | GitHub Actions used `setup-android@v3` / interactive license prompt in the failing run | [x] | CI pinned to `setup-android@v4`, automatic non-interactive license acceptance enabled; no `sdkmanager --licenses` prompt remains |
| P02 | CI disabled license acceptance and then invoked an interactive license command | [x] | Removed the manual interactive step; exact SDK packages are installed by the action |
| P03 | Gradle/Kotlin toolchain mismatch risk: AGP 8.5.2 + KGP 2.4.20 with deprecated Gradle 8.7 | [x] | KGP 2.4.20 retained; wrapper updated to Gradle 8.14.4 |
| P04 | Gemma `.litertlm` needed LiteRT-LM runtime | [x] | Gemma runtime uses LiteRT-LM `Engine`/`Conversation` |
| P05 | LiteRT-LM backend constructor was using outdated object syntax | [x] | Uses `Backend.CPU()` / `Backend.GPU()` |
| P06 | Imported Gemma could be treated as loaded without real inference | [x] | `LOADED` only after native engine + end-to-end probe; failures become ERROR |
| P07 | Legacy `.model` filename could break runtime | [x] | Legacy filename migration preserves bytes and restores `.litertlm` / `.gguf` extension |
| P08 | LiteRT-LM container was not header-validated | [x] | Import validation checks `LITERTLM` magic; GGUF checks `GGUF` magic |
| P09 | Coder slot could accept a non-coding LiteRT-LM model | [x] | Coder Helper slot restricted to GGUF |
| P10 | Coder could fall back to Gemma | [x] | Strict `preferCoder` path; no Gemma fallback |
| P11 | Gemma → Coder → Gemma handoff could release Coder before Gemma recovery | [x] | Handoff restores Gemma first, then unloads Coder |
| P12 | Vague Gemma → Coder handoff | [x] | Structured Implementation Contract is mandatory |
| P13 | Missing Patch Guard | [x] | Allowed/protected/deletion/dependency/path/syntax checks precede every write |
| P14 | Delete protection incomplete | [x] | Protected paths + delete blocking before patch application |
| P15 | Final changed-path scan could miss unrelated edits | [x] | Final scan compares filesystem changes with contract allowlist |
| P16 | Existing-project baseline weak | [x] | Tree, Git status/diff, instructions, dependencies, tests, stack, LSP status and structural baseline captured |
| P17 | ZIP extraction needed safe boundary | [x] | Staging extraction + zip-slip protection + entry/size limits |
| P18 | Reference ZIP could overwrite target | [x] | Reference roots are separate/read-only inputs |
| P19 | PDF visual/provenance path incomplete | [x] | Structured extraction + rendered page images + source/page provenance + Gemma visual analysis |
| P20 | PDF extraction failures were silently swallowed | [x] | Explicit `PDF_EXTRACT_ERROR` evidence recorded |
| P21 | Image input did not have a structured vision specification path | [x] | Gemma image analysis asks for layout/components/spacing/typography/colors/icons/states/interactions and implementation requirements |
| P22 | LSP was absent as a usable integration point | [x] | Trusted configured-LSP availability probe plus deterministic symbol-index fallback |
| P23 | Model/state persistence races | [x] | EngineStore compound operations synchronized |
| P24 | Fake run snapshot marked every new run `valid` | [x] | New runs start with a logical checkpoint; coding runs switch to a real filesystem checkpoint |
| P25 | Stop could jump illegally from PLANNING/RUNNING directly to PAUSED | [x] | Explicit `STOP_REQUESTED → PAUSED` transition |
| P26 | Pause resolution could clear interruption before resume validity was checked | [x] | Resume/discard guards are checked first |
| P27 | Duplicate execution could start two workers for one run | [x] | `putIfAbsent` run-handle guard |
| P28 | Composer could clear user text before attachment staging succeeded | [x] | Composer clears only after staging + run creation path succeeds; failure restores sendability |
| P29 | Attachments were copied without enforcing byte limits at the actual backend copy | [x] | Bounded streaming copy with 10 MB image / 25 MB file limits and cleanup |
| P30 | Retry could drop attachments | [x] | Retry reuses stored staged local paths |
| P31 | Ordinary “batao kya” / “dekhkar” could over-route to web research | [x] | Web intent triggers made explicit |
| P32 | Validation could be mistaken for a claimed real build | [x] | Validation evidence distinguishes structural checks from skipped Android build; no fake build claim in the app pipeline |

## Required workflow now

```text
Keyboard / user message
        ↓
Composer validation
        ↓
Attachment staging (bounded, localPath)
        ↓
INPUT ROUTER
        ↓
WORKSPACE MANAGER
        ├── safe ZIP extraction
        ├── file tree / index
        ├── Git status + diff
        ├── project instructions
        ├── PDF text + page images + provenance
        ├── image vision evidence
        └── reference project READ ONLY
        ↓
PROJECT INTELLIGENCE
        ├── search / grep
        ├── symbols / LSP fallback
        ├── dependency scan
        └── baseline evidence
        ↓
GEMMA
        ├── understand
        ├── inspect
        ├── research only when needed
        ├── architecture
        ├── target files
        ├── constraints
        └── Implementation Contract
        ↓
CODER HELPER (code only)
        ↓
PATCH GUARD
        ├── allowed files
        ├── protected files
        ├── path safety
        ├── deletion block
        ├── dependency guard
        └── syntax guard
        ↓
APPLY ATOMIC PATCH
        ↓
EXECUTION / VALIDATION
        ├── static checks
        ├── structural tests
        ├── project commands where safely runnable
        └── Android build delegated to GitHub CI for this source-only delivery mode
        ↓
GEMMA REVIEW
        ↓
FAIL → rollback → correction contract → Coder → re-test
PASS → final scan → ZIP
```

## GitHub Actions license fix

The failing log showed an interactive `sdkmanager --licenses` prompt. The repaired workflow does not disable license acceptance and does not invoke a manual interactive license command. It uses `android-actions/setup-android@v4` with automatic license acceptance enabled, then installs the exact Android 34 SDK/platform/build-tools package set.

Current action documentation states that `setup-android` accepts Android SDK licenses and that `accept-android-sdk-licenses` defaults to true; the maintained README also documents `@v4` and package installation. citeturn585029search0turn585029search1

The source aligns KGP 2.4.20 with AGP 8.5.2 and Gradle 8.14.4; Kotlin's current compatibility table lists AGP 8.5.2–9.3.1 and Gradle 7.6.3–9.7.0 for KGP 2.4.20. citeturn585029search2

## Gemma runtime verification basis

The current LiteRT-LM Kotlin API uses `EngineConfig(modelPath=..., backend=Backend.CPU())`, and the current `Engine` implementation initializes the native engine before creating a conversation. citeturn936360search1turn936360search2

The official Gemma 4 E4B LiteRT-LM model card says the deployment model is `.litertlm`, is about 3.66 GB, and loads vision/audio models as needed; the repository provides `gemma-4-E4B-it.litertlm`. citeturn371569search0turn371569search1

## Verification status

- Source/structural audit: [x]
- Workflow static audit: [x]
- ZIP integrity: [x]
- Actual GitHub-hosted runner execution: [~] not executable from this local environment; must be confirmed by pushing this exact workflow and rerunning Actions.
- Actual Android device Gemma E4B native inference: [~] requires the real `.litertlm` model on the device.
- No fake remote-CI PASS is recorded.

## Safe-fix method

The fixes are deliberately layered: preserve existing public UI, stage attachments before mutating chat state, preserve old model files until replacements validate, reject unauthorized patches before any write, keep reference projects isolated, checkpoint before coding, rollback failed coding attempts, and only mark a native model `LOADED` after a real inference probe.

This implements the separation described in the supplied research PDF: Gemma = WHAT/WHY/WHERE/HOW TO VERIFY, Coder = code-only, tools = execution, validator = truth checker, checkpoint = recovery, skills = on-demand expertise.

## Re-audit correction pass — 2026-10-02

### P33 — Previous CI license repair itself contained the failure
[x] Corrected. The previous workflow had `accept-android-sdk-licenses: false` and a manual `sdkmanager --licenses` command, which directly reproduced the interactive `Accept? (y/N)` failure shown by the user. The workflow now uses `accept-android-sdk-licenses: true` and contains no manual license prompt command.

### P34 — Structural verifier was asserting the broken CI behavior
[x] Corrected. `tools/verify_pipeline.py` now requires automatic license acceptance and rejects the manual `--licenses` flow.

### P35 — Non-Latin keyboard text was stripped by router normalization
[x] Corrected. Router normalization now preserves Unicode letters/numbers, including Hindi text, before intent matching.

### P36 — Workspace chat composer bypassed the backend run pipeline
[x] Corrected. WorkspaceActivity now creates the same Orchestrator run after staging/persisting the message instead of only saving the message and opening MainActivity.

### P37 — Workspace chat attachment picker used the wrong AttachmentHelper
[x] Corrected. Project-file uploads and chat-composer attachments now have separate helpers; the composer plus button opens the composer-owned helper.

### P38 — Workspace composer could remain stuck after a failed run start
[x] Corrected. Composer is cleared only after a run is successfully created; failure calls `finishSend(false)`.

### P39 — Workspace tree report duplicated the root line
[x] Corrected. Duplicate root output removed.

### CI verification state
[~] Remote GitHub Actions execution is still required. Local YAML + structural checks pass. No remote PASS is fabricated.

## Re-audit compile failure correction — GitHub log 2026-10-02

### P40 — HomeFragment missing java.io.File import
[x] Fixed. `HomeFragment.kt` uses `File(...)` in attachment staging and run creation; the import was missing, causing the exact `Unresolved reference 'File'` errors at lines 222, 226 and 296.

### P41 — WorkspaceActivity referenced nonexistent `composer`
[x] Fixed. The activity declares `composerController`; the plus-button handler was incorrectly attached to `composer`. It now attaches to `composerController`, removing the exact `Unresolved reference 'composer'` error at line 93.

### P42 — Deprecated Gradle 8.7 warning with Kotlin 2.4.20
[x] Fixed. Gradle wrapper updated from 8.7 to 8.14.4, matching the minimum Gradle version reported by the Kotlin plugin warning in the supplied CI log.

### Verification after P40–P42
[~] Local Android Gradle execution still requires the Gradle/dependency distribution network, which is unavailable in this environment. The exact compiler errors from the supplied CI log were corrected at source level. Static source scans show no remaining `File(...)` usages without `java.io.File` import in the affected HomeFragment and no remaining `composer.` reference in WorkspaceActivity.

### P43 — Manifest-referenced SkillBuildActivity was missing
[x] Fixed. The manifest and CreateProjectDialog referenced `com.codeassist.ai.build.SkillBuildActivity`, but the class was absent from the source tree. This would have become the next Android compilation failure after P40/P41. A real activity was restored using the existing `activity_skill_build.xml`; it prepares the coding/android skills and then opens the real project workspace without claiming that source code was built.

### P44 — SkillBuildActivity layout referenced missing GlowArcView
[x] Fixed. The existing `activity_skill_build.xml` referenced `com.codeassist.ai.build.GlowArcView`, but that custom view class was also absent. A lightweight Android `View` implementation was restored.

### P45 — CI verifier still expected the old Gradle 8.7 wrapper
[x] Fixed. `tools/verify_pipeline.py` now requires Gradle 8.14.4, matching the wrapper and the Kotlin 2.4.20 warning shown by the actual GitHub Actions compiler log.

### P40–P45 verification
[x] Exact reported Kotlin errors corrected.
[x] Manifest class scan: PASS.
[x] Custom XML view class scan: PASS.
[x] Offline structural pipeline verifier: PASS.
[~] Full Android compilation/unit tests/lint remain remote-runner verification because this environment cannot download the Gradle/dependency distributions.
