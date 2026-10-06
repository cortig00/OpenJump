"""Independent fixtures for the unsigned device IPA helper.

Every case builds its own minimal Mach-O/Info.plist/archive inputs; no test
reuses the native build output, and no fixture success implies native proof.
The happy path calls the real package_device_ipa.package_app entry point.
"""

import hashlib
import json
import plistlib
import struct
import sys
import unittest
import zipfile
from pathlib import Path

import package_device_ipa as helper

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT))

ARM64 = helper.CPU_TYPE_ARM64
X86_64 = helper.CPU_TYPE_X86_64


def encode_version(major, minor, patch=0):
    return (major << 16) | (minor << 8) | patch


def build_command_platform(platform, major, minor, patch=0, sdk=(26, 0, 0)):
    return struct.pack("<6I", helper.LC_BUILD_VERSION, 24, platform,
                       encode_version(major, minor, patch),
                       encode_version(*sdk), 0)


def build_command_version_min(major, minor, patch=0, sdk=(26, 0, 0)):
    return struct.pack("<4I", helper.LC_VERSION_MIN_IPHONEOS, 16,
                       encode_version(major, minor, patch), encode_version(*sdk))


def build_command_signature():
    return struct.pack("<4I", helper.LC_CODE_SIGNATURE, 16, 0, 0)


def build_command_encryption(cryptid=1):
    return struct.pack("<5I", helper.LC_ENCRYPTION_INFO, 20, 0, 0, cryptid)


def thin_macho(cputype=ARM64, commands=None, filetype=2):
    if commands is None:
        commands = [build_command_platform(helper.PLATFORM_IOS, 16, 0)]
    payload = b"".join(commands)
    header = struct.pack("<8I", helper.MH_MAGIC_64_LE, cputype, 0, filetype,
                         len(commands), len(payload), 0, 0)
    body = b"\x00" * 64
    return header + payload + body


def fat_macho(slices):
    header = struct.pack(">2I", 0xCAFEBABE, len(slices))
    offset = 8 + 20 * len(slices)
    entries = b""
    body = b""
    for blob in slices:
        entries += struct.pack(">5I", ARM64, 0, offset, len(blob), 3)
        offset += len(blob)
        body += blob
    return header + entries + body


def make_app(base, *, bundle_id=helper.EXPECTED_BUNDLE_ID,
             executable=helper.EXPECTED_EXECUTABLE,
             min_version="16.0", macho_bytes=None, extra_files=None):
    app = base / helper.EXPECTED_APP_DIRNAME
    app.mkdir(parents=True, exist_ok=True)
    info = {"CFBundleExecutable": executable, "CFBundleIdentifier": bundle_id,
            "MinimumOSVersion": min_version, "CFBundleVersion": "1",
            "DTPlatformName": "iphoneos", "CFBundleSupportedPlatforms": ["iPhoneOS"]}
    with (app / "Info.plist").open("wb") as handle:
        plistlib.dump(info, handle)
    if macho_bytes is None:
        macho_bytes = thin_macho()
    (app / executable).write_bytes(macho_bytes)
    try:
        import os
        os.chmod(app / executable, 0o755)
    except OSError:
        pass
    (app / "resource.txt").write_text("resource", encoding="utf-8")
    for name, content in (extra_files or {}).items():
        path = app / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(content)
    return app


