#!/usr/bin/env python3
"""Offline structural verification for CodeAssistAI's two-module pipeline.

This script intentionally does not build Android or execute model runtimes. It checks
high-risk wiring/invariants that can be verified without an Android SDK or model files.
"""
from pathlib import Path
import json
import re
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
errors = []
warnings = []


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8", errors="ignore")


def require(cond: bool, msg: str) -> None:
    if not cond:
        errors.append(msg)

# 1) Runtime/dependency invariants.
build = read("app/build.gradle")
require("com.google.ai.edge.litertlm:litertlm-android" in build,
        "LiteRT-LM Android dependency missing")
require("dev.ffmpegkit-maintained:llama-android" in build,
        "GGUF llama-android dependency missing")
require("tasks-genai" not in build and "mediaPipe" not in build.lower(),
        "legacy MediaPipe tasks-genai runtime still present in app/build.gradle")

main_sources = list((ROOT / "app/src/main").rglob("*.kt"))
legacy_refs = []
for p in main_sources:
    s = p.read_text(encoding="utf-8", errors="ignore")
    if "LlmInference" in s or "com.google.mediapipe" in s or "tasks-genai" in s:
        legacy_refs.append(str(p.relative_to(ROOT)))
require(not legacy_refs, f"legacy MediaPipe/LlmInference references remain: {legacy_refs}")

runtime = read("app/src/main/java/com/codeassist/ai/engine/Runtime.kt")
require("class LiteRtLmRuntime" in runtime, "LiteRT-LM runtime implementation missing")
require("class LlamaCppRuntime" in runtime, "GGUF Coder runtime implementation missing")
require("runtime.isRealModel" in read("app/src/main/java/com/codeassist/ai/engine/ModelLifecycle.kt"),
        "model lifecycle is not gated by real runtime readiness")

# 2) Strict two-module handoff.
llm = read("app/src/main/java/com/codeassist/ai/engine/Llm.kt")
require("val primary = if (preferCoder) coder() else gemma()" in llm,
        "Coder mode still has a Gemma fallback")
require("coderUnavailableReason" in llm, "Coder-specific unavailable diagnostics missing")
helper = runtime
require("synchronized(handoffLock)" in helper, "Coder handoff lock missing")
require("Gemma restored before Coder release" in helper, "Gemma-before-Coder release handoff invariant missing")

# 3) Model file/state invariants.
ml = read("app/src/main/java/com/codeassist/ai/engine/ModelLifecycle.kt")
require("endsWith(\".litertlm\")" in ml, "Gemma .litertlm validation missing")
require("endsWith(\".gguf\")" in ml, "Coder GGUF validation missing")
require(".model" in ml, "legacy .model migration path missing")
require("ModelState.LOADED" in ml and "runtime.isRealModel" in ml,
        "LOADED state is not visibly guarded by real inference readiness")

# 4) Pipeline safety invariants.
pipeline = read("app/src/main/java/com/codeassist/ai/engine/CodingPipeline.kt")
for needle in [
    "ImplementationContract", "PatchGuard", "CheckpointManager", "ExecutionEngine",
    "GemmaCodingPlanner.review", "finalScan", "safeExtract", "provenance=source=",
]:
    require(needle in pipeline, f"pipeline invariant missing: {needle}")
require("Delete operations are blocked" in pipeline, "delete guard missing")
require("Unexpected changed paths after patch" in pipeline, "final changed-path guard missing")
require("references" in pipeline and "READ ONLY" in pipeline, "reference-project isolation marker missing")

# 5) Resource/config XML + JSON parse.
for p in (ROOT / "app/src/main/res").rglob("*.xml"):
    try:
        ET.parse(p)
    except Exception as exc:
        errors.append(f"invalid XML: {p.relative_to(ROOT)}: {exc}")
for p in ROOT.rglob("*.json"):
    if any(part in {"build", ".gradle", "node_modules", "out", "dist"} for part in p.parts):
        continue
    try:
        json.loads(p.read_text(encoding="utf-8", errors="ignore"))
    except Exception as exc:
        errors.append(f"invalid JSON: {p.relative_to(ROOT)}: {exc}")

# 6) Required Android classes referenced by manifest/layout must exist.
manifest = read("app/src/main/AndroidManifest.xml")
for fq in re.findall(r'android:name="([.A-Za-z0-9_]+)"', manifest):
    if fq.startswith("."):
        fq = "com.codeassist.ai" + fq
    if fq.startswith("com."):
        rel = ROOT / "app/src/main/java" / Path(fq.replace(".", "/")).with_suffix(".kt")
        require(rel.exists(), f"manifest Activity class missing: {fq}")

# 7) Verify no model-generated code path writes directly in the project-edit executor.
executors = read("app/src/main/java/com/codeassist/ai/engine/Executors.kt")
require("CodingPipeline.run" in executors, "project editing is not routed through CodingPipeline")

if errors:
    print("FAIL")
    for e in errors:
        print("-", e)
    sys.exit(1)

print("PASS")
print(f"Kotlin sources inspected: {len(main_sources)}")
print("Legacy MediaPipe runtime references: 0")
print("LiteRT-LM + GGUF runtime wiring: present")
print("Implementation Contract + Patch Guard + checkpoint + review loop: present")
print("XML/JSON structural parse: passed")
if warnings:
    print("Warnings:")
    for w in warnings:
        print("-", w)
