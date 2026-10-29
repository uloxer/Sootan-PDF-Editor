"""Saved overlay page selection, displayed geometry and immutable-input proof."""
import argparse,hashlib,json,shutil,subprocess,uuid
from pathlib import Path
import pikepdf
import pypdfium2 as pdfium
p=argparse.ArgumentParser();p.add_argument('--java',type=Path,required=True);args=p.parse_args();root=Path(__file__).resolve().parents[2];ev=root/'.tooling/nonweb-overlay-golden';ev.mkdir(exist_ok=True);cases=[]
def make(path,labels,base=False):
 pdf=pikepdf.Pdf.new()
 for i,label in enumerate(labels):
  page=pdf.add_blank_page(page_size=(240,320) if base else (100,140));w,h=(240,320) if base else (100,140);page.MediaBox=pikepdf.Array([20,30,20+w,30+h]);page.CropBox=page.MediaBox;page.Rotate=(i%4)*90
  page.Resources=pikepdf.Dictionary(Font=pikepdf.Dictionary(F1=pdf.make_indirect(pikepdf.Dictionary(Type=pikepdf.Name.Font,Subtype=pikepdf.Name.Type1,BaseFont=pikepdf.Name.Helvetica))))
  fill=f'.8 .8 .8 rg 20 30 {w} {h} re f' if base else '1 0 0 rg 30 50 20 30 re f'
  page.Contents=pdf.make_stream((fill+f' 0 0 0 rg BT /F1 10 Tf 35 100 Td ({label}) Tj ET').encode())
 pdf.save(path);pdf.close()
base=ev/'Base with spaces.pdf';a=ev/'First.pdf';b=ev/'Second.pdf';make(base,[f'BASE{i}' for i in range(7)],True);make(a,['A1','A2']);make(b,['B1','B2']);hashes={str(f):hashlib.sha256(f.read_bytes()).hexdigest() for f in [base,a,b]}
def center(im):
 pts=[(x,y) for y in range(im.height) for x in range(im.width) if im.getpixel((x,y))[0]>240 and im.getpixel((x,y))[1]<10 and im.getpixel((x,y))[2]<10];assert pts;return tuple(sum(v[k] for v in pts)/len(pts) for k in range(2))
source_images={}
for src,labels in [(a,['A1','A2']),(b,['B1','B2'])]:
 doc=pdfium.PdfDocument(str(src))
 for page,label in zip(doc,labels):source_images[label]=page.render().to_pil().convert('RGB')
for mode,expected in [('SequentialOverlay',['A1','A2','B1','B2','A1','A2','B1']),('InterleavedOverlay',['A1','B1','A2','B2','A1','B1','A2']),('FixedRepeatOverlay',['A1','A2','A1','A2','B1','B2',None])]:
 for position in ([0,1] if mode=='SequentialOverlay' else [0]):
  job=ev/str(uuid.uuid4());job.mkdir();shutil.copyfile(base,job/'input-main.pdf')
  for index,src in enumerate([a,b]):shutil.copyfile(src,job/f'attachment-{index}.bin')
  options=dict(overlayMode=mode,overlayPosition=position,selectedFiles=[dict(index=i,name=f.name,contentType='application/pdf') for i,f in enumerate([a,b])]);
  if mode=='FixedRepeatOverlay':options['counts']=[2,1]
  request=dict(requestId=job.name,operation='overlay',input='input-main.pdf',options=options)
  run=subprocess.run([str(args.java.resolve()),'-cp',str(root/'app/worker/build/install/worker/lib/*'),'stirling.software.worker.WorkerMain',str(job)],input=json.dumps(request)+'\n',text=True,capture_output=True,timeout=120);messages=[json.loads(line) for line in run.stdout.splitlines()];assert messages[-1]['type']=='result',(messages,run.stderr);out=job/messages[-1]['file'];doc=pdfium.PdfDocument(str(out));assert len(doc)==7
  for i,(page,label) in enumerate(zip(doc,expected)):
   text=page.get_textpage().get_text_range();assert f'BASE{i}' in text
   for other in ['A1','A2','B1','B2']:assert (other in text)==(other==label),(mode,i,text,label)
   image=page.render().to_pil().convert('RGB')
   if label and position==0:
    overlay=source_images[label];cx,cy=center(overlay);actual=center(image);wanted=((image.width-overlay.width)/2+cx,(image.height-overlay.height)/2+cy);assert max(abs(x-y) for x,y in zip(actual,wanted))<2,(mode,i,actual,wanted)
   if position==1:assert not any(image.getpixel((x,y))[0]>240 and image.getpixel((x,y))[1]<10 for y in range(image.height) for x in range(image.width))
  cases.append(dict(mode=mode,position=position,output=str(out),expected=expected))
assert all(hashlib.sha256(Path(f).read_bytes()).hexdigest()==digest for f,digest in hashes.items());(ev/'results.json').write_text(json.dumps(dict(cases=cases,originalSHA256=hashes),indent=2),encoding='utf-8');print('Overlay PASS: four jobs; page order/repeats, four base rotations/nonzero origins, foreground/background, source hashes unchanged.')
