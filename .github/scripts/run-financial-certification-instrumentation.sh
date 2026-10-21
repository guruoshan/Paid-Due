#!/usr/bin/env bash
set -uo pipefail

mkdir -p certification-artifacts

test_class="com.dailymoneyregister.core.cloud.sync.TwoDatabaseFinancialCertificationTest"
bash ./gradlew --no-daemon :app:connectedDebugAndroidTest \
  "-Pandroid.testInstrumentationRunnerArguments.class=$test_class"
test_status=$?

find_evidence_path() {
  for package_name in $(adb shell pm list packages | tr -d '\r' | sed 's/^package://g' | grep '^com\.dailymoneyregister' || true); do
    candidate="/sdcard/Android/data/$package_name/files/financial-certification-evidence"
    if adb shell "test -d '$candidate'"; then
      printf '%s' "$candidate"
      return 0
    fi
  done
  return 1
}

evidence_path="$(find_evidence_path || true)"

# Some AGP/device combinations report a successful connected task without
# honoring the class filter. If the expected side-effect is absent, run only
# the certification class directly on the already-installed instrumentation.
if [ -z "$evidence_path" ]; then
  component="$(adb shell pm list instrumentation | tr -d '\r' | sed -n 's/^instrumentation:\([^ ]*\).*/\1/p' | grep 'com\.dailymoneyregister' | head -1 || true)"
  if [ -n "$component" ]; then
    echo "Evidence absent after Gradle task; explicitly running only $test_class via $component"
    adb shell am instrument -w -e class "$test_class" "$component" || test_status=$?
    evidence_path="$(find_evidence_path || true)"
  else
    echo "::error::No installed Paid&Due instrumentation runner was found."
  fi
fi

pull_status=0
if [ -n "$evidence_path" ]; then
  adb pull "$evidence_path" certification-artifacts/financial-certification-evidence || pull_status=$?
else
  echo "::error::Certification evidence directory was not created by instrumentation."
  echo "Installed Paid&Due packages:"
  adb shell pm list packages | grep 'com.dailymoneyregister' || true
  echo "Installed instrumentation runners:"
  adb shell pm list instrumentation || true
  echo "Connected instrumentation test result files:"
  find app/build/outputs/androidTest-results app/build/reports/androidTests -type f 2>/dev/null | sort | head -100 || true
  echo "Instrumentation logcat (matching test runner/errors):"
  adb logcat -d -t 12000 | grep -E 'TwoDatabaseFinancialCertificationTest|Instrumentation|AndroidJUnitRunner|FATAL EXCEPTION|Test run failed|No tests found' | tail -200 || true
  printf '{"status":"MISSING","reason":"instrumentation test did not create evidence directory","test_exit_code":%s}\n' "$test_status" > certification-artifacts/evidence-pull-status.json
  pull_status=1
fi

if [ "$test_status" -ne 0 ]; then
  echo "Instrumentation test failed with exit code $test_status"
  exit "$test_status"
fi

if [ "$pull_status" -ne 0 ]; then
  echo "Evidence collection failed; diagnostics were printed above."
  exit "$pull_status"
fi

printf '{"status":"COLLECTED","test_exit_code":0}\n' > certification-artifacts/evidence-pull-status.json
