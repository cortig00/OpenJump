# Apple data export — creation only

Implemented in source for `dev/apple`. This document is a format contract, not a
claim that the new code has compiled, passed native tests, or exported a file on
an iPhone. See [PRODUCT_STATUS.md](PRODUCT_STATUS.md) for qualified evidence.

## User flow and privacy

Settings → Export data → prepare analytical CSV or create a JSON data copy →
inspect snapshot counts → Save to Files → choose a destination. Preparation reads
a single consistent SQLite snapshot. It does not change profiles, history, notes,
preferences, measurements, or schema. Cancellation does not reset anything.

The files contain personal profile/measurement data and **are not encrypted or
authenticated**. A user-selected Files provider may synchronize them to the cloud.
There is no automatic upload, account, backend, or app-initiated network export.
No video bytes, dedicated video/database/cache URLs, or app preferences are
exported. User-entered text and unknown avatar keys are preserved verbatim and
may themselves contain URLs; this is not a URL scrubber or anonymization feature.
The app does not retain the chosen destination URL.

**Restoration/import is unavailable in this version.** The required FileDocument
reading initializer rejects input; there is no import picker, decoder, merge,
replacement, reset, or raw SQLite export. These files are **not compatible with
Android backups or Android analytical exports**. Keep an installed, same-team,
same-bundle copy of the app; this JSON copy is not a currently supported recovery
route after uninstalling it.

## JSON data copy

- Contract `openjump-apple-backup`, format version 1, source SQLite schema 3.
- `dateEncoding: "unix-seconds"`, `canonicalUnits: true`.
- `mediaIncluded`, `preferencesIncluded`, `restorationSupported`, and
  `androidCompatible` are all false.
- `createdAtEpochSeconds` and stored profile/measurement dates are numeric Unix
  seconds, preserving the stored Double value rather than rounding to a display
  timestamp or using Foundation's 2001 epoch.
- `athletes`: all current and archived profiles, with stored IDs, names, canonical
  kg/cm, notes, dates, and raw avatar keys. Optional nil profile fields may be
  omitted; an empty text field is retained as an empty string.
- `measurements`: stored IDs, session keys, owners (including unowned legacy
  records), protocol/context/date/notes, ordered canonical metrics, and optional
  temporal analysis. Raw UUID spelling/text is not normalized for output.
- A present analysis includes source kind, compressed-source frame count and
  origin, real-time declaration, analysis version 1, and ordered event marks:
  kind, ordinal, source frame index, exact microsecond PTS and optional neighboring
  PTS. Missing analysis is explicit null only when no event rows exist.
- Existing temporal validators and the shared Kotlin calculator verify the full
  graph/metric array. Corrupt, future-version, orphaned, or inconsistent data
  fails the entire export; nothing is silently repaired or discarded.

This is a creation-only logical data snapshot, not a SQLite binary file. A future
restore feature would require its own strict incoming-data validation and atomic
conflict policy; it is not inferred from the current JSON structure.

## Analytical CSV

UTF-8 without BOM; RFC 4180 quoting and CRLF row endings. One row per metric,
ordered by stored measurement date descending, stored ID descending, then metric
ordinal. It is not a full profile backup: profiles with no measurements have no
CSV rows. The owner name is its **current snapshot name**, not a fabricated
historical name. Values and units remain canonical, independent of display
preferences. The numeric epoch preserves the timestamp; `date_time_utc` is a
readable UTC millisecond companion. CSV requires dates in years 1–9999 and fails
rather than clamping out-of-range dates.

Exact 28-column header:

```text
schema_version,measurement_id,session_key,recorded_at_epoch_seconds,date_time_utc,athlete_id,athlete_name_current,protocol_key,side,drop_height_cm,metric_key,metric_value,metric_unit,metric_ordinal,analysis_source,analysis_version,source_frame_count,source_origin_us,temporal_state,movement_start_frame,movement_start_pts_us,initial_contact_frame,initial_contact_pts_us,takeoff_frame,takeoff_pts_us,landing_frame,landing_pts_us,notes
```

`schema_version` is 1. Missing legacy analysis/context/event cells are empty,
never invented zeroes. Text cells beginning with `=`, `+`, `-`, or `@` after ASCII
space/tab/CR/LF are prefixed with an apostrophe; leading tab/CR/LF is hardened too.
Comma, quote, CR, and LF require quoting; embedded quotes are doubled. Checks use
Unicode scalars, including signs followed by combining marks. Numeric negatives
remain numeric, not apostrophe-prefixed text. Consumers should continue treating
untrusted spreadsheet content cautiously; no universal spreadsheet safety claim
is made.

## Bounds and consistency

Export is a synchronous method on the existing SQLite actor, with one deferred
read transaction and no awaited page calls. Five fixed-table 64-bit counts and a
foreign-key check precede record materialization. Ordered SQL cursors stream roots
without normalizing models or losing tied dates. Complete child counts also
reject orphaned rows in older schemas lacking declared foreign keys.

Admission ceilings: 5,000 profiles; 5,000 measurements; 25,000 metrics; 20,000 event
rows; 5,000 analysis rows. Output is at most 8 MiB, checked before every append;
total raw UTF-8 text is also bounded to 8 MiB with a 64 KiB per-field ceiling in
addition to existing domain character limits. Above any limit, creation fails
without a partial file. Tests may lower but cannot enlarge production ceilings.
These are row/text/output limits, **not an 8 MiB RSS guarantee**. Native encoding,
SQLite, Swift/Foundation, and file-provider buffers need additional memory.
Cancellation is checked during reads/appends and before commit. Failure rolls
back the read transaction and does not repair, replace, or mutate saved data.

## Validation boundary

New source tests cover bounds, raw identity/text/date fidelity, canonical CMJ/DJ
metrics, full graph integrity, archived/unowned records, tied dates, SQL type and
UTF-8 rejection, failure without repair/reset, CSV quoting/formula hardening,
FileDocument byte representation and rejection of input. A product UI test
previews the disposable fixture's 2 profiles / 1 measurement / 5 metrics; it does
not operate an external Files provider. Authored test counts and Windows helper
results are not native test passes. The corrected history UI test and these new
export tests still require exact-source native execution and independent review
before publication/delivery. Physical XR/iOS 16, iPad/VoiceOver, destination-provider
save/cancel/error behavior, and memory behavior remain unqualified.
