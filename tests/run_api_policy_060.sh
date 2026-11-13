#!/usr/bin/env bash
set -euo pipefail
task_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
task_output="${1:-/tmp/starccm-capabilities060/regression/api-policy060}"
task_jar="${2:-$task_root/build/plugin/local-starccm-assistant.jar}"
task_star="${STARCCM_HOME:-/opt/Siemens/19.04.009-R8/STAR-CCM+19.04.009-R8}"
task_jdk="${STARCCM_JDK:-$(dirname -- "$task_star")/jdk/linux-x86_64/jdk21.0.1}"
task_platform="$task_star/star/lib/java/platform"
mkdir -p "$task_output/classes"
cp "$task_jar" "$task_output/plugin-under-test.jar"
task_classpath="$task_output/plugin-under-test.jar:$task_platform/lib/*:$task_platform/modules/*:$task_platform/modules/ext/*"
"$task_jdk/bin/javac" -proc:none -encoding UTF-8 -cp "$task_classpath" -d "$task_output/classes" "$task_root/tests/ApiDocumentation060Test.java" "$task_root/tests/NativeApiBridgePolicy060Smoke.java" "$task_root/tests/CommonActions060Smoke.java"
sha256sum "$task_output/plugin-under-test.jar" "$task_root/tests/ApiDocumentation060Test.java" "$task_root/tests/NativeApiBridgePolicy060Smoke.java" "$task_root/tests/CommonActions060Smoke.java" > "$task_output/sha256.txt"
"$task_jdk/bin/java" -Djava.awt.headless=true -cp "$task_output/classes:$task_output/plugin-under-test.jar" local.starccm.assistant.ApiDocumentation060Test "$task_star/doc/client/html" > "$task_output/documentation-offline.log" 2>&1
"$task_jdk/bin/java" -Djava.awt.headless=true -cp "$task_output/classes:$task_classpath" local.starccm.assistant.ApiDocumentation060Test "$task_star/doc/client/html" runtime > "$task_output/documentation-runtime.log" 2>&1
"$task_jdk/bin/java" -Djava.awt.headless=true -cp "$task_output/classes:$task_classpath" local.starccm.assistant.NativeApiBridgePolicy060Smoke > "$task_output/native-policy.log" 2>&1
"$task_jdk/bin/java" -Djava.awt.headless=true -cp "$task_output/classes:$task_classpath" local.starccm.assistant.CommonActions060Smoke > "$task_output/common-policy.log" 2>&1
printf 'PASS API documentation and property policy tests\n'
