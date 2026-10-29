"""Inspect real vector layout order/geometry/rotation/origin and original-byte preservation."""
import argparse,hashlib,json,shutil,subprocess,uuid
from pathlib import Path
import pikepdf
import pypdfium2 as pdfium
p=argparse.ArgumentParser();p.add_argument('--java',type=Path,required=True);a=p.parse_args();root=Path(__file__).resolve().parents[2];ev=root/'.tooling/nonweb-layout-golden';ev.mkdir(exist_ok=True);source=ev/'Rotated origin pages.pdf';colors=[(1,0,0),(0,1,0),(0,0,1),(1,0,1)]
pdf=pikepdf.Pdf.new()
for i,color in enumerate(colors):
 page=pdf.add_blank_page(page_size=(200,300));page.MediaBox=pikepdf.Array([20,30,220,330]);page.CropBox=pikepdf.Array([20,30,220,330]);page.Rotate=i*90
 page.Resources=pikepdf.Dictionary(Font=pikepdf.Dictionary(F1=pdf.make_indirect(pikepdf.Dictionary(Type=pikepdf.Name.Font,Subtype=pikepdf.Name.Type1,BaseFont=pikepdf.Name.Helvetica))))
 page.Contents=pdf.make_stream((f'{color[0]} {color[1]} {color[2]} rg 40 70 30 50 re f 0 0 0 rg BT /F1 12 Tf 40 150 Td (PAGE{i+1}) Tj ET').encode())
pdf.save(source);pdf.close();digest=hashlib.sha256(source.read_bytes()).hexdigest();cases=[]
def run(op,options):
 job=ev/str(uuid.uuid4());job.mkdir();shutil.copyfile(source,job/'input-main.pdf');done=subprocess.run([str(a.java.resolve()),'-cp',str(root/'app/worker/build/install/worker/lib/*'),'stirling.software.worker.WorkerMain',str(job)],input=json.dumps(dict(requestId=job.name,operation=op,input='input-main.pdf',options=options))+'\n',capture_output=True,text=True,timeout=120);messages=[json.loads(x) for x in done.stdout.splitlines()];assert messages[-1]['type']=='result',(messages,done.stderr);assert hashlib.sha256(source.read_bytes()).hexdigest()==digest;cases.append(dict(operation=op,result=messages[-1],sourceSHA256=digest));return job/'output.pdf'
def centroid(image,color):
 coords=[(x,y) for y in range(image.height) for x in range(image.width) if all(abs(image.getpixel((x,y))[k]-255*color[k])<10 for k in range(3))];assert coords,color;return tuple(sum(q[k] for q in coords)/len(coords) for k in range(2))
src=pdfium.PdfDocument(str(source));rendered=[page.render(scale=1).to_pil().convert('RGB') for page in src];centers=[centroid(im,c) for im,c in zip(rendered,colors)]
book=run('layout-booklet',dict(pagesPerSheet=2,doubleSided=True,duplexPass='BOTH'));doc=pdfium.PdfDocument(str(book));assert len(doc)==2
for outindex,pages in enumerate(((3,0),(1,2))):
 image=doc[outindex].render().to_pil().convert('RGB');assert image.size==(300,200)
 for col,pageindex in enumerate(pages):
  page=rendered[pageindex];scale=min(150/page.width,200/page.height);expected=(col*150+(150-page.width*scale)/2+centers[pageindex][0]*scale,(200-page.height*scale)/2+centers[pageindex][1]*scale);actual=centroid(image,colors[pageindex]);assert max(abs(a-b) for a,b in zip(actual,expected))<2,(actual,expected)
 assert all(f'PAGE{j+1}' in doc[outindex].get_textpage().get_text_range() for j in pages)
long=run('layout-single-page',{});doc=pdfium.PdfDocument(str(long));assert len(doc)==1;image=doc[0].render().to_pil().convert('RGB');assert image.size==(300,1000),image.size;y=0
for i,page in enumerate(rendered):
 actual=centroid(image,colors[i]);expected=(centers[i][0],y+centers[i][1]);assert max(abs(a-b) for a,b in zip(actual,expected))<2,(actual,expected);y+=page.height
assert all(f'PAGE{i+1}' in doc[0].get_textpage().get_text_range() for i in range(4))
(ev/'results.json').write_text(json.dumps(dict(cases=cases,booklet=str(book),single=str(long),centroidTolerancePixels=2,rotations=[0,90,180,270],cropOrigin=[20,30]),indent=2),encoding='utf-8');print('Vector layout PASS: booklet and long-page text/order/geometry/colors, four rotations/nonzero origins; originals unchanged.')
