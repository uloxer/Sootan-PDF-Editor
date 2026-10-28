"""Package an already-built core UI as an unsigned, self-contained Firefox extension."""
import argparse
import json
from pathlib import Path
import re
import shutil
import zipfile


def build(bundle: Path, destination: Path):
    destination.mkdir(parents=True, exist_ok=True)
    extension = destination / "extension"
    if extension.exists():
        shutil.rmtree(extension)
    shutil.copytree(bundle, extension)
    manifest = {
        "manifest_version": 3,
        "name": "Sootan PDF Editor",
        "version": "3.2.5",
        "description": "Edit local PDFs offline in a full editor tab.",
        "browser_specific_settings": {"gecko": {
            "id": "sootan-pdf-editor@uloxer.local",
            "strict_min_version": "128.0",
            "data_collection_permissions": {"required": ["none"]},
        }},
        "action": {"default_title": "Open Sootan PDF Editor", "default_icon": "modern-logo/logo192.png"},
        "icons": {"192": "modern-logo/logo192.png"},
        "background": {"scripts": ["extension-background.js"]},
        "content_security_policy": {"extension_pages":
            "default-src 'self'; script-src 'self' 'wasm-unsafe-eval'; worker-src 'self'; "
            "object-src 'none'; connect-src 'self'; img-src 'self' blob: data:; "
            "style-src 'self' 'unsafe-inline'; font-src 'self' data:; frame-src 'self' blob: data:"},
    }
    (extension / "manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    (extension / "extension-background.js").write_text(
        "browser.action.onClicked.addListener(() => browser.tabs.create({url: browser.runtime.getURL('index.html')}));\n",
        encoding="utf-8")
    html_path = extension / "index.html"
    html = html_path.read_text(encoding="utf-8")
    scripts = ["window.__SOOTAN_RUNTIME__={kind:'extension',serverOperations:false,nativeFileDialogs:false,renderer:'wasm'};"]

    def externalize(match):
        if re.search(r"\bsrc\s*=", match[1]):
            return match[0]
        scripts.append(match[2])
        return ""

    html = re.sub(r"<script([^>]*)>(.*?)</script>", externalize, html, flags=re.S | re.I)
    html = html.replace("<head>", '<head>\n<script src="extension-bootstrap.js"></script>', 1)
    html_path.write_text(html, encoding="utf-8")
    (extension / "extension-bootstrap.js").write_text("\n".join(scripts), encoding="utf-8")
    output = destination / "sootan-pdf-editor-3.2.5-unsigned.xpi"
    with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for path in sorted(extension.rglob("*")):
            if path.is_file():
                archive.write(path, path.relative_to(extension).as_posix())
    print(f"{output}: {output.stat().st_size} bytes")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bundle", type=Path, default=Path("frontend/dist"))
    parser.add_argument("--destination", type=Path, default=Path(".tooling/firefox-package"))
    args = parser.parse_args()
    build(args.bundle.resolve(strict=True), args.destination.resolve())
