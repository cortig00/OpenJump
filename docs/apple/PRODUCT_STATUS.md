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
