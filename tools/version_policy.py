#!/usr/bin/env python3
"""Validate OpenJump release metadata against local annotated Git tags.

This tool deliberately uses only the Python standard library and the local Git
object database. It never fetches, publishes, or contacts a remote service.
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable

MAX_VERSION_CODE = 2_100_000_000
_ALLOWED_KEYS = {"VERSION_CODE", "VERSION_NAME"}
_VERSION_RE = re.compile(r"^(?P<major>0|[1-9][0-9]*)\.(?P<minor>0|[1-9][0-9]*)\.(?P<patch>0|[1-9][0-9]*)(?P<beta>-beta\.(?P<beta_number>[1-9][0-9]*))?$")
_LEGACY_CODE_RE = re.compile(r"\bversionCode\s*=\s*(\d+)")
_LEGACY_NAME_RE = re.compile(r'\bversionName\s*=\s*"([^"]+)"')


class PolicyError(ValueError):
    """An actionable release-policy violation."""


@dataclass(frozen=True)
class VersionMetadata:
    code: int
    name: str


def _git(repo: Path, *args: str, check: bool = True) -> str:
    result = subprocess.run(
        ["git", *args],
        cwd=repo,
        check=False,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    if check and result.returncode != 0:
        detail = result.stderr.strip() or result.stdout.strip() or "unknown Git error"
        raise PolicyError(f"git {' '.join(args)} failed: {detail}")
    return result.stdout.strip()


def _parse_version_name(name: str, source: str) -> None:
    if name == "1.0":  # Authorized first public Play identity; historical names stay immutable.
        return
    match = _VERSION_RE.fullmatch(name)
    if not match:
        raise PolicyError(
            f"{source}: VERSION_NAME={name!r} is not stable MAJOR.MINOR.PATCH "
            "or beta MAJOR.MINOR.PATCH-beta.N (N must be positive), or public 1.0"
        )


def _version_name_parts(name: str) -> tuple[tuple[int, int, int], int | None]:
    if name == "1.0":
        return (1, 0, 0), None
    match = _VERSION_RE.fullmatch(name)
    if match is None:  # parse_properties validates names before policy compares them
        raise PolicyError(f"cannot compare malformed VERSION_NAME={name!r}")
    base = (int(match.group("major")), int(match.group("minor")), int(match.group("patch")))
    beta = int(match.group("beta_number")) if match.group("beta_number") is not None else None
    return base, beta


def _validate_release_name_transition(
    left_tag: str,
    left: VersionMetadata,
    right_tag: str,
    right: VersionMetadata,
    *,
    allow_equal: bool = False,
) -> None:
    left_base, left_beta = _version_name_parts(left.name)
    right_base, right_beta = _version_name_parts(right.name)
    if right_base < left_base:
        # Public naming reset requested for the first production launch. Never
        # relax Android code ordering, nor allow arbitrary semantic downgrades.
        if right.name == "1.0" and right.code > left.code:
            return
        raise PolicyError(
            f"release names must not decrease by ancestry: {left_tag} {left.name}, "
            f"then {right_tag} {right.name}"
        )
    if left_base != right_base:
        return
    if left_beta is None and right_beta is not None:
        raise PolicyError(
            f"beta release cannot follow stable release of the same base: "
            f"{left_tag} {left.name}, then {right_tag} {right.name}"
        )
    if left_beta is not None and right_beta is not None:
        if allow_equal and left_beta == right_beta:
            return
        if right_beta <= left_beta:
            raise PolicyError(
                f"beta ordinals must increase by ancestry: {left_tag} {left.name}, "
                f"then {right_tag} {right.name}"
            )


def parse_properties(text: str, source: str = "version.properties") -> VersionMetadata:
    """Parse the deliberately small, strict version.properties format."""
    values: dict[str, str] = {}
    for line_number, raw_line in enumerate(text.splitlines(), 1):
        line = raw_line.rstrip("\r")
        if not line.strip() or line.lstrip().startswith(("#", "!")):
            continue
        match = re.fullmatch(r"([A-Z][A-Z0-9_]*)=(.*)", line)
        if not match:
            raise PolicyError(f"{source}:{line_number}: expected KEY=VALUE")
        key, value = match.groups()
        if key not in _ALLOWED_KEYS:
            raise PolicyError(f"{source}:{line_number}: unexpected key {key!r}")
        if key in values:
            raise PolicyError(f"{source}:{line_number}: repeated key {key!r}")
        if not value:
            raise PolicyError(f"{source}:{line_number}: empty value for {key}")
        values[key] = value

    missing = sorted(_ALLOWED_KEYS - values.keys())
    if missing:
        raise PolicyError(f"{source}: missing key(s): {', '.join(missing)}")

    code_text = values["VERSION_CODE"]
    if not re.fullmatch(r"[1-9][0-9]*", code_text):
        raise PolicyError(f"{source}: VERSION_CODE={code_text!r} is not a positive integer")
    code = int(code_text)
    if code > MAX_VERSION_CODE:
        raise PolicyError(
            f"{source}: VERSION_CODE={code} exceeds Android/Play limit {MAX_VERSION_CODE}"
        )
    name = values["VERSION_NAME"]
    _parse_version_name(name, source)
    return VersionMetadata(code=code, name=name)


def _show_file(repo: Path, revision: str, path: str) -> str | None:
    result = subprocess.run(
        ["git", "show", f"{revision}:{path}"],
        cwd=repo,
        check=False,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    if result.returncode != 0:
        return None
    return result.stdout


def _metadata_at_tag(repo: Path, tag: str, commit: str) -> VersionMetadata:
    properties = _show_file(repo, commit, "version.properties")
    if properties is not None:
        return parse_properties(properties, f"{tag}@{commit[:12]}/version.properties")

    # v1.0.0 predates the single-source file. Keep this one explicitly bounded
    # compatibility exception; a future release without version.properties is
    # a policy failure rather than an implicit fallback to Gradle source.
    if tag != "v1.0.0":
        raise PolicyError(f"{tag}@{commit[:12]}: missing version.properties")
    legacy = _show_file(repo, commit, "app/build.gradle.kts")
    if legacy is None:
        raise PolicyError(f"{tag}@{commit[:12]}: missing legacy app/build.gradle.kts")
    code_match = _LEGACY_CODE_RE.search(legacy)
    name_match = _LEGACY_NAME_RE.search(legacy)
    if not code_match or not name_match:
        raise PolicyError(f"{tag}@{commit[:12]}: legacy version metadata is incomplete")
    return parse_properties(
        f"VERSION_CODE={code_match.group(1)}\nVERSION_NAME={name_match.group(1)}\n",
        f"{tag}@{commit[:12]}/legacy Gradle metadata",
    )


def _is_ancestor(repo: Path, ancestor: str, descendant: str) -> bool:
    result = subprocess.run(
        ["git", "merge-base", "--is-ancestor", ancestor, descendant],
        cwd=repo,
        check=False,
        capture_output=True,
        text=True,
    )
    if result.returncode not in (0, 1):
        detail = result.stderr.strip() or "unknown Git error"
        raise PolicyError(f"cannot compare {ancestor[:12]} and {descendant[:12]}: {detail}")
    return result.returncode == 0


def _ref_exists(repo: Path, ref: str) -> bool:
    result = subprocess.run(
        ["git", "show-ref", "--verify", "--quiet", ref],
        cwd=repo,
        check=False,
    )
    return result.returncode == 0


def _main_refs(repo: Path) -> list[str]:
    return [
        ref
        for ref in ("refs/heads/main", "refs/remotes/origin/main")
        if _ref_exists(repo, ref)
    ]


def _tag_names(repo: Path) -> list[str]:
    output = _git(repo, "for-each-ref", "--format=%(refname:strip=2)", "refs/tags/v*")
    return [line for line in output.splitlines() if line]


def validate(repo: Path | str = ".", strict_tag: bool = False) -> VersionMetadata:
    """Validate the checked-out metadata and all local v* release tags."""
    repo = Path(repo).resolve()
    current_text = (repo / "version.properties").read_text(encoding="utf-8") if (repo / "version.properties").is_file() else None
    if current_text is None:
        raise PolicyError("working tree: missing version.properties")
    current = parse_properties(current_text, "working tree version.properties")

    refs = _main_refs(repo)
    if not refs:
        raise PolicyError("cannot validate release containment: refs/heads/main or refs/remotes/origin/main is missing")

    releases: list[tuple[str, str, VersionMetadata]] = []
    for tag in _tag_names(repo):
        ref = f"refs/tags/{tag}"
        object_type = _git(repo, "cat-file", "-t", ref)
        if object_type != "tag":
            raise PolicyError(f"{tag}: release tag must be annotated, found {object_type} tag")
        commit = _git(repo, "rev-parse", f"{ref}^{{}}")
        if _git(repo, "cat-file", "-t", commit) != "commit":
            raise PolicyError(f"{tag}: annotated tag must dereference to a commit, found {commit[:12]}")
        metadata = _metadata_at_tag(repo, tag, commit)
        expected_tag = f"v{metadata.name}"
        if tag != expected_tag:
            raise PolicyError(
                f"{tag}@{commit[:12]}: tag name must equal {expected_tag}, observed metadata {metadata.name!r}"
            )
        if not any(_is_ancestor(repo, commit, main_ref) for main_ref in refs):
            raise PolicyError(f"{tag}@{commit[:12]}: release commit is not contained in main/origin/main")
        releases.append((tag, commit, metadata))

    by_code: dict[int, str] = {}
    for tag, commit, metadata in releases:
        previous = by_code.get(metadata.code)
        if previous is not None:
            raise PolicyError(
                f"versionCode {metadata.code} is duplicated by {previous} and {tag} "
                f"(commits {commit[:12]} and {_git(repo, 'rev-parse', f'refs/tags/{previous}^{{}}')[:12]})"
            )
        by_code[metadata.code] = tag

    for index, (left_tag, left_commit, left_meta) in enumerate(releases):
        for right_tag, right_commit, right_meta in releases[index + 1 :]:
            if _is_ancestor(repo, left_commit, right_commit):
                _validate_release_name_transition(left_tag, left_meta, right_tag, right_meta)
                if left_meta.code >= right_meta.code:
                    raise PolicyError(
                        f"release codes must increase by ancestry: {left_tag}@{left_commit[:12]} "
                        f"code {left_meta.code}, then {right_tag}@{right_commit[:12]} code {right_meta.code}"
                    )
            if _is_ancestor(repo, right_commit, left_commit):
                _validate_release_name_transition(right_tag, right_meta, left_tag, left_meta)
                if right_meta.code >= left_meta.code:
                    raise PolicyError(
                        f"release codes must increase by ancestry: {right_tag}@{right_commit[:12]} "
                        f"code {right_meta.code}, then {left_tag}@{left_commit[:12]} code {left_meta.code}"
                    )

    # A governed beta sequence starts at beta.1, even when no earlier beta tag
    # exists locally. This prevents a partial tag checkout from blessing beta.2.
    tagged_bases: dict[tuple[int, int, int], list[int]] = {}
    for _, _, metadata in releases:
        base, beta = _version_name_parts(metadata.name)
        if beta is not None:
            tagged_bases.setdefault(base, []).append(beta)
    for base, ordinals in tagged_bases.items():
        if min(ordinals) != 1:
            raise PolicyError(f"beta release sequence for {base[0]}.{base[1]}.{base[2]} must start at beta.1")

    if releases:
        greatest_code = max(metadata.code for _, _, metadata in releases)
        greatest = next(metadata for _, _, metadata in releases if metadata.code == greatest_code)
        if current.code < greatest_code:
            raise PolicyError(
                f"working tree versionCode {current.code} is below greatest tagged code "
                f"{greatest_code} ({greatest.name})"
            )
        if current.code == greatest_code and current.name != greatest.name:
            raise PolicyError(
                f"working tree metadata {current.name!r}/{current.code} does not match "
                f"greatest released version {greatest.name!r}/{greatest_code}"
            )

    # The working tree is the next candidate on HEAD. Apply the same release
    # name chronology whenever a governed tag is an ancestor of that candidate.
    head = _git(repo, "rev-parse", "HEAD")
    current_base, current_beta = _version_name_parts(current.name)
    same_base_tags = []
    for tag, commit, metadata in releases:
        if _is_ancestor(repo, commit, head):
            tag_base, tag_beta = _version_name_parts(metadata.name)
            if tag_base == current_base:
                same_base_tags.append(tag_beta)
            _validate_release_name_transition(
                tag,
                metadata,
                "working tree candidate",
                current,
                allow_equal=metadata == current or strict_tag,
            )
    if current_beta is not None and not same_base_tags and current_beta != 1:
        raise PolicyError(
            f"beta release sequence for {current_base[0]}.{current_base[1]}.{current_base[2]} "
            "must start at beta.1"
        )

    if strict_tag:
        expected_tag = f"v{current.name}"
        head = _git(repo, "rev-parse", "HEAD")
        tag_ref = f"refs/tags/{expected_tag}"
        if not _ref_exists(repo, tag_ref):
            raise PolicyError(f"strict tag mode: missing exact HEAD tag {expected_tag}")
        if _git(repo, "cat-file", "-t", tag_ref) != "tag":
            raise PolicyError(f"strict tag mode: {expected_tag} must be annotated")
        target = _git(repo, "rev-parse", f"{tag_ref}^{{}}")
        if target != head:
            raise PolicyError(
                f"strict tag mode: {expected_tag} targets {target[:12]}, but HEAD is {head[:12]}"
            )
        tagged_metadata = _metadata_at_tag(repo, expected_tag, head)
        if current != tagged_metadata:
            raise PolicyError(
                f"strict tag mode: working tree metadata {current.name!r}/{current.code} "
                f"does not match {expected_tag}@{head[:12]} metadata "
                f"{tagged_metadata.name!r}/{tagged_metadata.code}"
            )

    return current


def main(argv: Iterable[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, default=Path("."), help="repository to inspect (default: current directory)")
    parser.add_argument("--strict-tag", action="store_true", help="require v<VERSION_NAME> to point exactly at HEAD")
    args = parser.parse_args(list(argv) if argv is not None else None)
    try:
        metadata = validate(args.repo, strict_tag=args.strict_tag)
    except (OSError, PolicyError) as error:
        print(f"version policy: FAIL: {error}", file=sys.stderr)
        return 1
    suffix = " (strict tag)" if args.strict_tag else ""
    print(f"version policy: PASS: {metadata.name} (versionCode {metadata.code}){suffix}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
