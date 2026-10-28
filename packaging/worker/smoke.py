"""Exercise the non-web worker with private helpers and existing synthetic golden fixtures."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import uuid
import zipfile

import pikepdf
import pypdfium2 as pdfium


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java", type=Path, required=True)
    parser.add_argument("--helpers", type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    evidence = root / ".tooling/nonweb-worker-golden"
    evidence.mkdir(exist_ok=True)
    distribution = root / "app/worker/build/install/worker/lib"
    env = dict(os.environ)
    helpers = args.helpers.resolve()
    env["SOOTAN_OCRMYPDF"] = str(helpers / "bin/ocrmypdf.exe")
    env["TESSDATA_PREFIX"] = str(helpers / "tesseract/tessdata")
    env["PATH"] = os.pathsep.join(str(helpers / d) for d in ["bin", "tesseract", "python"]) + os.pathsep + env.get("PATH", "")
    env["PYTHONDONTWRITEBYTECODE"] = "1"
    command = [str(args.java.resolve()), "-cp", str(distribution / "*"), "stirling.software.worker.WorkerMain"]
    results = {}

    def run(operation, source, options=None, certificate=None, success=True):
        job = evidence / str(uuid.uuid4())
        job.mkdir()
        target = job / "input-main.pdf"
        shutil.copyfile(source, target)
        original = hashlib.sha256(target.read_bytes()).hexdigest()
        if certificate:
            shutil.copyfile(certificate, job / "certificate.p12")
        request = {"requestId": job.name, "operation": operation, "input": target.name, "options": options or {}}
        completed = subprocess.run(command + [str(job)], input=json.dumps(request) + "\n", capture_output=True, text=True, env=env, timeout=180)
        messages = [json.loads(line) for line in completed.stdout.splitlines() if line.startswith("{")]
        assert messages and (messages[-1]["type"] == "result") == success, (operation, messages, completed.stderr)
        assert hashlib.sha256(target.read_bytes()).hexdigest() == original
        results.setdefault(operation, []).append({"ok": success, "exit": completed.returncode, "messages": messages})
        return job / messages[-1]["file"] if success else None

    source = root / ".tooling/smoke-input.pdf"
    sanitized = run("sanitize", source, {"removeJavaScript": True, "removeMetadata": True, "removeXMPMetadata": True})
    with pikepdf.Pdf.open(sanitized) as doc:
        assert "/OpenAction" not in doc.Root and not doc.docinfo
    encrypted = run("aes-encrypt", source, {"password": "reader-fixture", "ownerPassword": "owner-fixture", "preventPrinting": True})
    with pikepdf.Pdf.open(encrypted, password="reader-fixture") as doc:
        assert doc.encryption.bits == 256 and not doc.allow.print_lowres
    clear = run("decrypt", encrypted, {"sourcePassword": "owner-fixture"})
    with pikepdf.Pdf.open(clear) as doc:
        assert len(doc.pages) == 2 and not doc.is_encrypted
    run("decrypt", encrypted, {"sourcePassword": "reader-fixture"}, success=False)
    certificate = evidence / "fixture.p12"
    if not certificate.exists():
        subprocess.run([str(args.java.resolve().with_name("keytool.exe")), "-genkeypair", "-alias", "fixture", "-keyalg", "RSA", "-keysize", "2048", "-validity", "30", "-dname", "CN=Sootan Worker Fixture", "-storetype", "PKCS12", "-keystore", str(certificate), "-storepass", "smoke-test-password"], check=True, capture_output=True)
    signed = run("cert-sign-invisible", source, {"password": "smoke-test-password"}, certificate)
    report = run("signature-integrity", signed)
    signature = json.loads(report.read_text())[0]
    assert signature["valid"] and signature["coversEntireDocument"] and not signature["trustValid"]
    appended = evidence / "appended.pdf"
    appended.write_bytes(signed.read_bytes() + b"\n% unsigned append\n")
    report = run("signature-integrity", appended)
    assert not json.loads(report.read_text())[0]["coversEntireDocument"]
    scan = root / ".tooling/OCR Test With Spaces/scanned English.pdf"
    ocr = run("ocr", scan, {"sidecar": True, "deskew": True, "rotatePages": True})
    with zipfile.ZipFile(ocr) as archive:
        text = archive.read("output.txt").decode()
        assert "grassy hill and slate cliffs" in text
        pdf = pdfium.PdfDocument(archive.read("output.pdf"))
        assert "Invoice 2026 1042" in pdf[0].get_textpage().get_text_range()
    run("ocr", scan, {"clean": True}, success=False)
    run("unknown", source, success=False)
    results["runtime_jars"] = sorted(p.name for p in distribution.glob("*.jar"))
    assert not any(any(word in name.lower() for word in ["spring", "tomcat", "jetty"]) for name in results["runtime_jars"])
    results["distribution_bytes"] = sum(p.stat().st_size for p in distribution.glob("*.jar"))
    (evidence / "results.json").write_text(json.dumps(results, indent=2))
    print(f"Non-web worker golden operations PASS; {results['distribution_bytes']} dependency bytes; evidence {evidence}")


if __name__ == "__main__":
    main()
