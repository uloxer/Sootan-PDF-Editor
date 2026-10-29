"""Actual N-up content/form placement and poster tile checks without rendering PDFs into output."""
import argparse,hashlib,json,shutil,subprocess,uuid,zipfile
from pathlib import Path
import pikepdf
import pypdfium2 as pdfium
p=argparse.ArgumentParser();p.add_argument('--java',type=Path,required=True);a=p.parse_args();root=Path(__file__).resolve().parents[2];ev=root/'.tooling/nonweb-nup-poster-golden';ev.mkdir(exist_ok=True);java=str(a.java.resolve());cp=str(root/'app/worker/build/install/worker/lib/*');cases=[]
def run(op,src,options):
 job=ev/str(uuid.uuid4());job.mkdir();shutil.copyfile(src,job/'input-main.pdf');digest=hashlib.sha256(src.read_bytes()).hexdigest();done=subprocess.run([java,'-cp',cp,'stirling.software.worker.WorkerMain',str(job)],input=json.dumps(dict(requestId=job.name,operation=op,input='input-main.pdf',options=options))+'\n',capture_output=True,text=True,timeout=120);messages=[json.loads(x) for x in done.stdout.splitlines()];assert messages[-1]['type']=='result',(messages,done.stderr);assert digest==hashlib.sha256(src.read_bytes()).hexdigest();cases.append(dict(operation=op,source=src.name,sourceSHA256=digest,result=messages[-1]));return job/messages[-1]['file']
colors=[(1,0,0),(0,1,0),(0,0,1),(1,0,1)];src=ev/'Rotated origin pages.pdf';pdf=pikepdf.Pdf.new()
for i,c in enumerate(colors):
 page=pdf.add_blank_page(page_size=(200,300));page.MediaBox=pikepdf.Array([20,30,220,330]);page.CropBox=pikepdf.Array([20,30,220,330]);page.Rotate=i*90
 page.Resources=pikepdf.Dictionary(Font=pikepdf.Dictionary(F1=pdf.make_indirect(pikepdf.Dictionary(Type=pikepdf.Name.Font,Subtype=pikepdf.Name.Type1,BaseFont=pikepdf.Name.Helvetica))))
 page.Contents=pdf.make_stream((f'{c[0]} {c[1]} {c[2]} rg 40 70 30 50 re f 0 0 0 rg BT /F1 12 Tf 40 150 Td (PAGE{i+1}) Tj ET').encode())
pdf.save(src);pdf.close()
def centroid(image,color):
 coords=[(x,y) for y in range(image.height) for x in range(image.width) if all(abs(image.getpixel((x,y))[k]-255*color[k])<10 for k in range(3))];assert coords;return tuple(sum(q[k] for q in coords)/len(coords) for k in range(2))
source=pdfium.PdfDocument(str(src));renders=[p.render().to_pil().convert('RGB') for p in source];output=run('layout-nup',src,dict(mode='CUSTOM',rows=2,cols=2,orientation='LANDSCAPE',arrangement='BY_COLUMNS',readingDirection='RTL',leftMargin=20,rightMargin=20,topMargin=10,bottomMargin=10,innerMargin=5));doc=pdfium.PdfDocument(str(output));assert len(doc)==1;w,h=doc[0].get_size();assert abs(w-841.89)<.1 and abs(h-595.276)<.1;im=doc[0].render().to_pil().convert('RGB');cw=(w-40)/2;ch=(h-20)/2
for i,page in enumerate(renders):
 row=i%2;col=1-i//2;scale=min((cw-10)/page.width,(ch-10)/page.height);cx,cy=centroid(page,colors[i]);expected=(20+col*cw+5+(cw-10-page.width*scale)/2+cx*scale,10+row*ch+5+(ch-10-page.height*scale)/2+cy*scale);actual=centroid(im,colors[i]);assert max(abs(a-b) for a,b in zip(actual,expected))<2,(actual,expected)
assert all(f'PAGE{i+1}' in doc[0].get_textpage().get_text_range() for i in range(4))
form=ev/'Editable form.pdf';subprocess.run([java,'-cp',cp,str(root/'packaging/worker/NUpFormFixture.java'),str(form)],capture_output=True,text=True,check=True);out=run('layout-nup',form,dict(mode='CUSTOM',rows=1,cols=2,orientation='LANDSCAPE',arrangement='BY_ROWS',readingDirection='RTL',leftMargin=20,rightMargin=20,topMargin=10,bottomMargin=10,innerMargin=5))
with pikepdf.Pdf.open(out) as pdf:
 assert len(pdf.pages)==1;field=pdf.Root.AcroForm.Fields[0];assert str(field.V)=='Swona' and int(field.Ff)==1;assert len(field.Kids)==1;widget=field.Kids[0];assert widget.P.objgen==pdf.pages[0].objgen and int(widget.F)&4;rect=list(map(float,widget.Rect));w,h=map(float,pdf.pages[0].MediaBox[2:]);cw=(w-40)/2;ch=h-20;scale=min((cw-10)/200,(ch-10)/300);x=20+cw+5+(cw-10-200*scale)/2;y=10+5+(ch-10-300*scale)/2;expected=[x+30*scale,y+70*scale,x+110*scale,y+90*scale];assert max(abs(a-b) for a,b in zip(rect,expected))<.05,(rect,expected);assert widget.AP.N
poster=ev/'Poster with origin.pdf';pdf=pikepdf.Pdf.new();page=pdf.add_blank_page(page_size=(200,300));page.MediaBox=pikepdf.Array([20,30,220,330]);page.CropBox=pikepdf.Array([20,30,220,330]);page.Rotate=90;page.Contents=pdf.make_stream(b'1 0 0 rg 20 30 100 150 re f 0 1 0 rg 120 30 100 150 re f 0 0 1 rg 20 180 100 150 re f 1 0 1 rg 120 180 100 150 re f');pdf.save(poster);pdf.close();archive=run('layout-poster',poster,dict(pageSize='Letter',xFactor=2,yFactor=2,rightToLeft=True))
with zipfile.ZipFile(archive) as z:
 assert z.namelist()==['document_poster.pdf'];tilepdf=archive.parent/'poster.pdf';tilepdf.write_bytes(z.read(z.namelist()[0]))
original=pdfium.PdfDocument(str(poster));source_image=original[0].render().to_pil().convert('RGB');doc=pdfium.PdfDocument(str(tilepdf));assert len(doc)==4
for i,page in enumerate(doc):
 image=page.render().to_pil().convert('RGB');assert image.size==(612,792);row=i//2;col=1-i%2;expected=source_image.getpixel((int((col+.5)*source_image.width/2),int((row+.5)*source_image.height/2)));assert image.getpixel((306,396))==expected;assert image.getpixel((306,10))==(255,255,255) and image.getpixel((306,780))==(255,255,255)
(ev/'results.json').write_text(json.dumps(dict(cases=cases,formOutput=str(out),posterOutput=str(tilepdf),centroidTolerancePixels=2,formRectTolerancePoints=.05),indent=2),encoding='utf-8');print('N-up/poster PASS: 3 actual jobs; rotations/origins/column-RTL/margins, editable field value/flags/widget placement, RTL Letter tiles/padding and original hashes.')
