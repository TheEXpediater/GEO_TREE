"""Facts about an APK file, read from the file itself (standard library only).

Mobile Settings must describe the exact bytes the download route serves, not what someone typed
into a form, so everything here is derived from the APK:

* SHA-256 and size of the file;
* package name, versionName/versionCode, min/target SDK and the debuggable flag, parsed from the
  binary AndroidManifest.xml;
* the signing certificate's SHA-256, read from the APK Signature Scheme v2/v3 block. This is the
  same fingerprint ``apksigner verify --print-certs`` shows. The server does NOT cryptographically
  verify the signature; ``apksigner verify`` stays the release-time gate;
* the on-device leaf model bundled under ``assets/leaf_model/``: its manifest, the classifier head
  version and classes, and SHA-256s recomputed from the bundled bytes (compared with the manifest).

Results are cached per (path, size, mtime, central directory) so a 200 MB APK is hashed once, not
on every request.
"""

from __future__ import annotations

import hashlib
import json
import struct
import threading
import zipfile
from pathlib import Path
from typing import Any, BinaryIO

MODEL_DIR = "assets/leaf_model/"
_CHUNK = 1024 * 1024

# Android framework attribute resource ids (android.R.attr).
_ATTR_IDS = {
    0x0101021B: "versionCode",
    0x0101021C: "versionName",
    0x0101020C: "minSdkVersion",
    0x01010270: "targetSdkVersion",
    0x0101000F: "debuggable",
}
_SIG_BLOCK_MAGIC = b"APK Sig Block 42"
_SIG_SCHEMES = {0x7109871A: "v2", 0xF05368C0: "v3", 0x1B93AD61: "v3.1"}

_cache: dict[tuple[str, int, int, str], dict[str, Any]] = {}
_cache_lock = threading.Lock()


class ApkError(ValueError):
    """The file is not a readable APK."""


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(_CHUNK), b""):
            digest.update(chunk)
    return digest.hexdigest()


def inspect_apk(path: Path) -> dict[str, Any]:
    """Facts about the APK at ``path`` (cached until the file's size or mtime changes)."""
    stat = path.stat()
    # The tail holds the ZIP central directory (every entry's CRC-32), so any content change shows
    # there even if a same-size file is written within the file system's timestamp granularity.
    with path.open("rb") as fh:
        fh.seek(max(0, stat.st_size - 65536))
        tail = hashlib.sha256(fh.read()).hexdigest()
    key = (str(path.resolve()), stat.st_size, stat.st_mtime_ns, tail)
    with _cache_lock:
        cached = _cache.get(key)
    if cached is not None:
        return cached
    result = _inspect(path, stat.st_size)
    with _cache_lock:
        _cache.clear()  # only the currently served file matters
        _cache[key] = result
    return result


def _inspect(path: Path, size: int) -> dict[str, Any]:
    try:
        with zipfile.ZipFile(path) as archive:
            manifest = parse_binary_manifest(archive.read("AndroidManifest.xml"))
            model = _bundled_model(archive)
        with path.open("rb") as fh:
            signing = read_signing_certificates(fh, size)
    except ApkError:
        raise
    # ValueError covers a corrupt bundled JSON file; struct/Index errors a truncated binary structure.
    except (zipfile.BadZipFile, KeyError, struct.error, IndexError, ValueError) as exc:
        raise ApkError(f"Not a readable APK: {exc.__class__.__name__}") from exc
    return {
        "fileName": path.name,
        "bytes": size,
        "sha256": sha256_file(path),
        **manifest,
        "signing": signing,
        "bundledModel": model,
    }


# Binary AndroidManifest.xml (AXML) -------------------------------------------------------------

