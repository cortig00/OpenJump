#!/usr/bin/env python3
"""Validate a locally built unsigned iPhone device app and package an unsigned IPA.

Reads the ACTUAL app bundle produced by the native device job: Info.plist plus
the real Mach-O executable. Never proves platform/minimum from mocks or from
the arm64 architecture string alone.

Fail-closed checks (any violation exits nonzero, no IPA/manifest written):
  - bundle id must be org.openjump.apple, executable must be OpenJumpApple
  - plist minimum (MinimumOSVersion / LSMinimumSystemVersion) must be 16.0
  - every Mach-O slice must be 64-bit ARM64, MH_EXECUTE, platform IOS (2);
    IOSSIMULATOR (7) is explicitly rejected as simulator, not device
  - every Mach-O minimum must agree with the plist exactly (16.0.0);
    a newer minimum (for example 17.0) is rejected, an older minimum is also
    rejected so a lowered deployment target cannot hide the binary minimum
  - encrypted images (cryptid != 0), LC_CODE_SIGNATURE, _CodeSignature, or
    embedded.mobileprovision are rejected: this helper never relabels a
    signed binary as unsigned. If the SDK ever adds an automatic signature,
    this tool fails and the CI job fails instead of claiming UNSIGNED.
  - malformed/truncated/wrong-architecture binaries fail; absolute paths,
    traversal (..), all symlinks, and nested Mach-O payloads fail.

On success writes exactly three outputs: the unsigned IPA
(Payload/OpenJumpApple.app only), a small JSON manifest, and a SHA256 file.
Only stdlib is used so the helper runs on the macOS CI job and on Windows.
"""

import argparse
import hashlib
import json
import os
import plistlib
import re
import struct
import sys
import time
import zipfile
from pathlib import Path, PurePosixPath

EXPECTED_BUNDLE_ID = "org.openjump.apple"
EXPECTED_EXECUTABLE = "OpenJumpApple"
EXPECTED_APP_DIRNAME = "OpenJumpApple.app"
EXPECTED_MIN_MAJOR = 16
EXPECTED_MIN_MINOR = 0

PLATFORM_IOS = 2
PLATFORM_IOSSIMULATOR = 7

CPU_TYPE_ARM64 = 0x0100000C
CPU_TYPE_X86_64 = 0x01000007

MH_MAGIC_64_LE = 0xFEEDFACF
FAT_MAGIC_BE_BYTES = b"\xca\xfe\xba\xbe"
FAT_CIGAM_LE_BYTES = b"\xbe\xba\xfe\xca"

LC_CODE_SIGNATURE = 0x1D
LC_ENCRYPTION_INFO = 0x21
LC_VERSION_MIN_IPHONEOS = 0x24
LC_ENCRYPTION_INFO_64 = 0x2C
LC_BUILD_VERSION = 0x32

MH_EXECUTE = 0x2

SOURCE_SHA_RE = re.compile(r"\A[0-9a-f]{40}\Z")


class DevicePackageError(ValueError):
    pass


def decode_version(value):
    return ((value >> 16) & 0xFFFF, (value >> 8) & 0xFF, value & 0xFF)


def version_dotted(version):
    major, minor, patch = version
    if patch:
        return "%d.%d.%d" % (major, minor, patch)
    return "%d.%d" % (major, minor)


def parse_plist_version(text, where):
    parts = str(text).strip().split(".")
    if len(parts) < 2 or len(parts) > 3 or not all(p.isdigit() for p in parts):
        raise DevicePackageError("bad %s %r: expected MAJOR.MINOR like 16.0" % (where, text))
    major, minor = int(parts[0]), int(parts[1])
    patch = int(parts[2]) if len(parts) == 3 else 0
    return (major, minor, patch)


