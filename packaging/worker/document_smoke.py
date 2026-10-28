"""Saved-file goldens for the shared non-web document-maintenance operations."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import uuid

import pikepdf
import pypdfium2 as pdfium


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java", type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    evidence = root / ".tooling/nonweb-document-golden"
    evidence.mkdir(exist_ok=True)
    fixture = evidence / "Original forms with spaces.pdf"
    with pikepdf.Pdf.new() as doc:
        page = doc.add_blank_page(page_size=(400, 500))
        font = doc.make_indirect(pikepdf.Dictionary(Type=pikepdf.Name.Font, Subtype=pikepdf.Name.Type1, BaseFont=pikepdf.Name.Helvetica))
        page.Resources = pikepdf.Dictionary(Font=pikepdf.Dictionary(F1=font))
        page.Contents = doc.make_stream(b"BT /F1 20 Tf 20 440 Td (Preserved vector text) Tj ET")
        appearance = doc.make_stream(b"BT /F1 12 Tf 2 5 Td (Island) Tj ET")
        appearance.Type = pikepdf.Name.XObject
        appearance.Subtype = pikepdf.Name.Form
        appearance.BBox = [0, 0, 140, 25]
        appearance.Resources = page.Resources
        widget = doc.make_indirect(pikepdf.Dictionary(Type=pikepdf.Name.Annot, Subtype=pikepdf.Name.Widget, FT=pikepdf.Name.Tx, T="place", V="Island", Ff=1, Rect=[20, 360, 160, 385], P=page.obj, AP=pikepdf.Dictionary(N=appearance), Lock=pikepdf.Dictionary(Type=pikepdf.Name.SigFieldLock)))
        page.Annots = [widget]
        doc.Root.AcroForm = doc.make_indirect(pikepdf.Dictionary(Fields=[widget], NeedAppearances=False, DA="/F1 12 Tf 0 g", DR=page.Resources, XFA=doc.make_stream(b'<template><field access = "readOnly"/></template>')))
        doc.docinfo["/Title"] = "Original title"
        doc.docinfo["/Private"] = "private fixture"
        doc.Root.Metadata = doc.make_stream(b'<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"><rdf:Description rdf:about=""/></rdf:RDF></x:xmpmeta>' )
        doc.Root.PieceInfo = pikepdf.Dictionary(Private=pikepdf.Dictionary())
        doc.save(fixture)
    original = hashlib.sha256(fixture.read_bytes()).hexdigest()
    results = []
    command = [str(args.java.resolve()), "-cp", str(root / "app/worker/build/install/worker/lib/*"), "stirling.software.worker.WorkerMain"]

    def run(operation, source=fixture, options=None, success=True):
        job = evidence / str(uuid.uuid4())
        job.mkdir()
        shutil.copyfile(source, job / "input-main.pdf")
        request = {"requestId": job.name, "operation": operation, "input": "input-main.pdf", "options": options or {}}
        completed = subprocess.run(command + [str(job)], input=json.dumps(request) + "\n", capture_output=True, text=True, timeout=60)
        messages = [json.loads(line) for line in completed.stdout.splitlines() if line.startswith("{")]
        assert messages and (messages[-1]["type"] == "result") == success, (operation, completed.stderr, messages)
        assert (job / "input-main.pdf").read_bytes() == source.read_bytes()
        results.append({"operation": operation, "expected_success": success, "exit": completed.returncode, "result": messages[-1]})
        return job / messages[-1]["file"] if success else None

    updated = run("metadata-update", options={"title": "Swona", "author": "Sootan", "creationDate": "2026/10/28 12:34:56", "allRequestParams": {"customKey3": "Island", "customValue3": "Swona"}})
    with pikepdf.Pdf.open(updated) as doc:
        assert str(doc.docinfo.Title) == "Swona" and str(doc.docinfo.Island) == "Swona"
        assert str(doc.docinfo.CreationDate).startswith("D:20261028123456")
        assert str(doc.pages[0].Contents.read_bytes(), "ascii").find("Preserved vector text") >= 0
        assert str(doc.Root.AcroForm.Fields[0].V) == "Island"
    removed = run("metadata-update", options={"deleteAll": True, "title": "must not be re-added"})
    with pikepdf.Pdf.open(removed) as doc:
        assert not doc.docinfo and "/Metadata" not in doc.Root and "/PieceInfo" not in doc.Root
    unlocked = run("forms-unlock")
    with pikepdf.Pdf.open(unlocked) as doc:
        field = doc.Root.AcroForm.Fields[0]
        assert not int(field.Ff) & 1 and "/Lock" not in field and str(field.V) == "Island"
        assert b'access="open"' in doc.Root.AcroForm.XFA.read_bytes()
    run("flatten", options={"flattenOnlyForms": True}, success=False)
    # PDFBox cannot flatten XFA; the AcroForm fixture retains the same page and appearance streams.
    acro = evidence / "AcroForm fixture.pdf"
    with pikepdf.Pdf.open(fixture) as doc:
        del doc.Root.AcroForm.XFA
        doc.save(acro)
    flattened = run("flatten", acro, {"flattenOnlyForms": True})
    with pikepdf.Pdf.open(flattened) as doc:
        assert not doc.Root.AcroForm.Fields and not doc.pages[0].get("/Annots", [])
    pdf = pdfium.PdfDocument(str(flattened))
    assert "Preserved vector text" in pdf[0].get_textpage().get_text_range()
    assert "Island" in pdf[0].get_textpage().get_text_range()
    pdf.close()
    raster = run("flatten", acro, {"flattenOnlyForms": False, "renderDpi": 72})
    with pikepdf.Pdf.open(raster) as doc:
        images = [value for value in doc.pages[0].Resources.XObject.values() if value.get("/Subtype") == pikepdf.Name.Image]
        assert len(images) == 1 and images[0].Width == 400 and images[0].Height == 500
    pdf = pdfium.PdfDocument(str(raster))
    assert not pdf[0].get_textpage().get_text_range().strip()
    pdf[0].render(scale=1).to_pil().save(evidence / "raster-render.png")
    pdf.close()
    encrypted = evidence / "protected.pdf"
    with pikepdf.Pdf.open(acro) as doc:
        doc.save(encrypted, encryption=pikepdf.Encryption(owner="owner-fixture", user="reader-fixture", R=6, allow=pikepdf.Permissions(modify_form=False)))
    run("forms-unlock", encrypted, {"sourcePassword": "reader-fixture"}, success=False)
    run("metadata-update", options={"unrecognizedOption": "must fail"}, success=False)
    assert hashlib.sha256(fixture.read_bytes()).hexdigest() == original
    (evidence / "results.json").write_text(json.dumps({"original_sha256": original, "cases": results}, indent=2))
    print(f"Document worker goldens PASS: {len(results)} saved-output/refusal cases; {evidence}")


if __name__ == "__main__":
    main()