def _string_pool(data: bytes, offset: int) -> list[str]:
    _, header_size, _ = struct.unpack_from("<HHI", data, offset)
    count, _styles, flags, strings_start, _ = struct.unpack_from("<IIIII", data, offset + 8)
    utf8 = bool(flags & (1 << 8))
    offsets = struct.unpack_from(f"<{count}I", data, offset + header_size)
    base = offset + strings_start
    strings = []
    for item in offsets:
        pos = base + item
        if utf8:
            pos += 2 if data[pos] & 0x80 else 1  # UTF-16 length, unused
            length = data[pos]
            if length & 0x80:
                length = ((length & 0x7F) << 8) | data[pos + 1]
                pos += 2
            else:
                pos += 1
            strings.append(data[pos:pos + length].decode("utf-8", errors="replace"))
        else:
            length = struct.unpack_from("<H", data, pos)[0]
            pos += 2
            if length & 0x8000:
                length = ((length & 0x7FFF) << 16) | struct.unpack_from("<H", data, pos)[0]
                pos += 2
            strings.append(data[pos:pos + 2 * length].decode("utf-16-le", errors="replace"))
    return strings


def parse_binary_manifest(data: bytes) -> dict[str, Any]:
    """Package, versions, SDK levels and debuggable flag from a compiled AndroidManifest.xml."""
    if struct.unpack_from("<H", data, 0)[0] != 0x0003:
        raise ApkError("AndroidManifest.xml is not a compiled (binary) manifest")
    strings: list[str] = []
    resource_ids: list[int] = []
    found: dict[str, dict[str, Any]] = {}
    offset = struct.unpack_from("<H", data, 2)[0]
    while offset + 8 <= len(data):
        chunk_type, header_size, chunk_size = struct.unpack_from("<HHI", data, offset)
        if chunk_size == 0:
            break
        if chunk_type == 0x0001:
            strings = _string_pool(data, offset)
        elif chunk_type == 0x0180:
            resource_ids = list(struct.unpack_from(f"<{(chunk_size - header_size) // 4}I", data, offset + header_size))
        elif chunk_type == 0x0102:
            name_index = struct.unpack_from("<I", data, offset + 20)[0]
            element = strings[name_index] if name_index < len(strings) else ""
            if element in ("manifest", "uses-sdk", "application") and element not in found:
                found[element] = _attributes(data, offset, header_size, strings, resource_ids)
        offset += chunk_size
    manifest, sdk, application = found.get("manifest", {}), found.get("uses-sdk", {}), found.get("application", {})
    version_code = manifest.get("versionCode")
    return {
        "packageName": manifest.get("package"),
        "versionName": manifest.get("versionName"),
        "versionCode": int(version_code) if isinstance(version_code, int) else None,
        "minSdk": sdk.get("minSdkVersion"),
        "targetSdk": sdk.get("targetSdkVersion"),
        "debuggable": bool(application.get("debuggable", False)),
    }


def _attributes(data: bytes, offset: int, header_size: int, strings: list[str], resource_ids: list[int]) -> dict[str, Any]:
    start, size, count = struct.unpack_from("<HHH", data, offset + header_size + 8)
    values: dict[str, Any] = {}
    base = offset + header_size + start
    for i in range(count):
        _ns, name_index, raw_index, _sz, _res0, data_type, value = struct.unpack_from("<IIIHBBI", data, base + i * size)
        name = strings[name_index] if name_index < len(strings) else ""
        if name_index < len(resource_ids) and resource_ids[name_index] in _ATTR_IDS:
            name = _ATTR_IDS[resource_ids[name_index]]
        if data_type == 0x03:  # string
            values[name] = strings[value] if value < len(strings) else None
        elif data_type in (0x10, 0x11):  # int (decimal / hex)
            values[name] = struct.unpack("<i", struct.pack("<I", value))[0]
        elif data_type == 0x12:  # boolean
            values[name] = value != 0
        elif raw_index != 0xFFFFFFFF and raw_index < len(strings):
            values[name] = strings[raw_index]
    return values


# APK Signature Scheme v2/v3 --------------------------------------------------------------------

def _central_directory_offset(fh: BinaryIO, size: int) -> int:
    tail_len = min(size, 65535 + 22)
    fh.seek(size - tail_len)
    tail = fh.read(tail_len)
    eocd = tail.rfind(b"PK\x05\x06")
    if eocd < 0:
        raise ApkError("No ZIP end-of-central-directory record")
    return struct.unpack_from("<I", tail, eocd + 16)[0]


