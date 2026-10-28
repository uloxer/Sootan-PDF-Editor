"""Check actual editable Office structures and private Office-to-PDF results."""
import argparse,hashlib,json,shutil,subprocess,uuid,zipfile,xml.etree.ElementTree as ET
from pathlib import Path
import pikepdf
import pypdfium2 as pdfium


def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--java',type=Path,required=True);p.add_argument('--fonts',type=Path,required=True);args=p.parse_args();root=Path(__file__).resolve().parents[2];ev=root/'.tooling/nonweb-office-golden';ev.mkdir(exist_ok=True);cp=str(root/'app/worker/build/install/worker/lib/*');java=str(args.java.resolve());fonts=args.fonts.resolve();cases=[]
 subprocess.run([java,'-cp',cp,str(root/'packaging/worker/OfficeFixtures.java'),str(ev),str(fonts/'NotoSans-Regular.ttf')],check=True,capture_output=True,text=True,timeout=60)
 source=ev/'Office source.pdf'
 def run(op,options=None,src=source,success=True):
  job=ev/str(uuid.uuid4());job.mkdir();shutil.copyfile(src,job/'input-main.pdf');before=hashlib.sha256(src.read_bytes()).hexdigest();options=dict(options or {})
  if op=='office-import':
   shutil.copyfile(src,job/'attachment-0.bin');options['selectedFiles']=[dict(index=0,name=src.name,contentType='application/octet-stream')]
  import os
  env=dict(os.environ,SOOTAN_WORKER_FONTS=str(fonts))
  done=subprocess.run([java,'-cp',cp,'stirling.software.worker.WorkerMain',str(job)],input=json.dumps(dict(requestId=job.name,operation=op,input='input-main.pdf',options=options))+'\n',capture_output=True,text=True,timeout=150,env=env);messages=[json.loads(x) for x in done.stdout.splitlines()];assert messages and (messages[-1]['type']=='result')==success,(op,options,messages,done.stderr);assert hashlib.sha256(src.read_bytes()).hexdigest()==before
  cases.append(dict(operation=op,format=options.get('outputFormat'),input=src.name,expectedSuccess=success,result=messages[-1],sourceSHA256=before));return job/messages[-1]['file'] if success else None
 for fmt in ['docx','odt','rtf','doc','pptx','odp','ppt','xlsx','ods','txt']:
  result=run('office-export',dict(outputFormat=fmt))
  if fmt in ['rtf','doc']:assert result.read_bytes().startswith(b'{\\rtf') and b'Swona' in result.read_bytes()
  elif fmt=='ppt':assert result.read_bytes().startswith(bytes.fromhex('d0cf11e0a1b11ae1'))
  elif fmt=='txt':assert 'Swona' in result.read_text(encoding='utf-8')
  else:
   with zipfile.ZipFile(result) as z:
    part={'docx':'word/document.xml','pptx':'ppt/slides/slide1.xml','odt':'content.xml','odp':'content.xml','xlsx':'xl/worksheets/sheet1.xml','ods':'content.xml'}[fmt];assert part in z.namelist();body=''.join(ET.fromstring(z.read(part)).itertext())
    if fmt=='xlsx':assert '<c ' in z.read(part).decode();assert any(b'Swona' in z.read(n) for n in z.namelist() if n.endswith('.xml'))
    else:assert 'Swona' in body,(fmt,body)
    if fmt=='docx':assert 'café' in body and 'Ω' in body and '<w:t' in z.read(part).decode()
 for name in ['Word with spaces.docx','Slides.pptx','Workbook.xlsx','Legacy.ppt','Notes.txt','Table.csv','Table.tsv']:
  result=run('office-import',src=ev/name)
  with pikepdf.Pdf.open(result) as pdf:assert len(pdf.pages)>0
  doc=pdfium.PdfDocument(str(result));text=''.join(page.get_textpage().get_text_range() for page in doc);assert 'Swona' in text,(name,text)
  if name=='Word with spaces.docx':assert 'café' in text and 'Ω' in text;text_path=result.parent/'text.txt';text_path.write_text(text,encoding='utf-8');doc[0].render(scale=1).to_pil().save(result.parent/'render.png')
  doc.close()
 # Explicit refusals are checked before allowing a parser to consume dangerous package data.
 original=ev/'Word with spaces.docx'
 def altered(name,part,data):
  target=ev/name
  with zipfile.ZipFile(original) as z,zipfile.ZipFile(target,'w',compression=zipfile.ZIP_DEFLATED) as out:
   for entry in z.infolist():
    if entry.filename!=part:out.writestr(entry.filename,z.read(entry))
   out.writestr(part,data)
  return target
 external=altered('Linked.docx','word/_rels/document.xml.rels',b'<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rIdExternal" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="https://example.invalid/private.png" TargetMode="External"/></Relationships>');run('office-import',src=external,success=False)
 xxe=altered('Entities.docx','custom.xml',b'<!DOCTYPE a [<!ENTITY e SYSTEM "file:///not-granted">]><a>&e;</a>');run('office-import',src=xxe,success=False)
 bomb=altered('Bomb.docx','large.bin',b'0'*(10*1024*1024+1));run('office-import',src=bomb,success=False)
 mismatch=ev/'Wrong.xlsx';mismatch.write_bytes(original.read_bytes());run('office-import',src=mismatch,success=False)
 unsupported=ev/'Unsupported.doc';unsupported.write_bytes(b'not an Office document');run('office-import',src=unsupported,success=False)
 restricted=ev/'Restricted.pdf'
 with pikepdf.Pdf.open(source) as pdf:pdf.save(restricted,encryption=pikepdf.Encryption(owner='owner-fixture',user='reader-fixture',R=6,allow=pikepdf.Permissions(extract=False)))
 run('office-export',dict(outputFormat='docx',sourcePassword='reader-fixture'),src=restricted,success=False)
 run('office-export',dict(outputFormat='docx',sourcePassword='owner-fixture'),src=restricted)
 run('office-export',dict(outputFormat='docx',useStirlingOfficeConvert=False),success=False)
 (ev/'results.json').write_text(json.dumps(dict(cases=cases),indent=2),encoding='utf-8');print(f'Office goldens PASS: {len(cases)} cases; {ev}')


if __name__=='__main__':main()
