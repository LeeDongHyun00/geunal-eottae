# PR #75 open items at the pause (2026-10-30 16:58Z)

Codex review on 221087d, three P2 findings, not answered (project paused):

1. DeleteAccountModel.swift:81 — `ownerConfirmed` survives closing the confirmation sheet, so a reopened sheet skips
   the device-owner check. Fix: reset it when the sheet closes (5b's `sheetClosed()` is the natural place; in PR 5 add
   a reset on the sheet's onDismiss). Thread comment id 4426233875.
2. NotificationSettingsModel.swift:94 — concurrent full-settings writes via detached tasks can land out of order.
   Fix: serialize the writes (chain on the previous task, as the widget snapshot writer does). Comment id 4426233896.
3. BackupScreen.swift:89 — backup readiness is read once; a later Change::Account (recovery uploaded) does not
   refresh it. Fix: reload on account changes (AppModel.accountChanges). Comment id 4426233883.

QA review on 221087d (review id 5715742869): nothing blocking against app-review.md 2-4. Asks for two QA-gate tests
named as in its table, in this PR:

1. D11 `asks for the device owner before showing the recovery code`: move the check out of the view's
   `openRecoveryCode()` into a model function (e.g. `MeModel.mayOpenRecoveryCode(owner:) async -> Bool`) and test
   pass, cancel and no screen lock with a fake `DeviceOwnerCheck`. In the PR body, map D11 to the existing start-fresh
   and deletion tests (`testStartsFreshWithoutTheCodeOnceTheOwnerPasses`, `testCancellingTheOwnerPromptKeepsEverything`).
2. D7 `keeps the recovery code out of screenshots and the app switcher`: make the cover condition
   (`scenePhase != .active`) a small testable function or modifier, or check it in a UI test across backgrounding.
- Optional: also cover the words while the screen is recorded or mirrored (`sceneCaptureState` on iOS 17+).
- For PR 6 (already done there): remove scheduled and delivered alerts on sign-out and deletion.

PR CI on 221087d was still running when paused; the 17:45 safety-net trigger trig_01YHXoQG92zgVR3EcZGQLBeU is
disabled by the coordination session. On resume: resubscribe to #75, fix the three in PR 5's branch, carry the
fixes into the 5b/6/7 restack, reply and resolve the threads, then continue the merge plan.
