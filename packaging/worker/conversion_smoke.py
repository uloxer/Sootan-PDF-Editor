"""Verify local text/bitmap exports and bitmap imports through the private worker."""
import argparse,hashlib,io,json,shutil,struct,subprocess,uuid,zipfile,zlib
from pathlib import Path
import pikepdf
import pypdfium2 as pdfium
from PIL import Image


def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--java',type=Path,required=True);args=p.parse_args();root=Path(__file__).resolve().parents[2];ev=root/'.tooling/nonweb-conversion-golden';ev.mkdir(exist_ok=True);cases=[]
 png=ev/'Hill with spaces.png';image=Image.new('RGB',(128,64),(20,140,60));image.save(png);tiff=ev/'Cliff.tiff';Image.new('RGB',(80,40),'red').save(tiff,save_all=True,append_images=[Image.new('RGB',(80,40),'blue')]);source=ev/'Conversion source.pdf'
 with pikepdf.Pdf.new() as pdf:
  embedded=pdf.make_stream(image.tobytes());embedded.Type=pikepdf.Name.XObject;embedded.Subtype=pikepdf.Name.Image;embedded.Width=128;embedded.Height=64;embedded.ColorSpace=pikepdf.Name.DeviceRGB;embedded.BitsPerComponent=8
  for n in range(2):
   page=pdf.add_blank_page(page_size=(320,240));font=pdf.make_indirect(pikepdf.Dictionary(Type=pikepdf.Name.Font,Subtype=pikepdf.Name.Type1,BaseFont=pikepdf.Name.Helvetica));page.Resources=pikepdf.Dictionary(Font=pikepdf.Dictionary(F1=font),XObject=pikepdf.Dictionary(Im1=embedded));page.Contents=pdf.make_stream(f'BT /F1 14 Tf 30 60 Td (Swona original page {n+1}) Tj ET q 80 0 0 40 30 20 cm /Im1 Do Q'.encode())
   if n==0:page.CropBox=[20,10,220,110];page.Rotate=90
  pdf.docinfo['/Title']='Conversion proof';pdf.save(source)
 cmd=[str(args.java.resolve()),'-cp',str(root/'app/worker/build/install/worker/lib/*'),'stirling.software.worker.WorkerMain']
 def run(op,options=None,src=source,images=None,success=True):
  job=ev/str(uuid.uuid4());job.mkdir();shutil.copyfile(src,job/'input-main.pdf');before=hashlib.sha256(src.read_bytes()).hexdigest();options=dict(options or {})
  if images is not None:
   options['selectedFiles']=[]
   for index,file in enumerate(images):
    data=file.read_bytes();(job/f'attachment-{index}.bin').write_bytes(data);options['selectedFiles'].append(dict(index=index,name=file.name,contentType='image/tiff' if file.suffix=='.tiff' else 'image/png'))
  done=subprocess.run(cmd+[str(job)],input=json.dumps(dict(requestId=job.name,operation=op,input='input-main.pdf',options=options))+'\n',capture_output=True,text=True,timeout=60);messages=[json.loads(x) for x in done.stdout.splitlines() if x.startswith('{')];assert messages and (messages[-1]['type']=='result')==success,(op,options,messages,done.stderr);assert hashlib.sha256(src.read_bytes()).hexdigest()==before
  cases.append(dict(operation=op,options=options,expectedSuccess=success,result=messages[-1],sourceSHA256=before));return job/messages[-1]['file'] if success else None
 txt=run('pdf-text-export',{'outputFormat':'txt'});assert 'Swonaoriginalpage1' in ''.join(txt.read_text().split()) and 'Swonaoriginalpage2' in ''.join(txt.read_text().split())
 for fmt in ['png','jpg','gif','bmp','tiff']:
  output=run('pdf-image-export',dict(imageFormat=fmt,singleOrMultiple='single',dpi=72,colorType='color'))
  with Image.open(output) as im:
   assert im.size==((100,200) if fmt=='tiff' else (320,440)),(fmt,im.size)
   if fmt=='tiff':assert im.n_frames==2;im.seek(1);assert im.size==(320,240)
 archive=run('pdf-image-export',dict(imageFormat='png',singleOrMultiple='multiple',pageNumbers='2,1',dpi=144,colorType='grayscale',filename='../../unsafe'))
 with zipfile.ZipFile(archive) as z:
  assert all('/' not in name and '\\' not in name for name in z.namelist());sizes=[]
  for name in z.namelist():
   with Image.open(io.BytesIO(z.read(name))) as im:
    sizes.append(im.size);rgb=im.convert('RGB');assert all(r==g==b for r,g,b in rgb.getdata())
  assert sizes==[(640,480),(200,400)]
 bitmap=run('image-extract',dict(format='png',filename='../../unsafe'))
 with zipfile.ZipFile(bitmap) as z:
  assert len(z.namelist())==1 and '/' not in z.namelist()[0]
  with Image.open(io.BytesIO(z.read(z.namelist()[0]))) as im:assert im.size==(128,64) and im.convert('RGB').tobytes()==image.tobytes()
 imported=run('image-pdf-create',dict(fitOption='fitDocumentToPage',colorType='color'),src=png,images=[png,tiff])
 with pikepdf.Pdf.open(imported) as pdf:assert len(pdf.pages)==3 and [list(page.MediaBox)[2:] for page in pdf.pages]==[[128,64],[80,40],[80,40]]
 rendered=pdfium.PdfDocument(str(imported));assert rendered[0].render().to_pil().size==(128,64);rendered.close()
 rotated=run('image-pdf-create',dict(fitOption='maintainAspectRatio',autoRotate=True,colorType='blackwhite'),src=png,images=[png])
 with pikepdf.Pdf.open(rotated) as pdf:assert float(pdf.pages[0].MediaBox[2])>float(pdf.pages[0].MediaBox[3])
 for options in [dict(imageFormat='webp'),dict(imageFormat='png',dpi=501),dict(imageFormat='png',pageNumbers='100')]:run('pdf-image-export',options,success=False)
 run('pdf-text-export',dict(outputFormat='rtf'),success=False)
 huge=ev/'Huge header.png';raw=bytearray(png.read_bytes());raw[16:24]=struct.pack('>II',100000,100000);raw[29:33]=struct.pack('>I',zlib.crc32(raw[12:29]));huge.write_bytes(raw);run('image-pdf-create',dict(fitOption='fillPage'),src=huge,images=[huge],success=False)
 encrypted=ev/'Restricted.pdf'
 with pikepdf.Pdf.open(source) as pdf:pdf.save(encrypted,encryption=pikepdf.Encryption(owner='owner-fixture',user='reader-fixture',R=6,allow=pikepdf.Permissions(extract=False)))
 for op in ['pdf-text-export','pdf-image-export','image-extract']:run(op,dict(sourcePassword='reader-fixture'),src=encrypted,success=False)
 run('pdf-text-export',dict(sourcePassword='owner-fixture',outputFormat='txt'),src=encrypted)
 signed=ev/'Signed marker.pdf'
 with pikepdf.Pdf.open(source) as pdf:
  signature=pdf.make_indirect(pikepdf.Dictionary(Type=pikepdf.Name.Sig,ByteRange=[0,1,2,3],Contents=pikepdf.String(b'not a CMS integrity fixture')));field=pdf.make_indirect(pikepdf.Dictionary(FT=pikepdf.Name.Sig,T='signature marker',V=signature));pdf.Root.AcroForm=pdf.make_indirect(pikepdf.Dictionary(Fields=[field]));pdf.save(signed)
 run('pdf-text-export',dict(outputFormat='txt'),src=signed)
 (ev/'results.json').write_text(json.dumps(dict(cases=cases),indent=2),encoding='utf-8');print(f'Conversion goldens PASS: {len(cases)} cases; {ev}')


if __name__=='__main__':main()
