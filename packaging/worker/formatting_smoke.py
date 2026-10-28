"""Reopen and render standalone page-formatting outputs with independent readers."""
import argparse,hashlib,json,os,shutil,subprocess,uuid
from pathlib import Path
import pikepdf
import pypdfium2 as pdfium
from PIL import Image


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument("--java",type=Path,required=True);parser.add_argument("--fonts",type=Path,required=True);args=parser.parse_args()
    root=Path(__file__).resolve().parents[2];evidence=root/".tooling/nonweb-formatting-golden";evidence.mkdir(exist_ok=True)
    source=evidence/"Page Test with spaces.pdf"
    with pikepdf.Pdf.new() as pdf:
        for n in [1,2]:
            page=pdf.add_blank_page(page_size=(500,600));font=pdf.make_indirect(pikepdf.Dictionary(Type=pikepdf.Name.Font,Subtype=pikepdf.Name.Type1,BaseFont=pikepdf.Name.Helvetica))
            page.Resources=pikepdf.Dictionary(Font=pikepdf.Dictionary(F1=font));page.Contents=pdf.make_stream(f"BT /F1 20 Tf 180 300 Td (Vector original {n}) Tj ET".encode());page.CropBox=[10,20,410,520]
            page.Rotate=90 if n==1 else 0
        pdf.save(source)
    image=evidence/"Selected hill.png";Image.new("RGB",(80,40),(30,130,60)).save(image)
    env=dict(os.environ);env["SOOTAN_WORKER_FONTS"]=str(args.fonts.resolve())
    cmd=[str(args.java.resolve()),"-cp",str(root/"app/worker/build/install/worker/lib/*"),"stirling.software.worker.WorkerMain"];results=[]
    def run(op,options=None,bitmap=None,src=source,success=True):
        original=hashlib.sha256(src.read_bytes()).hexdigest();job=evidence/str(uuid.uuid4());job.mkdir();shutil.copyfile(src,job/"input-main.pdf")
        if bitmap:shutil.copyfile(bitmap,job/"image.bin")
        completed=subprocess.run(cmd+[str(job)],input=json.dumps({"requestId":job.name,"operation":op,"input":"input-main.pdf","options":options or {}})+"\n",text=True,capture_output=True,env=env,timeout=60)
        messages=[json.loads(line) for line in completed.stdout.splitlines() if line.startswith("{")]
        assert messages and (messages[-1]["type"]=="result")==success,(op,completed.stderr,messages)
        assert hashlib.sha256(src.read_bytes()).hexdigest()==original and src.read_bytes()==(job/"input-main.pdf").read_bytes()
        results.append({"operation":op,"success_expected":success,"exit":completed.returncode,"result":messages[-1]})
        if not success:return None
        output=job/messages[-1]["file"]
        doc=pdfium.PdfDocument(str(output));assert len(doc)==2
        doc[0].render(scale=0.5).to_pil().save(job/"render.png");doc.close();return output
    crop=run("page-crop",{"x":50,"y":70,"width":300,"height":400,"pageNumbers":"1"})
    with pikepdf.Pdf.open(crop) as pdf:
        assert list(pdf.pages[0].MediaBox)==[50,70,350,470]
        assert list(pdf.pages[1].CropBox)==[10,20,410,520] and int(pdf.pages[1].Rotate)==0
    auto=run("page-crop",{"autoCrop":True,"pageNumbers":"1"})
    with pikepdf.Pdf.open(auto) as pdf:assert float(pdf.pages[0].MediaBox[2]-pdf.pages[0].MediaBox[0])<500
    scale=run("page-scale",{"pageSize":"A4","orientation":"LANDSCAPE","scaleFactor":0.8})
    with pikepdf.Pdf.open(scale) as pdf:
        assert abs(float(pdf.pages[0].MediaBox[2])-841.89)<1 and abs(float(pdf.pages[0].MediaBox[3])-595.28)<1
    numbers=run("page-numbers",{"pagesToNumber":"2","startingNumber":7,"zeroPad":3,"customText":"{filename} Page {n}/{total}","filename":"Safe with spaces.pdf","fontType":"courier","fontSize":12})
    doc=pdfium.PdfDocument(str(numbers));assert "Safe with spaces Page 007/2" in doc[1].get_textpage().get_text_range();assert "Page 007" not in doc[0].get_textpage().get_text_range();doc.close()
    for mode,extra,bitmap in [("text",{"watermarkText":"Sootan proof","alphabet":"roman"},None),("text",{"watermarkText":"Swona 島","alphabet":"chinese"},None),("image",{},image)]:
        output=run("watermark",{"watermarkType":mode,"fontSize":30,"opacity":0.3,"rotation":25,**extra},bitmap)
        doc=pdfium.PdfDocument(str(output));text=doc[0].get_textpage().get_text_range();assert "Vector original 1" in text
        if mode=="text":assert extra["watermarkText"] in text
        doc.close()
        with pikepdf.Pdf.open(output) as pdf:assert int(pdf.pages[0].Rotate)==90 and list(pdf.pages[0].CropBox)==[10,20,410,520]
    run("page-crop",{"x":0,"y":0,"width":1000,"height":400},success=False)
    run("page-crop",{"x":0,"y":0,"width":300,"height":400,"pageNumbers":"999"},success=False)
    run("page-crop",{"autoCrop":True},src=root/".tooling/nonweb-document-golden/Original forms with spaces.pdf",success=False)
    malformed=evidence/"not a bitmap.png";malformed.write_bytes(b"not a bitmap")
    run("watermark",{"watermarkType":"image"},malformed,success=False)
    run("watermark",{"watermarkType":"text","watermarkText":"x","fontSize":1,"widthSpacer":0,"heightSpacer":0},success=False)
    (evidence/"results.json").write_text(json.dumps({"cases":results},indent=2));print(f"Formatting worker goldens PASS: {len(results)} cases; {evidence}")


if __name__=="__main__":main()
