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
adb shell am instrument -w -e class "$test_class" "$component" || test_status=$?

# Always persist and print sync diagnostics, even when the app creates a manifest
# but one or more financial operations are BLOCKED/FAIL. Never infer PASS from logs.
mkdir -p certification-artifacts
adb logcat -d -t 20000 | grep -E 'PaidDueFinancialSync.*(OUTBOUND_SYNC_START|OUTBOUND_SYNC_END|OUTBOUND_SYNC_EXCEPTION|OUTBOUND_SYNC_FINALLY|OUTBOUND_ITEM_FAILED|UPLOAD_FAILED|INBOUND_SYNC_START|INBOUND_SYNC_END|INBOUND_SYNC_EXCEPTION|INBOUND_SYNC_FINALLY|INBOUND_FETCH_EXCEPTION|INBOUND_APPLY_EXCEPTION|ADMIN_SUPABASE_AUTH_REQUIRED|UPLOAD_RECEIPT_STATUS_UNEXPECTED)' | tail -500 | tee certification-artifacts/sync-diagnostics.log || true

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

printf '{"status":"COLLECTED","test_exit_code":0,"evidence_manifest":"financial-certification-evidence/manifest.json"}\n' > certification-artifacts/evidence-pull-status.json
echo "Certification evidence bundle collected. Inspect manifest operation statuses separately; collection does not mean financial certification PASS."
