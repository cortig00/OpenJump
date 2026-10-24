import subprocess
import tempfile
import unittest
from pathlib import Path

from tools import version_policy


class TemporaryRepository:
    def __init__(self, path: Path):
        self.path = path

    def git(self, *args: str, check: bool = True) -> str:
        result = subprocess.run(
            ["git", *args],
            cwd=self.path,
            check=False,
            capture_output=True,
            text=True,
            encoding="utf-8",
        )
        if check and result.returncode:
            raise AssertionError(result.stderr)
        return result.stdout.strip()

    def write(self, relative: str, contents: str) -> None:
        target = self.path / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(contents, encoding="utf-8")

    def commit(self, message: str) -> str:
        self.git("add", ".")
        self.git("commit", "-m", message)
        return self.git("rev-parse", "HEAD")

    def version(self, code: int | str, name: str, commit: bool = True) -> None:
        self.write("version.properties", f"VERSION_CODE={code}\nVERSION_NAME={name}\n")
        if commit:
            self.commit(f"prepare {name}")

    def annotate(self, name: str, commit: str | None = None) -> None:
        args = ["tag", "-a", name, "-m", name]
        if commit is not None:
            args.append(commit)
        self.git(*args)


def make_repository() -> tuple[tempfile.TemporaryDirectory, TemporaryRepository]:
    temporary = tempfile.TemporaryDirectory()
    repo = TemporaryRepository(Path(temporary.name))
    repo.git("init", "--initial-branch=main")
    repo.git("config", "user.name", "Version Tests")
    repo.git("config", "user.email", "version-tests@example.invalid")
    # This is the real pre-version.properties shape of the v1.0.0 release.
    repo.write(
        "app/build.gradle.kts",
        'defaultConfig {\n    versionCode = 1\n    versionName = "1.0.0"\n}\n',
    )
    legacy = repo.commit("legacy 1.0.0")
    repo.annotate("v1.0.0", legacy)
    repo.version(2, "1.1.0-beta.1")
    return temporary, repo


