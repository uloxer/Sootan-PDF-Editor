"""Small actual stdio PDF/A compliance/refusal and original-byte check."""
import argparse,hashlib,json,os,shutil,subprocess,uuid
from pathlib import Path
import pikepdf
import pypdfium2 as pdfium
p=argparse.ArgumentParser();p.add_argument('--java',type=Path,required=True);p.add_argument('--fonts',type=Path,required=True);a=p.parse_args();root=Path(__file__).resolve().parents[2];ev=root/'.tooling/nonweb-pdfa-golden';ev.mkdir(exist_ok=True);cp=str(root/'app/worker/build/install/worker/lib/*');java=str(a.java.resolve());cases=[]
subprocess.run([java,'-cp',cp,str(root/'packaging/worker/PdfaFixtures.java'),str(ev),str(a.fonts/'NotoSans-Regular.ttf')],check=True,capture_output=True,text=True)
def run(src,format,success=True,options=None):
 job=ev/str(uuid.uuid4());job.mkdir();shutil.copyfile(src,job/'input-main.pdf');digest=hashlib.sha256(src.read_bytes()).hexdigest();o=dict(options or {},outputFormat=format)
 done=subprocess.run([java,'-cp',cp,'stirling.software.worker.WorkerMain',str(job)],input=json.dumps(dict(requestId=job.name,operation='pdfa-convert',input='input-main.pdf',options=o))+'\n',capture_output=True,text=True,timeout=120)
 messages=[json.loads(line) for line in done.stdout.splitlines()];last=messages[-1];assert (last['type']=='result')==success,(last,done.stderr);assert digest==hashlib.sha256(src.read_bytes()).hexdigest()
 cases.append(dict(input=src.name,format=format,success=success,result=last,sourceSHA256=digest))
 if success:
  with pikepdf.Pdf.open(job/'output.pdf') as pdf:assert len(pdf.pages)==1 and not pdf.is_encrypted
  doc=pdfium.PdfDocument(str(job/'output.pdf'));assert doc[0].render().width>0
  if src.name=='Embedded.pdf':assert 'Swona café Ω' in doc[0].get_textpage().get_text_range()
  doc.close()
for part in (1,2,3):run(ev/'Vector.pdf',f'pdfa-{part}')
run(ev/'Embedded.pdf','pdfa-2b');run(ev/'Unembedded.pdf','pdfa-2b',False);assert cases[-1]['result']['code']=='PDFA_HELPER_REQUIRED'
run(ev/'Vector.pdf','pdfa-2a',False)
with pikepdf.Pdf.open(ev/'Vector.pdf') as pdf:pdf.save(ev/'Protected.pdf',encryption=pikepdf.Encryption(owner='owner-fixture',user='reader-fixture',R=6))
run(ev/'Protected.pdf','pdfa-2b',False,dict(sourcePassword='reader-fixture'));run(ev/'Protected.pdf','pdfa-2b',True,dict(sourcePassword='owner-fixture'))
(ev/'results.json').write_text(json.dumps(dict(cases=cases),indent=2),encoding='utf-8');print(f'PDF/A worker PASS: {len(cases)} cases; {ev}')
