"""S2 product-asset wiring tests (stdlib only; never import Pillow here).

Independent oracles for the identity/profile slices against the frozen
self-contained baseline apple/ci/fixtures/product_baseline.json. The manifest
pins the public 7abf288 source commit, the original 185 locale keys and their
per-locale canonical value hashes, the S1 jumps.import.title values and the
84 avatar PNG hashes. These tests read only the manifest and the working
tree: no git, no network, no skips. A missing manifest fails loudly.
"""
import hashlib
import json
import re
import struct
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent
APPLE = ROOT.parent
PUBLIC = APPLE.parent
APP = APPLE / "OpenJumpApple"
CATALOG = APP / "Assets.xcassets"
DRAWABLE = PUBLIC / "app" / "src" / "main" / "res" / "drawable-nodpi"
PBX = APPLE / "OpenJumpApple.xcodeproj" / "project.pbxproj"
BASELINE = ROOT / "fixtures" / "product_baseline.json"

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

# S2 appended profile keys (1 S1-gap fix + 12 profile editor keys).
EXPECTED_S2_KEYS = [
    "common.confirm",
    "common.done",
    "common.discard",
    "profiles.search",
    "profiles.identity",
    "profiles.physical",
    "profiles.physicalHint",
    "profiles.notesHint",
    "profiles.discardTitle",
    "profiles.discardBody",
    "profiles.archiveTitle",
    "profiles.restoreTitle",
    "profiles.noResults",
]


def png_info(path):
    data = Path(path).read_bytes()
    if data[:8] != PNG_MAGIC:
        raise AssertionError(f"{path}: missing PNG signature")
    if data[12:16] != b"IHDR":
        raise AssertionError(f"{path}: first chunk is not IHDR")
    width, height, bit_depth, color_type, _, _, _ = struct.unpack(">IIBBBBB", data[16:29])
    return {"width": width, "height": height, "bit_depth": bit_depth, "color_type": color_type}


def load_baseline():
    if not BASELINE.is_file():
        raise AssertionError(f"frozen baseline missing: {BASELINE}")
    manifest = json.loads(BASELINE.read_text(encoding="utf-8"))
    for field in ("sourceCommit", "originalKeys", "localeHash", "s1Key", "s1Values", "avatars"):
        if field not in manifest:
            raise AssertionError(f"baseline missing field: {field}")
    if len(manifest["originalKeys"]) != 185:
        raise AssertionError(f"baseline must pin 185 original keys, has {len(manifest['originalKeys'])}")
    if set(manifest["localeHash"]) != set(LOCALES):
        raise AssertionError("baseline must pin all 8 locale hashes")
    if len(manifest["avatars"]) != 84:
        raise AssertionError(f"baseline must pin 84 avatar hashes, has {len(manifest['avatars'])}")
    return manifest