class DeviceParserTests(unittest.TestCase):
    def write_binary(self, data):
        import tempfile
        tmp = Path(self.tmp) / ("bin-%d" % len(list(Path(self.tmp).glob("*"))))
        tmp.write_bytes(data)
        return tmp

    def setUp(self):
        import tempfile
        self._dir = tempfile.TemporaryDirectory()
        self.tmp = Path(self._dir.name)

    def tearDown(self):
        self._dir.cleanup()

    def test_happy_thin_parses_ios_arm64_min16(self):
        result = helper.parse_macho_file(self.write_binary(thin_macho()))
        self.assertEqual((result["arch"], result["platform"]), ("arm64", "IOS"))
        self.assertEqual(result["minos"][:2], (16, 0))

    def test_simulator_platform_7_is_rejected_not_device(self):
        blob = thin_macho(commands=[build_command_platform(helper.PLATFORM_IOSSIMULATOR, 16, 0)])
        with self.assertRaisesRegex(helper.DevicePackageError, "IOSSIMULATOR"):
            helper.parse_macho_file(self.write_binary(blob))

    def test_newer_macho_minimum_17_is_rejected(self):
        blob = thin_macho(commands=[build_command_platform(helper.PLATFORM_IOS, 17, 0)])
        with self.assertRaisesRegex(helper.DevicePackageError, "newer binary minimum"):
            helper.parse_macho_file(self.write_binary(blob))

    def test_older_macho_minimum_is_not_silently_accepted(self):
        blob = thin_macho(commands=[build_command_platform(helper.PLATFORM_IOS, 15, 0)])
        with self.assertRaisesRegex(helper.DevicePackageError, "unexpected binary minimum"):
            helper.parse_macho_file(self.write_binary(blob))

    def test_x86_64_is_rejected(self):
        with self.assertRaisesRegex(helper.DevicePackageError, "x86_64"):
            helper.parse_macho_file(self.write_binary(thin_macho(cputype=X86_64)))

    def test_truncated_binary_is_rejected(self):
        with self.assertRaises(helper.DevicePackageError):
            helper.parse_macho_file(self.write_binary(thin_macho()[:40]))

    def test_garbage_magic_is_rejected(self):
        with self.assertRaisesRegex(helper.DevicePackageError, "magic"):
            helper.parse_macho_file(self.write_binary(b"\x00\x01\x02\x03" + b"\x00" * 64))

    def test_32bit_magic_is_rejected(self):
        blob = struct.pack("<I", 0xFEEDFACE) + b"\x00" * 64
        with self.assertRaisesRegex(helper.DevicePackageError, "32-bit"):
            helper.parse_macho_file(self.write_binary(blob))

    def test_missing_version_command_is_rejected(self):
        blob = thin_macho(commands=[struct.pack("<2I", 0x99, 8)])
        with self.assertRaisesRegex(helper.DevicePackageError, "no LC_BUILD_VERSION"):
            helper.parse_macho_file(self.write_binary(blob))

    def test_encrypted_image_is_rejected(self):
        blob = thin_macho(commands=[build_command_platform(helper.PLATFORM_IOS, 16, 0),
                                    build_command_encryption(1)])
        with self.assertRaisesRegex(helper.DevicePackageError, "encrypted"):
            helper.parse_macho_file(self.write_binary(blob))

    def test_code_signature_lc_is_not_called_unsigned(self):
        blob = thin_macho(commands=[build_command_platform(helper.PLATFORM_IOS, 16, 0),
                                    build_command_signature()])
        with self.assertRaisesRegex(helper.DevicePackageError, "LC_CODE_SIGNATURE"):
            helper.parse_macho_file(self.write_binary(blob))

    def test_legacy_minimum_alone_has_no_platform_proof(self):
        blob = thin_macho(commands=[build_command_version_min(16, 0)])
        with self.assertRaisesRegex(helper.DevicePackageError, "no LC_BUILD_VERSION platform proof"):
            helper.parse_macho_file(self.write_binary(blob))

    def test_legacy_minimum_supplementary_agreement_and_newer_rejection(self):
        modern = build_command_platform(2, 16, 0)
        blob = thin_macho(commands=[modern, build_command_version_min(16, 0)])
        self.assertEqual(helper.parse_macho_file(self.write_binary(blob))["minos"], (16, 0, 0))
        bad = thin_macho(commands=[modern, build_command_version_min(17, 0)])
        with self.assertRaisesRegex(helper.DevicePackageError, "newer LC_VERSION_MIN"):
            helper.parse_macho_file(self.write_binary(bad))

    def test_platform_numeric_device_vs_simulator_independent_oracle(self):
        self.assertEqual(helper.PLATFORM_IOS, 2)
        self.assertEqual(helper.PLATFORM_IOSSIMULATOR, 7)
        self.assertEqual(helper.parse_macho_file(self.write_binary(thin_macho(commands=[build_command_platform(2, 16, 0)])))["platform"], "IOS")
        with self.assertRaisesRegex(helper.DevicePackageError, "IOSSIMULATOR"):
            helper.parse_macho_file(self.write_binary(thin_macho(commands=[build_command_platform(7, 16, 0)])))

    def test_patch_level_newer_minimum_is_rejected(self):
        blob = thin_macho(commands=[build_command_platform(2, 16, 0, 1)])
        with self.assertRaisesRegex(helper.DevicePackageError, "newer binary minimum"):
            helper.parse_macho_file(self.write_binary(blob))

    def test_fat_slice_with_simulator_platform_is_rejected(self):
        blob = fat_macho([thin_macho(),
                          thin_macho(commands=[build_command_platform(helper.PLATFORM_IOSSIMULATOR, 16, 0)])])
        with self.assertRaisesRegex(helper.DevicePackageError, "IOSSIMULATOR"):
            helper.parse_macho_file(self.write_binary(blob))


