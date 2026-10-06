# Apple development snapshot — 2026-10-06

This `dev/apple` source snapshot extends the earlier synthetic prototype. The
historical prototype runs and screenshots in [PROTOTYPE.md](PROTOTYPE.md) do not
validate this newer product UI or measurement workflow.

## Implemented in source

- Native SwiftUI Jumps, History, Profiles and Settings destinations.
- Eight languages, persisted appearance and configurable display units.
- Athlete selection/archive, local history, notes and measurement deletion.
- The same 84 avatar illustrations from this repository's public Android assets;
  stable keys and legacy aliases, with initials for absent/unknown keys.
- Files/Photos movie import into an owned, streamed, size-limited temporary copy.
- Compressed-sample presentation-timestamp indexing, exact still-frame requests,
  required-event marking and an explicit real-time declaration.
- CMJ, SJ, Abalakov, unilateral and drop-jump calculations through the shared
  Kotlin `JumpMath` facade; no separate Swift formula implementation.
- Explicit confirmation and atomic SQLite schema-3 persistence of canonical
  metrics, event marks and provenance. Additive schema-1/2 migrations preserve
  existing records. Video URLs, names and pixels are not persisted in the graph.
- History displays saved metrics and analysis provenance/events. The synthetic
  `-openjump-demo` route remains isolated and is never saved as a measurement.

## Evidence at initial publication (`57fc438`)

Source-only independent reviews and static integrity checks completed. Checks
covered approved file scope, Xcode source/test membership and reference identity,
185 localization keys per language, unchanged previous localization values,
placeholder parity, exact avatar pixels, and whitespace checks.

**At the initial publication of commit `57fc438`, the product snapshot had not
yet been compiled or tested natively.** The scheme then contained 43 authored
native unit-test methods and one UI-test method, none executed against that
snapshot at publication. Subsequent actual compile and execution outcomes are
recorded below. A source review, authored test count, or historical prototype run
is not an SDK/runtime PASS. Pushing Apple sources to `dev/apple` triggers the
existing hosted CI; compile, simulator-readiness and runtime conclusions are
recorded separately.

## First product-snapshot CI attempt

