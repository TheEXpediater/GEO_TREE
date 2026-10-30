"""Builds small but structurally real APKs for tests: a binary (AXML) AndroidManifest.xml, an
APK Signature Scheme v2 block in front of the ZIP central directory, and a bundled leaf model."""

from __future__ import annotations

import hashlib
import io
import json
import struct
import zipfile
from pathlib import Path

CERT = b"0\x82test-distribution-certificate-der"
CERT_SHA256 = hashlib.sha256(CERT).hexdigest()
CLASSES = ["Healthy", "Spotting", "Powdery Mildew"]
EMBEDDING = "dinov2_vits14|336px|resize384-bicubic-centercrop|imagenet-norm|cls|v1"

_ATTR_IDS = {"versionCode": 0x0101021B, "versionName": 0x0101021C, "minSdkVersion": 0x0101020C,
             "targetSdkVersion": 0x01010270, "debuggable": 0x0101000F}


def _string_pool(strings: list[str]) -> bytes:
    encoded = [struct.pack("<H", len(s)) + s.encode("utf-16-le") + b"\x00\x00" for s in strings]
    offsets, pos = [], 0
    for item in encoded:
        offsets.append(pos)
        pos += len(item)
    body = b"".join(encoded)
    body += b"\x00" * (-len(body) % 4)
    header_size = 28
    strings_start = header_size + 4 * len(strings)
    size = strings_start + len(body)
    return struct.pack("<HHIIIIII", 0x0001, header_size, size, len(strings), 0, 0, strings_start, 0) + \
        struct.pack(f"<{len(strings)}I", *offsets) + body


def _element(strings: list[str], name: str, attributes: list[tuple[str, int, int, int]]) -> bytes:
    """attributes: (name, raw string index or 0xFFFFFFFF, data type, data)."""
    attrs = b"".join(struct.pack("<IIIHBBI", 0xFFFFFFFF, strings.index(n), raw, 8, 0, t, d) for n, raw, t, d in attributes)
    ext = struct.pack("<IIHHHHHH", 0xFFFFFFFF, strings.index(name), 20, 20, len(attributes), 0, 0, 0)
    body = struct.pack("<II", 1, 0xFFFFFFFF) + ext + attrs
    return struct.pack("<HHI", 0x0102, 16, 8 + len(body)) + body


def binary_manifest(package: str, version_name: str, version_code: int, debuggable: bool = False) -> bytes:
    strings = list(_ATTR_IDS) + ["package", "manifest", "uses-sdk", "application", version_name, package]
    pool = _string_pool(strings)
    resmap_ids = list(_ATTR_IDS.values())
    resmap = struct.pack("<HHI", 0x0180, 8, 8 + 4 * len(resmap_ids)) + struct.pack(f"<{len(resmap_ids)}I", *resmap_ids)
    string = lambda value: (strings.index(value), 0x03, strings.index(value))  # noqa: E731
    elements = [
        _element(strings, "manifest", [("versionCode", 0xFFFFFFFF, 0x10, version_code), ("versionName", *string(version_name)),
                                       ("package", *string(package))]),
        _element(strings, "uses-sdk", [("minSdkVersion", 0xFFFFFFFF, 0x10, 26), ("targetSdkVersion", 0xFFFFFFFF, 0x10, 35)]),
        _element(strings, "application", [("debuggable", 0xFFFFFFFF, 0x12, 0xFFFFFFFF if debuggable else 0)]),
    ]
    body = pool + resmap + b"".join(elements)
    return struct.pack("<HHI", 0x0003, 8, 8 + len(body)) + body


def _lp(data: bytes) -> bytes:
    return struct.pack("<I", len(data)) + data


def _signing_block(cert: bytes) -> bytes:
    signed_data = _lp(b"") + _lp(_lp(cert)) + _lp(b"")
    signer = _lp(signed_data) + _lp(b"") + _lp(b"public-key")
    value = _lp(_lp(signer))
    pairs = struct.pack("<QI", 4 + len(value), 0x7109871A) + value
    size = len(pairs) + 8 + 16
    return struct.pack("<Q", size) + pairs + struct.pack("<Q", size) + b"APK Sig Block 42"


def _insert_signing_block(data: bytes, cert: bytes) -> bytes:
    eocd = data.rfind(b"PK\x05\x06")
    cd_offset = struct.unpack_from("<I", data, eocd + 16)[0]
    block = _signing_block(cert)
    patched = data[:cd_offset] + block + data[cd_offset:]
    eocd += len(block)
    return patched[:eocd + 16] + struct.pack("<I", cd_offset + len(block)) + patched[eocd + 20:]


def build_apk(path: Path, version_name: str = "0.8.1", version_code: int = 12, *, package: str = "com.geotree.app",
              debuggable: bool = False, cert: bytes | None = CERT, model_version: int = 3, corrupt_model: bool = False,
              extra: bytes = b"") -> Path:
    onnx = b"fake-onnx-backbone" + extra
    head = json.dumps({"formatVersion": 1, "modelVersion": model_version, "embeddingModel": EMBEDDING,
                       "classes": [{"index": i, "classId": f"c{i}", "name": n} for i, n in enumerate(CLASSES)]}).encode()
    manifest = {"model": "dinov2_vits14", "modelDisplayName": "DINOv2 ViT-S/14", "embeddingModel": EMBEDDING,
                "embeddingDimension": 384, "confidenceThreshold": 0.6,
                "backbone": {"file": "dinov2_vits14_336.onnx", "sha256": hashlib.sha256(onnx).hexdigest()},
                "head": {"file": "classifier_head.json",
                         "sha256": "0" * 64 if corrupt_model else hashlib.sha256(head).hexdigest()}}
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("AndroidManifest.xml", binary_manifest(package, version_name, version_code, debuggable))
        archive.writestr("classes.dex", b"dex\n035\x00" + version_name.encode())
        archive.writestr("assets/leaf_model/manifest.json", json.dumps(manifest))
        archive.writestr("assets/leaf_model/classifier_head.json", head)
        archive.writestr("assets/leaf_model/dinov2_vits14_336.onnx", onnx)
    data = buffer.getvalue()
    path.write_bytes(_insert_signing_block(data, cert) if cert else data)
    return path