def _length_prefixed(blob: bytes) -> list[bytes]:
    items, pos = [], 0
    while pos + 4 <= len(blob):
        length = struct.unpack_from("<I", blob, pos)[0]
        items.append(blob[pos + 4:pos + 4 + length])
        pos += 4 + length
    return items


def read_signing_certificates(fh: BinaryIO, size: int) -> dict[str, Any]:
    """Signer certificate SHA-256s per signature scheme found in the APK Signing Block."""
    cd_offset = _central_directory_offset(fh, size)
    fh.seek(cd_offset - 24)
    footer = fh.read(24)
    if len(footer) != 24 or footer[8:] != _SIG_BLOCK_MAGIC:
        return {"schemes": [], "certificateSha256": [], "note": "No v2/v3 signing block (unsigned or v1-only APK)."}
    block_size = struct.unpack_from("<Q", footer, 0)[0]
    fh.seek(cd_offset - block_size - 8)
    block = fh.read(block_size + 8)
    pairs = block[8:-24]
    schemes: list[str] = []
    certificates: list[str] = []
    pos = 0
    while pos + 12 <= len(pairs):
        length, block_id = struct.unpack_from("<QI", pairs, pos)
        value = pairs[pos + 12:pos + 8 + length]
        pos += 8 + length
        scheme = _SIG_SCHEMES.get(block_id)
        if scheme is None:
            continue
        schemes.append(scheme)
        for signer in _length_prefixed(_length_prefixed(value)[0]):
            signed_data = _length_prefixed(signer)[0]
            # signed data = digests, certificates, ... (all length-prefixed)
            certs_blob = _length_prefixed(signed_data)[1]
            for cert in _length_prefixed(certs_blob):
                fingerprint = hashlib.sha256(cert).hexdigest()
                if fingerprint not in certificates:
                    certificates.append(fingerprint)
    return {"schemes": schemes, "certificateSha256": certificates, "note": None}


# Bundled on-device model -----------------------------------------------------------------------

def _zip_sha256(archive: zipfile.ZipFile, name: str) -> str:
    digest = hashlib.sha256()
    with archive.open(name) as fh:
        for chunk in iter(lambda: fh.read(_CHUNK), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _bundled_model(archive: zipfile.ZipFile) -> dict[str, Any] | None:
    names = set(archive.namelist())
    manifest_name = MODEL_DIR + "manifest.json"
    if manifest_name not in names:
        return None
    manifest = json.loads(archive.read(manifest_name))
    backbone, head_ref = manifest.get("backbone") or {}, manifest.get("head") or {}
    head_name = MODEL_DIR + str(head_ref.get("file", "classifier_head.json"))
    onnx_name = MODEL_DIR + str(backbone.get("file", ""))
    head = json.loads(archive.read(head_name)) if head_name in names else {}
    head_sha = _zip_sha256(archive, head_name) if head_name in names else None
    onnx_sha = _zip_sha256(archive, onnx_name) if onnx_name in names else None
    return {
        "backbone": manifest.get("model"),
        "backboneDisplayName": manifest.get("modelDisplayName"),
        "embeddingModel": manifest.get("embeddingModel"),
        "embeddingDimension": manifest.get("embeddingDimension"),
        "modelVersion": head.get("modelVersion"),
        "classes": [c.get("name") for c in sorted(head.get("classes", []), key=lambda c: c.get("index", 0))],
        "confidenceThreshold": manifest.get("confidenceThreshold"),
        "headSha256": head_sha,
        "onnxSha256": onnx_sha,
        "onnxBytes": archive.getinfo(onnx_name).file_size if onnx_name in names else None,
        # The bundled bytes must hash to what the exporter recorded in the manifest.
        "integrityOk": bool(head_sha and onnx_sha and head_sha == head_ref.get("sha256") and onnx_sha == backbone.get("sha256")),
        "exportParity": manifest.get("exportParity"),
    }
