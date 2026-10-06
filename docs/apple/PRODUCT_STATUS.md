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

## Evidence at publication

Source-only independent reviews and static integrity checks completed. Checks
covered approved file scope, Xcode source/test membership and reference identity,
185 localization keys per language, unchanged previous localization values,
placeholder parity, exact avatar pixels, and whitespace checks.

**This product snapshot has not yet been compiled or tested natively.** There
are 43 authored native unit-test methods and one UI-test method in the current
scheme; these have not been executed against this snapshot. A successful source
review, test count, or historical prototype run is not an SDK/runtime PASS.
Pushing Apple sources to `dev/apple` triggers the existing hosted CI; its eventual
compile, simulator-readiness and runtime conclusions must be recorded separately.

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