def parse_thin_macho64(data, base, bound, label):
    """Parse one 64-bit little-endian Mach-O image in data[base:bound]."""
    if bound - base < 32:
        raise DevicePackageError("%s: truncated Mach-O header (%d bytes)" % (label, bound - base))
    (magic, cputype, _cpusubtype, filetype, ncmds, sizeofcmds, _flags, _reserved) = struct.unpack_from(
        "<8I", data, base)
    if magic != MH_MAGIC_64_LE:
        raise DevicePackageError(
            "%s: not a 64-bit LE Mach-O (magic 0x%08x); fat slices are unwrapped before this check" % (label, magic))
    if cputype != CPU_TYPE_ARM64:
        if cputype == CPU_TYPE_X86_64:
            raise DevicePackageError("%s: wrong architecture x86_64, expected ARM64" % label)
        raise DevicePackageError("%s: wrong cputype 0x%08x, expected ARM64" % (label, cputype))
    if filetype != MH_EXECUTE:
        raise DevicePackageError("%s: wrong filetype %d, expected MH_EXECUTE(2) app binary" % (label, filetype))
    if ncmds <= 0 or ncmds > 256:
        raise DevicePackageError("%s: implausible ncmds %d" % (label, ncmds))
    offset = base + 32
    commands_end = offset + sizeofcmds
    if commands_end > bound:
        raise DevicePackageError("%s: truncated load commands (%d > %d)" % (label, commands_end, bound))
    build_versions = []
    version_mins = []
    has_code_signature = False
    encrypted = False
    for index in range(ncmds):
        if offset + 8 > commands_end:
            raise DevicePackageError("%s: truncated load command header %d" % (label, index))
        (cmd, cmdsize) = struct.unpack_from("<2I", data, offset)
        if cmdsize < 8 or offset + cmdsize > commands_end:
            raise DevicePackageError("%s: malformed load command %d (cmd 0x%x size %d)" % (label, index, cmd, cmdsize))
        if cmd == LC_BUILD_VERSION:
            if cmdsize < 24:
                raise DevicePackageError("%s: truncated LC_BUILD_VERSION" % label)
            (platform, minos, _sdk) = struct.unpack_from("<3I", data, offset + 8)
            build_versions.append({"platform": platform, "minos": decode_version(minos), "raw": minos})
        elif cmd == LC_VERSION_MIN_IPHONEOS:
            if cmdsize < 16:
                raise DevicePackageError("%s: truncated LC_VERSION_MIN_IPHONEOS" % label)
            (version, _sdk) = struct.unpack_from("<2I", data, offset + 8)
            version_mins.append(decode_version(version))
        elif cmd == LC_CODE_SIGNATURE:
            has_code_signature = True
        elif cmd in (LC_ENCRYPTION_INFO, LC_ENCRYPTION_INFO_64):
            if cmdsize < 20:
                raise DevicePackageError("%s: truncated encryption command" % label)
            (cryptid,) = struct.unpack_from("<I", data, offset + 16)
            if cryptid != 0:
                encrypted = True
        offset += cmdsize
    if offset != commands_end:
        raise DevicePackageError("%s: load command size mismatch" % label)
    if not build_versions:
        raise DevicePackageError("%s: no LC_BUILD_VERSION platform proof found; legacy minimum alone is insufficient" % label)
    return {
        "cputype": cputype,
        "build_versions": build_versions,
        "version_mins": version_mins,
        "has_code_signature": has_code_signature,
        "encrypted": encrypted,
    }