Commit `57fc438c169edcc28f04b1d55d5d9d20f879c4d2`,
[run 37487334711](https://github.com/cortig00/OpenJump/actions/runs/37487334711):
**FAILURE** at the Swift `build-for-testing` gate (exit 65). CI helpers, shared
Kotlin JVM tests and the ARM64 Kotlin framework build passed. Swift module
emission reported `JumpVideoImport.swift:149:38: cannot find type
'PhotosPickerItem' in scope`; that file imported `PhotosUI` but not `SwiftUI`,
needed to load the PhotosUI/SwiftUI cross-import overlay. The other two consumers
already imported both modules.

The minimal source correction adds `import SwiftUI` to the importer. Its source
import-contract check fails on the old file and passes on all three consumers
after the correction; this is not native compilation evidence. The corrected
snapshot still needs a new native build. Simulator discovery, boot, runtime
tests and screenshot export were all **SKIPPED** in the failed run, not PASS.

## Follow-up compiler diagnostics

The overlay correction was published as
`ba73c477b2af1b709139b6000f903e7814d71f79`.
[Run 37489725873](https://github.com/cortig00/OpenJump/actions/runs/37489725873)
no longer reported `PhotosPickerItem`, but still **FAILED** Swift compilation
(exit 65). It exposed three diagnostics in the unchanged `ProfileViews.swift`,
with two causes:

- The String/system-image initializer of `ContentUnavailableView` does not accept
  `actions`. The correction uses the documented label/description/actions
  ViewBuilder initializer, preserving the localized add-profile action.
- An implicit `catch` binding named `error` shadowed the error-message state. The
  correction writes `self.error`, preserving the caught error as the formatter's
  input.

Kotlin JVM/framework gates passed again; simulator and native runtime gates were
**SKIPPED**, with no screenshot artifact. These ProfileViews corrections have
source-contract checks and need native recompilation; neither source checks nor
the disappearance of the earlier diagnostic prove that the entire app compiles.

## Remaining native type-check diagnostics

[Run 37491083002](https://github.com/cortig00/OpenJump/actions/runs/37491083002)
for `514206347b6d9c082bdaf3e2551eeb3058a4434f` no longer reported the PhotosUI
or ProfileViews errors. It still **FAILED** Swift compilation (exit 65), exposing
four errors in the workflow/media compile batch:

- A selected-event guard binding hid the mutable property; assignment now
  explicitly targets `self.selectedEvent`.
- A generic `foregroundStyle` could not infer the custom Color member; the two
  same-pattern call sites now use `Color.openJumpGreen`.
- A deadline callback returning `Void` needed explicit generic type context; the
  importer and matching test-fixture writer callback are explicitly typed.
- Core Media format extensions are optional; HDR-marker inspection now unwraps
  them before dictionary bridging. Absent extensions provide no known HDR marker,
  not proof of SDR or of physically accurate timing.

The grouped corrections preserve cancellation/deadline policy, event selection,
colors and fixture assertions. Source-contract checks and a focused review are
separate from native confirmation. Helpers, Kotlin JVM and framework gates passed
in this run; simulator and runtime gates were again **SKIPPED**, with no artifact.
The native unit-test bundle had not reached compilation, so its tests remained
unexecuted. No tests are removed or skipped by the corrections.

## Runtime CI attempt 37492693488

The native attempt compiled all three Swift bundles and passed simulator boot/readiness. It executed all 43 unit-test methods and the one UI-test method (44 cases): 41 passed and three methods failed at four assertions. The UI demonstration passed, but exported zero artifacts; no persisted screenshot/export artifact was produced. This is **not** a native-green run or a full-app UX, physical-device, or scientific-accuracy qualification.

Two confirmed source corrections retain the existing input grammar and timestamp rules: locale decimal input now returns the directly rounded `Double` after the existing `Decimal` validity gate, and all indexed timestamps are validated before the minimum-frame-count decision while the maximum-count guard remains early. Existing regression assertions are unchanged. A byte-for-byte imported-copy assertion now distinguishes source-copy corruption from later AVFoundation inspection behavior in the existing VFR test.

The VFR inspection failure remains unexplained; no speculative VFR/cadence fix is claimed. Unexpected asset metadata/reader initialization failures and typed reader inspection failures now expose a fixed diagnostic stage, optional allowlisted platform error domain/code, reader status, and timing OSStatus. These diagnostics deliberately omit user media identifiers and contents, and do not replace the ordinary generic user-facing error route. The source changes still require a new native CI run; only a fully green run can close the native CI result.

## Known boundaries

- The coordinated-copy wrapper has a 60-second deadline, cooperative checks and
  native cancellation signaling. Caller cancellation/deadline completion still
  depends on the synchronous native cancellation call returning. Native provider
  cooperation/termination is unverified. A still-running copy retains its single
  admission slot, security scope and owned staging until actual worker exit.
- Initial Photos provider transfer loading is outside the copy deadline.
- Exact presentation timestamps/frame identity plus a user's real-time
  declaration do not independently prove physical-clock or clinical accuracy.
- Native codecs/rotation/retiming rejection, migrations, durability, backup
  exclusion, iPhone/iPad layout, accessibility and performance need runtime
  qualification. Camera capture and physical-device validation remain pending.
- Horizontal calibration, bilateral comparisons, multi-attempt workflows,
  groups/testing, statistics, exports/backups, onboarding and full Android parity
  are later work. Encoder and Body Tracking are outside this Apple slice.
- This is not a signed release, TestFlight upload, App Store qualification or a
  clinically validated product. Distribution rights and physical validation gates
  remain separate.

The Android source, toolchain, CI definitions and app release versions are
unchanged by this snapshot. No private athlete data, videos, development corpus,
signing material or local agent settings are included.

## Actual native run 37500468909 and zero-sample guard correction (authored, unexecuted)

[Run 37500468909](https://github.com/cortig00/OpenJump/actions/runs/37500468909):
all three Swift bundles built and simulator boot passed. 44 executed cases: 43
PASS, 1 FAIL. The failure is
`JumpVideoServiceTests.testImportedVariableCadenceStillIsBoundToItsCompressedSourceIndexAndPTS`,
which threw `Jump video inspection failed (stage=readSamples, readerStatus=1)`.
The other pre-existing `JumpVideoServiceTests` ordering method passed, as did the
copied-fixture byte-equality path, the precision and negative-PTS checks, and the
six pilot `MediaProbe` VFR/CFR PTS-plus-pixel oracles. The actual failing buffer's
validity and data-readiness remain unknown; no attachment, validity, or timing
probe of that buffer existed in that run's source or logs.

The source guard correction (not yet executed natively) implements the reconciled
CoreMedia semantics: `CMSampleBufferCreate` legally permits `sampleCount` 0 with a
nil format description and nil data buffer when `dataReady` is true, while
`CMSampleBufferGetNumSamples` returns 0 on error. A new internal pure helper used
by the `readSamples` loop therefore skips a buffer only when it is valid,
data-ready, and zero-sample (no PTS, time, FPS, or frame-index contribution);
an invalid buffer — or a zero-sample buffer that is not data-ready — still fails
with `inspectionFailed`. Negative counts also fail closed. Positive counts gain
an `IsValid` gate alongside the existing 250k-cap and per-sample timing checks. No attachment allowlist or marker cutoff is introduced.
The fixed inspection diagnostic additionally records three privacy-safe
primitives (`sampleCount`, `sampleValid`, `sampleReady`) before cancellation so a
persisting native failure discriminates the invalid/unready branch.

Two new authored regression methods in `JumpVideoServiceTests` construct real
`CMSampleBufferCreate` bare-zero fixtures (nil data, `dataReady` true, nil
format, zero timing/size entry counts and arrays): the valid ready zero must
classify as a skippable non-frame, and the same buffer after
`CMSampleBufferInvalidate` must classify as invalid. The not-ready-zero negative
branch has no authored fixture: a NULL-data zero requires `dataReady` true per
the `CMSampleBufferCreate` primary, so no legal fragile construction was
invented; production still rejects that branch. Authored counts move from 43 unit
plus one UI method (44 cases) to 45 unit plus one UI method (46 cases). The two
new methods have not been executed natively and no GREEN is claimed for them or
for the VFR inspection; only a new fully green CI run can close the native
result. This remains a source snapshot: no clinical, full-app UX, physical-device,
or iOS 16 runtime qualification is implied.
