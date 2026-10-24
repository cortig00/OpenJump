#!/usr/bin/env python3
"""Check that the bundled GPL text is byte-for-byte the repository LICENSE."""

from __future__ import annotations

import argparse
import sys
from pathlib import Path


class LicenseCheckError(ValueError):
    """The checked-in license source and Android raw resource differ."""


def check_license(root: Path | str = ".") -> None:
    root = Path(root).resolve()
    source_path = root / "LICENSE"
    bundled_path = root / "app" / "src" / "main" / "res" / "raw" / "gpl_3_0.txt"
    source = source_path.read_bytes()
    bundled = bundled_path.read_bytes()
    if source != bundled:
        limit = min(len(source), len(bundled))
        first_difference = next(
            (index for index in range(limit) if source[index] != bundled[index]),
            limit if len(source) != len(bundled) else None,
        )
        raise LicenseCheckError(
            f"{source_path} and {bundled_path} differ at byte {first_difference} "
            f"(source={len(source)} bytes, bundled={len(bundled)} bytes)"
        )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path("."), help="repository root")
    args = parser.parse_args()
    try:
        check_license(args.root)
    except (OSError, LicenseCheckError) as error:
        print(f"license check: FAIL: {error}", file=sys.stderr)
        return 1
    print("license check: PASS: LICENSE matches bundled GPL raw resource byte-for-byte")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
