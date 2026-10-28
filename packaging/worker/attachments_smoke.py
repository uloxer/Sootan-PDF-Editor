"""Verify attachment/portfolio bytes and document preservation through the private non-web worker."""
import argparse, hashlib, json, os, shutil, subprocess, uuid, zipfile
from pathlib import Path
import pikepdf
import pypdfium2 as pdfium


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--java',type=Path,required=True);args=parser.parse_args()
    root=Path(__file__).resolve().parents[2];evidence=root/'.tooling/nonweb-attachments-golden';evidence.mkdir(exist_ok=True)
    source=evidence/'Source with spaces.pdf'
    with pikepdf.Pdf.new() as pdf:
        page=pdf.add_blank_page(page_size=(500,600));page.Rotate=90;page.CropBox=[10,20,410,520]
        font=pdf.make_indirect(pikepdf.Dictionary(Type=pikepdf.Name.Font,Subtype=pikepdf.Name.Type1,BaseFont=pikepdf.Name.Helvetica));page.Resources=pikepdf.Dictionary(Font=pikepdf.Dictionary(F1=font));page.Contents=pdf.make_stream(b'BT /F1 18 Tf 100 300 Td (Attachment original) Tj ET')
        widget=pdf.make_indirect(pikepdf.Dictionary(Type=pikepdf.Name.Annot,Subtype=pikepdf.Name.Widget,FT=pikepdf.Name.Tx,T='place',V='Swona',Rect=[40,100,180,125],P=page.obj));page.Annots=[widget];pdf.Root.AcroForm=pdf.make_indirect(pikepdf.Dictionary(Fields=[widget],NeedAppearances=True,DA='/F1 12 Tf 0 g',DR=page.Resources))
        pdf.docinfo['/Title']='Attachment proof'
        with pdf.open_outline() as outline:outline.root.append(pikepdf.OutlineItem('Hill',0))
        pdf.save(source)
    files=[('Notes with spaces.txt','text/plain',b'private notes\x00\n'),('../../escape.txt','text/plain',b'dangerous name only'),('folder/escape.txt','application/octet-stream',b'collision'),('report.pdf','application/pdf',source.read_bytes())]
    cases=[];cmd=[str(args.java.resolve()),'-cp',str(root/'app/worker/build/install/worker/lib/*'),'stirling.software.worker.WorkerMain']
    def run(operation='attachments',opts=None,src=source,members=None,success=True):
        job=evidence/str(uuid.uuid4());job.mkdir();shutil.copyfile(src,job/'input-main.pdf');before=hashlib.sha256(src.read_bytes()).hexdigest();options=dict(opts or {})
        if members is not None:
            options['selectedFiles']=[]
            for index,(name,mime,data) in enumerate(members):
                (job/f'attachment-{index}.bin').write_bytes(data);options['selectedFiles'].append(dict(index=index,name=name,contentType=mime))
        done=subprocess.run(cmd+[str(job)],input=json.dumps(dict(requestId=job.name,operation=operation,input='input-main.pdf',options=options))+'\n',text=True,capture_output=True,timeout=60)
        messages=[json.loads(line) for line in done.stdout.splitlines() if line.startswith('{')];assert messages and (messages[-1]['type']=='result')==success,(operation,options,done.stderr,messages)
        assert hashlib.sha256(src.read_bytes()).hexdigest()==before
        cases.append(dict(operation=operation,action=options.get('action'),expected_success=success,exit=done.returncode,result=messages[-1],sourceSHA256=before))
        return job/messages[-1]['file'] if success else None
    def preserved(path):
        with pikepdf.Pdf.open(path) as pdf:
            assert str(pdf.docinfo.Title)=='Attachment proof';assert len(pdf.pages)==1 and list(pdf.pages[0].CropBox)==[10,20,410,520] and int(pdf.pages[0].Rotate)==90;assert str(pdf.Root.AcroForm.Fields[0].V)=='Swona';assert len(pdf.open_outline().root)==1
        doc=pdfium.PdfDocument(str(path));assert 'Attachment original' in doc[0].get_textpage().get_text_range();doc.close()
    added=run(opts={'action':'add'},members=files);preserved(added)
    listing=json.loads(run(opts={'action':'list'},src=added).read_text());assert len(listing)==4 and listing[0].keys()=={'filename','size','contentType','description','creationDate','modificationDate'}
    for name,mime,data in files:
        assert run(opts={'action':'single','attachmentName':name},src=added).read_bytes()==data
    archive=run(opts={'action':'extract'},src=added)
    with zipfile.ZipFile(archive) as z:
        assert len(z.namelist())==4 and len({n.casefold() for n in z.namelist()})==4
        assert all('/' not in n and '\\' not in n and n not in ('.','..') for n in z.namelist());assert sorted(z.read(n) for n in z.namelist())==sorted(f[2] for f in files)
    associated=evidence/'Associated.pdf'
    with pikepdf.Pdf.open(added) as pdf:
        names=pdf.Root.Names.EmbeddedFiles.Names
        spec=next(names[i+1] for i in range(0,len(names),2) if str(names[i])=='Notes with spaces.txt')
        spec.AFRelationship=pikepdf.Name.Data;pdf.Root.AF=[spec];pdf.Root.Collection=pikepdf.Dictionary(D='Notes with spaces.txt');pdf.save(associated)
    associated_renamed=run(opts={'action':'rename','attachmentName':'Notes with spaces.txt','newName':'Hill.txt'},src=associated)
    with pikepdf.Pdf.open(associated_renamed) as pdf:assert str(pdf.Root.AF[0].AFRelationship)=='/Data' and str(pdf.Root.AF[0].UF)=='Hill.txt' and str(pdf.Root.Collection.D)=='Hill.txt'
    associated_deleted=run(opts={'action':'delete','attachmentName':'Hill.txt'},src=associated_renamed)
    with pikepdf.Pdf.open(associated_deleted) as pdf:assert len(pdf.Root.AF)==0 and '/D' not in pdf.Root.Collection
    signed=evidence/'Signed marker.pdf'
    with pikepdf.Pdf.open(added) as pdf:
        signature=pdf.make_indirect(pikepdf.Dictionary(Type=pikepdf.Name.Sig,ByteRange=[0,1,2,3],Contents=pikepdf.String(b'not a CMS integrity fixture')))
        field=pdf.make_indirect(pikepdf.Dictionary(FT=pikepdf.Name.Sig,T='signature marker',V=signature));pdf.Root.AcroForm.Fields.append(field);pdf.save(signed)
    run(opts={'action':'list'},src=signed)
    assert run(opts={'action':'single','attachmentName':'report.pdf'},src=signed).read_bytes()==source.read_bytes()
    run(opts={'action':'delete','attachmentName':'report.pdf'},src=signed,success=False)
    renamed=run(opts={'action':'rename','attachmentName':'Notes with spaces.txt','newName':'Renamed notes.txt'},src=added);preserved(renamed)
    assert run(opts={'action':'single','attachmentName':'Renamed notes.txt'},src=renamed).read_bytes()==files[0][2]
    deleted=run(opts={'action':'delete','attachmentName':'Renamed notes.txt'},src=renamed);preserved(deleted);assert len(json.loads(run(opts={'action':'list'},src=deleted).read_text()))==3
    batch=run(opts={'action':'batch','opsJson':json.dumps(dict(deletions=['missing','report.pdf'],renames=[dict(oldName='folder/escape.txt',newName='Hill.txt'),dict(oldName='absent',newName='x')]))},src=added,members=[('new.csv','text/csv',b'a,b\n1,2')]);preserved(batch)
    names={x['filename'] for x in json.loads(run(opts={'action':'list'},src=batch).read_text())};assert names=={'Notes with spaces.txt','../../escape.txt','Hill.txt','new.csv'}
    run(opts={'action':'add'},src=added,members=[files[0]],success=False)
    run(opts={'action':'rename','attachmentName':'report.pdf','newName':'Notes with spaces.txt'},src=added,success=False)
    run(opts={'action':'add','convertToPdfA3b':True},members=files[:1],success=False)
    run(opts={'action':'add','selectedFiles':[dict(index=-1,name='bad',contentType='text/plain')]},success=False)
    encrypted=evidence/'Encrypted.pdf'
    with pikepdf.Pdf.open(added) as pdf:pdf.save(encrypted,encryption=pikepdf.Encryption(owner='owner-fixture',user='reader-fixture',R=6,allow=pikepdf.Permissions(extract=True)))
    run(opts={'action':'list','sourcePassword':'reader-fixture'},src=encrypted)
    run(opts={'action':'single','attachmentName':'report.pdf','sourcePassword':'reader-fixture'},src=encrypted)
    run(opts={'action':'delete','attachmentName':'report.pdf','sourcePassword':'reader-fixture'},src=encrypted,success=False)
    run(opts={'action':'list','sourcePassword':'wrong'},src=encrypted,success=False)
    restricted=evidence/'Restricted.pdf'
    with pikepdf.Pdf.open(added) as pdf:pdf.save(restricted,encryption=pikepdf.Encryption(owner='owner-fixture',user='reader-fixture',R=6,allow=pikepdf.Permissions(extract=False)))
    run(opts={'action':'extract','sourcePassword':'reader-fixture'},src=restricted,success=False)
    portfolio=run('portfolio-create',{'coverTitle':'Swona portfolio'},members=[files[0],files[0],files[3]])
    with pikepdf.Pdf.open(portfolio) as pdf:assert '/Collection' in pdf.Root and len(pdf.pages)==1
    items=json.loads(run(opts={'action':'list'},src=portfolio).read_text());assert {x['filename'] for x in items}=={'Notes with spaces.txt','Notes with spaces (2).txt','report.pdf'}
    flat=run('portfolio-flatten',src=portfolio)
    with pikepdf.Pdf.open(flat) as pdf:assert '/Collection' not in pdf.Root
    assert run(opts={'action':'single','attachmentName':'report.pdf'},src=flat).read_bytes()==source.read_bytes()
    run('portfolio-flatten',success=False)
    (evidence/'results.json').write_text(json.dumps(dict(cases=cases,archiveEntries=zipfile.ZipFile(archive).namelist()),indent=2),encoding='utf-8');print(f'Attachments/portfolio goldens PASS: {len(cases)} cases; {evidence}')


if __name__=='__main__':main()