def canonical_hash(values):
    blob = json.dumps(values, sort_keys=True, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    return hashlib.sha256(blob).hexdigest()


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
        if match.group(1) in entries:
            raise AssertionError(f"{path}:{lineno}: duplicate key: {match.group(1)}")
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
        profiles = (APP / "ProfileViews.swift").read_text(encoding="utf-8")
        for token in ("AthleteEditorDraft", "AthleteAvatarView", "pendingEditor", "interactiveDismissDisabled"):
            self.assertIn(token, profiles, f"ProfileViews missing S2 wiring {token}")

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
        draft = (APP / "AthleteEditorDraft.swift").read_text(encoding="utf-8")
        for token in (
            "struct AthleteEditorDraft",
            "defaultNewAvatarKey",
            "canonicalAnthropometrics()",
            "canonicalWeightKg()",
            "canonicalHeightCm()",
            "validatedAthlete(",
            "var isDirty",
            "MeasurementPresentation.parsePositive",
            "MeasurementPresentation.kilograms",
            "MeasurementPresentation.centimeters",
        ):
            self.assertIn(token, draft, f"AthleteEditorDraft missing {token}")
        self.assertNotIn("2.54", draft, "draft must not reimplement inch factor")
        self.assertNotIn("0.453", draft, "draft must not reimplement pound factor")
        avatar = (APP / "AthleteAvatarView.swift").read_text(encoding="utf-8")
        for token in ("struct AthleteAvatarView", "import UIKit", "UIImage(named", "initials(for:"):
            self.assertIn(token, avatar, f"AthleteAvatarView missing {token}")

    def test_pbx_membership_and_config(self):
        text = PBX.read_text(encoding="utf-8")
        for name in ("DesignSystem.swift", "ProtocolPresentation.swift", "PresentationTests.swift"):
            self.assertIn(f"path = {name}", text, f"PBX missing file ref {name}")
        for name in ("AthleteEditorDraft.swift", "AthleteAvatarView.swift", "ProfileEditingTests.swift"):
            self.assertIn(f"path = {name}", text, f"PBX missing S2 file ref {name}")
        for build_id, ref_id in (
            ("C00000000000000000000003", "C00000000000000000000001"),
            ("C00000000000000000000004", "C00000000000000000000002"),
            ("C00000000000000000000006", "C00000000000000000000005"),
        ):
            self.assertIn(
                f"{build_id} = {{isa = PBXBuildFile; fileRef = {ref_id};}};",
                text,
                f"PBX missing S2 build file {build_id}",
            )
        app_sources = re.search(
            r"A000000000000000000000a0 = \{isa = PBXSourcesBuildPhase; files = \(([^)]*)\)",
            text,
        )
        self.assertIsNotNone(app_sources, "app Sources phase not found")
        for build_id in ("C00000000000000000000003", "C00000000000000000000004"):
            self.assertIn(build_id, app_sources.group(1), f"app Sources missing {build_id}")
        test_sources = re.search(
            r"A000000000000000000000a3 = \{isa = PBXSourcesBuildPhase; files = \(([^)]*)\)",
            text,
        )
        self.assertIsNotNone(test_sources, "test Sources phase not found")
        self.assertIn("C00000000000000000000006", test_sources.group(1), "test Sources missing S2 tests")
        self.assertIn("ASSETCATALOG_COMPILER_APPICON_NAME = AppIcon", text)
        self.assertEqual(text.count("ASSETCATALOG_COMPILER_APPICON_NAME = AppIcon"), 2)
        self.assertIn("INFOPLIST_KEY_CFBundleDisplayName = OpenJump;", text)
        self.assertNotIn("OpenJumpPrototype", text)
        self.assertIn("PRODUCT_BUNDLE_IDENTIFIER = org.openjump.apple;", text)
        self.assertIn("IPHONEOS_DEPLOYMENT_TARGET = 16.0", text)
        self.assertIn("SWIFT_VERSION = 5.0", text)

    def test_locale_parity_and_new_key(self):
        manifest = load_baseline()
        original_keys = manifest["originalKeys"]
        parsed = {}
        for locale in LOCALES:
            path = APP / f"{locale}.lproj" / "Localizable.strings"
            self.assertTrue(path.is_file(), f"missing strings for {locale}")
            parsed[locale] = parse_strings(path)
        keysets = {locale: set(entries) for locale, entries in parsed.items()}
        self.assertEqual(len(set(map(lambda s: len(s), keysets.values()))), 1, "locale counts differ")
        for locale in LOCALES:
            self.assertEqual(keysets[locale], keysets["en"], f"{locale} keyset differs from en")
        for locale in LOCALES:
            with self.subTest(locale=locale):
                frozen = {key: parsed[locale][key] for key in original_keys}
                self.assertEqual(
                    canonical_hash(frozen),
                    manifest["localeHash"][locale],
                    f"{locale}: original 185 values differ from frozen baseline",
                )
                self.assertEqual(
                    parsed[locale][manifest["s1Key"]],
                    manifest["s1Values"][locale],
                    f"{locale}: S1 {manifest['s1Key']} value changed",
                )
                for key in EXPECTED_S2_KEYS:
                    self.assertIn(key, parsed[locale], f"{locale} missing S2 key {key}")
                    self.assertTrue(parsed[locale][key].strip(), f"{locale} empty S2 value {key}")
        for key, value in parsed["en"].items():
            self.assertTrue(value.strip(), f"en empty value: {key}")

    def test_avatars_pixel_exact(self):
        manifest = load_baseline()
        imagesets = sorted(CATALOG.glob("avatar_*.imageset"))
        found = set()
        for d in imagesets:
            contents = json.loads((d / "Contents.json").read_text(encoding="utf-8"))
            images = contents.get("images", [])
            self.assertEqual(len(images), 1, f"{d.name}: expected one image entry")
            filename = images[0].get("filename")
            self.assertTrue(filename, f"{d.name}: image entry without filename")
            rel = f"apple/OpenJumpApple/Assets.xcassets/{d.name}/{filename}"
            found.add(rel)
            with self.subTest(asset=rel):
                png = APPLE / rel.split("apple/", 1)[1]
                self.assertTrue(png.is_file(), f"missing {rel}")
                self.assertIn(rel, manifest["avatars"], f"{rel}: imageset not in frozen baseline")
                self.assertEqual(
                    hashlib.sha256(png.read_bytes()).hexdigest(),
                    manifest["avatars"][rel],
                    f"{rel}: avatar bytes differ from frozen baseline",
                )
        self.assertEqual(found, set(manifest["avatars"]), "avatar set differs from frozen baseline")


if __name__ == "__main__":
    unittest.main()
