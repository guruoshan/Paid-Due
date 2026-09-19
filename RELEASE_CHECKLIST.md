# PaidnDue — Release Checklist

**Purpose:** Mechanical checklist for every Google Play production release.
**Rule:** Re-check current Android/Google Play requirements at release time; this file is not a substitute for current policy.
**Change control:** This checklist is a governed project-control artifact. Any change requires review, diff, approval, and a separate commit under `PROJECT_GOVERNANCE.md` §1.

## Financial integrity
- [ ] Golden financial law preserved
- [ ] No destructive overwrite/delete path for committed financial facts
- [ ] Financial tables/features touched by this release are classified/audited
- [ ] Idempotency/duplicate prevention verified where relevant
- [ ] Crash/recovery behavior verified where relevant

## Tests
- [ ] Unit tests pass
- [ ] Integration tests pass
- [ ] Migration tests pass
- [ ] Financial characterization/invariant tests pass
- [ ] Security/auth tests pass
- [ ] Required device/runtime proof passes
- [ ] `git diff --check` passes

## Security and privacy
- [ ] No hard-coded secrets/test credentials
- [ ] No debug/auth bypass
- [ ] No sensitive production logging
- [ ] Database encryption remains intact
- [ ] Backup/device-transfer behavior reviewed
- [ ] Permissions are minimal
- [ ] Privacy policy matches actual behavior
- [ ] Play Data Safety matches actual behavior and SDKs
- [ ] Account-deletion obligations reviewed if applicable

## Build and size
- [ ] Release AAB builds successfully
- [ ] Release build is not debuggable
- [ ] Minified release launches
- [ ] R8 rules reviewed
- [ ] Resource shrinking reviewed/verified if enabled
- [ ] AAB/APK size inspected
- [ ] No unexplained major size increase
- [ ] No backup/database/log/patch/test artifacts packaged

## Android / Google Play compatibility
- [ ] Current target SDK requirement verified
- [ ] Current 64-bit/native-library requirements verified
- [ ] Current 16 KB/page-size requirements verified when applicable
- [ ] SQLCipher/native `.so` compatibility verified
- [ ] Current Play policy changes reviewed
- [ ] Content rating/store declarations reviewed

## Accessibility and localization
- [ ] Critical flows reviewed for accessibility
- [ ] Supported translations remain complete enough for release
- [ ] Arabic/Urdu RTL flow reviewed where affected
- [ ] Financial meaning/formatting remains correct in supported locales

## Signing and release identity
- [ ] `versionCode` increased
- [ ] `versionName` correct
- [ ] Signing configuration correct
- [ ] Play App Signing configuration reviewed
- [ ] No signing private key committed to source control

## Final release gate
- [ ] No known critical/high security issue
- [ ] No known data-loss defect
- [ ] No known duplicate-money defect
- [ ] No known authentication bypass
- [ ] No known normal-flow crash/ANR blocker
- [ ] Git tree contains only intended release content
- [ ] Final human release approval given
