#!/usr/bin/env python3
"""Generate OpenJump Apple product assets (S1 identity slice).

Reads tracked public Android drawables and writes optimized Xcode assets:
- six protocol illustration imagesets (largest edge <= 768px, aspect + alpha kept)
- AppIcon.appiconset (opaque RGB 1024 + downscaled slots on brand background)

Requires Pillow locally (already installed on this machine). Must NOT be
imported by apple/ci/test_product_assets.py (stdlib-only there).

Deterministic: fixed resample filter, no timestamps in outputs; writes
asset_metadata.json recording source relpath/SHA/dims and output SHA/dims.
"""
from __future__ import annotations

import hashlib
import json
import sys
from pathlib import Path

from PIL import Image

APPLE_DIR = Path(__file__).resolve().parent.parent
PUBLIC_ROOT = APPLE_DIR.parent
DRAWABLE = PUBLIC_ROOT / "app" / "src" / "main" / "res" / "drawable-nodpi"
CATALOG = APPLE_DIR / "OpenJumpApple" / "Assets.xcassets"

ILLUSTRATIONS = {
    "jump_cmj": "cmj_male.png",
    "jump_sj": "squat_jump_male.png",
    "jump_abalakov": "abakalov_jump_male.png",
    "jump_drop_jump": "drop_jump_male.png",
    "jump_unilateral_left": "unilateral_left_male.png",
    "jump_unilateral_right": "unilateral_right_male.png",
}
MAX_EDGE = 720

# AppIcon: opaque RGB on the existing OpenJump brand green (matches the
# in-app openJumpGreen light value), frog logo centered on top. The 256px
# source logo is upscaled ~2.5x for the 1024 slot; this is a composition,
# not a higher-resolution original.
ICON_BACKGROUND = (5, 110, 89)  # #056E59
ICON_LOGO = "ic_openjump_logo.png"
ICON_SIZES = [20, 29, 40, 58, 60, 76, 80, 87, 120, 152, 167, 180, 1024]


