# Android release signing

1. Generate the one permanent upload/release key locally; enter your own passwords when prompted:
   `keytool -genkeypair -v -keystore paid-due-release.jks -alias paid-due-release -keyalg RSA -keysize 4096 -validity 10000`
2. Record its SHA-256 certificate fingerprint:
   `keytool -list -v -keystore paid-due-release.jks -alias paid-due-release`
3. Base64-encode the keystore (macOS/Linux):
   `base64 < paid-due-release.jks | tr -d '\n'`
4. In GitHub **Settings → Secrets and variables → Actions → Secrets**, add `KEYSTORE_BASE64` (command output), `KEYSTORE_PASSWORD`, `KEY_ALIAS` (normally `paid-due-release`), and `KEY_PASSWORD`.
5. Under **Variables**, add `EXPECTED_SHA256` using the SHA-256 fingerprint from step 2 (colons are accepted).
6. For local signed builds, put `KEYSTORE_PATH` (absolute path), `KEY_ALIAS`, `KEYSTORE_PASSWORD`, and `KEY_PASSWORD` in ignored `local.properties`; never commit it.
7. Keep two encrypted offline backups of the keystore and passwords in separate secure locations; never put the keystore in Git or an issue/PR.
8. Do not regenerate or replace this key after release: Android updates require the same signing identity. Losing it can prevent updates to installed copies.
9. CI release signing fails closed when required secrets/variable are absent or the APK fingerprint differs. Debug builds do not need signing secrets.
