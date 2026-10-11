"""
Prepares a GEO Tree APK release for the stable download page.

    python tools/distribution/prepare_release.py --apk android/app/build/outputs/apk/debug/app-debug.apk

Writes to tools/distribution/release/ (gitignored):
    GEO_Tree.apk          the APK under its permanent name (the URL never changes)
    index.html            the /download page: version, size, SHA-256, install steps
    SHA256SUMS            sha256 of the APK
    geotree_download_qr.png / .svg   QR code for the page URL (generate once, print once)

The QR encodes ONLY the page URL (default https://geotree.museuma7k9x3.tech/download), never an
APK file name or version, so a printed code stays valid for every future release. Publishing a
new release replaces GEO_Tree.apk and index.html on the server; see README "Hosted deployment".
"""

from __future__ import annotations

import argparse
import hashlib
import html
import shutil
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent
DEFAULT_URL = "https://geotree.museuma7k9x3.tech/download"
APK_NAME = "GEO_Tree.apk"


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def apk_version(apk: Path) -> str:
    """versionName from the APK via aapt (Android SDK), or 'unknown'."""
    sdk = Path.home() / "AppData/Local/Android/Sdk/build-tools"
    for aapt in sorted(sdk.glob("*/aapt*"), reverse=True):
        try:
            out = subprocess.run([str(aapt), "dump", "badging", str(apk)], capture_output=True, text=True, timeout=60).stdout
        except OSError:
            continue
        for part in out.splitlines()[0].split():
            if part.startswith("versionName="):
                return part.split("=", 1)[1].strip("'")
    return "unknown"


PAGE = """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>GEO Tree for Android</title>
<style>
  :root {{ --forest:#1F3D2B; --moss:#5E7A3A; --sand:#F4EEDF; --cream:#FBF8F1; --charcoal:#23262A; --stone:#5C605A; }}
  * {{ box-sizing: border-box; }}
  body {{ margin:0; background:var(--sand); color:var(--charcoal); font:16px/1.5 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif; }}
  main {{ max-width:560px; margin:0 auto; padding:32px 16px 48px; }}
  .card {{ background:var(--cream); border:1px solid #C9C3B2; border-radius:16px; padding:24px; }}
  h1 {{ margin:0 0 4px; color:var(--forest); font-size:28px; }}
  .sub {{ margin:0 0 20px; color:var(--stone); }}
  a.button {{ display:block; text-align:center; background:var(--forest); color:#fff; text-decoration:none; font-weight:600; padding:16px; border-radius:999px; margin:20px 0; }}
  dl {{ display:grid; grid-template-columns:auto 1fr; gap:6px 16px; margin:0; font-size:14px; }}
  dt {{ color:var(--stone); }}
  dd {{ margin:0; overflow-wrap:anywhere; }}
  code {{ font:13px/1.4 ui-monospace,Consolas,monospace; }}
  ol {{ padding-left:20px; }}
  footer {{ margin-top:16px; color:var(--stone); font-size:13px; }}
</style>
</head>
<body>
<main>
  <div class="card">
    <h1>GEO Tree</h1>
    <p class="sub">Tamarind field mapping, field guidance and leaf assessment &middot; Android</p>
    <a class="button" href="/download/{apk}" download>Download GEO Tree {version}</a>
    <dl>
      <dt>Version</dt><dd>{version}</dd>
      <dt>Size</dt><dd>{size_mb:.1f} MB</dd>
      <dt>Published</dt><dd>{published}</dd>
      <dt>SHA-256</dt><dd><code>{digest}</code></dd>
    </dl>
    <h2 style="font-size:18px;margin:24px 0 8px">Install</h2>
    <ol>
      <li>Tap <strong>Download</strong> and open the file when it finishes.</li>
      <li>If Android asks, allow installing apps from this browser.</li>
      <li>Open GEO Tree and sign in with the account you were given.</li>
    </ol>
    <p style="font-size:14px;color:var(--stone)">The SHA-256 lets you check that the file arrived intact
    (for example <code>Get-FileHash GEO_Tree.apk</code> on Windows). This page always offers the latest release.</p>
  </div>
  <footer>Map data &copy; OpenStreetMap contributors. Test build for the GEO Tree capstone project.</footer>
</main>
</body>
</html>
"""


def make_qr(url: str, out: Path) -> None:
    try:
        import segno
    except ImportError:
        print("QR skipped: install segno (python -m pip install segno) and run again.")
        return
    qr = segno.make(url, error="m")
    qr.save(str(out / "geotree_download_qr.png"), scale=12, border=4, dark="#1F3D2B")
    qr.save(str(out / "geotree_download_qr.svg"), scale=12, border=4, dark="#1F3D2B")
    decoded = qr.designator  # version-level info; the payload is exactly `url`
    print(f"QR ({decoded}) encodes: {url}")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--url", default=DEFAULT_URL, help="stable page URL encoded in the QR code")
    parser.add_argument("--out", type=Path, default=HERE / "release")
    args = parser.parse_args(argv)

    if not args.url.endswith("/download"):
        print("Refusing: the QR must point at the stable /download page, not a file.")
        return 2
    apk = args.apk.resolve()
    if not apk.is_file():
        print(f"APK not found: {apk}")
        return 2
    args.out.mkdir(parents=True, exist_ok=True)
    target = args.out / APK_NAME
    shutil.copyfile(apk, target)
    digest = sha256(target)
    if digest != sha256(apk):
        print("Copy verification failed.")
        return 1
    version = apk_version(apk)
    (args.out / "SHA256SUMS").write_text(f"{digest}  {APK_NAME}\n", encoding="ascii")
    (args.out / "index.html").write_text(
        PAGE.format(
            apk=APK_NAME,
            version=html.escape(version),
            size_mb=target.stat().st_size / (1024 * 1024),
            published=datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M UTC"),
            digest=digest,
        ),
        encoding="utf-8",
    )
    make_qr(args.url, args.out)
    print(f"Release prepared in {args.out}")
    print(f"  {APK_NAME}  version {version}  {target.stat().st_size} bytes")
    print(f"  SHA-256 {digest}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