def sha256_file(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write_imageset(name: str, png: Image.Image) -> dict:
    d = CATALOG / f"{name}.imageset"
    d.mkdir(parents=True, exist_ok=True)
    out = d / f"{name}.png"
    png.save(out, format="PNG", optimize=True)
    (d / "Contents.json").write_text(
        json.dumps(
            {
                "images": [{"filename": f"{name}.png", "idiom": "universal"}],
                "info": {"author": "xcode", "version": 1},
            },
            indent=2,
        )
        + "\n",
        encoding="utf-8",
    )
    return {"sha256": sha256_file(out), "size": list(png.size), "mode": png.mode}


def main() -> int:
    records: dict = {"illustrations": {}, "appicon": {}}
    total_bytes = 0

    for asset, source in ILLUSTRATIONS.items():
        src = DRAWABLE / source
        if not src.is_file():
            print(f"missing source: {src}", file=sys.stderr)
            return 1
        img = Image.open(src)
        img.load()
        src_size, src_mode = list(img.size), img.mode
        src_sha = sha256_file(src)
        w, h = img.size
        scale = min(1.0, MAX_EDGE / max(w, h))
        if scale < 1.0:
            img = img.resize((round(w * scale), round(h * scale)), Image.LANCZOS)
        if img.mode not in ("RGB", "RGBA"):
            img = img.convert("RGBA")
        meta = write_imageset(asset, img)
        out_path = CATALOG / f"{asset}.imageset" / f"{asset}.png"
        total_bytes += out_path.stat().st_size
        records["illustrations"][asset] = {
            "source": f"app/src/main/res/drawable-nodpi/{source}",
            "source_sha256": src_sha,
            "source_size": src_size,
            "source_mode": src_mode,
            "output": f"apple/OpenJumpApple/Assets.xcassets/{asset}.imageset/{asset}.png",
            "output_sha256": meta["sha256"],
            "output_size": meta["size"],
            "output_mode": meta["mode"],
            "max_edge": MAX_EDGE,
            "resample": "LANCZOS",
            "cropped": False,
        }

    # AppIcon composition.
    logo_src = DRAWABLE / ICON_LOGO
    logo = Image.open(logo_src).convert("RGBA")
    logo_sha = sha256_file(logo_src)
    icon_dir = CATALOG / "AppIcon.appiconset"
    icon_dir.mkdir(parents=True, exist_ok=True)
    images = []
    seen: dict[int, str] = {}
    for size in ICON_SIZES:
        canvas = Image.new("RGB", (1024, 1024), ICON_BACKGROUND)
        side = 640  # logo box on the 1024 canvas
        scaled = logo.resize((side, side), Image.LANCZOS)
        canvas.paste(scaled, ((1024 - side) // 2, (1024 - side) // 2), scaled)
        if size != 1024:
            canvas = canvas.resize((size, size), Image.LANCZOS)
        fname = f"icon-{size}.png" if size != 1024 else "icon-1024.png"
        # Deduplicate identical sizes (none expected, kept for determinism).
        out = icon_dir / fname
        canvas.save(out, format="PNG", optimize=True)
        seen[size] = fname
        records["appicon"][str(size)] = {
            "sha256": sha256_file(out),
            "size": [size, size],
            "mode": "RGB",
        }
    total_bytes += sum((icon_dir / f).stat().st_size for f in seen.values())

    def entry(size: int, idiom: str, scale: str) -> dict:
        return {
            "filename": seen[size],
            "idiom": idiom,
            "scale": scale,
            "size": f"{size}x{size}" if size < 100 else f"{size // int(scale[0])}x{size // int(scale[0])}",
        }

    # Explicit iOS 16-compatible slot list.
    contents = {
        "images": [
            {"filename": seen[40], "idiom": "iphone", "scale": "2x", "size": "20x20"},
            {"filename": seen[60], "idiom": "iphone", "scale": "3x", "size": "20x20"},
            {"filename": seen[58], "idiom": "iphone", "scale": "2x", "size": "29x29"},
            {"filename": seen[87], "idiom": "iphone", "scale": "3x", "size": "29x29"},
            {"filename": seen[80], "idiom": "iphone", "scale": "2x", "size": "40x40"},
            {"filename": seen[120], "idiom": "iphone", "scale": "3x", "size": "40x40"},
            {"filename": seen[120], "idiom": "iphone", "scale": "2x", "size": "60x60"},
            {"filename": seen[180], "idiom": "iphone", "scale": "3x", "size": "60x60"},
            {"filename": seen[20], "idiom": "ipad", "scale": "1x", "size": "20x20"},
            {"filename": seen[40], "idiom": "ipad", "scale": "2x", "size": "20x20"},
            {"filename": seen[29], "idiom": "ipad", "scale": "1x", "size": "29x29"},
            {"filename": seen[58], "idiom": "ipad", "scale": "2x", "size": "29x29"},
            {"filename": seen[40], "idiom": "ipad", "scale": "1x", "size": "40x40"},
            {"filename": seen[80], "idiom": "ipad", "scale": "2x", "size": "40x40"},
            {"filename": seen[76], "idiom": "ipad", "scale": "1x", "size": "76x76"},
            {"filename": seen[152], "idiom": "ipad", "scale": "2x", "size": "76x76"},
            {"filename": seen[167], "idiom": "ipad", "scale": "2x", "size": "83.5x83.5"},
            {"filename": seen[1024], "idiom": "ios-marketing", "scale": "1x", "size": "1024x1024"},
        ],
        "info": {"author": "xcode", "version": 1},
    }
    (icon_dir / "Contents.json").write_text(json.dumps(contents, indent=2) + "\n", encoding="utf-8")
    records["appicon_source"] = {
        "source": f"app/src/main/res/drawable-nodpi/{ICON_LOGO}",
        "source_sha256": logo_sha,
        "source_size": [256, 256],
        "background_rgb": list(ICON_BACKGROUND),
        "logo_box_1024": 640,
        "note": "upscaled composition, not a higher-resolution original",
    }
    (icon_dir / "asset_metadata.json").write_text(json.dumps(records, indent=2) + "\n", encoding="utf-8")
    print(f"illustrations=6 appicon_sizes={len(seen)} total_bytes={total_bytes}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
