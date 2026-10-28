"""Run actual SVG/Tabula worker jobs and independently inspect their saved pixels and CSV cells."""
import argparse,base64,csv,hashlib,io,json,shutil,struct,subprocess,uuid,zipfile,zlib
from pathlib import Path
import pikepdf
import pypdfium2 as pdfium
from PIL import Image

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--java',type=Path,required=True);args=p.parse_args();root=Path(__file__).resolve().parents[2];ev=root/'.tooling/nonweb-structured-golden';ev.mkdir(exist_ok=True);cp=str(root/'app/worker/build/install/worker/lib/*');java=str(args.java.resolve());cases=[]
 subprocess.run([java,'-cp',cp,str(root/'packaging/worker/StructuredFixtures.java'),str(ev)],check=True,capture_output=True,text=True,timeout=60)
 source=ev/'Tables with spaces.pdf'
 def svg(name,body):
  path=ev/name;path.write_text(body,encoding='utf-8');return path
 good=svg('Hill with spaces.svg','<svg xmlns="http://www.w3.org/2000/svg" width="100" height="100"><style>.hill{fill:#315d37}</style><rect class="hill" width="100" height="100"/></svg>')
 def run(op,options=None,src=source,selected=None,success=True,code=None):
  job=ev/str(uuid.uuid4());job.mkdir();shutil.copyfile(src,job/'input-main.pdf');before=hashlib.sha256(src.read_bytes()).hexdigest();options=dict(options or {})
  if selected:
   for i,file in enumerate(selected):shutil.copyfile(file,job/f'attachment-{i}.bin')
   options['selectedFiles']=[dict(index=i,name=file.name,contentType='image/svg+xml') for i,file in enumerate(selected)]
  done=subprocess.run([java,'-cp',cp,'stirling.software.worker.WorkerMain',str(job)],input=json.dumps(dict(requestId=job.name,operation=op,input='input-main.pdf',options=options))+'\n',capture_output=True,text=True,timeout=90);messages=[json.loads(x) for x in done.stdout.splitlines()];assert messages and (messages[-1]['type']=='result')==success,(messages,done.stderr);assert hashlib.sha256(src.read_bytes()).hexdigest()==before
  if code:assert messages[-1]['code']==code,messages
  cases.append(dict(operation=op,input=src.name,success=success,result=messages[-1],sourceSHA256=before));return job/messages[-1]['file'] if success else None
 result=run('svg-pdf-create',src=good,selected=[good]);doc=pdfium.PdfDocument(str(result));image=doc[0].render(scale=1).to_pil().convert('RGB');assert image.size==(100,100);assert max(abs(a-b) for a,b in zip(image.getpixel((50,50)),(49,93,55)))<=2;image.save(result.parent/'render.png');doc.close()
 result=run('svg-pdf-create',dict(combineIntoSinglePdf=True),src=good,selected=[good,good]);
 with pikepdf.Pdf.open(result) as pdf:assert len(pdf.pages)==2
 result=run('svg-pdf-create',src=good,selected=[good,good]);
 with zipfile.ZipFile(result) as z:assert z.namelist()==['svg-1.pdf','svg-2.pdf'];assert all(z.read(n).startswith(b'%PDF') for n in z.namelist())
 image=Image.new('RGB',(2,2),'red');buffer=io.BytesIO();image.save(buffer,format='PNG');data=base64.b64encode(buffer.getvalue()).decode();embedded=svg('Embedded.svg',f'<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" width="20" height="20"><image xlink:href="data:image/png;base64,{data}" width="20" height="20"/></svg>');result=run('svg-pdf-create',src=embedded,selected=[embedded]);doc=pdfium.PdfDocument(str(result));assert doc[0].render().to_pil().convert('RGB').getpixel((10,10))[0]>240;doc.close()
 invalid={
 'External.svg':'<image href="https://example.invalid/secret.png"/>',
 'File.svg':'<use href="file:///C:/not-granted.svg#x"/>',
 'Css.svg':'<style>@import url(https://example.invalid/style.css);</style>',
 'Font.svg':'<style>@font-face{font-family:a;src:url(file:///private.ttf)}</style>',
 'CssEscape.svg':r'<style>rect{fill:u\72l(https://example.invalid/x)}</style>',
 'Nested.svg':'<image href="data:image/svg+xml;base64,PHN2Zy8+"/>',
 'Huge.svg':'<rect width="1" height="1"/>'}
 for name,body in invalid.items():
  text=f'<svg xmlns="http://www.w3.org/2000/svg" width="100" height="100">{body}</svg>'
  if name=='Huge.svg':text=text.replace('width="100"','width="15000"')
  file=svg(name,text);run('svg-pdf-create',src=file,selected=[file],success=False)
 processing=svg('Stylesheet.svg','<?xml-stylesheet href="https://example.invalid/style.css"?><svg xmlns="http://www.w3.org/2000/svg" width="20" height="20"/>');run('svg-pdf-create',src=processing,selected=[processing],success=False,code='SVG_RESOURCE_DENIED')
 deep=svg('Deep.svg','<svg xmlns="http://www.w3.org/2000/svg">'+'<g>'*130+'<rect width="1" height="1"/>'+'</g>'*130+'</svg>');run('svg-pdf-create',src=deep,selected=[deep],success=False)
 def chunk(kind,data):return struct.pack('>I',len(data))+kind+data+struct.pack('>I',zlib.crc32(kind+data)&0xffffffff)
 png=b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',5001,5001,8,2,0,0,0))+chunk(b'IDAT',zlib.compress(b'\0'))+chunk(b'IEND',b'')
 pixel=svg('Pixel limit.svg','<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" width="20" height="20"><image xlink:href="data:image/png;base64,'+base64.b64encode(png).decode()+'" width="20" height="20"/></svg>');run('svg-pdf-create',src=pixel,selected=[pixel],success=False,code='SVG_RESOURCE_DENIED')
 xxe=svg('Entities.svg','<!DOCTYPE svg [<!ENTITY e SYSTEM "file:///not-granted">]><svg xmlns="http://www.w3.org/2000/svg"><text>&e;</text></svg>');run('svg-pdf-create',src=xxe,selected=[xxe],success=False,code='SVG_RESOURCE_DENIED')
 malformed=svg('Malformed.svg','<svg><broken');run('svg-pdf-create',src=malformed,selected=[malformed],success=False)
 oversized=svg('Oversized.svg','x'*(8*1024*1024+1));run('svg-pdf-create',src=oversized,selected=[oversized],success=False)
 result=run('pdf-csv-export',dict(pageNumbers='1'));rows=list(csv.reader(io.StringIO(result.read_text(encoding='utf-8'))));assert rows==[['Island','Value'],['Swona',"'=SUM(A1:A2)"],['Cliff','-42']],rows
 result=run('pdf-csv-export',dict(pageNumbers='2,1'))
 with zipfile.ZipFile(result) as z:
  assert z.namelist()==['table-1-p2-t1.csv','table-2-p1-t1.csv'];rows=list(csv.reader(io.StringIO(z.read(z.namelist()[0]).decode())));assert rows[1]==['Hill',"'@CMD"] and rows[2]==['Slate','+17'],rows
 run('pdf-csv-export',dict(pageNumbers='3'),success=False);run('pdf-csv-export',src=ev/'No tables.pdf',success=False)
 restricted=ev/'Restricted.pdf'
 with pikepdf.Pdf.open(source) as pdf:pdf.save(restricted,encryption=pikepdf.Encryption(owner='owner-fixture',user='reader-fixture',R=6,allow=pikepdf.Permissions(extract=False)))
 run('pdf-csv-export',dict(pageNumbers='1',sourcePassword='reader-fixture'),src=restricted,success=False,code='INSPECTION_ACCESS_DENIED');run('pdf-csv-export',dict(pageNumbers='1',sourcePassword='owner-fixture'),src=restricted)
 (ev/'results.json').write_text(json.dumps(dict(cases=cases),indent=2),encoding='utf-8');print(f'Structured goldens PASS: {len(cases)} cases; {ev}')

if __name__=='__main__':main()
