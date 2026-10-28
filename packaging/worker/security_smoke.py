"""Independent saved-file checks for local password and certificate worker modes."""
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
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java",type=Path,required=True)
    args=parser.parse_args()
    root=Path(__file__).resolve().parents[2]
    evidence=root/".tooling/nonweb-security-golden"
    evidence.mkdir(exist_ok=True)
    java=args.java.resolve()
    source=evidence/"Security source with spaces.pdf"
    with pikepdf.Pdf.new() as pdf:
        page=pdf.add_blank_page(page_size=(400,500))
        font=pdf.make_indirect(pikepdf.Dictionary(Type=pikepdf.Name.Font,Subtype=pikepdf.Name.Type1,BaseFont=pikepdf.Name.Helvetica))
        page.Resources=pikepdf.Dictionary(Font=pikepdf.Dictionary(F1=font))
        page.Contents=pdf.make_stream(b"BT /F1 14 Tf 20 420 Td (Security test document) Tj ET")
        page.Rotate=90
        pdf.save(source,compress_streams=False)
    key=evidence/"fixture.p12"
    if not key.exists():
        subprocess.run([str(java.with_name("keytool.exe")),"-genkeypair","-alias","fixture","-keyalg","RSA","-keysize","2048","-validity","30","-dname","CN=Sootan Local Fixture","-storetype","PKCS12","-keystore",str(key),"-storepass","fixture-password"],check=True,capture_output=True)
    anchor=evidence/"explicit-root.cer"
    subprocess.run([str(java.with_name("keytool.exe")),"-exportcert","-alias","fixture","-keystore",str(key),"-storepass","fixture-password","-file",str(anchor)],check=True,capture_output=True)
    cmd=[str(java),"-cp",str(root/"app/worker/build/install/worker/lib/*"),"stirling.software.worker.WorkerMain"]
    results=[]

    def run(op,src=source,options=None,cert=None,trust=None,success=True):
        before=hashlib.sha256(src.read_bytes()).hexdigest()
        job=evidence/str(uuid.uuid4());job.mkdir()
        shutil.copyfile(src,job/"input-main.pdf")
        if cert:shutil.copyfile(cert,job/"certificate.p12")
        if trust:shutil.copyfile(trust,job/"trust.cer")
        completed=subprocess.run(cmd+[str(job)],input=json.dumps({"requestId":job.name,"operation":op,"input":"input-main.pdf","options":options or {}})+"\n",text=True,capture_output=True,timeout=60)
        messages=[json.loads(line) for line in completed.stdout.splitlines() if line.startswith("{")]
        assert messages and (messages[-1]["type"]=="result")==success,(op,completed.stderr,messages)
        assert before==hashlib.sha256(src.read_bytes()).hexdigest()
        assert (job/"input-main.pdf").read_bytes()==src.read_bytes()
        results.append({"operation":op,"success_expected":success,"exit":completed.returncode,"result":messages[-1]})
        return job/messages[-1]["file"] if success else None

    for length in [40,128,256]:
        protected=run("password-protect",options={"password":"reader-fixture","ownerPassword":"owner-fixture","keyLength":length,"preventPrinting":True})
        with pikepdf.Pdf.open(protected,password="reader-fixture") as pdf:
            assert pdf.encryption.bits==length and not pdf.allow.print_lowres
            if length>=128:assert str(pdf.trailer.Encrypt.CF.StdCF.CFM)==("/AESV2" if length==128 else "/AESV3")
            else:assert int(pdf.trailer.Encrypt.V)==1
        preserved=run("password-protect",protected,{"sourcePassword":"owner-fixture","preserveExisting":True,"preventModify":True})
        for credential in ["reader-fixture","owner-fixture"]:
            with pikepdf.Pdf.open(preserved,password=credential) as pdf:
                assert pdf.encryption.bits==length and not pdf.allow.modify_other
        clear=run("decrypt",preserved,{"sourcePassword":"owner-fixture","removeAll":True})
        with pikepdf.Pdf.open(clear) as pdf:assert not pdf.is_encrypted
        run("password-protect",protected,{"sourcePassword":"reader-fixture","preserveExisting":True},success=False)
        run("decrypt",protected,{"sourcePassword":"wrong-fixture","removeAll":True},success=False)
        no_opening=run("decrypt",protected,{"sourcePassword":"owner-fixture","password":"reader-fixture"})
        with pikepdf.Pdf.open(no_opening) as pdf:assert pdf.is_encrypted and not pdf.allow.print_lowres
        with pikepdf.Pdf.open(no_opening,password="owner-fixture") as pdf:assert pdf.owner_password_matched
        no_owner=run("decrypt",protected,{"ownerPassword":"owner-fixture","retainedOpeningPassword":"reader-fixture"})
        with pikepdf.Pdf.open(no_owner,password="reader-fixture") as pdf:assert pdf.allow.print_lowres
    for label,extra in [("expired",["-startdate","2020/01/01 00:00:00","-validity","1"]),("wrong-usage",["-validity","30","-ext","KeyUsage=keyEncipherment"])]:
        unsuitable=evidence/(label+".p12")
        if not unsuitable.exists():
            subprocess.run([str(java.with_name("keytool.exe")),"-genkeypair","-alias","fixture","-keyalg","RSA","-keysize","2048","-dname","CN=Unsuitable Fixture","-storetype","PKCS12","-keystore",str(unsuitable),"-storepass","fixture-password"]+extra,check=True,capture_output=True)
        run("cert-sign-pkcs12",options={"password":"fixture-password"},cert=unsuitable,success=False)
    signed=run("cert-sign-pkcs12",options={"password":"fixture-password","showSignature":True,"showLogo":True,"pageNumber":1,"reason":"Local fixture","name":"Sootan test","location":"Swona"},cert=key)
    with pikepdf.Pdf.open(signed) as pdf:
        widget=pdf.Root.AcroForm.Fields[0]
        assert list(widget.Rect)==[0,0,200,50]
        assert int(pdf.pages[0].Rotate)==90 and "/AP" in widget
    doc=pdfium.PdfDocument(str(signed));image=doc[0].render(scale=1).to_pil();image.save(evidence/"visible-signature.png");doc.close()
    unknown=json.loads(run("signature-integrity",signed).read_text())[0]
    assert unknown["valid"] and unknown["coversEntireDocument"] and unknown["notExpired"] and not unknown["trustValid"]
    assert unknown["issuerDN"] and unknown["subjectDN"] and unknown["reason"]=="Local fixture" and not unknown["revocationChecked"]
    trusted=json.loads(run("signature-integrity",signed,trust=anchor).read_text())[0]
    assert trusted["valid"] and trusted["trustValid"] and trusted["chainValid"]
    tampered=evidence/"tampered.pdf"
    raw=signed.read_bytes();assert b"Security test document" in raw
    tampered.write_bytes(raw.replace(b"Security test document",b"Security best document",1))
    assert not json.loads(run("signature-integrity",tampered,trust=anchor).read_text())[0]["valid"]
    run("password-protect",signed,{"password":"new-fixture"},success=False)
    run("cert-sign-pkcs12",options={"password":"fixture-password","showSignature":True,"pageNumber":2},cert=key,success=False)
    malformed=evidence/"malformed.cer";malformed.write_bytes(b"not a certificate")
    run("signature-integrity",signed,trust=malformed,success=False)
    unsigned=run("signature-remove",signed)
    with pikepdf.Pdf.open(unsigned) as pdf:assert not pdf.Root.AcroForm.Fields
    assert json.loads(run("signature-integrity",unsigned).read_text())==[]
    (evidence/"results.json").write_text(json.dumps({"cases":results},indent=2))
    print(f"Security worker goldens PASS: {len(results)} cases; {evidence}")


if __name__=="__main__":main()