def parse_macho_file(path):
    """Parse a thin-64 or FAT Mach-O file. Returns (slices, arch, platform, minos)."""
    raw = Path(path).read_bytes()
    label = os.path.basename(str(path))
    if len(raw) < 4:
        raise DevicePackageError("%s: truncated file (%d bytes)" % (label, len(raw)))
    magic_bytes = raw[0:4]
    slices = []
    if magic_bytes == FAT_MAGIC_BE_BYTES or magic_bytes == FAT_CIGAM_LE_BYTES:
        swapped = magic_bytes == FAT_CIGAM_LE_BYTES
        endian = "<" if swapped else ">"
        (nfat,) = struct.unpack_from(endian + "I", raw, 4)
        if nfat <= 0 or nfat > 8:
            raise DevicePackageError("%s: implausible fat slice count %d" % (label, nfat))
        if len(raw) < 8 + 20 * nfat:
            raise DevicePackageError("%s: truncated fat header" % label)
        for i in range(nfat):
            off = 8 + 20 * i
            fields = struct.unpack_from(endian + "5I", raw, off)
            (cputype, _cpusubtype, slice_off, slice_size, _align) = fields
            if slice_off + slice_size > len(raw) or slice_size < 32:
                raise DevicePackageError("%s: truncated fat slice %d" % (label, i))
            slices.append(parse_thin_macho64(raw, slice_off, slice_off + slice_size, "%s slice %d" % (label, i)))
    elif struct.unpack_from("<I", raw, 0)[0] == MH_MAGIC_64_LE:
        slices.append(parse_thin_macho64(raw, 0, len(raw), label))
    else:
        (magic,) = struct.unpack_from("<I", raw, 0)
        if magic == 0xFEEDFACE:
            raise DevicePackageError("%s: 32-bit Mach-O is not a valid arm64 device binary" % label)
        raise DevicePackageError("%s: unknown Mach-O magic 0x%08x" % (label, magic))
    if not slices:
        raise DevicePackageError("%s: no Mach-O slices found" % label)
    for sl in slices:
        if sl["encrypted"]:
            raise DevicePackageError("%s: encrypted image (cryptid != 0) is not an unsigned local build" % label)
        if sl["has_code_signature"]:
            raise DevicePackageError(
                "%s: LC_CODE_SIGNATURE present; refusing to label a signed binary UNSIGNED" % label)
        for entry in sl["build_versions"]:
            if entry["platform"] == PLATFORM_IOSSIMULATOR:
                raise DevicePackageError(
                    "%s: platform IOSSIMULATOR(7) is a simulator slice, not an iPhone device slice" % label)
            if entry["platform"] != PLATFORM_IOS:
                raise DevicePackageError("%s: wrong platform %d, expected IOS(2)" % (label, entry["platform"]))
            if entry["minos"] != (EXPECTED_MIN_MAJOR, EXPECTED_MIN_MINOR, 0):
                if entry["minos"] > (EXPECTED_MIN_MAJOR, EXPECTED_MIN_MINOR, 0):
                    raise DevicePackageError(
                        "%s: newer binary minimum %s rejected; expected 16.0" % (label, version_dotted(entry["minos"])))
                raise DevicePackageError(
                    "%s: unexpected binary minimum %s; expected 16.0 (not lowered to hide minimum)"
                    % (label, version_dotted(entry["minos"])))
        for version in sl["version_mins"]:
            if version != (EXPECTED_MIN_MAJOR, EXPECTED_MIN_MINOR, 0):
                if version > (EXPECTED_MIN_MAJOR, EXPECTED_MIN_MINOR, 0):
                    raise DevicePackageError(
                        "%s: newer LC_VERSION_MIN %s rejected; expected 16.0" % (label, version_dotted(version)))
                raise DevicePackageError(
                    "%s: unexpected LC_VERSION_MIN %s; expected 16.0" % (label, version_dotted(version)))
    first_min = slices[0]["build_versions"][0]["minos"] if slices[0]["build_versions"] else slices[0]["version_mins"][0]
    for sl in slices[1:]:
        other = sl["build_versions"][0]["minos"] if sl["build_versions"] else sl["version_mins"][0]
        if other != first_min:
            raise DevicePackageError("%s: fat slices disagree on minimum (%s vs %s)" % (
                label, version_dotted(first_min), version_dotted(other)))
    return {"slices": slices, "arch": "arm64", "platform": "IOS", "minos": first_min}


def read_app_info(app_dir):
    app_dir = Path(app_dir)
    if app_dir.name != EXPECTED_APP_DIRNAME:
        raise DevicePackageError("app directory must be named %s, got %r" % (EXPECTED_APP_DIRNAME, app_dir.name))
    info_path = app_dir / "Info.plist"
    if not info_path.is_file() or info_path.is_symlink():
        raise DevicePackageError("Info.plist missing at %s" % info_path)
    try:
        with info_path.open("rb") as handle:
            info = plistlib.load(handle)
    except Exception as exc:
        raise DevicePackageError("Info.plist unreadable: %s" % exc)
    if not isinstance(info, dict):
        raise DevicePackageError("Info.plist top level must be a dict")
    executable = info.get("CFBundleExecutable")
    bundle_id = info.get("CFBundleIdentifier")
    if executable != EXPECTED_EXECUTABLE:
        raise DevicePackageError("wrong CFBundleExecutable %r, expected %r" % (executable, EXPECTED_EXECUTABLE))
    if bundle_id != EXPECTED_BUNDLE_ID:
        raise DevicePackageError("wrong CFBundleIdentifier %r, expected %r" % (bundle_id, EXPECTED_BUNDLE_ID))
    if info.get("DTPlatformName") != "iphoneos" or info.get("CFBundleSupportedPlatforms") != ["iPhoneOS"]:
        raise DevicePackageError("Info.plist must declare DTPlatformName=iphoneos and CFBundleSupportedPlatforms=[iPhoneOS]")
    raw_min = info.get("MinimumOSVersion")
    if raw_min is None:
        raise DevicePackageError("Info.plist has no MinimumOSVersion")
    plist_min = parse_plist_version(raw_min, "MinimumOSVersion")
    if plist_min != (EXPECTED_MIN_MAJOR, EXPECTED_MIN_MINOR, 0):
        if plist_min > (EXPECTED_MIN_MAJOR, EXPECTED_MIN_MINOR, 0):
            raise DevicePackageError("newer plist minimum %s rejected; expected 16.0" % version_dotted(plist_min))
        raise DevicePackageError("unexpected plist minimum %s; expected 16.0" % version_dotted(plist_min))
    executable_path = app_dir / str(executable)
    if not executable_path.is_file() or executable_path.is_symlink():
        raise DevicePackageError("bundle executable missing at %s" % executable_path)
    return {"executable": str(executable), "bundle_id": str(bundle_id), "minos": plist_min,
            "executable_path": executable_path, "info": info}


