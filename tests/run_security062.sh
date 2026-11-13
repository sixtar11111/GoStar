#!/usr/bin/env bash
set -euo pipefail
task_root="$(cd -- "$(dirname -- "$0")/.." && pwd)"
task_output="${1:-/tmp/gostar-security062}"
task_jar="${2:-$task_root/build/plugin/local-starccm-assistant.jar}"
task_star="${STARCCM_HOME:-/opt/Siemens/19.04.009-R8/STAR-CCM+19.04.009-R8}"
task_jdk="${STARCCM_JDK:-$(dirname -- "$task_star")/jdk/linux-x86_64/jdk21.0.1}"
task_platform="$task_star/star/lib/java/platform"
mkdir -p "$task_output/classes"
task_cp="$task_jar:$task_platform/lib/*:$task_platform/modules/*:$task_platform/modules/ext/*"
"$task_jdk/bin/javac" -proc:none --add-modules jdk.httpserver -encoding UTF-8 -cp "$task_cp" -d "$task_output/classes" "$task_root/tests/Security062Regression.java"
env -u STARCCM_ASSISTANT_HOME "$task_jdk/bin/java" --add-modules jdk.httpserver -Djava.awt.headless=true -cp "$task_output/classes:$task_cp" local.starccm.assistant.Security062Regression "$task_output" "${3:-strict}"
