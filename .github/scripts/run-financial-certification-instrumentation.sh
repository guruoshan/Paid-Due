#!/usr/bin/env bash
set -uo pipefail

mkdir -p certification-artifacts

bash ./gradlew --no-daemon :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.dailymoneyregister.core.cloud.sync.TwoDatabaseFinancialCertificationTest
test_status=$?

# The debug variant declares applicationIdSuffix = ".debug", so Android stores
# targetContext.getExternalFilesDir(null) under the debug package, not the release ID.
evidence_path="/sdcard/Android/data/com.dailymoneyregister.debug/files/financial-certification-evidence"
pull_status=0

if adb shell "test -d '$evidence_path'"; then
  adb pull "$evidence_path" certification-artifacts/financial-certification-evidence || pull_status=$?
else
  echo "::warning::Certification test did not create evidence directory: $evidence_path"
  printf '{"status":"MISSING","reason":"instrumentation test did not create evidence directory","test_exit_code":%s}\n' "$test_status" > certification-artifacts/evidence-pull-status.json
  pull_status=1
fi

if [ "$test_status" -ne 0 ]; then
  echo "Instrumentation test failed with exit code $test_status"
  exit "$test_status"
fi

if [ "$pull_status" -ne 0 ]; then
  echo "Evidence collection failed; see certification-artifacts/evidence-pull-status.json"
  exit "$pull_status"
fi

printf '{"status":"COLLECTED","test_exit_code":0}\n' > certification-artifacts/evidence-pull-status.json