def check_unsigned_app(app_dir):
    app_dir = Path(app_dir)
    signature_dir = app_dir / "_CodeSignature"
    if signature_dir.exists() or (app_dir / "embedded.mobileprovision").exists():
        raise DevicePackageError("app contains provisioning/signature material; not UNSIGNED")
    # Exact-name check only; stray profiles under subdirectories are caught by the
    # packaging walk refusing unexpected signature/profile paths.
    for child in app_dir.rglob("*"):
        name = child.name
        if name == "embedded.mobileprovision" or (name == "_CodeSignature"):
            raise DevicePackageError("app contains %r; not UNSIGNED" % name)


def validate_symlink_target(rel_posix, target):
    if not target or target.startswith("/") or target.startswith("\\"):
        raise DevicePackageError("absolute symlink target rejected: %r" % rel_posix)
    if any(part == ".." for part in PurePosixPath(target).parts):
        raise DevicePackageError("escaping symlink target rejected: %r" % rel_posix)
    if len(target) > 512:
        raise DevicePackageError("overlong symlink target rejected: %r" % rel_posix)


def iter_app_entries(app_dir):
    """Yield (relative_posix, absolute_path, lstat) with path/symlink safety."""
    app_dir = Path(app_dir)
    if app_dir.is_symlink():
        raise DevicePackageError("app bundle root must not be a symlink")
    entries = []
    for root, dirs, files in os.walk(app_dir, topdown=True, followlinks=False):
        dirs.sort()
        files.sort()
        for name in dirs + files:
            abs_path = Path(root) / name
            try:
                rel = abs_path.relative_to(app_dir)
            except ValueError:
                raise DevicePackageError("entry outside app bundle: %r" % name)
            rel_posix = rel.as_posix()
            if rel_posix.startswith("/") or rel_posix.startswith("\\"):
                raise DevicePackageError("absolute bundle path rejected: %r" % rel_posix)
            if any(part in ("", ".", "..") for part in PurePosixPath(rel_posix).parts):
                raise DevicePackageError("unsafe bundle path rejected: %r" % rel_posix)
            if len(rel_posix) > 512:
                raise DevicePackageError("overlong bundle path rejected: %r" % rel_posix[:64])
            lst = os.lstat(abs_path)
            entries.append((rel_posix, abs_path, lst))
            # Native iPhone bundles in this static-framework lane need no links.
            # Reject even internal links, rather than follow hidden payloads.
            if abs_path.is_symlink():
                validate_symlink_target(rel_posix, os.readlink(abs_path))
                raise DevicePackageError("symlink bundle entry rejected: %r" % rel_posix)
    # Also validate the top-level app dir itself is a real directory, not a link.
    if app_dir.is_symlink():
        raise DevicePackageError("app bundle root must not be a symlink")
    entries.sort(key=lambda item: item[0])
    if not entries:
        raise DevicePackageError("app bundle is empty")
    return entries


def validate_app(app_dir):
    info = read_app_info(app_dir)
    check_unsigned_app(app_dir)
    macho = parse_macho_file(info["executable_path"])
    if macho["minos"] != info["minos"]:
        raise DevicePackageError(
            "plist minimum %s disagrees with Mach-O minimum %s" % (
                version_dotted(info["minos"]), version_dotted(macho["minos"])))
    for rel_posix, path, _lst in iter_app_entries(Path(app_dir)):
        if path.is_file() and rel_posix != info["executable"]:
            with path.open("rb") as handle:
                magic = handle.read(4)
            if magic in (b"\xcf\xfa\xed\xfe", b"\xce\xfa\xed\xfe", b"\xfe\xed\xfa\xcf", b"\xfe\xed\xfa\xce",
                         FAT_MAGIC_BE_BYTES, FAT_CIGAM_LE_BYTES, b"\xca\xfe\xba\xbf", b"\xbf\xba\xfe\xca"):
                raise DevicePackageError("unexpected nested Mach-O %r; this lane requires a statically linked app" % rel_posix)
    return {"bundle_id": info["bundle_id"], "executable": info["executable"],
            "minos": info["minos"], "arch": macho["arch"], "platform": macho["platform"]}


