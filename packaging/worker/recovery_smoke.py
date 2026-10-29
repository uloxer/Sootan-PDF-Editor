"""Actual image-only compression and PDFBox recovery without external tools."""
import argparse,hashlib,json,random,re,shutil,subprocess,uuid,zlib
from pathlib import Path
import pikepdf
import pypdfium2 as pdfium
p=argparse.ArgumentParser();p.add_argument('--java',type=Path,required=True);a=p.parse_args();root=Path(__file__).resolve().parents[2];ev=root/'.tooling/nonweb-recovery-golden';ev.mkdir(exist_ok=True);cases=[]
src=ev/'Text and image.pdf';pdf=pikepdf.Pdf.new();page=pdf.add_blank_page(page_size=(600,700));font=pdf.make_indirect(pikepdf.Dictionary(Type=pikepdf.Name.Font,Subtype=pikepdf.Name.Type1,BaseFont=pikepdf.Name.Helvetica));image=pdf.make_stream(zlib.compress(random.Random(42).randbytes(1200*1200*3)));image.Type=pikepdf.Name.XObject;image.Subtype=pikepdf.Name.Image;image.Width=1200;image.Height=1200;image.ColorSpace=pikepdf.Name.DeviceRGB;image.BitsPerComponent=8;image.Filter=pikepdf.Name.FlateDecode;page.Resources=pikepdf.Dictionary(Font=pikepdf.Dictionary(F1=font),XObject=pikepdf.Dictionary(Im=image));page.Contents=pdf.make_stream(b'q 500 0 0 500 50 50 cm /Im Do Q BT /F1 18 Tf 40 620 Td (Swona text remains editable) Tj ET');pdf.save(src);pdf.close()
def run(op,source,options,success=True):
 job=ev/str(uuid.uuid4());job.mkdir();shutil.copyfile(source,job/'input-main.pdf');before=hashlib.sha256(source.read_bytes()).hexdigest();proc=subprocess.run([str(a.java.resolve()),'-cp',str(root/'app/worker/build/install/worker/lib/*'),'stirling.software.worker.WorkerMain',str(job)],input=json.dumps(dict(requestId=job.name,operation=op,input='input-main.pdf',options=options))+'\n',text=True,capture_output=True,timeout=120);messages=[json.loads(line) for line in proc.stdout.splitlines()];last=messages[-1];assert (last['type']=='result')==success,(last,proc.stderr);assert before==hashlib.sha256(source.read_bytes()).hexdigest();cases.append(dict(operation=op,options=options,inputSHA256=before,result=last));return job/last['file'] if success else None
compressed=run('compress',src,dict(optimizeLevel=5,grayscale=False));assert compressed.stat().st_size<src.stat().st_size/2
with pikepdf.Pdf.open(compressed) as pdf:assert len(pdf.pages)==1 and int(pdf.pages[0].Resources.XObject.Im.Width)==816
read=pdfium.PdfDocument(str(compressed));assert 'Swona text remains editable' in read[0].get_textpage().get_text_range();assert read[0].get_size()==(600,700)
unchanged=run('compress',src,dict(optimizeLevel=1));assert unchanged.read_bytes()==src.read_bytes()
decompressed=run('decompress',compressed,{})
with pikepdf.Pdf.open(decompressed) as pdf:
 assert all('/Filter' not in obj for obj in pdf.objects if isinstance(obj,pikepdf.Stream));assert len(pdf.pages)==1
read=pdfium.PdfDocument(str(decompressed));assert 'Swona text remains editable' in read[0].get_textpage().get_text_range()
damaged=ev/'Broken xref.pdf';damaged.write_bytes(re.sub(rb'startxref\s+\d+',b'startxref\n999999999',src.read_bytes()));repaired=run('repair',damaged,{});read=pdfium.PdfDocument(str(repaired));assert len(read)==1 and 'Swona text remains editable' in read[0].get_textpage().get_text_range()
bad=ev/'Not recoverable.pdf';bad.write_bytes(b'not a PDF');run('repair',bad,{},False)
(ev/'results.json').write_text(json.dumps(dict(cases=cases,sourceBytes=src.stat().st_size,compressedBytes=compressed.stat().st_size,decompressedBytes=decompressed.stat().st_size),indent=2),encoding='utf-8');print('Recovery PASS:5 real jobs; image size reduction, exact level1 passthrough, decoded streams, broken-xref recovery and irrecoverable refusal; text/geometry/source hashes preserved.')
