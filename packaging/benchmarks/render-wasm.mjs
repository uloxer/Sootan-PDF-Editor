import fs from 'node:fs';
import path from 'node:path';
import {performance} from 'node:perf_hooks';
import {init} from '../../frontend/node_modules/@embedpdf/pdfium/dist/index.js';
const out=path.resolve(process.argv[2]??'.tooling/render-benchmark');fs.mkdirSync(out,{recursive:true});
const fixtures=JSON.parse(fs.readFileSync('packaging/benchmarks/fixtures.json'));
const start=performance.now();const m=await init({wasmBinary:fs.readFileSync('frontend/node_modules/@embedpdf/pdfium/dist/pdfium.wasm')});m.PDFiumExt_Init();
const initMs=performance.now()-start,results=[];
for(const fixture of fixtures){
 if(!fs.existsSync(fixture.path)){results.push({...fixture,skipped:'fixture unavailable'});continue;}
 const bytes=fs.readFileSync(fixture.path),times=[],zoomTimes=[];let pageCount;
 for(let i=0;i<21;i++){
  const started=performance.now(),ptr=m.pdfium.wasmExports.malloc(bytes.length);m.pdfium.HEAPU8.set(bytes,ptr);
  const doc=m.FPDF_LoadMemDocument(ptr,bytes.length,fixture.password??'');if(!doc)throw Error('Load failed: '+fixture.name);pageCount=m.FPDF_GetPageCount(doc);
  const page=m.FPDF_LoadPage(doc,0);const render=(w,h)=>{const bitmap=m.FPDFBitmap_Create(w,h,1);m.FPDFBitmap_FillRect(bitmap,0,0,w,h,0xffffffff);m.FPDF_RenderPageBitmap(bitmap,page,0,0,w,h,0,1);return bitmap;};
  const bitmap=render(1000,1400);times.push(performance.now()-started);
  if(i===0){const ptr=m.FPDFBitmap_GetBuffer(bitmap);fs.writeFileSync(path.join(out,fixture.name+'-wasm.bgra'),m.pdfium.HEAPU8.slice(ptr,ptr+1000*1400*4));}
  m.FPDFBitmap_Destroy(bitmap);const z=performance.now(),zoom=render(1500,2100);zoomTimes.push(performance.now()-z);m.FPDFBitmap_Destroy(zoom);
  m.FPDF_ClosePage(page);m.FPDF_CloseDocument(doc);m.pdfium.wasmExports.free(ptr);
 }
 results.push({name:fixture.name,pageCount,openRenderMs:times,zoomRenderMs:zoomTimes});
}
fs.writeFileSync(path.join(out,'wasm.json'),JSON.stringify({engine:'@embedpdf/pdfium2.15.1, Node V8, in-process raster kernel only',initMs,results},null,2));