class DevicePackageTests(unittest.TestCase):
    def setUp(self):
        import tempfile
        self._dir = tempfile.TemporaryDirectory()
        self.tmp = Path(self._dir.name)
        self.source_sha = "a" * 40

    def tearDown(self):
        self._dir.cleanup()

    def package(self, app):
        ipa = self.tmp / "out.ipa"
        manifest = self.tmp / "manifest.json"
        sha = self.tmp / "out.sha256"
        result = helper.package_app(app, ipa, manifest, sha, self.source_sha)
        return ipa, manifest, sha, result

    def test_happy_archive_paths_perms_sha_and_manifest(self):
        app = make_app(self.tmp / "happy")
        ipa, manifest_path, sha_path, _ = self.package(app)
        with zipfile.ZipFile(ipa) as archive:
            names = sorted(archive.namelist())
        self.assertTrue(all(n.startswith("Payload/OpenJumpApple.app/") for n in names))
        self.assertIn("Payload/OpenJumpApple.app/Info.plist", names)
        self.assertIn("Payload/OpenJumpApple.app/OpenJumpApple", names)
        self.assertFalse(any(".." in n or n.startswith("/") for n in names))
        with zipfile.ZipFile(ipa) as archive:
            exe_info = archive.getinfo("Payload/OpenJumpApple.app/OpenJumpApple")
            res_info = archive.getinfo("Payload/OpenJumpApple.app/resource.txt")
        self.assertEqual((exe_info.external_attr >> 16) & 0o777, 0o755)
        self.assertEqual(exe_info.compress_type, zipfile.ZIP_DEFLATED)
        self.assertEqual(res_info.compress_type, zipfile.ZIP_DEFLATED)
        import os
        expected_res = (os.lstat(app / "resource.txt").st_mode & 0o777) or 0o644
        self.assertEqual((res_info.external_attr >> 16) & 0o777, expected_res)
        digest = hashlib.sha256(ipa.read_bytes()).hexdigest()
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        self.assertEqual(manifest["sha256"], digest)
        self.assertEqual(manifest["source_sha"], self.source_sha)
        self.assertEqual(manifest["bundle_id"], helper.EXPECTED_BUNDLE_ID)
        self.assertEqual(manifest["min_os"], "16.0")
        self.assertEqual(manifest["arch"], "arm64")
        self.assertEqual(manifest["platform"], "IOS")
        self.assertEqual(manifest["signing"], "none")
        self.assertEqual(manifest["provisioning"], "none")
        self.assertEqual(sha_path.read_text(encoding="utf-8"), "%s  %s\n" % (digest, ipa.name))

    def test_wrong_bundle_id_is_rejected(self):
        app = make_app(self.tmp / "badid", bundle_id="com.example.wrong")
        with self.assertRaisesRegex(helper.DevicePackageError, "CFBundleIdentifier"):
            self.package(app)

    def test_wrong_executable_name_is_rejected(self):
        app = make_app(self.tmp / "badexe", executable="WrongName",
                       macho_bytes=thin_macho())
        with self.assertRaisesRegex(helper.DevicePackageError, "CFBundleExecutable"):
            self.package(app)

    def test_newer_plist_minimum_is_rejected(self):
        app = make_app(self.tmp / "newplist", min_version="17.0",
                       macho_bytes=thin_macho(commands=[build_command_platform(helper.PLATFORM_IOS, 17, 0)]))
        with self.assertRaisesRegex(helper.DevicePackageError, "newer plist minimum"):
            self.package(app)

    def test_embedded_provisioning_profile_is_rejected(self):
        app = make_app(self.tmp / "profile")
        (app / "embedded.mobileprovision").write_bytes(b"profile")
        with self.assertRaisesRegex(helper.DevicePackageError, "provisioning/signature"):
            self.package(app)

    def test_code_signature_directory_is_rejected(self):
        app = make_app(self.tmp / "sigdir")
        (app / "_CodeSignature").mkdir()
        (app / "_CodeSignature" / "CodeResources").write_bytes(b"sig")
        with self.assertRaisesRegex(helper.DevicePackageError, "provisioning/signature"):
            self.package(app)

    def test_absolute_symlink_target_is_rejected(self):
        import os
        app = make_app(self.tmp / "abslink")
        link = app / "evil-link"
        try:
            os.symlink("/etc/passwd", link)
        except OSError as exc:
            self.skipTest("symlinks unavailable: %s" % exc)
        with self.assertRaisesRegex(helper.DevicePackageError, "absolute symlink"):
            self.package(app)

    def test_escaping_symlink_target_is_rejected(self):
        import os
        app = make_app(self.tmp / "esclink")
        link = app / "escape-link"
        try:
            os.symlink("../outside.txt", link)
        except OSError as exc:
            self.skipTest("symlinks unavailable: %s" % exc)
        with self.assertRaisesRegex(helper.DevicePackageError, "escaping symlink"):
            self.package(app)

    def test_nested_macho_is_rejected(self):
        app = make_app(self.tmp / "nested", extra_files={"Frameworks/unexpected.dylib": thin_macho()})
        with self.assertRaisesRegex(helper.DevicePackageError, "unexpected nested Mach-O"):
            self.package(app)
        self.assertFalse((self.tmp / "out.ipa").exists())

    def test_plist_simulator_platform_and_missing_keys_are_rejected(self):
        app = make_app(self.tmp / "platform")
        plist = app / "Info.plist"
        for extra in ({"DTPlatformName": "iphonesimulator", "CFBundleSupportedPlatforms": ["iPhoneSimulator"]},
                      {"DTPlatformName": "iphoneos"}, {}):
            data = {"CFBundleExecutable": "OpenJumpApple", "CFBundleIdentifier": "org.openjump.apple",
                    "MinimumOSVersion": "16.0", **extra}
            plist.write_bytes(plistlib.dumps(data))
            with self.assertRaisesRegex(helper.DevicePackageError, "DTPlatformName"):
                self.package(app)

    def test_newer_plist_patch_level_is_rejected(self):
        app = make_app(self.tmp / "patch", min_version="16.0.1")
        with self.assertRaisesRegex(helper.DevicePackageError, "newer plist minimum"):
            self.package(app)

    def test_internal_symlink_policy_is_not_packaged(self):
        app = make_app(self.tmp / "internal")
        link = app / "linked-resource"
        # Model lstat/is_symlink without requiring Windows symlink privilege.
        from unittest.mock import patch
        import os
        original = Path.is_symlink
        with patch.object(Path, "is_symlink", lambda path: path == app / "resource.txt" or original(path)), \
             patch.object(os, "readlink", return_value="Info.plist"):
            with self.assertRaisesRegex(helper.DevicePackageError, "symlink bundle entry"):
                self.package(app)
        self.assertFalse((self.tmp / "out.ipa").exists())

    def test_bad_source_sha_is_rejected(self):
        app = make_app(self.tmp / "badsha")
        with self.assertRaisesRegex(helper.DevicePackageError, "source-sha"):
            helper.package_app(app, self.tmp / "o.ipa", self.tmp / "m.json",
                               self.tmp / "o.sha", "not-a-sha")

    def test_symlink_target_policy_rejects_absolute_and_escape_without_filesystem(self):
        with self.assertRaisesRegex(helper.DevicePackageError, "absolute symlink"):
            helper.validate_symlink_target("evil-link", "/etc/passwd")
        with self.assertRaisesRegex(helper.DevicePackageError, "escaping symlink"):
            helper.validate_symlink_target("escape-link", "../outside.txt")
        # A benign relative target passes the policy check.
        helper.validate_symlink_target("ok-link", "resource.txt")


if __name__ == "__main__":
    unittest.main()
