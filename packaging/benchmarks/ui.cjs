// Measures the actual isolated WebView, not headless Chromium. CDP is opt-in and loopback-only.
const fs=require('fs'),path=require('path'),cp=require('child_process');
const {performance}=require('perf_hooks');
const {chromium,expect}=require('../../frontend/node_modules/@playwright/test');
const host=path.resolve(process.argv[2]??'.tooling/offline-shell/SootanHost.exe'),bundle=path.resolve(process.argv[3]??'frontend/dist');
const out=path.resolve('.tooling/offline-ui-benchmark');fs.mkdirSync(out,{recursive:true});
const data=path.join(out,'profile-'+Date.now());fs.mkdirSync(data);
const base='frontend/src/editor/core/tests/test-fixtures';
const fixtures=['annotation-text-sample.pdf','form-fields-sample.pdf','pattern-fill-sample.pdf','type3-sample.pdf','many-pages-sample.pdf','letter-spacing-sample.pdf','rotated-text-sample.pdf','compare_sample_a.pdf','justified-sample.pdf','shading-sample.pdf'].map(n=>path.resolve(base,n));
const port=9442;
function memory(root){const script=`$ids=@(${Number(root)});$all=Get-CimInstance Win32_Process;do{$next=@($all|Where-Object{$ids -contains $_.ParentProcessId -and $ids -notcontains $_.ProcessId}|ForEach-Object{$_.ProcessId});$ids+=$next}while($next.Count);$ps=@($ids|ForEach-Object{Get-Process -Id $_ -ErrorAction SilentlyContinue});[pscustomobject]@{processes=$ps.Count;working_set_bytes=($ps|Measure-Object WorkingSet64 -Sum).Sum}|ConvertTo-Json`;return JSON.parse(cp.execFileSync('powershell.exe',['-NoProfile','-Command',script],{encoding:'utf8'}));}
const sharp=()=>{const es=[...document.querySelectorAll('img[src^="blob:"]')].filter(e=>e.clientWidth>20);return es.length&&es.every(e=>e.complete&&e.naturalWidth>=e.clientWidth*devicePixelRatio*.95);};
const summary=xs=>({samples:xs.length,p50_ms:[...xs].sort((a,b)=>a-b)[Math.ceil(xs.length*.5)-1],p95_ms:[...xs].sort((a,b)=>a-b)[Math.ceil(xs.length*.95)-1]});
(async()=>{
 const start=performance.now(),child=cp.spawn(host,['--offline',data,bundle,fixtures[0]],{env:{...process.env,SOOTAN_DESKTOP_DEBUG_PORT:String(port)},stdio:'ignore'});
 await expect.poll(async()=>{try{return(await fetch(`http://127.0.0.1:${port}/json/list`)).ok}catch{return false}},{timeout:30000}).toBe(true);
 const browser=await chromium.connectOverCDP(`http://127.0.0.1:${port}`),page=browser.contexts()[0].pages()[0];
 await page.waitForFunction(sharp,null,{timeout:30000});await page.evaluate(()=>new Promise(r=>requestAnimationFrame(()=>requestAnimationFrame(r))));
 const firstSharp=performance.now()-start;const snapshots={1:memory(child.pid)};
 for(const [from,to]of[[1,5],[5,10]]){cp.execFileSync(host,['--activate',data,...fixtures.slice(from,to)]);await expect(page.getByRole('tab')).toHaveCount(to);await page.waitForFunction(sharp);snapshots[to]=memory(child.pid);}
 const tabs=page.getByRole('tab'),switches=[],zooms=[];
 for(let i=0;i<20;i++){
  const old=await page.locator('img[src^="blob:"]').evaluateAll(es=>es.map(e=>e.src).join());const t=performance.now();await tabs.nth(i%10).click();await page.waitForFunction(old=>[...document.querySelectorAll('img[src^="blob:"]')].map(e=>e.src).join()!==old,old);await page.waitForFunction(sharp);await page.evaluate(()=>new Promise(r=>requestAnimationFrame(r)));switches.push(performance.now()-t);
 }
 for(let i=0;i<20;i++){
  const old=await page.locator('img[src^="blob:"]').evaluateAll(es=>es.map(e=>e.src).join());const t=performance.now();await page.getByRole('button',{name:i%2?'Zoom Out':'Zoom In',exact:true}).click();await page.waitForFunction(old=>[...document.querySelectorAll('img[src^="blob:"]')].map(e=>e.src).join()!==old,old);await page.waitForFunction(sharp);await page.evaluate(()=>new Promise(r=>requestAnimationFrame(r)));zooms.push(performance.now()-t);
 }
 const requests=[],failures=[];page.on('request',r=>requests.push(r.url()));page.on('requestfailed',r=>failures.push(r.url()));await page.reload({waitUntil:'networkidle'});await page.waitForFunction(sharp);
 const result={profile:data,host_pid:child.pid,first_dpr_sharp_paint_upper_bound_ms:firstSharp,launch_samples:1,cold_definition:'fresh process and fresh WebView profile; OS disk cache not reset; includes CDP polling/attachment',device_pixel_ratio:await page.evaluate(()=>devicePixelRatio),switch:summary(switches),zoom:summary(zooms),switch_samples_ms:switches,zoom_samples_ms:zooms,memory:snapshots,requests:requests.length,api_requests:requests.filter(u=>u.includes('/api/')),external_requests:requests.filter(u=>/^https?:/.test(u)&&!u.startsWith('https://sootan.local/')),failed_requests:failures};
 fs.writeFileSync(path.join(out,'results.json'),JSON.stringify(result,null,2));console.log(JSON.stringify(result,null,2));
 cp.execFileSync(host,['--stop',data]);process.exit(0);
})().catch(e=>{console.error(e);process.exit(1)});
