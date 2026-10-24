#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

if [[ -x ./gradlew ]]; then
  GRADLE=(./gradlew)
elif [[ -f ./gradlew ]]; then
  # The wrapper is historically mode 0644 in this repository. Running it through
  # Bash works in Linux CI and Git Bash without relying on Windows-only cmd.exe.
  GRADLE=(bash ./gradlew)
else
  echo "Gradle wrapper not found at $ROOT/gradlew" >&2
  exit 2
fi

usage() {
  cat <<'EOF'
Usage:
  ./tools/validate.sh focused <fully.qualified.TestClass> [...]
  ./tools/validate.sh version [--strict-tag]
  ./tools/validate.sh compile
  ./tools/validate.sh android-test-compile
  ./tools/validate.sh license
  ./tools/validate.sh pre-push [git-base]
  ./tools/validate.sh release

Use focused/compile while editing. Run pre-push once after the final diff is stable.
Version checks are local-only and use --strict-tag only for an exact tagged release.
Run release before publishing. Set OPENJUMP_REQUIRE_SIGNED_RELEASE=1 to reject an
unsigned AAB. The complete gates are also enforced by CI on pull requests and v* tags.
EOF
}

mode="${1:-}"
if [[ -z "$mode" || "$mode" == "-h" || "$mode" == "--help" ]]; then
  usage
  exit 0
fi
shift

case "$mode" in
  version)
    if [[ $# -gt 1 || ( $# -eq 1 && "$1" != "--strict-tag" ) ]]; then
      echo "version accepts only the optional --strict-tag flag" >&2
      usage >&2
      exit 2
    fi
    version_args=()
    if [[ $# -eq 1 ]]; then
      version_args+=(--strict-tag)
    fi
    python tools/version_policy.py "${version_args[@]}"
    ;;
  license)
    if [[ $# -ne 0 ]]; then
      echo "license accepts no arguments" >&2
      usage >&2
      exit 2
    fi
    python tools/check_license.py
    ;;
  focused)
    if [[ $# -eq 0 ]]; then
      echo "focused requires at least one fully-qualified test class" >&2
      usage >&2
      exit 2
    fi
    args=(--max-workers=1 :app:testDebugUnitTest)
    for test_class in "$@"; do
      args+=(--tests "$test_class")
    done
    "${GRADLE[@]}" "${args[@]}"
    ;;
  compile)
    "${GRADLE[@]}" --max-workers=1 \
      :app:compileDebugKotlin \
      :app:compileDebugUnitTestKotlin
    ;;
  android-test-compile)
    "${GRADLE[@]}" --max-workers=1 :app:compileDebugAndroidTestKotlin
    ;;
  pre-push)
    if [[ $# -gt 1 ]]; then
      echo "pre-push accepts at most one git base" >&2
      usage >&2
      exit 2
    fi
    python tools/version_policy.py
    python tools/check_license.py
    base="${1:-origin/main}"
    if git rev-parse --verify --quiet "$base" >/dev/null; then
      git diff --check "$base"...HEAD
    else
      echo "warning: $base not found; checking working tree only" >&2
    fi
    git diff --check
    git diff --cached --check
    "${GRADLE[@]}" --no-daemon --max-workers=1 \
      :app:testDebugUnitTest \
      :app:assembleDebug \
      :app:assembleDebugAndroidTest \
      :app:lintDebug
    ;;
  release)
    "${GRADLE[@]}" --no-daemon --max-workers=1 \
      :app:bundleRelease \
      :app:lintRelease
    aab="$ROOT/app/build/outputs/bundle/release/app-release.aab"
    if [[ ! -f "$aab" ]]; then
      echo "Release AAB not found at $aab" >&2
      exit 1
    fi
    if [[ "${OPENJUMP_REQUIRE_SIGNED_RELEASE:-0}" == "1" ]]; then
      signature_entries="$(jar tf "$aab" | grep -E '^META-INF/[^/]+\.(RSA|DSA|EC)$' || true)"
      if [[ -z "$signature_entries" ]]; then
        echo "Release AAB is unsigned; configure the upload key as documented in RELEASE.md." >&2
        exit 1
      fi
      jarsigner -verify -strict "$aab"
    else
      echo "Release AAB built. Signature enforcement is disabled; see RELEASE.md."
    fi
    ;;
  *)
    echo "unknown validation mode: $mode" >&2
    usage >&2
    exit 2
    ;;
esac
