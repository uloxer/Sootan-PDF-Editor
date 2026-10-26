"""Build the x64 per-user Windows application without changing machine state.

Run with JDK 25, Node 24, Task, uv and Python 3.11+ on the build host.
Pinned downloads and wheel hashes are verified before producing the image.
"""
from pathlib import Path
import argparse
import hashlib
import json
import os
import shutil
import struct
import stat
import subprocess
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[2]
SPEC = Path(__file__).resolve().parent
CACHE = ROOT / ".tooling/windows-downloads"
WORK = ROOT / ".tooling/windows-build"
OUT = ROOT / ".tooling/windows-package"


def run(args, **kwargs):
    print("+", " ".join(map(str, args)), flush=True)
    subprocess.run(list(map(str, args)), check=True, cwd=ROOT, **kwargs)


def clear(path):
    path = path.resolve()
    if not path.is_relative_to(ROOT / ".tooling"):
        raise ValueError("Build cleanup outside .tooling is forbidden")
    if path.exists():
        def make_writable(function, filename, error):
            os.chmod(filename, stat.S_IWRITE)
            function(filename)
        shutil.rmtree(path, onerror=make_writable)
    path.mkdir(parents=True)


def extract(archive, target):
    target.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(archive) as source:
        for name in source.namelist():
            if not (target / name).resolve().is_relative_to(target.resolve()):
                raise ValueError("Archive path escapes extraction directory")
        source.extractall(target)


def imported_dlls(path):
    """Read normal PE imports to retain Tesseract's actual DLL closure."""
    data = path.read_bytes()
    pe = struct.unpack_from("<I", data, 60)[0]
    count = struct.unpack_from("<H", data, pe + 6)[0]
    optional_size = struct.unpack_from("<H", data, pe + 20)[0]
    optional = pe + 24
    directories = optional + (112 if struct.unpack_from("<H", data, optional)[0] == 0x20B else 96)
    imports = struct.unpack_from("<I", data, directories + 8)[0]
    sections = []
    for i in range(count):
        header = optional + optional_size + i * 40
        size, address, raw_size, raw = struct.unpack_from("<IIII", data, header + 8)
        sections.append((address, max(size, raw_size), raw))
    def offset(rva):
        for address, size, raw in sections:
            if address <= rva < address + size:
                return raw + rva - address
        raise ValueError("Unmapped PE import RVA")
    if not imports:
        return []
    result = []
    cursor = offset(imports)
    while any(data[cursor:cursor + 20]):
        name_rva = struct.unpack_from("<I", data, cursor + 12)[0]
        start = offset(name_rva)
        result.append(data[start:data.index(b"\0", start)].decode("ascii"))
        cursor += 20
    return result


