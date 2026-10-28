"""Exercise retained inspection schemas and stamp exports through the non-web process boundary."""
import argparse,hashlib,json,os,shutil,subprocess,uuid
from pathlib import Path
import pikepdf
import pypdfium2 as pdfium
from PIL import Image


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument("--java",type=Path,required=True);parser.add_argument("--fonts",type=Path,required=True);args=parser.parse_args()
    root=Path(__file__).resolve().parents[2];evidence=root/".tooling/nonweb-inspection-stamp-golden";evidence.mkdir(exist_ok=True)
    source=evidence/"Inspection fixture with spaces.pdf"
    with pikepdf.Pdf.new() as pdf:
        for n in [1,2]:
            page=pdf.add_blank_page(page_size=(500,600));font=pdf.make_indirect(pikepdf.Dictionary(Type=pikepdf.Name.Font,Subtype=pikepdf.Name.Type1,BaseFont=pikepdf.Name.Helvetica))
            page.Resources=pikepdf.Dictionary(Font=pikepdf.Dictionary(F1=font));page.Contents=pdf.make_stream(f"BT /F1 18 Tf 100 300 Td (Inspection original {n}) Tj ET".encode());page.CropBox=[10,20,410,520];page.Rotate=90 if n==1 else 0
        widget=pdf.make_indirect(pikepdf.Dictionary(Type=pikepdf.Name.Annot,Subtype=pikepdf.Name.Widget,FT=pikepdf.Name.Tx,T="place",V="Swona",Rect=[40,100,180,125],P=pdf.pages[0].obj))
        pdf.pages[0].Annots=[widget];pdf.Root.AcroForm=pdf.make_indirect(pikepdf.Dictionary(Fields=[widget],NeedAppearances=True,DA="/F1 12 Tf 0 g",DR=pdf.pages[0].Resources))
        pdf.Root.Names=pikepdf.Dictionary(JavaScript=pdf.make_indirect(pikepdf.Dictionary(Names=["fixture",pdf.make_indirect(pikepdf.Dictionary(S=pikepdf.Name.JavaScript,JS="app.alert('inert fixture');"))])))
        pdf.docinfo["/Title"]="Swona report";pdf.docinfo["/PrivateFixture"]="preserved metadata"
        pdf.save(source)
    env=dict(os.environ);env["SOOTAN_WORKER_FONTS"]=str(args.fonts.resolve());cmd=[str(args.java.resolve()),"-cp",str(root/"app/worker/build/install/worker/lib/*"),"stirling.software.worker.WorkerMain"];cases=[]
    def run(op,options=None,src=source,image=None,success=True):
        job=evidence/str(uuid.uuid4());job.mkdir();shutil.copyfile(src,job/"input-main.pdf");before=hashlib.sha256(src.read_bytes()).hexdigest()
        if image:shutil.copyfile(image,job/"image.bin")
        completed=subprocess.run(cmd+[str(job)],input=json.dumps({"requestId":job.name,"operation":op,"input":"input-main.pdf","options":options or {}})+"\n",capture_output=True,text=True,env=env,timeout=90)
        messages=[json.loads(line) for line in completed.stdout.splitlines() if line.startswith("{")]
        assert messages and (messages[-1]["type"]=="result")==success,(op,options,completed.stderr,messages)
        assert hashlib.sha256(src.read_bytes()).hexdigest()==before and src.read_bytes()==(job/"input-main.pdf").read_bytes()
        cases.append({"operation":op,"report":(options or {}).get("report"),"success_expected":success,"exit":completed.returncode,"result":messages[-1]})
        return job/messages[-1]["file"] if success else None
    reports={}
    for kind in ["page-count","basic-info","document-properties","page-dimensions","form-fields","annotation-info","font-info","security-info","all-info","verify-pdf","javascript"]:
        output=run("document-inspect",{"report":kind,"filename":source.name})
        reports[kind]=output.read_text(encoding="utf-8") if kind=="javascript" else json.loads(output.read_text())
    assert reports["page-count"]=={"pageCount":2}
    assert reports["basic-info"]["fileSize"]==source.stat().st_size
    assert reports["document-properties"]["title"]=="Swona report" and "subject" in reports["document-properties"]
    assert reports["page-dimensions"][0]=={"width":400.0,"height":500.0}
    assert reports["form-fields"]["fieldCount"]==1 and reports["annotation-info"]["totalCount"]==1
    assert reports["font-info"]["fontCount"]==1 and reports["security-info"]["isEncrypted"] is False
    assert set(["Metadata","BasicInfo","DocumentInfo","Compliancy","Encryption","Permissions","FormFields","Other","PerPageInfo"]).issubset(reports["all-info"])
    assert reports["all-info"]["BasicInfo"]["Number of pages"]==2
    assert "preserved metadata" in json.dumps(reports["all-info"]) and "Swona" in json.dumps(reports["all-info"])
    assert reports["verify-pdf"] and reports["verify-pdf"][0]["standard"]=="not-pdfa" and reports["verify-pdf"][0]["declaredPdfa"] is False
    assert "app.alert('inert fixture')" in reports["javascript"] and source.name in reports["javascript"]
    encrypted=evidence/"restricted.pdf"
    with pikepdf.Pdf.open(source) as pdf:pdf.save(encrypted,encryption=pikepdf.Encryption(owner="owner-fixture",user="reader-fixture",R=6,allow=pikepdf.Permissions(extract=False)))
    report=json.loads(run("document-inspect",{"report":"security-info","sourcePassword":"reader-fixture"},encrypted).read_text());assert report["isEncrypted"] and report["permissions"]["preventExtractContent"]
    run("document-inspect",{"report":"all-info","sourcePassword":"reader-fixture"},encrypted,success=False)
    run("document-inspect",{"report":"all-info","sourcePassword":"owner-fixture"},encrypted)
    run("document-inspect",{"report":"security-info","sourcePassword":"wrong-fixture"},encrypted,success=False)
    image=evidence/"Hill.png";Image.new("RGB",(80,40),(20,140,60)).save(image)
    for mode,extra,bitmap in [("text",{"stampText":"@title @page/@page_count @@date @date{yyyy}","alphabet":"roman"},None),("text",{"stampText":"Swona 島","alphabet":"chinese"},None),("image",{},image),("qr",{"stampText":"Swona @page/@page_count"},None)]:
        output=run("stamp",{"stampType":mode,"fontSize":24,"opacity":0.5,"position":8,"pageNumbers":"2","filename":source.name,**extra},image=bitmap)
        doc=pdfium.PdfDocument(str(output));assert len(doc)==2
        text=doc[1].get_textpage().get_text_range();assert "Inspection original 2" in text
        if mode=="text":assert ("Swona report 2/2 @date" if extra["alphabet"]=="roman" else "Swona 島") in text
        doc[1].render(scale=1).to_pil().save(output.parent/"stamp-render.png");doc.close()
        with pikepdf.Pdf.open(output) as pdf:assert int(pdf.pages[0].Rotate)==90 and list(pdf.pages[0].CropBox)==[10,20,410,520] and str(pdf.Root.AcroForm.Fields[0].V)=="Swona"
    run("stamp",{"stampType":"qr","stampText":"x"*1001},success=False)
    run("stamp",{"stampType":"text","stampText":"Swona","sourcePassword":"reader-fixture"},encrypted,success=False)
    run("document-inspect",{"report":"arbitrary-method"},success=False)
    (evidence/"results.json").write_text(json.dumps({"cases":cases,"schema_reports":reports},indent=2));print(f"Inspection/stamp goldens PASS: {len(cases)} cases; {evidence}")


if __name__=="__main__":main()
