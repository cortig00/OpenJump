# Asset provenance — S1 identity slice

2026-10-07. All product images in this slice derive from tracked files in the
same public repository (`app/src/main/res/drawable-nodpi/`). No private,
downloaded, or third-party art was introduced.

## Sources

| Output | Source (repo-relative) | Source dims | Output dims |
|---|---|---|---|
| `jump_cmj` | `app/src/main/res/drawable-nodpi/cmj_male.png` | 1536×1024 RGBA | 720×480 RGBA |
| `jump_sj` | `app/src/main/res/drawable-nodpi/squat_jump_male.png` | 1672×941 RGBA | 720×405 RGBA |
| `jump_abalakov` | `app/src/main/res/drawable-nodpi/abakalov_jump_male.png` | 1448×1086 RGBA | 720×540 RGBA |
| `jump_drop_jump` | `app/src/main/res/drawable-nodpi/drop_jump_male.png` | 1536×1024 RGBA | 720×480 RGBA |
| `jump_unilateral_left` | `app/src/main/res/drawable-nodpi/unilateral_left_male.png` | 1536×1024 RGBA | 720×480 RGBA |
| `jump_unilateral_right` | `app/src/main/res/drawable-nodpi/unilateral_right_male.png` | 1536×1024 RGBA | 720×480 RGBA |
| `AppIcon` | `app/src/main/res/drawable-nodpi/ic_openjump_logo.png` (256×256 RGBA) | — | 1024×1024 RGB opaque + 12 downscaled slots |

Full SHA-256 (source and output) per asset: `apple/OpenJumpApple/Assets.xcassets/AppIcon.appiconset/asset_metadata.json`
(machine-written by `apple/tools/generate_product_assets.py`).

## Derivation

- Illustrations: aspect-preserving LANCZOS downscale, largest edge ≤ 720px
  (≤ 768px budget), never cropped; alpha kept. Six files total
  1,729,640 bytes (≤ 2 MB budget).
- AppIcon: opaque RGB composition — the 256px frog logo centered at 640px on
  a 1024px brand-green field (#056E59, matching in-app `openJumpGreen`).
  This is an upscaled composition, **not** a higher-resolution original.
- The public repo is GPL-licensed, but that alone does not certify
  per-image rights or Apple distribution clearance; no blanket clearance is
  declared here. Horizontal/asymmetry art exists upstream but is deliberately
  **not** shipped, so unsupported protocols are never presented as features.

## Untouched

All 84 pre-existing `avatar_*.imageset` PNGs are byte-identical to HEAD
(verified by hash in `apple/ci/test_product_assets.py`). No avatar key,
alias, or pixel was modified.
