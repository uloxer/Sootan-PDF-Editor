from pathlib import Path
import ctypes,json,time,sys
import pypdfium2_raw as p
out=Path(sys.argv[1] if len(sys.argv)>1 else '.tooling/render-benchmark');out.mkdir(parents=True,exist_ok=True)
start=time.perf_counter();p.FPDF_InitLibrary();init_ms=(time.perf_counter()-start)*1000;results=[]
for fixture in json.loads(Path('packaging/benchmarks/fixtures.json').read_text()):
 source=Path(fixture['path'])
 if not source.exists():results.append({'name':fixture['name'],'skipped':'fixture unavailable'});continue
 data=source.read_bytes();times=[];zooms=[]
 for i in range(21):
  started=time.perf_counter();buffer=ctypes.create_string_buffer(data);doc=p.FPDF_LoadMemDocument(buffer,len(data),fixture.get('password','').encode())
  assert doc,fixture['name'];count=p.FPDF_GetPageCount(doc);page=p.FPDF_LoadPage(doc,0)
  def render(w,h):
   bitmap=p.FPDFBitmap_Create(w,h,1);p.FPDFBitmap_FillRect(bitmap,0,0,w,h,0xffffffff);p.FPDF_RenderPageBitmap(bitmap,page,0,0,w,h,0,1);return bitmap
  bitmap=render(1000,1400);times.append((time.perf_counter()-started)*1000)
  if i==0:(out/(fixture['name']+'-native.bgra')).write_bytes(ctypes.string_at(p.FPDFBitmap_GetBuffer(bitmap),1000*1400*4))
  p.FPDFBitmap_Destroy(bitmap);started=time.perf_counter();bitmap=render(1500,2100);zooms.append((time.perf_counter()-started)*1000);p.FPDFBitmap_Destroy(bitmap);p.FPDF_ClosePage(page);p.FPDF_CloseDocument(doc)
 results.append({'name':fixture['name'],'pageCount':count,'openRenderMs':times,'zoomRenderMs':zooms})
(out/'native.json').write_text(json.dumps({'engine':'native PDFium156.0.8076.0 via private Python ctypes, raster kernel only','initMs':init_ms,'results':results},indent=2));p.FPDF_DestroyLibrary()
