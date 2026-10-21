#!/usr/bin/env bash
set -uo pipefail

mkdir -p certification-artifacts

test_class="com.dailymoneyregister.core.cloud.sync.TwoDatabaseFinancialCertificationTest"
target_package="com.dailymoneyregister.debug"
app_apk="app/build/outputs/apk/debug/app-debug.apk"
test_apk="app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
device_evidence_path="/sdcard/Android/data/${target_package}/files/financial-certification-evidence"

# Build only the APKs needed for this certification class. Do not run the full
# connectedAndroidTest task: AGP may tear down/uninstall the test target before
# a later step can pull files from getExternalFilesDir().
if ! bash ./gradlew --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest; then
  printf '{"status":"BUILD_FAILED"}\n' > certification-artifacts/evidence-pull-status.json
  exit 1
fi

if [[ ! -f "$app_apk" || ! -f "$test_apk" ]]; then
  echo "::error::Expected debug or instrumentation APK was not built."
  ls -la app/build/outputs/apk/debug app/build/outputs/apk/androidTest/debug 2>/dev/null || true
  printf '{"status":"APK_MISSING"}\n' > certification-artifacts/evidence-pull-status.json
  exit 1
fi

# Explicitly install both APKs, then run just the certification class. This
# keeps the target app installed while evidence is pulled from its external dir.
adb start-server
if ! timeout 180 adb wait-for-device; then
  echo "::error::Emulator is not connected to ADB after APK build."
  adb devices -l || true
  printf '{"status":"DEVICE_MISSING"}\n' > certification-artifacts/evidence-pull-status.json
  exit 1
fi
booted="$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
if [[ "$booted" != "1" ]]; then
  echo "::error::Emulator is connected but not boot-complete."
  adb devices -l || true
  printf '{"status":"DEVICE_NOT_BOOTED"}\n' > certification-artifacts/evidence-pull-status.json
  exit 1
fi

if ! adb install -r "$app_apk"; then
  echo "::error::Could not install target debug APK."
  exit 1
fi
if ! adb install -r "$test_apk"; then
  echo "::error::Could not install instrumentation APK."
  exit 1
fi

component="$(adb shell pm list instrumentation | tr -d '\r' | sed -n 's/^instrumentation:\([^ ]*\).*/\1/p' | grep 'com\.dailymoneyregister\.debug' | head -1 || true)"
if [[ -z "$component" ]]; then
  echo "::error::Instrumentation runner missing after explicit APK installation."
  adb shell pm list packages | grep 'com\.dailymoneyregister' || true
  adb shell pm list instrumentation || true
  printf '{"status":"INSTRUMENTATION_RUNNER_MISSING"}\n' > certification-artifacts/evidence-pull-status.json
  exit 1
fi

# Remove stale evidence from an earlier attempt so a previous run cannot
# accidentally satisfy this run's evidence check.
adb shell rm -rf "$device_evidence_path"
test_status=0
# Pass only this runner's disposable local Supabase endpoint and keys to instrumentation.
# Never fall back to BuildConfig's production endpoint.
: "${CERT_SUPABASE_URL:?Local Supabase URL was not exported by the workflow}"
: "${CERT_SUPABASE_ANON_KEY:?Local Supabase anon key was not exported by the workflow}"
: "${CERT_SUPABASE_SERVICE_ROLE_KEY:?Local Supabase service-role key was not exported by the workflow}"
adb shell am instrument -w \
  -e class "$test_class" \
  -e supabase_url "$CERT_SUPABASE_URL" \
  -e supabase_anon_key "$CERT_SUPABASE_ANON_KEY" \
  -e supabase_service_role_key "$CERT_SUPABASE_SERVICE_ROLE_KEY" \
  "$component" || test_status=$?

pull_status=0
if [[ "$test_status" -eq 0 ]] && adb shell "test -f '$device_evidence_path/manifest.json'"; then
  adb pull "$device_evidence_path" certification-artifacts/financial-certification-evidence || pull_status=$?
else
  echo "::error::Certification test did not produce a manifest at $device_evidence_path."
  adb shell ls -la "$device_evidence_path" || true
  adb logcat -d -t 12000 | grep -E 'TwoDatabaseFinancialCertificationTest|Instrumentation|AndroidJUnitRunner|FATAL EXCEPTION|Test run failed|No tests found' | tail -200 || true
  printf '{"status":"EVIDENCE_NOT_CREATED","test_exit_code":%s}\n' "$test_status" > certification-artifacts/evidence-pull-status.json
  exit 1
fi

if [[ "$pull_status" -ne 0 || ! -f certification-artifacts/financial-certification-evidence/manifest.json ]]; then
  echo "::error::Evidence pull failed or manifest is missing from the collected bundle."
  printf '{"status":"EVIDENCE_PULL_FAILED","test_exit_code":%s,"pull_exit_code":%s}\n' "$test_status" "$pull_status" > certification-artifacts/evidence-pull-status.json
  exit 1
fi

# Evidence collection is not certification. Fail unless all thirteen operations
# completed the full lifecycle and are explicitly PASS.
python3 - <<'PY'
import json
import sys
from pathlib import Path

manifest_path = Path('certification-artifacts/financial-certification-evidence/manifest.json')
try:
    manifest = json.loads(manifest_path.read_text())
except Exception as exc:
    print('::error::Cannot parse certification manifest: ' + str(exc))
    sys.exit(1)

operations = manifest.get('operations')
expected = 13
if manifest.get('operation_count') != expected or not isinstance(operations, list) or len(operations) != expected:
    count = len(operations) if isinstance(operations, list) else 'missing'
    print('::error::Expected exactly 13 operation results; found count=' + str(manifest.get('operation_count')) + ' list=' + str(count))
    sys.exit(1)

failures = [op for op in operations if op.get('status') != 'PASS']
if manifest.get('overall_status') != 'PASS' or failures:
    print('::error::Financial certification did not PASS. Evidence collection alone cannot satisfy the release gate.')
    for op in failures:
        print('::error::' + str(op.get('operation_type', '<unknown>')) + ': ' + str(op.get('status', 'MISSING')))
    sys.exit(1)

print('All 13 operation lifecycles explicitly PASS.')
PY
gate_status=$?
if [[ "$gate_status" -ne 0 ]]; then
  printf '{"status":"CERTIFICATION_NOT_PASSED","test_exit_code":0,"manifest":"financial-certification-evidence/manifest.json"}\n' > certification-artifacts/evidence-pull-status.json
  exit "$gate_status"
fi

printf '{"status":"CERTIFIED_PASS","test_exit_code":0,"evidence_manifest":"financial-certification-evidence/manifest.json"}\n' > certification-artifacts/evidence-pull-status.json
echo "Financial certification PASS: all 13 operation lifecycles verified."
