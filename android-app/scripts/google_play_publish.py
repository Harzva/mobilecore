#!/usr/bin/env python3
"""Publish a signed TuiMa AAB through the official Google Play Edits API."""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import mimetypes
import os
import pathlib
import shutil
import struct
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from typing import Any, Mapping, Protocol


API_ROOT = "https://androidpublisher.googleapis.com/androidpublisher/v3"
UPLOAD_ROOT = "https://androidpublisher.googleapis.com/upload/androidpublisher/v3"
ANDROID_PUBLISHER_SCOPE = "https://www.googleapis.com/auth/androidpublisher"
TOKEN_URI = "https://oauth2.googleapis.com/token"


class PublishError(RuntimeError):
    pass


def sha256_file(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def png_dimensions(path: pathlib.Path) -> tuple[int, int]:
    with path.open("rb") as handle:
        header = handle.read(24)
    if len(header) != 24 or header[:8] != b"\x89PNG\r\n\x1a\n" or header[12:16] != b"IHDR":
        raise PublishError(f"Store image is not a valid PNG: {path}")
    return struct.unpack(">II", header[16:24])


def base64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")


def compact_json(value: Any) -> bytes:
    return json.dumps(value, separators=(",", ":"), ensure_ascii=False).encode("utf-8")


def load_json_object(path: pathlib.Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise PublishError(f"Could not read JSON from {path}: {error}") from error
    if not isinstance(value, dict):
        raise PublishError(f"Expected a JSON object in {path}")
    return value


def service_account_token(credentials: Mapping[str, Any]) -> str:
    email = credentials.get("client_email")
    private_key = credentials.get("private_key")
    private_key_id = credentials.get("private_key_id")
    token_uri = credentials.get("token_uri", TOKEN_URI)
    if not all(isinstance(value, str) and value for value in (email, private_key, private_key_id, token_uri)):
        raise PublishError("Service-account JSON is missing client_email, private_key, private_key_id, or token_uri")
    if shutil.which("openssl") is None:
        raise PublishError("openssl is required to exchange service-account credentials")

    issued_at = int(time.time())
    header = {"alg": "RS256", "kid": private_key_id, "typ": "JWT"}
    claims = {
        "iss": email,
        "scope": ANDROID_PUBLISHER_SCOPE,
        "aud": token_uri,
        "iat": issued_at,
        "exp": issued_at + 3600,
    }
    signing_input = f"{base64url(compact_json(header))}.{base64url(compact_json(claims))}".encode("ascii")

    key_path: pathlib.Path | None = None
    try:
        with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", delete=False) as key_file:
            key_file.write(private_key)
            key_path = pathlib.Path(key_file.name)
        key_path.chmod(0o600)
        signature = subprocess.run(
            ["openssl", "dgst", "-sha256", "-sign", str(key_path)],
            input=signing_input,
            check=True,
            capture_output=True,
        ).stdout
    except (OSError, subprocess.CalledProcessError) as error:
        raise PublishError("Could not sign the service-account JWT assertion") from error
    finally:
        if key_path is not None:
            key_path.unlink(missing_ok=True)

    assertion = f"{signing_input.decode('ascii')}.{base64url(signature)}"
    body = urllib.parse.urlencode(
        {
            "grant_type": "urn:ietf:params:oauth:grant-type:jwt-bearer",
            "assertion": assertion,
        }
    ).encode("ascii")
    request = urllib.request.Request(
        token_uri,
        data=body,
        method="POST",
        headers={"Content-Type": "application/x-www-form-urlencoded"},
    )
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            payload = json.loads(response.read().decode("utf-8"))
    except (urllib.error.URLError, json.JSONDecodeError) as error:
        raise PublishError("Could not exchange service-account credentials for an access token") from error
    token = payload.get("access_token") if isinstance(payload, dict) else None
    if not isinstance(token, str) or not token:
        raise PublishError("Google OAuth response did not contain an access_token")
    return token


def resolve_access_token() -> str:
    explicit = os.environ.get("GOOGLE_PLAY_ACCESS_TOKEN", "").strip()
    if explicit:
        return explicit

    inline = os.environ.get("GOOGLE_PLAY_SERVICE_ACCOUNT_JSON", "").strip()
    credential_path = os.environ.get("GOOGLE_APPLICATION_CREDENTIALS", "").strip()
    if inline:
        try:
            credentials = json.loads(inline)
        except json.JSONDecodeError as error:
            raise PublishError("GOOGLE_PLAY_SERVICE_ACCOUNT_JSON is not valid JSON") from error
        if not isinstance(credentials, dict):
            raise PublishError("GOOGLE_PLAY_SERVICE_ACCOUNT_JSON must contain a JSON object")
        return service_account_token(credentials)
    if credential_path:
        return service_account_token(load_json_object(pathlib.Path(credential_path)))

    gcloud = shutil.which("gcloud")
    if gcloud:
        result = subprocess.run(
            [gcloud, "auth", "application-default", "print-access-token"],
            text=True,
            capture_output=True,
        )
        if result.returncode == 0 and result.stdout.strip():
            return result.stdout.strip()

    raise PublishError(
        "No Google Play credential was found. Set GOOGLE_PLAY_ACCESS_TOKEN, "
        "GOOGLE_PLAY_SERVICE_ACCOUNT_JSON, or GOOGLE_APPLICATION_CREDENTIALS."
    )


class Transport(Protocol):
    def request(
        self,
        method: str,
        url: str,
        *,
        json_body: Any | None = None,
        binary_body: bytes | None = None,
        content_type: str | None = None,
        timeout: int = 60,
    ) -> dict[str, Any]: ...


class UrlLibTransport:
    def __init__(self, access_token: str):
        self._access_token = access_token

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
        if json_body is not None and binary_body is not None:
            raise PublishError("A request cannot have both JSON and binary bodies")
        data = compact_json(json_body) if json_body is not None else binary_body
        headers = {"Authorization": f"Bearer {self._access_token}", "Accept": "application/json"}
        if json_body is not None:
            headers["Content-Type"] = "application/json; charset=utf-8"
        elif data is not None:
            headers["Content-Type"] = content_type or "application/octet-stream"
        request = urllib.request.Request(url, data=data, method=method, headers=headers)
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                response_body = response.read()
        except urllib.error.HTTPError as error:
            response_body = error.read().decode("utf-8", errors="replace")
            raise PublishError(f"Google Play API {method} failed with HTTP {error.code}: {response_body}") from error
        except urllib.error.URLError as error:
            raise PublishError(f"Google Play API {method} failed: {error.reason}") from error
        if not response_body:
            return {}
        try:
            value = json.loads(response_body.decode("utf-8"))
        except json.JSONDecodeError as error:
            raise PublishError(f"Google Play API returned invalid JSON for {method} {url}") from error
        if not isinstance(value, dict):
            raise PublishError(f"Google Play API returned a non-object response for {method} {url}")
        return value


@dataclass(frozen=True)
class PublishInputs:
    repo_root: pathlib.Path
    config_path: pathlib.Path
    aab_path: pathlib.Path
    package_name: str
    version_code: int
    release_name: str
    track: str
    status: str
    expected_aab_sha256: str
    listing: dict[str, Any]
    release_notes: list[dict[str, str]]
    images: list[dict[str, str]]
    sync_listing: bool


def validate_identifier(value: str, label: str) -> str:
    allowed = set("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789._:-")
    if not value or any(character not in allowed for character in value):
        raise PublishError(f"Invalid {label}: {value!r}")
    return value


def load_inputs(args: argparse.Namespace) -> PublishInputs:
    repo_root = pathlib.Path(__file__).resolve().parents[2]
    config_path = pathlib.Path(args.config).resolve()
    config = load_json_object(config_path)
    aab_path = pathlib.Path(args.aab).resolve()
    if not aab_path.is_file():
        raise PublishError(f"AAB does not exist: {aab_path}")

    package_name = validate_identifier(str(config.get("packageName", "")), "package name")
    if args.confirm_package != package_name:
        raise PublishError(f"--confirm-package must exactly match {package_name}")
    version_code = int(config.get("versionCode", 0))
    if version_code < 1:
        raise PublishError("versionCode must be a positive integer")
    release_name = args.release_name or str(config.get("releaseName", "")).strip()
    track = validate_identifier(args.track or str(config.get("track", "internal")), "track")
    status = args.status or str(config.get("status", "completed"))
    if status not in {"draft", "inProgress", "halted", "completed"}:
        raise PublishError(f"Unsupported release status: {status}")
    expected_sha = str(config.get("expectedAabSha256", "")).lower()
    if len(expected_sha) != 64 or any(character not in "0123456789abcdef" for character in expected_sha):
        raise PublishError("expectedAabSha256 must be a lowercase SHA-256 digest")
    actual_sha = sha256_file(aab_path)
    if actual_sha != expected_sha:
        raise PublishError(f"AAB SHA-256 mismatch: expected {expected_sha}, got {actual_sha}")

    listing = config.get("listing")
    release_notes = config.get("releaseNotes")
    images = config.get("images")
    if not isinstance(listing, dict) or not isinstance(release_notes, list) or not isinstance(images, list):
        raise PublishError("Config must contain listing, releaseNotes, and images")
    for key in ("language", "title", "shortDescription", "fullDescription"):
        if not isinstance(listing.get(key), str) or not listing[key].strip():
            raise PublishError(f"listing.{key} must be a non-empty string")
    listing_limits = {"title": 30, "shortDescription": 80, "fullDescription": 4000}
    for key, maximum in listing_limits.items():
        if len(listing[key]) > maximum:
            raise PublishError(f"listing.{key} exceeds the Google Play limit of {maximum} characters")

    normalized_notes: list[dict[str, str]] = []
    for note in release_notes:
        if not isinstance(note, dict):
            raise PublishError("Each releaseNotes entry must be an object")
        language = note.get("language")
        text = note.get("text")
        if not isinstance(language, str) or not language or not isinstance(text, str) or not text:
            raise PublishError("Each release note requires non-empty language and text values")
        if len(text) > 500:
            raise PublishError("A release note exceeds the Google Play limit of 500 characters")
        normalized_notes.append({"language": language, "text": text})

    normalized_images: list[dict[str, str]] = []
    for image in images:
        if not isinstance(image, dict):
            raise PublishError("Each image entry must be an object")
        image_type = validate_identifier(str(image.get("type", "")), "image type")
        relative_path = pathlib.Path(str(image.get("path", "")))
        image_path = (repo_root / relative_path).resolve()
        if not image_path.is_file() or repo_root not in image_path.parents:
            raise PublishError(f"Store image is missing or outside the repository: {relative_path}")
        expected_width = int(image.get("width", 0))
        expected_height = int(image.get("height", 0))
        actual_width, actual_height = png_dimensions(image_path)
        if (expected_width, expected_height) != (actual_width, actual_height):
            raise PublishError(
                f"Store image dimensions do not match config for {relative_path}: "
                f"expected {expected_width}x{expected_height}, got {actual_width}x{actual_height}"
            )
        normalized_images.append({"type": image_type, "path": str(image_path)})

    return PublishInputs(
        repo_root=repo_root,
        config_path=config_path,
        aab_path=aab_path,
        package_name=package_name,
        version_code=version_code,
        release_name=release_name,
        track=track,
        status=status,
        expected_aab_sha256=expected_sha,
        listing=listing,
        release_notes=normalized_notes,
        images=normalized_images,
        sync_listing=args.sync_listing,
    )


def edit_url(inputs: PublishInputs, edit_id: str = "{editId}") -> str:
    package = urllib.parse.quote(inputs.package_name, safe="")
    return f"{API_ROOT}/applications/{package}/edits/{edit_id}"


def build_track_payload(inputs: PublishInputs) -> dict[str, Any]:
    return {
        "track": inputs.track,
        "releases": [
            {
                "name": inputs.release_name,
                "versionCodes": [str(inputs.version_code)],
                "releaseNotes": inputs.release_notes,
                "status": inputs.status,
            }
        ],
    }


def build_plan(inputs: PublishInputs) -> dict[str, Any]:
    base = edit_url(inputs)
    image_plan = []
    if inputs.sync_listing:
        for image in inputs.images:
            image_plan.append(
                {
                    "type": image["type"],
                    "file": pathlib.Path(image["path"]).name,
                    "sha256": sha256_file(pathlib.Path(image["path"])),
                }
            )
    return {
        "mode": "dry-run",
        "packageName": inputs.package_name,
        "versionCode": inputs.version_code,
        "aab": {
            "file": inputs.aab_path.name,
            "sizeBytes": inputs.aab_path.stat().st_size,
            "sha256": inputs.expected_aab_sha256,
        },
        "release": build_track_payload(inputs)["releases"][0],
        "syncListing": inputs.sync_listing,
        "listingLanguage": inputs.listing["language"] if inputs.sync_listing else None,
        "images": image_plan,
        "operations": [
            "insert-edit",
            *( ["update-listing", "replace-store-images"] if inputs.sync_listing else [] ),
            "upload-aab",
            "update-track",
            "validate-edit",
            "commit-edit-with-error-if-review-in-progress",
        ],
        "endpoints": {
            "edit": base,
            "bundleUpload": f"{UPLOAD_ROOT}/applications/{inputs.package_name}/edits/{{editId}}/bundles?uploadType=media",
            "track": f"{base}/tracks/{inputs.track}",
        },
    }


class PlayPublisher:
    def __init__(self, transport: Transport):
        self.transport = transport

    def publish(self, inputs: PublishInputs) -> dict[str, Any]:
        package = urllib.parse.quote(inputs.package_name, safe="")
        collection = f"{API_ROOT}/applications/{package}/edits"
        edit = self.transport.request("POST", collection)
        edit_id = edit.get("id")
        if not isinstance(edit_id, str) or not edit_id:
            raise PublishError("Google Play did not return an edit id")
        base = edit_url(inputs, urllib.parse.quote(edit_id, safe=""))

        try:
            if inputs.sync_listing:
                self._sync_listing(inputs, base, edit_id)

            bundle_url = (
                f"{UPLOAD_ROOT}/applications/{package}/edits/{urllib.parse.quote(edit_id, safe='')}/bundles"
                "?uploadType=media"
            )
            bundle = self.transport.request(
                "POST",
                bundle_url,
                binary_body=inputs.aab_path.read_bytes(),
                content_type="application/octet-stream",
                timeout=180,
            )
            uploaded_version = int(bundle.get("versionCode", 0))
            uploaded_sha = str(bundle.get("sha256", "")).lower()
            if uploaded_version != inputs.version_code:
                raise PublishError(
                    f"Uploaded bundle versionCode mismatch: expected {inputs.version_code}, got {uploaded_version}"
                )
            if uploaded_sha and uploaded_sha != inputs.expected_aab_sha256:
                raise PublishError(
                    f"Uploaded bundle SHA-256 mismatch: expected {inputs.expected_aab_sha256}, got {uploaded_sha}"
                )

            track_url = f"{base}/tracks/{urllib.parse.quote(inputs.track, safe=':_-')}"
            track = self.transport.request("PUT", track_url, json_body=build_track_payload(inputs))
            self.transport.request("POST", f"{base}:validate")
            commit_url = (
                f"{base}:commit?"
                + urllib.parse.urlencode({"changesInReviewBehavior": "ERROR_IF_IN_REVIEW"})
            )
            committed = self.transport.request("POST", commit_url)
            return {
                "mode": "execute",
                "ok": True,
                "packageName": inputs.package_name,
                "editId": edit_id,
                "versionCode": uploaded_version,
                "aabSha256": uploaded_sha or inputs.expected_aab_sha256,
                "track": track.get("track", inputs.track),
                "status": inputs.status,
                "listingSynced": inputs.sync_listing,
                "committedEdit": committed.get("id", edit_id),
            }
        except Exception:
            try:
                self.transport.request("DELETE", base)
            except Exception:
                pass
            raise

    def _sync_listing(self, inputs: PublishInputs, base: str, edit_id: str) -> None:
        language = urllib.parse.quote(str(inputs.listing["language"]), safe="-")
        listing_body = {
            "language": inputs.listing["language"],
            "title": inputs.listing["title"],
            "shortDescription": inputs.listing["shortDescription"],
            "fullDescription": inputs.listing["fullDescription"],
        }
        self.transport.request("PUT", f"{base}/listings/{language}", json_body=listing_body)

        grouped: dict[str, list[pathlib.Path]] = {}
        for image in inputs.images:
            grouped.setdefault(image["type"], []).append(pathlib.Path(image["path"]))
        package = urllib.parse.quote(inputs.package_name, safe="")
        encoded_edit = urllib.parse.quote(edit_id, safe="")
        for image_type, image_paths in grouped.items():
            encoded_type = urllib.parse.quote(image_type, safe="")
            image_base = f"{base}/listings/{language}/{encoded_type}"
            self.transport.request("DELETE", image_base)
            upload_url = (
                f"{UPLOAD_ROOT}/applications/{package}/edits/{encoded_edit}/listings/"
                f"{language}/{encoded_type}?uploadType=media"
            )
            for image_path in image_paths:
                content_type = mimetypes.guess_type(image_path.name)[0] or "application/octet-stream"
                self.transport.request(
                    "POST",
                    upload_url,
                    binary_body=image_path.read_bytes(),
                    content_type=content_type,
                    timeout=120,
                )


def write_result(path: str | None, result: Mapping[str, Any]) -> None:
    rendered = json.dumps(result, indent=2, ensure_ascii=False, sort_keys=True) + "\n"
    if path:
        output_path = pathlib.Path(path)
        output_path.parent.mkdir(parents=True, exist_ok=True)
        temporary = output_path.with_suffix(output_path.suffix + ".tmp")
        temporary.write_text(rendered, encoding="utf-8")
        temporary.replace(output_path)
    print(rendered, end="")


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--aab", required=True, help="Path to the upload-signed Android App Bundle")
    parser.add_argument(
        "--config",
        default=str(pathlib.Path(__file__).resolve().parents[2] / "docs/store/google-play-publish.json"),
    )
    parser.add_argument("--confirm-package", required=True, help="Must exactly match config packageName")
    parser.add_argument("--track", help="Override the configured Play track")
    parser.add_argument("--status", choices=["draft", "inProgress", "halted", "completed"])
    parser.add_argument("--release-name")
    parser.add_argument("--sync-listing", action=argparse.BooleanOptionalAction, default=True)
    parser.add_argument("--result", help="Write the machine-readable result to this path")
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--execute", action="store_true", help="Perform and commit the Play edit")
    mode.add_argument("--dry-run", action="store_true", help="Validate inputs and print the operation plan")
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv if argv is not None else sys.argv[1:])
    try:
        inputs = load_inputs(args)
        if not args.execute:
            write_result(args.result, build_plan(inputs))
            return 0
        token = resolve_access_token()
        result = PlayPublisher(UrlLibTransport(token)).publish(inputs)
        write_result(args.result, result)
        return 0
    except PublishError as error:
        print(f"google-play-publish: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
