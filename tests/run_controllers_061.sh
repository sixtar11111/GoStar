#!/usr/bin/env bash
set -euo pipefail
test_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
test_output="${1:-/tmp/starccm-capabilities060/regression/api-controller060}"
test_plugin_jar="${2:-$test_root/build/plugin/local-starccm-assistant.jar}"
test_star_home="${STARCCM_HOME:-/opt/Siemens/19.04.009-R8/STAR-CCM+19.04.009-R8}"
test_jdk="${STARCCM_JDK:-$(dirname -- "$test_star_home")/jdk/linux-x86_64/jdk21.0.1}"
test_platform="$test_star_home/star/lib/java/platform"
mkdir -p "$test_output/classes" "$test_output/prefs"
cp "$test_plugin_jar" "$test_output/plugin-under-test.jar"
sha256sum "$test_output/plugin-under-test.jar" "$test_root/tests/Controller059Regression.java" "$test_root/tests/Api060ControllerRegression.java" "$test_root/tests/ChangeSummary060Smoke.java" "$test_root/tests/Reports061ControllerRegression.java" > "$test_output/sha256.txt"
test_classpath="$test_output/plugin-under-test.jar:$test_platform/lib/*:$test_platform/modules/*:$test_platform/modules/ext/*"
"$test_jdk/bin/javac" -proc:none --add-modules jdk.httpserver -encoding UTF-8 -cp "$test_classpath" -d "$test_output/classes" "$test_root/tests/Controller059Regression.java" "$test_root/tests/Api060ControllerRegression.java" "$test_root/tests/ChangeSummary060Smoke.java" "$test_root/tests/Reports061ControllerRegression.java"
PATH="/usr/bin:/bin:$PATH" xvfb-run -a -e "$test_output/xvfb.log" -s '-screen 0 1280x900x24' "$test_jdk/bin/java" --add-modules jdk.httpserver -Djava.util.prefs.userRoot="$test_output/prefs" -cp "$test_output/classes:$test_classpath" local.starccm.assistant.Api060ControllerRegression "$test_output/controller"
"$test_jdk/bin/java" -Djava.awt.headless=true -cp "$test_output/classes:$test_classpath" local.starccm.assistant.ChangeSummary060Smoke "$test_output/summary"

PATH="/usr/bin:/bin:$PATH" xvfb-run -a "$test_jdk/bin/java" --add-modules jdk.httpserver -Djava.util.prefs.userRoot="$test_output/prefs" -cp "$test_output/classes:$test_classpath" local.starccm.assistant.Reports061ControllerRegression "$test_output/reports061"