class VersionPolicyTest(unittest.TestCase):
    def setUp(self):
        self.temporary, self.repo = make_repository()

    def tearDown(self):
        self.temporary.cleanup()

    def assertPolicyError(self, expected: str, *, strict_tag: bool = False):
        with self.assertRaisesRegex(version_policy.PolicyError, expected):
            version_policy.validate(self.repo.path, strict_tag=strict_tag)

    def test_valid_current_beta_and_legacy_annotated_tag(self):
        metadata = version_policy.validate(self.repo.path)
        self.assertEqual(metadata, version_policy.VersionMetadata(2, "1.1.0-beta.1"))

    def test_valid_stable_candidate_and_valid_unchanged_post_release_main(self):
        self.repo.version(2, "1.1.0")
        self.assertEqual(version_policy.validate(self.repo.path).name, "1.1.0")

        # A normal post-release merge can retain the released identity.
        self.repo.version(1, "1.0.0")
        self.assertEqual(version_policy.validate(self.repo.path).code, 1)

    def test_valid_strict_tag_at_exact_head(self):
        self.repo.annotate("v1.1.0-beta.1")
        self.assertEqual(
            version_policy.validate(self.repo.path, strict_tag=True).name,
            "1.1.0-beta.1",
        )

    def test_strict_tag_requires_head_tag(self):
        self.assertPolicyError(r"missing exact HEAD tag v1\.1\.0-beta\.1", strict_tag=True)

    def test_strict_tag_rejects_tag_not_at_head(self):
        self.repo.annotate("v1.1.0-beta.1")
        self.repo.write("release-notes.txt", "post-tag change\n")
        self.repo.commit("post-tag change")
        self.assertPolicyError(r"targets .* but HEAD is", strict_tag=True)

    def test_strict_tag_rejects_dirty_metadata_drift(self):
        self.repo.annotate("v1.1.0-beta.1")
        self.repo.write("version.properties", "VERSION_CODE=3\nVERSION_NAME=1.1.0-beta.1\n")
        self.assertPolicyError(r"working tree metadata .* does not match", strict_tag=True)

    def test_release_names_increase_beta_ordinals(self):
        self.repo.annotate("v1.1.0-beta.1")
        self.repo.version(3, "1.1.0-beta.2")
        self.repo.annotate("v1.1.0-beta.2")
        self.repo.version(4, "1.1.0")
        self.assertEqual(version_policy.validate(self.repo.path).name, "1.1.0")

    def test_release_names_reject_decreasing_beta_ordinal(self):
        self.repo.version(3, "1.1.0-beta.2")
        self.repo.annotate("v1.1.0-beta.2")
        self.repo.version(4, "1.1.0-beta.1")
        self.repo.annotate("v1.1.0-beta.1")
        self.assertPolicyError(r"beta ordinals must increase by ancestry")

    def test_current_candidate_release_name_must_follow_ancestral_tag(self):
        self.repo.annotate("v1.1.0-beta.1")
        self.repo.version(3, "1.1.0-beta.2")
        self.assertEqual(version_policy.validate(self.repo.path).name, "1.1.0-beta.2")
        self.repo.version(4, "1.1.0-beta.1")
        self.assertPolicyError(r"beta ordinals must increase by ancestry")

    def test_release_names_reject_beta_after_same_base_stable(self):
        self.repo.version(3, "1.1.0")
        self.repo.annotate("v1.1.0")
        self.repo.version(4, "1.1.0-beta.1")
        self.repo.annotate("v1.1.0-beta.1")
        self.assertPolicyError(r"beta release cannot follow stable")

    def test_duplicate_version_codes_are_rejected(self):
        first = self.repo.git("rev-parse", "HEAD")
        self.repo.annotate("v1.1.0-beta.1", first)
        self.repo.version(2, "1.1.0")
        self.repo.annotate("v1.1.0")
        self.repo.version(3, "1.2.0")
        self.assertPolicyError(r"versionCode 2 is duplicated")

    def test_ancestral_release_codes_must_increase(self):
        self.repo.version(3, "1.1.0")
        self.repo.annotate("v1.1.0")
        self.repo.version(2, "1.2.0")
        self.repo.annotate("v1.2.0")
        self.repo.version(4, "1.3.0")
        self.assertPolicyError(r"release codes must increase by ancestry")

    def test_tag_name_must_match_metadata(self):
        self.repo.annotate("v1.1.0")
        self.assertPolicyError(r"tag name must equal v1\.1\.0-beta\.1")

    def test_lightweight_tag_is_rejected(self):
        self.repo.git("tag", "v1.1.0-beta.1")
        self.assertPolicyError(r"must be annotated")

    def test_future_tag_requires_version_properties(self):
        self.repo.git("rm", "version.properties")
        commit = self.repo.commit("invalid future release")
        self.repo.annotate("v1.1.0", commit)
        self.repo.version(3, "1.2.0")
        self.assertPolicyError(r"v1\.1\.0.*missing version\.properties")

    def test_current_code_cannot_be_below_latest_tag(self):
        self.repo.version(3, "1.1.0")
        self.repo.annotate("v1.1.0")
        self.repo.version(2, "1.2.0-beta.1")
        self.assertPolicyError(r"below greatest tagged code 3")

    def test_equal_current_code_must_keep_released_name(self):
        self.repo.version(2, "1.1.0")
        self.repo.annotate("v1.1.0")
        self.repo.version(2, "1.1.0-beta.1")
        self.assertPolicyError(r"does not match greatest released version")

    def test_tag_release_must_be_contained_in_main(self):
        self.repo.version(3, "1.2.0")
        self.repo.git("checkout", "-b", "unmerged-release")
        self.repo.version(4, "1.3.0")
        self.repo.annotate("v1.3.0")
        self.repo.git("checkout", "main")
        self.assertPolicyError(r"not contained in main/origin/main")

    def test_public_1_0_reset_preserves_code_order_and_strict_tag(self):
        self.repo.annotate("v1.1.0-beta.1")
        self.repo.version(3, "1.0")
        self.assertEqual(version_policy.validate(self.repo.path).name, "1.0")
        self.repo.annotate("v1.0")
        self.assertEqual(version_policy.validate(self.repo.path, strict_tag=True).code, 3)

    def test_public_1_0_reset_rejects_reused_or_lower_code(self):
        self.repo.annotate("v1.1.0-beta.1")
        self.repo.version(2, "1.0")
        self.assertPolicyError(r"does not match greatest released version")
        self.repo.version(1, "1.0")
        self.assertPolicyError(r"below greatest tagged code")

    def test_unrelated_semantic_downgrade_is_still_rejected(self):
        self.repo.annotate("v1.1.0-beta.1")
        self.repo.version(3, "1.0.1")
        self.assertPolicyError(r"release names must not decrease")

    def test_public_reset_tag_remains_valid_for_next_release(self):
        self.repo.annotate("v1.1.0-beta.1")
        self.repo.version(3, "1.0")
        self.repo.annotate("v1.0")
        self.repo.version(4, "1.3.0")
        self.assertEqual(version_policy.validate(self.repo.path).code, 4)

    def test_malformed_stable_and_beta_names_are_rejected(self):
        for name in ("1.1", "1.1.0.1", "01.1.0", "1.1.0-BETA.1", "1.1.0-beta.0", "1.1.0-beta"):
            with self.subTest(name=name):
                self.repo.version(3, name)
                self.assertPolicyError(r"VERSION_NAME=.*not stable")
                # Restore a valid worktree for the next subcase.
                self.repo.version(3, "1.2.0")

    def test_properties_reject_duplicate_unknown_and_missing_keys(self):
        for contents, expected in (
            ("VERSION_CODE=2\nVERSION_CODE=3\nVERSION_NAME=1.1.0\n", "repeated key"),
            ("VERSION_CODE=2\nVERSION_NAME=1.1.0\nOTHER=x\n", "unexpected key"),
            ("VERSION_CODE=2\n", "missing key"),
        ):
            with self.subTest(contents=contents):
                with self.assertRaisesRegex(version_policy.PolicyError, expected):
                    version_policy.parse_properties(contents)

    def test_version_code_range_is_strict(self):
        for code in ("0", "-1", "2.5", str(version_policy.MAX_VERSION_CODE + 1)):
            with self.subTest(code=code):
                with self.assertRaises(version_policy.PolicyError):
                    version_policy.parse_properties(f"VERSION_CODE={code}\nVERSION_NAME=1.1.0\n")


if __name__ == "__main__":
    unittest.main()
