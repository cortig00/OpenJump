"""S1 product-asset wiring tests (stdlib only; never import Pillow here).

Independent oracles for the identity slice: hardcoded asset names, PNG-header
parsing, PBX membership, locale parity against git HEAD, and pixel-exact
avatar preservation. These assertions do not read the generator's output
metadata as expected values.
"""
import hashlib
import json
import re
import struct
import subprocess
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent
APPLE = ROOT.parent
PUBLIC = APPLE.parent
APP = APPLE / "OpenJumpApple"
CATALOG = APP / "Assets.xcassets"
DRAWABLE = PUBLIC / "app" / "src" / "main" / "res" / "drawable-nodpi"
PBX = APPLE / "OpenJumpApple.xcodeproj" / "project.pbxproj"

PNG_MAGIC = b"\x89PNG\r\n\x1a\n"

# Hardcoded contract: six illustration imagesets for the five supported
# temporal protocols (unilateral has LEFT/RIGHT).
EXPECTED_ILLUSTRATIONS = [
    "jump_cmj",
    "jump_sj",
    "jump_abalakov",
    "jump_drop_jump",
    "jump_unilateral_left",
    "jump_unilateral_right",
]

# Hardcoded protocol -> asset mapping, mirroring the frozen Swift contract.
EXPECTED_MAPPING = {
    "cmj": ["jump_cmj"],
    "sj": ["jump_sj"],
    "abalakov": ["jump_abalakov"],
    "dropJump": ["jump_drop_jump"],
    "unilateral:LEFT": ["jump_unilateral_left"],
    "unilateral:RIGHT": ["jump_unilateral_right"],
}

LOCALES = ["en", "es", "fr", "de", "pt-BR", "pt-PT", "it", "tr"]


def png_info(path):
    data = Path(path).read_bytes()
    if data[:8] != PNG_MAGIC:
        raise AssertionError(f"{path}: missing PNG signature")
    if data[12:16] != b"IHDR":
        raise AssertionError(f"{path}: first chunk is not IHDR")
    width, height, bit_depth, color_type, _, _, _ = struct.unpack(">IIBBBBB", data[16:29])
    return {"width": width, "height": height, "bit_depth": bit_depth, "color_type": color_type}


def git_show(relpath):
    proc = subprocess.run(
        ["git", "-C", str(PUBLIC), "show", f"HEAD:{relpath}"],
        capture_output=True,
        timeout=30,
    )
    if proc.returncode != 0:
        raise unittest.SkipTest(f"git HEAD unavailable for {relpath}: {proc.stderr.decode()[:200]}")
    return proc.stdout


def parse_strings(path):
    entries = {}
    pattern = re.compile(r'^\s*"((?:[^"\\]|\\.)*)"\s*=\s*"((?:[^"\\]|\\.)*)";\s*$')
    for lineno, line in enumerate(Path(path).read_text(encoding="utf-8").splitlines(), 1):
        line = line.strip()
        if not line or line.startswith("//"):
            continue
        match = pattern.match(line)
        if not match:
            raise AssertionError(f"{path}:{lineno}: unparsable line: {line[:120]}")
        entries[match.group(1)] = match.group(2)
    return entries


