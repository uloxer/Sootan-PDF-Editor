"""Preserve a stopped legacy Data/profile before its offline-origin migration/upgrade."""
from pathlib import Path
import argparse,hashlib,json,shutil,os,stat

def manifest(root):
 return {str(p.relative_to(root)):hashlib.sha256(p.read_bytes()).hexdigest() for p in root.rglob('*') if p.is_file() and p.name!='upgrade-backup.json'}
def prepare(source,destination):
 source=source.resolve();destination=destination.resolve()
 if source==destination or source in destination.parents or destination in source.parents:raise ValueError('Backup must be separate from Data')
 if destination.exists():raise ValueError('Use a new backup directory')
 if any(p.is_symlink() or bool(getattr(p.lstat(),'st_file_attributes',0)&stat.FILE_ATTRIBUTE_REPARSE_POINT) for p in source.rglob('*')):raise ValueError('Data contains links; review before copying')
 before=manifest(source);shutil.copytree(source,destination);after=manifest(destination)
 if before!=after or before!=manifest(source):raise ValueError('Data changed or could not be preserved; stop all old Sootan windows and retry to a new destination')
 record=dict(source=str(source),archive=str(destination),files=len(before),sha256=before)
 (destination/'preservation-manifest.json').write_text(json.dumps(record,indent=2),encoding='utf-8')
 (source/'upgrade-backup.json').write_text(json.dumps(dict(archive=str(destination),files=len(before))),encoding='utf-8')
 return record
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('--data',type=Path,required=True);p.add_argument('--archive',type=Path,required=True);a=p.parse_args();r=prepare(a.data,a.archive);print(f'Preserved and verified {r["files"]} files; source key values were not printed.')
