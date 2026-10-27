from pathlib import Path
import json,math,hashlib
from PIL import Image,ImageChops,ImageStat
out=Path('.tooling/render-benchmark');a=json.loads((out/'wasm.json').read_text());b=json.loads((out/'native.json').read_text());native={r['name']:r for r in b['results']};rows=[]
def percentile(xs,p):return sorted(xs)[math.ceil(len(xs)*p)-1]
for w in a['results']:
 n=native[w['name']]
 if 'skipped' in w or 'skipped' in n:rows.append({'name':w['name'],'skipped':True});continue
 row={'name':w['name'],'pages':w['pageCount']}
 for op in ['openRenderMs','zoomRenderMs']:
  for key,r in [('wasm',w),('native',n)]:row[key+'_'+op]={'first':r[op][0],'warm_p50':percentile(r[op][1:],.5),'warm_p95':percentile(r[op][1:],.95),'warm_samples':20}
  row[op+'_native_p95_improvement_percent']=100*(1-row['native_'+op]['warm_p95']/row['wasm_'+op]['warm_p95'])
 x=(out/(w['name']+'-wasm.bgra')).read_bytes();y=(out/(w['name']+'-native.bgra')).read_bytes();assert len(x)==len(y)==1000*1400*4
 diff=ImageChops.difference(Image.frombytes('RGBA',(1000,1400),x,'raw','BGRA').convert('RGB'),Image.frombytes('RGBA',(1000,1400),y,'raw','BGRA').convert('RGB'));r,g,blue=diff.split();maximum=ImageChops.lighter(ImageChops.lighter(r,g),blue)
 row['pixels']={'exact':x==y,'different_percent':100*(1-maximum.histogram()[0]/1400000),'rms_rgb':ImageStat.Stat(diff).rms};rows.append(row)
result={'width':1000,'height':1400,'zoom_width':1500,'zoom_height':2100,'render_flags':1,'scope':'matched raster kernels; no host transport, compositor, form environment or editing parity','adopt_native':False,'reason':'No native viewer adapter, UI/memory and edit parity evidence; kernel timings alone do not meet adoption gate','rows':rows};(out/'comparison.json').write_text(json.dumps(result,indent=2));print(json.dumps(result,indent=2))
