from __future__ import annotations

import hashlib
import importlib.util
import pathlib
import sys
import tempfile
import unittest
from typing import Any


MODULE_PATH = pathlib.Path(__file__).resolve().parents[1] / "google_play_publish.py"
SPEC = importlib.util.spec_from_file_location("google_play_publish", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
play = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = play
SPEC.loader.exec_module(play)


class FakeTransport:
    def __init__(self, *, bundle_version: int = 4):
        self.bundle_version = bundle_version
        self.calls: list[dict[str, Any]] = []

    def request(
        self,
        method: str,
        url: str,
        *,
        json_body: Any | None = None,
        binary_body: bytes | None = None,
        content_type: str | None = None,
        timeout: int = 60,
    ) -> dict[str, Any]:
        self.calls.append(
            {
                "method": method,
                "url": url,
                "json_body": json_body,
                "binary_body": binary_body,
                "content_type": content_type,
                "timeout": timeout,
            }
        )
        if method == "POST" and url.endswith("/edits"):
            return {"id": "edit-123"}
        if "/bundles?uploadType=media" in url:
            return {
                "versionCode": self.bundle_version,
                "sha256": hashlib.sha256(binary_body or b"").hexdigest(),
            }
        if method == "PUT" and "/tracks/" in url:
            return {"track": "internal"}
        if method == "POST" and ":commit?" in url:
            return {"id": "edit-123"}
        if method == "POST" and "/listings/" in url:
            return {"image": {"id": "image-1"}}
        return {}


class GooglePlayPublishTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        root = pathlib.Path(self.temporary.name)
        self.aab = root / "tuima.aab"
        self.aab.write_bytes(b"signed-aab-fixture")
        self.image = root / "screen.png"
        self.image.write_bytes(b"png-fixture")
        self.inputs = play.PublishInputs(
            repo_root=root,
            config_path=root / "config.json",
            aab_path=self.aab,
            package_name="com.mobilecore.app",
            version_code=4,
            release_name="TuiMa 0.1.3-rc2",
            track="internal",
            status="completed",
            expected_aab_sha256=play.sha256_file(self.aab),
            listing={
                "language": "zh-CN",
                "title": "推嘛 TuiMa",
                "shortDescription": "short",
                "fullDescription": "full",
            },
            release_notes=[{"language": "zh-CN", "text": "notes"}],
            images=[{"type": "phoneScreenshots", "path": str(self.image)}],
            sync_listing=True,
        )

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def test_publish_runs_validated_edit_sequence(self) -> None:
        transport = FakeTransport()
        result = play.PlayPublisher(transport).publish(self.inputs)

        self.assertTrue(result["ok"])
        self.assertEqual(result["versionCode"], 4)
        operations = [(call["method"], call["url"]) for call in transport.calls]
        self.assertEqual(operations[0][0], "POST")
        self.assertTrue(operations[0][1].endswith("/applications/com.mobilecore.app/edits"))
        self.assertTrue(any(method == "PUT" and "/listings/zh-CN" in url for method, url in operations))
        self.assertTrue(any(method == "DELETE" and url.endswith("/phoneScreenshots") for method, url in operations))
        self.assertTrue(any("/bundles?uploadType=media" in url for _, url in operations))
        self.assertTrue(any(method == "POST" and url.endswith(":validate") for method, url in operations))
        self.assertTrue(
            operations[-1][1].endswith(":commit?changesInReviewBehavior=ERROR_IF_IN_REVIEW")
        )
        track_call = next(call for call in transport.calls if "/tracks/internal" in call["url"])
        release = track_call["json_body"]["releases"][0]
        self.assertEqual(release["versionCodes"], ["4"])
        self.assertEqual(release["status"], "completed")

    def test_bundle_version_mismatch_deletes_uncommitted_edit(self) -> None:
        transport = FakeTransport(bundle_version=5)

        with self.assertRaisesRegex(play.PublishError, "versionCode mismatch"):
            play.PlayPublisher(transport).publish(self.inputs)

        self.assertEqual(transport.calls[-1]["method"], "DELETE")
        self.assertTrue(transport.calls[-1]["url"].endswith("/edits/edit-123"))

    def test_dry_run_contains_hashes_and_no_credentials(self) -> None:
        plan = play.build_plan(self.inputs)

        self.assertEqual(plan["mode"], "dry-run")
        self.assertEqual(plan["aab"]["sha256"], play.sha256_file(self.aab))
        self.assertEqual(plan["images"][0]["sha256"], play.sha256_file(self.image))
        self.assertNotIn("accessToken", plan)
        self.assertEqual(plan["operations"][-1], "commit-edit-with-error-if-review-in-progress")

    def test_identifier_validation_rejects_path_content(self) -> None:
        with self.assertRaises(play.PublishError):
            play.validate_identifier("../../production", "track")


if __name__ == "__main__":
    unittest.main()