def footprint(path):
    return sum(p.stat().st_size for p in path.rglob("*") if p.is_file())


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--skip-build", action="store_true", help="Reuse the already verified Windows JAR")
    parser.add_argument("--image-only", action="store_true", help="Build the test app-image before creating an installer")
    args = parser.parse_args()
    if os.name != "nt":
        raise SystemExit("Build this package on Windows x64")
    jdk = Path(os.environ.get("JAVA_HOME", ""))
    if not (jdk / "bin/jpackage.exe").exists():
        raise SystemExit("Set JAVA_HOME to JDK 25")
    CACHE.mkdir(parents=True, exist_ok=True)
    for name, item in json.loads((SPEC / "downloads.json").read_text()).items():
        target = CACHE / name
        if not target.exists():
            urllib.request.urlretrieve(item["url"], target)
        if hashlib.sha256(target.read_bytes()).hexdigest() != item["sha256"]:
            raise ValueError("Download hash mismatch: " + name)
    extract(CACHE / "wix.zip", CACHE / "wix")
    if not (CACHE / "7zip/7z.exe").exists():
        run([CACHE / "7zr.exe", "x", CACHE / "7zip.exe", "-o" + str(CACHE / "7zip"), "-y"])
    if not (CACHE / "tesseract/tesseract.exe").exists():
        run([CACHE / "7zip/7z.exe", "x", CACHE / "tesseract.exe", "-o" + str(CACHE / "tesseract"), "-y"])
    if not (CACHE / "python/python.exe").exists():
        extract(CACHE / "python.zip", CACHE / "python")
    (CACHE / "python/python313._pth").write_text("python313.zip\n.\nLib/site-packages\nimport site\n")
    lock_hash = hashlib.sha256((SPEC / "ocr-requirements.txt").read_bytes()).hexdigest()
    marker = CACHE / "python/wheels.sha256"
    if not marker.exists() or marker.read_text() != lock_hash:
        run(["uv", "pip", "install", "--target", CACHE / "python/Lib/site-packages", "--python-version", "3.13", "--python-platform", "x86_64-pc-windows-msvc", "--only-binary", ":all:", "--require-hashes", "-r", SPEC / "ocr-requirements.txt"])
        marker.write_text(lock_hash)
    run([CACHE / "python/python.exe", SPEC / "brand_assets.py"])
    if not args.skip_build:
        run([ROOT / "gradlew.bat", ":stirling-pdf:bootJar", "-PbuildWithFrontend=true", "-PwindowsPackage", "-PjpdfiumPlatforms=windows-x64", "--console=plain"])
    jars = list((ROOT / "app/core/build/libs").glob("*.jar"))
    if len(jars) != 1:
        raise ValueError("Expected one executable JAR")
    with zipfile.ZipFile(jars[0]) as archive:
        names = archive.namelist()
        if any("/static/" in n and n.endswith((".br", ".gz")) for n in names):
            raise ValueError("Windows JAR still contains static precompression siblings")
        if any("jpdfium-natives-linux" in n for n in names):
            raise ValueError("Windows JAR contains Linux PDFium natives")
    clear(WORK)
    clear(OUT)
    inputs = WORK / "input"
    helpers = inputs / "helpers"
    inputs.mkdir()
    helpers.mkdir()
    shutil.copy2(jars[0], inputs / "sootan.jar")
    shutil.copytree(CACHE / "python", helpers / "python")
    for path in list((helpers / "python").rglob("__pycache__")):
        shutil.rmtree(path)
    # Wheel-shipped development suites are not used by the OCR runtime.
    for name in ["jsonschema", "jsonschema_specifications", "referencing"]:
        tests = helpers / "python/Lib/site-packages" / name / "tests"
        if tests.exists():
            shutil.rmtree(tests)
    scripts = helpers / "python/Lib/site-packages/bin"
    if scripts.exists():
        shutil.rmtree(scripts)
    tess = helpers / "tesseract"
    tess.mkdir()
    library_files = {p.name.lower(): p for p in (CACHE / "tesseract").glob("*.dll")}
    pending = [CACHE / "tesseract/tesseract.exe"]
    seen = set()
    while pending:
        source = pending.pop()
        if source.name.lower() in seen:
            continue
        seen.add(source.name.lower())
        shutil.copy2(source, tess / source.name)
        pending.extend(library_files[n.lower()] for n in imported_dlls(source) if n.lower() in library_files)
    (tess / "tessdata").mkdir()
    for name in ["eng.traineddata", "osd.traineddata"]:
        shutil.copy2(CACHE / name, tess / "tessdata" / name)
    # Tesseract's PDF/config files accompany the English model, without other language packs.
    for name in ["configs", "tessconfigs", "pdf.ttf"]:
        source = CACHE / "tesseract/tessdata" / name
        if source.is_dir(): shutil.copytree(source, tess / "tessdata" / name)
        elif source.is_file(): shutil.copy2(source, tess / "tessdata" / name)
    shutil.copytree(CACHE / "tesseract/doc", tess / "notices")
    shutil.copytree(SPEC / "notices", inputs / "notices")
    binary_dir = helpers / "bin"
    binary_dir.mkdir()
    csc = Path(os.environ["WINDIR"]) / "Microsoft.NET/Framework64/v4.0.30319/csc.exe"
    extract(CACHE / "webview2.nupkg", CACHE / "webview2")
    sdk = CACHE / "webview2"
    for name in ["Microsoft.Web.WebView2.Core.dll", "Microsoft.Web.WebView2.WinForms.dll"]:
        shutil.copy2(sdk / "lib/net462" / name, inputs / name)
    shutil.copy2(sdk / "runtimes/win-x64/native/WebView2Loader.dll", inputs / "WebView2Loader.dll")
    shutil.copy2(SPEC / "assets/sootan.ico", inputs / "sootan.ico")
    for name in ["LICENSE.txt", "NOTICE.txt"]:
        shutil.copy2(sdk / name, inputs / ("WEBVIEW2-" + name))
    run([csc, "/nologo", "/codepage:65001", "/target:winexe", "/platform:x64", "/out:" + str(inputs / "SootanHost.exe"), "/r:System.Windows.Forms.dll", "/r:System.Drawing.dll", "/r:System.Web.Extensions.dll", "/r:" + str(inputs / "Microsoft.Web.WebView2.Core.dll"), "/r:" + str(inputs / "Microsoft.Web.WebView2.WinForms.dll"), SPEC / "SootanHost.cs"])
    run([csc, "/nologo", "/codepage:65001", "/target:exe", "/platform:x64", "/main:SootanHostTests", "/out:" + str(inputs / "SootanHostTests.exe"), "/r:System.Windows.Forms.dll", "/r:System.Drawing.dll", "/r:System.Web.Extensions.dll", "/r:" + str(inputs / "Microsoft.Web.WebView2.Core.dll"), "/r:" + str(inputs / "Microsoft.Web.WebView2.WinForms.dll"), SPEC / "SootanHost.cs", SPEC / "tests/SootanHostTests.cs"])
    run([inputs / "SootanHostTests.exe"])
    (inputs / "SootanHostTests.exe").unlink()
    run([csc, "/nologo", "/target:exe", "/out:" + str(binary_dir / "ocrmypdf.exe"), SPEC / "OcrHelper.cs"])
    classes = WORK / "classes"
    classes.mkdir()
    run([jdk / "bin/javac.exe", "-d", classes, SPEC / "SootanLauncher.java"])
    test_classes = WORK / "test-classes"
    test_classes.mkdir()
    run([jdk / "bin/javac.exe", "-cp", classes, "-d", test_classes, SPEC / "tests/SootanLauncherPathTest.java"])
    run([jdk / "bin/java.exe", "-cp", str(classes) + os.pathsep + str(test_classes), "SootanLauncherPathTest"])
    run([jdk / "bin/jar.exe", "--create", "--file", inputs / "launcher.jar", "--main-class", "SootanLauncher", "-C", classes, "."])
    shutil.copy2(SPEC / "assets/sootan.png", inputs / "sootan.png")
    shutil.copy2(ROOT / "LICENSE", inputs / "LICENSE.txt")
    shutil.copy2(SPEC / "THIRD-PARTY.md", inputs / "THIRD-PARTY.md")
    shutil.copy2(SPEC / "downloads.json", inputs / "downloads.json")
    shutil.copy2(SPEC / "ocr-requirements.txt", inputs / "ocr-requirements.txt")
    modules = "java.base,java.compiler,java.desktop,java.instrument,java.logging,java.management,java.naming,java.net.http,java.rmi,java.security.jgss,java.security.sasl,java.smartcardio,java.sql,java.xml,java.xml.crypto,jdk.charsets,jdk.crypto.cryptoki,jdk.crypto.mscapi,jdk.jfr,jdk.localedata,jdk.management,jdk.naming.dns,jdk.unsupported,jdk.zipfs"
    runtime = WORK / "runtime"
    run([jdk / "bin/jlink.exe", "--add-modules", modules, "--output", runtime, "--strip-debug", "--no-header-files", "--no-man-pages", "--compress=zip-6"])
    # CPython extensions can resolve the redistributable VC runtime privately.
    for name in ["msvcp140.dll", "vcruntime140.dll", "vcruntime140_1.dll"]:
        source = runtime / "bin" / name
        if source.exists() and not (helpers / "python" / name).exists():
            shutil.copy2(source, helpers / "python" / name)
    stop = WORK / "stop.properties"
    stop.write_text("arguments=--stop\nicon=" + (SPEC / "assets/sootan.ico").as_posix() + "\n")
    run([jdk / "bin/jpackage.exe", "--type", "app-image", "--name", "Sootan PDF Editor", "--app-version", "3.2.5", "--vendor", "Sootan PDF Editor", "--input", inputs, "--main-jar", "launcher.jar", "--main-class", "SootanLauncher", "--runtime-image", runtime, "--icon", SPEC / "assets/sootan.ico", "--dest", OUT, "--add-launcher", "Stop Sootan PDF Editor=" + str(stop)])
    env = os.environ.copy()
    env["PATH"] = str(CACHE / "wix") + os.pathsep + env["PATH"]
    installer = OUT / "installer"
    installer.mkdir()
    if not args.image_only:
        run([jdk / "bin/jpackage.exe", "--type", "exe", "--app-image", OUT / "Sootan PDF Editor", "--name", "Sootan PDF Editor", "--app-version", "3.2.5", "--vendor", "Sootan PDF Editor", "--dest", installer, "--win-per-user-install", "--win-upgrade-uuid", "adcbdd25-309f-43c7-81cd-557cd7601674", "--win-menu", "--win-menu-group", "Sootan PDF Editor", "--win-shortcut", "--install-dir", "Sootan PDF Editor", "--license-file", ROOT / "LICENSE", "--temp", WORK / "installer"], env=env)
    report = {"jar_bytes": jars[0].stat().st_size, "runtime_bytes": footprint(runtime), "helpers_bytes": footprint(helpers), "installed_image_bytes": footprint(OUT / "Sootan PDF Editor"), "installer_bytes": {p.name: p.stat().st_size for p in installer.glob("*.exe")}, "java_modules": modules.split(","), "tesseract_files": sorted(seen)}
    (OUT / "sizes.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