def write_ipa(app_dir, ipa_path):
    import stat as statmod
    app_dir = Path(app_dir)
    validation = validate_app(app_dir)
    entries = iter_app_entries(app_dir)
    ipa_path = Path(ipa_path)
    ipa_path.parent.mkdir(parents=True, exist_ok=True)
    prefix = "Payload/%s/" % app_dir.name
    with zipfile.ZipFile(ipa_path, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for rel_posix, abs_path, lst in entries:
            arcname = prefix + rel_posix
            mtime = time.localtime(lst.st_mtime)
            if mtime.tm_year < 1980:
                mtime = time.struct_time((1980, 1, 1, 0, 0, 0, 0, 0, -1))
            info = zipfile.ZipInfo(arcname + ("/" if abs_path.is_dir() and not abs_path.is_symlink() else ""),
                                   date_time=mtime[:6])
            info.create_system = 3
            info.compress_type = zipfile.ZIP_DEFLATED
            if abs_path.is_symlink():
                target = os.readlink(abs_path)
                info.external_attr = (0o120777 & 0xFFFF) << 16
                archive.writestr(info, target.encode("utf-8"))
            elif abs_path.is_dir():
                info.external_attr = (0o40755 & 0xFFFF) << 16
                archive.writestr(info, b"")
            elif abs_path.is_file():
                if rel_posix == validation["executable"]:
                    info.external_attr = (0o100755 & 0xFFFF) << 16
                else:
                    preserved = statmod.S_IMODE(lst.st_mode) & 0o777
                    if preserved == 0:
                        preserved = 0o644
                    info.external_attr = ((0o100000 | preserved) & 0xFFFF) << 16
                with open(abs_path, "rb") as handle:
                    archive.writestr(info, handle.read())
            else:
                raise DevicePackageError("unsupported bundle entry type: %r" % rel_posix)
    return validation


def package_app(app_dir, ipa_path, manifest_path, sha_path, source_sha):
    if not SOURCE_SHA_RE.match(source_sha or ""):
        raise DevicePackageError("source-sha must be a 40-char lowercase hex commit SHA")
    validation = write_ipa(app_dir, ipa_path)
    digest = hashlib.sha256(Path(ipa_path).read_bytes()).hexdigest()
    manifest = {
        "schema_version": 1,
        "ipa": Path(ipa_path).name,
        "sha256": digest,
        "source_sha": source_sha,
        "bundle_id": validation["bundle_id"],
        "executable": validation["executable"],
        "min_os": version_dotted(validation["minos"]),
        "arch": validation["arch"],
        "platform": validation["platform"],
        "signing": "none",
        "provisioning": "none",
        "nested_macho": "none",
        "symlinks": "none",
        "physical_device_test": "NOT_RUN",
        "warning": ("Unsigned developer artifact: install requires the downloader's own "
                    "local signing; not installable as-is and not a store release."),
    }
    manifest_path = Path(manifest_path)
    manifest_path.parent.mkdir(parents=True, exist_ok=True)
    manifest_path.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    sha_path = Path(sha_path)
    sha_path.parent.mkdir(parents=True, exist_ok=True)
    sha_path.write_text("%s  %s\n" % (digest, Path(ipa_path).name), encoding="utf-8")
    return manifest


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--app", type=Path, required=True)
    parser.add_argument("--ipa", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--sha256-file", type=Path, required=True)
    parser.add_argument("--source-sha", required=True)
    args = parser.parse_args(argv)
    try:
        manifest = package_app(args.app, args.ipa, args.manifest, args.sha256_file, args.source_sha)
    except (DevicePackageError, OSError) as exc:
        print("device package validation FAILED: %s" % exc, file=sys.stderr)
        return 1
    print("device package PASS: %s sha256=%s source=%s arch=%s platform=%s min=%s signing=none" % (
        manifest["ipa"], manifest["sha256"][:12], manifest["source_sha"][:12],
        manifest["arch"], manifest["platform"], manifest["min_os"]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
