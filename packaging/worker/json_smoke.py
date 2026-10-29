"""Verify exact editable JSON/font/image/Type3 structure and actual edited PDF output."""
import argparse,base64,copy,hashlib,json,os,shutil,subprocess,uuid
from pathlib import Path
import pikepdf
import pypdfium2 as pdfium

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--java',type=Path,required=True);p.add_argument('--fonts',type=Path,required=True);a=p.parse_args();root=Path(__file__).resolve().parents[2];ev=root/'.tooling/nonweb-json-golden';ev.mkdir(exist_ok=True);cp=str(root/'app/worker/build/install/worker/lib/*');java=str(a.java.resolve());fonts=a.fonts.resolve();cases=[];env=dict(os.environ,SOOTAN_WORKER_FONTS=str(fonts))
 subprocess.run([java,'-cp',cp,str(root/'packaging/worker/JsonFixtures.java'),str(ev),str(fonts/'NotoSans-Regular.ttf')],check=True,capture_output=True,text=True,timeout=60);source=ev/'Editable with spaces.pdf'
 def run(op,src=source,options=None,success=True):
  job=ev/str(uuid.uuid4());job.mkdir();shutil.copyfile(src,job/'input-main.pdf');before=hashlib.sha256(src.read_bytes()).hexdigest();options=dict(options or {})
  if op=='json-import':shutil.copyfile(src,job/'attachment-0.bin');options['selectedFiles']=[dict(index=0,name=src.name,contentType='application/json')]
  done=subprocess.run([java,'-cp',cp,'stirling.software.worker.WorkerMain',str(job)],input=json.dumps(dict(requestId=job.name,operation=op,input='input-main.pdf',options=options))+'\n',env=env,capture_output=True,text=True,timeout=120);messages=[json.loads(x) for x in done.stdout.splitlines()];assert messages and (messages[-1]['type']=='result')==success,(op,messages,done.stderr);assert before==hashlib.sha256(src.read_bytes()).hexdigest();cases.append(dict(operation=op,input=src.name,success=success,result=messages[-1],sourceSHA256=before));return job/messages[-1]['file'] if success else None
 exported=run('json-export');model=json.loads(exported.read_text(encoding='utf-8'));assert len(model['pages'])==2 and model['pages'][0]['rotation']==90;assert model['pages'][0]['contentStreams'] and model['pages'][0]['resources'];assert any('café' in t['text'] and 'Ω' in t['text'] for t in model['pages'][0]['textElements']);assert model['pages'][0]['imageElements'];assert any(x['subtype']=='Type3' for x in model['fonts']);assert any(x.get('type3Glyphs') for x in model['fonts'] if x['subtype']=='Type3');assert model['formFields'] and model['pages'][0]['annotations'];assert model.get('lazyImages') is not True
 original=ev/'Complete.json';original.write_text(json.dumps(model),encoding='utf-8');rebuilt=run('json-import',src=original)
 def text(path):
  doc=pdfium.PdfDocument(str(path));value=''.join(page.get_textpage().get_text_range() for page in doc);doc.close();return value
 assert 'Swona café Ω editable text' in text(rebuilt),text(rebuilt)
 original_doc=pdfium.PdfDocument(str(source));rebuilt_doc=pdfium.PdfDocument(str(rebuilt));assert original_doc[1].render().to_pil().tobytes()==rebuilt_doc[1].render().to_pil().tobytes();original_doc.close();rebuilt_doc.close()
 with pikepdf.Pdf.open(rebuilt) as pdf:assert len(pdf.pages)==2 and pdf.pages[0].Rotate==90;assert pdf.Root.AcroForm.Fields[0].V=='Swona form';assert pdf.pages[0].Resources.XObject
 edited=copy.deepcopy(model)
 for item in edited['pages'][0]['textElements']:
  if 'Swona' in item.get('text',''):item['text']=item['text'].replace('Swona','Cliff')
 changed=ev/'Edited.json';changed.write_text(json.dumps(edited),encoding='utf-8');output=run('json-import',src=changed);assert 'Cliff café Ω editable text' in text(output),text(output)
 def bad(name,value):
  file=ev/name;file.write_text(json.dumps(value),encoding='utf-8');run('json-import',src=file,success=False)
 active=copy.deepcopy(model);active['pages'][0]['annotations'][0]['rawData']={'type':'DICTIONARY','entries':{'S':{'type':'NAME','value':'JavaScript'},'JS':{'type':'STRING','value':base64.b64encode(b'app.alert(1)').decode()}}};bad('Active.json',active)
 invalid=copy.deepcopy(model);invalid['fonts'][0]['program']='not valid base64!';bad('Malformed binary.json',invalid)
 lazy=copy.deepcopy(model);lazy['lazyImages']=True;bad('Lazy.json',lazy)
 missing=ev/'Malformed.json';missing.write_text('{broken',encoding='utf-8');run('json-import',src=missing,success=False)
 deep=ev/'Deep.json';deep.write_text('['*70+'0'+']'*70);run('json-import',src=deep,success=False)
 bomb=copy.deepcopy(model);bomb['fonts'][0]['program']=base64.b64encode(b'x'*(16*1024*1024+1)).decode();bad('Large binary.json',bomb)
 restricted=ev/'Restricted.pdf'
 with pikepdf.Pdf.open(source) as pdf:pdf.save(restricted,encryption=pikepdf.Encryption(owner='owner-fixture',user='reader-fixture',R=6,allow=pikepdf.Permissions(extract=False)))
 run('json-export',src=restricted,options={'sourcePassword':'reader-fixture'},success=False);run('json-export',src=restricted,options={'sourcePassword':'owner-fixture'});run('json-export',options={'lightweight':True},success=False)
 (ev/'results.json').write_text(json.dumps(dict(cases=cases,editedOutput=str(output),exported=str(exported)),indent=2),encoding='utf-8');print(f'Editable JSON goldens PASS: {len(cases)} cases; {ev}')

if __name__=='__main__':main()