class ProductAssetsTests(unittest.TestCase):
    def test_illustration_imagesets_valid(self):
        for name in EXPECTED_ILLUSTRATIONS:
            with self.subTest(asset=name):
                d = CATALOG / f"{name}.imageset"
                self.assertTrue(d.is_dir(), f"missing imageset {d}")
                contents = json.loads((d / "Contents.json").read_text(encoding="utf-8"))
                images = contents.get("images", [])
                self.assertEqual(len(images), 1, f"{name}: expected one image entry")
                self.assertEqual(images[0].get("idiom"), "universal")
                png = d / images[0]["filename"]
                self.assertTrue(png.is_file(), f"missing file {png}")
                info = png_info(png)
                largest = max(info["width"], info["height"])
                smallest = min(info["width"], info["height"])
                self.assertLessEqual(largest, 768, f"{name}: exceeds 768px budget")
                self.assertGreaterEqual(smallest, 400, f"{name}: suspiciously small")
                self.assertIn(info["color_type"], (2, 6), f"{name}: expected RGB/RGBA PNG")

    def test_illustrations_total_budget(self):
        total = sum(
            (CATALOG / f"{name}.imageset" / f"{name}.png").stat().st_size
            for name in EXPECTED_ILLUSTRATIONS
        )
        self.assertLessEqual(total, 2_000_000, f"illustrations total {total} exceeds 2MB")

    def test_appicon_opaque_rgb(self):
        d = CATALOG / "AppIcon.appiconset"
        self.assertTrue(d.is_dir(), "missing AppIcon.appiconset")
        contents = json.loads((d / "Contents.json").read_text(encoding="utf-8"))
        by_size = {(e.get("size"), e.get("scale")): e for e in contents.get("images", [])}
        self.assertIn(("1024x1024", "1x"), by_size, "missing ios-marketing 1024 slot")
        referenced = {e["filename"] for e in contents["images"]}
        for filename in sorted(referenced):
            png = d / filename
            self.assertTrue(png.is_file(), f"missing icon file {filename}")
            info = png_info(png)
            self.assertEqual(info["color_type"], 2, f"{filename}: AppIcon must be opaque RGB")
        icon1024 = d / by_size[("1024x1024", "1x")]["filename"]
        info = png_info(icon1024)
        self.assertEqual((info["width"], info["height"]), (1024, 1024))

    def test_source_drawables_tracked(self):
        sources = [
            "cmj_male.png",
            "squat_jump_male.png",
            "abakalov_jump_male.png",
            "drop_jump_male.png",
            "unilateral_left_male.png",
            "unilateral_right_male.png",
            "ic_openjump_logo.png",
            "ic_launcher_foreground.png",
        ]
        for name in sources:
            with self.subTest(source=name):
                path = DRAWABLE / name
                self.assertTrue(path.is_file(), f"missing source {name}")
                self.assertEqual(path.read_bytes()[:8], PNG_MAGIC, f"{name}: not a PNG")

    def test_swift_mapping_matches_hardcoded_contract(self):
        source = (APP / "ProtocolPresentation.swift").read_text(encoding="utf-8")
        for asset in EXPECTED_ILLUSTRATIONS:
            self.assertIn(f'"{asset}"', source, f"mapping missing {asset}")
        self.assertIn("case .horizontal, .asymmetry:", source)
        self.assertIn("return []", source)
        for key in ("RSI", "RSI_MOD", "HEIGHT_CM", "JUMP_HEIGHT"):
            self.assertIn(f'"{key}"', source, f"primary-metric preference missing {key}")

    def test_shared_api_shape(self):
        design = (APP / "DesignSystem.swift").read_text(encoding="utf-8")
        for token in (
            "enum OpenJumpSpacing",
            "struct OpenJumpSection",
            "struct OpenJumpMetricHero",
            "static let openJumpGreen",
            "openJumpBackground",
            "openJumpSurface",
            "openJumpSurfaceElevated",
            "openJumpOutline",
        ):
            self.assertIn(token, design, f"DesignSystem missing {token}")
        presentation = (APP / "ProtocolPresentation.swift").read_text(encoding="utf-8")
        self.assertIn("func illustrationAssets(for", presentation)
        self.assertIn("func primaryMetric(in", presentation)
        shell = (APP / "AppShell.swift").read_text(encoding="utf-8")
        self.assertEqual(
            shell.count("static let openJumpGreen"),
            0,
            "openJumpGreen must be declared exactly once (DesignSystem)",
        )

    def test_pbx_membership_and_config(self):
        text = PBX.read_text(encoding="utf-8")
        for name in ("DesignSystem.swift", "ProtocolPresentation.swift", "PresentationTests.swift"):
            self.assertIn(f"path = {name}", text, f"PBX missing file ref {name}")
        self.assertIn("ASSETCATALOG_COMPILER_APPICON_NAME = AppIcon", text)
        self.assertEqual(text.count("ASSETCATALOG_COMPILER_APPICON_NAME = AppIcon"), 2)
        self.assertIn("INFOPLIST_KEY_CFBundleDisplayName = OpenJump;", text)
        self.assertNotIn("OpenJumpPrototype", text)
        self.assertIn("PRODUCT_BUNDLE_IDENTIFIER = org.openjump.apple;", text)
        self.assertIn("IPHONEOS_DEPLOYMENT_TARGET = 16.0", text)
        self.assertIn("SWIFT_VERSION = 5.0", text)

    def test_locale_parity_and_new_key(self):
        parsed = {}
        for locale in LOCALES:
            path = APP / f"{locale}.lproj" / "Localizable.strings"
            self.assertTrue(path.is_file(), f"missing strings for {locale}")
            parsed[locale] = parse_strings(path)
        counts = {locale: len(entries) for locale, entries in parsed.items()}
        self.assertEqual(len(set(counts.values())), 1, f"locale counts differ: {counts}")
        for locale, entries in parsed.items():
            self.assertIn("jumps.import.title", entries, f"{locale} missing jumps.import.title")
            self.assertTrue(entries["jumps.import.title"].strip(), f"{locale} empty jumps.import.title")
        head_en = git_show("apple/OpenJumpApple/en.lproj/Localizable.strings").decode("utf-8")
        head_entries = {}
        pattern = re.compile(r'^\s*"((?:[^"\\]|\\.)*)"\s*=\s*"((?:[^"\\]|\\.)*)";\s*$')
        for line in head_en.splitlines():
            line = line.strip()
            if not line or line.startswith("//"):
                continue
            match = pattern.match(line)
            if match:
                head_entries[match.group(1)] = match.group(2)
        for key, value in head_entries.items():
            with self.subTest(key=key):
                self.assertIn(key, parsed["en"], f"existing key removed: {key}")
                self.assertEqual(parsed["en"][key], value, f"existing value changed: {key}")

    def test_avatars_pixel_exact(self):
        imagesets = sorted(CATALOG.glob("avatar_*.imageset"))
        self.assertEqual(len(imagesets), 84, f"expected 84 avatar imagesets, found {len(imagesets)}")
        for d in imagesets:
            with self.subTest(asset=d.name):
                contents = json.loads((d / "Contents.json").read_text(encoding="utf-8"))
                for entry in contents.get("images", []):
                    filename = entry.get("filename")
                    self.assertTrue(filename, f"{d.name}: image entry without filename")
                    png = d / filename
                    self.assertTrue(png.is_file(), f"missing {png}")
                    rel = f"apple/OpenJumpApple/Assets.xcassets/{d.name}/{filename}"
                    head_bytes = git_show(rel)
                    self.assertEqual(
                        hashlib.sha256(png.read_bytes()).hexdigest(),
                        hashlib.sha256(head_bytes).hexdigest(),
                        f"{rel}: avatar bytes differ from HEAD",
                    )


if __name__ == "__main__":
    unittest.main()
