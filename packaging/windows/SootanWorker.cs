using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading.Tasks;
using System.Web.Script.Serialization;
using Microsoft.Web.WebView2.Core;

// Only trusted virtual-origin requests reach private job copies. No socket or shell is used.
sealed class SootanWorker : IDisposable {
    private readonly CoreWebView2 core;
    private readonly CoreWebView2Environment environment;
    private readonly string root, java, libraries, helpers, fonts;
    private readonly JavaScriptSerializer json = new JavaScriptSerializer {MaxJsonLength=65536};
    private readonly Dictionary<string,Job> jobs=new Dictionary<string,Job>();
    private bool disposed;
    internal static readonly string[] Operations={"office-export","office-import","pdf-text-export","pdf-image-export","image-pdf-create","image-extract","attachments","portfolio-create","portfolio-flatten","document-inspect","stamp","page-crop","page-scale","page-numbers","watermark","sanitize","password-protect","decrypt","cert-sign-pkcs12","signature-integrity","signature-remove","ocr","metadata-update","forms-unlock","flatten"};
    private sealed class Job : IDisposable {
        internal string Directory; internal Process Process; internal Stream Output; internal ProcessTree Tree; internal bool Running,Cancelled;internal long AttachmentBytes;internal int AttachmentCount;internal Task Work;
        public void Dispose() {
            lock(this) {
            Cancelled=true;
            if(Tree!=null)Tree.Dispose();
            if(Process!=null){try{if(!Process.HasExited)Process.Kill();Process.WaitForExit(5000);}catch(InvalidOperationException){}}
            }
            if(Work!=null){try{Work.Wait(5000);}catch(AggregateException){}}
            if(Process!=null)Process.Dispose();
            if(Output!=null)Output.Dispose();
            if(System.IO.Directory.Exists(Directory)) {
                foreach(string path in System.IO.Directory.GetFiles(Directory))File.SetAttributes(path,FileAttributes.Normal);
                System.IO.Directory.Delete(Directory,true);
            }
        }
    }
    internal SootanWorker(CoreWebView2 view,CoreWebView2Environment runtime,string data) {
        core=view;environment=runtime;
        string app=AppDomain.CurrentDomain.BaseDirectory;
        root=Path.Combine(data,"worker-jobs");System.IO.Directory.CreateDirectory(root);
        if((File.GetAttributes(root)&FileAttributes.ReparsePoint)!=0)throw new IOException("The private worker directory cannot be a link.");
        java=Environment.GetEnvironmentVariable("SOOTAN_WORKER_JAVA")??Path.GetFullPath(Path.Combine(app,"..","runtime","bin","java.exe"));
        libraries=Environment.GetEnvironmentVariable("SOOTAN_WORKER_LIB")??Path.Combine(app,"worker","lib");
        helpers=Environment.GetEnvironmentVariable("SOOTAN_WORKER_HELPERS")??Path.Combine(app,"helpers");
        fonts=Environment.GetEnvironmentVariable("SOOTAN_WORKER_FONTS")??Path.Combine(app,"fonts");
        core.WebResourceRequested+=Resource;
    }
    internal string[] Capabilities {
        get {if(!File.Exists(java)||!System.IO.Directory.Exists(libraries)||!System.IO.Directory.GetFiles(libraries,"worker-*.jar").Any())return new string[0];
            return Operations.Where(operation=>(operation!="ocr"||File.Exists(Path.Combine(helpers,"bin","ocrmypdf.exe")))&&((operation!="watermark"&&operation!="stamp")||new[]{"NotoSans-Regular.ttf","NotoSansArabic-Regular.ttf","NotoSansJP-Regular.ttf","NotoSansKR-Regular.ttf","NotoSansSC-Regular.ttf","NotoSansThai-Regular.ttf"}.All(name=>File.Exists(Path.Combine(fonts,name))))).ToArray();}
    }
    private async void Resource(object sender,CoreWebView2WebResourceRequestedEventArgs e) {
        var uri=new Uri(e.Request.Uri);
        if(uri.GetLeftPart(UriPartial.Authority)!="https://sootan.local"||!uri.AbsolutePath.StartsWith("/__sootan_worker/",StringComparison.Ordinal))return;
        var deferral=e.GetDeferral();
        try {
            if(disposed)throw new IOException();
            string[] parts=uri.AbsolutePath.Substring("/__sootan_worker/".Length).Split('/');Guid request;
            if(parts.Length!=2||!Guid.TryParseExact(parts[0],"D",out request))throw new IOException();
            string id=request.ToString("D"),action=parts[1],method=e.Request.Method;
            if(method=="DELETE"&&action=="job") {
                Job removed=null;lock(jobs){if(jobs.TryGetValue(id,out removed))jobs.Remove(id);}
                if(removed!=null)await Task.Run(()=>removed.Dispose());
                Reply(e,200,"{}");return;
            }
            if(method!="POST")throw new IOException();
            Job job;
            lock(jobs) {
                if(!jobs.TryGetValue(id,out job)) {
                    if(action!="input"||jobs.Count>=2)throw new IOException();
                    string directory=Path.Combine(root,id);System.IO.Directory.CreateDirectory(directory);
                    if((File.GetAttributes(directory)&FileAttributes.ReparsePoint)!=0)throw new IOException();
                    job=new Job {Directory=directory};jobs.Add(id,job);
                }
            }
            bool attachment=System.Text.RegularExpressions.Regex.IsMatch(action,@"^attachment-(?:[0-9]|[1-9][0-9])$");
            if(action=="input"||action=="certificate"||action=="trust"||action=="image"||attachment) {
                if(job.Running||(job.Work!=null&&!job.Work.IsCompleted))throw new IOException();
                if(attachment&&job.AttachmentCount>=100)throw new IOException();
                string path=Path.Combine(job.Directory,attachment?action+".bin":action=="input"?"input-main.pdf":action=="trust"?"trust.cer":action=="image"?"image.bin":"certificate.p12");
                if(action=="image"&&!new[]{"image/png","image/jpeg","image/gif","image/bmp","image/x-ms-bmp","image/tiff"}.Contains(e.Request.Headers.GetHeader("Content-Type")))throw new IOException();
                Stream source=e.Request.Content;
                job.Work=Task.Run(()=> {
                    using(var output=new FileStream(path,FileMode.CreateNew,FileAccess.Write,FileShare.None)) {
                        var buffer=new byte[65536];long total=0;int count;
                        while((count=source.Read(buffer,0,buffer.Length))>0){if(job.Cancelled)throw new IOException();total+=count;if(total>(attachment?50L*1024*1024:action=="trust"?1024L*1024:action=="image"?32L*1024*1024:512L*1024*1024))throw new IOException("Selected file exceeds 512 MiB.");output.Write(buffer,0,count);}
                    }
                    if(attachment){if(totalSize(path)+job.AttachmentBytes>200L*1024*1024)throw new IOException();job.AttachmentBytes+=totalSize(path);job.AttachmentCount++;}
                    File.SetAttributes(path,FileAttributes.ReadOnly);
                });
                await job.Work;
                Reply(e,200,"{}");return;
            }
            if(action!="run"||job.Running)throw new IOException();
            string body;
            using(var reader=new StreamReader(e.Request.Content,Encoding.UTF8)) {
                var buffer=new char[65537];int count=reader.ReadBlock(buffer,0,buffer.Length);
                if(count>65536)throw new IOException();body=new string(buffer,0,count);
            }
            var message=json.Deserialize<Dictionary<string,object>>(body);
            string operation=message.ContainsKey("operation")?message["operation"] as string:null;
            if(!Capabilities.Contains(operation))throw new IOException();
            object options=message.ContainsKey("options")?message["options"]:new Dictionary<string,object>();
            job.Running=true;
            string header=json.Serialize(new {requestId=id,operation=operation,input="input-main.pdf",options=options});
            var running=Task.Run(()=>Run(job,header,operation));job.Work=running;
            var result=await running;
            if(job.Cancelled)throw new IOException();
            string name=result["file"] as string;
            if(name!="output.pdf"&&name!="output.zip"&&name!="output.json"&&name!="output.txt"&&name!="output.bin")throw new IOException();
            string outputPath=Path.Combine(job.Directory,name);
            if((File.GetAttributes(outputPath)&FileAttributes.ReparsePoint)!=0)throw new IOException();
            job.Output=new FileStream(outputPath,FileMode.Open,FileAccess.Read,FileShare.Read);
            e.Response=environment.CreateWebResourceResponse(job.Output,200,"OK","Content-Type: "+(string)result["contentType"]+"\r\nCache-Control: no-store\r\n");
        } catch(OfficeExternalResource) {Reply(e,400,"{\"code\":\"OFFICE_EXTERNAL_RESOURCE\"}");}
        catch(InspectionDenied) {Reply(e,400,"{\"code\":\"INSPECTION_ACCESS_DENIED\"}");}
        catch(UnsupportedStructure) {Reply(e,400,"{\"code\":\"UNSUPPORTED_DOCUMENT_STRUCTURE\"}");}
        catch(Exception) {Reply(e,400,"{\"message\":\"The private worker could not complete this job. Check the selected options and document permissions.\"}");}
        finally {deferral.Complete();}
    }
    private static long totalSize(string path){return new FileInfo(path).Length;}
    private Dictionary<string,object> Run(Job job,string header,string operation) {
        var start=new ProcessStartInfo(java,"-Djava.io.tmpdir="+Quote(job.Directory)+" -cp "+Quote(Path.Combine(libraries,"*"))+" stirling.software.worker.WorkerMain "+Quote(job.Directory)) {UseShellExecute=false,CreateNoWindow=true,RedirectStandardInput=true,RedirectStandardOutput=true,RedirectStandardError=true,WorkingDirectory=job.Directory};
        start.EnvironmentVariables["SOOTAN_WORKER_FONTS"]=fonts;
        start.EnvironmentVariables["SOOTAN_OCRMYPDF"]=Path.Combine(helpers,"bin","ocrmypdf.exe");
        start.EnvironmentVariables["TESSDATA_PREFIX"]=Path.Combine(helpers,"tesseract","tessdata");
        start.EnvironmentVariables["PYTHONDONTWRITEBYTECODE"]="1";
        start.EnvironmentVariables["TEMP"]=job.Directory;start.EnvironmentVariables["TMP"]=job.Directory;
        start.EnvironmentVariables["PATH"]=string.Join(";",new[]{Path.Combine(helpers,"bin"),Path.Combine(helpers,"tesseract"),Path.Combine(helpers,"python"),Environment.GetEnvironmentVariable("PATH")});
        lock(job) {
            if(job.Cancelled)throw new IOException();
            job.Process=Process.Start(start);job.Tree=new ProcessTree(job.Process);
        }
        using(var deadline=new System.Threading.Timer(_=>{lock(job){job.Cancelled=true;if(job.Tree!=null)job.Tree.Dispose();}},null,operation=="office-export"||operation=="office-import"?180000:System.Threading.Timeout.Infinite,System.Threading.Timeout.Infinite)) {
        var error=job.Process.StandardError.BaseStream.CopyToAsync(Stream.Null);
        job.Process.StandardInput.WriteLine(header);job.Process.StandardInput.Close();
        Dictionary<string,object> result=null;string line;
        while((line=job.Process.StandardOutput.ReadLine())!=null) {
            if(line.Length>65536)throw new IOException();
            var message=new JavaScriptSerializer {MaxJsonLength=65536}.Deserialize<Dictionary<string,object>>(line);
            if(message.ContainsKey("type")&&(string)message["type"]=="result")result=message;
            if(message.ContainsKey("code")&&(string)message["code"]=="UNSUPPORTED_DOCUMENT_STRUCTURE")throw new UnsupportedStructure();
            if(message.ContainsKey("code")&&(string)message["code"]=="OFFICE_EXTERNAL_RESOURCE")throw new OfficeExternalResource();
            if(message.ContainsKey("code")&&(string)message["code"]=="INSPECTION_ACCESS_DENIED")throw new InspectionDenied();
        }
        job.Process.WaitForExit();error.GetAwaiter().GetResult();
        if(job.Process.ExitCode!=0||result==null)throw new IOException();
        return result;
        }
    }
    private sealed class UnsupportedStructure : IOException {}
    private sealed class InspectionDenied : IOException {}
    private sealed class OfficeExternalResource : IOException {}
    private void Reply(CoreWebView2WebResourceRequestedEventArgs e,int status,string body) {
        e.Response=environment.CreateWebResourceResponse(new MemoryStream(Encoding.UTF8.GetBytes(body)),status,status==200?"OK":"Worker unavailable","Content-Type: application/json\r\nCache-Control: no-store\r\n");
    }
    internal static string Quote(string value) {return "\""+value.Replace("\"","\\\"")+"\"";}
    internal void CancelAll() {
        Job[] owned;lock(jobs){owned=jobs.Values.ToArray();jobs.Clear();}
        foreach(var job in owned){try{job.Dispose();}catch(IOException){}}
    }
    public void Dispose() {disposed=true;core.WebResourceRequested-=Resource;CancelAll();}
    // Closing the job handle terminates Java and every private OCR descendant, including abrupt cancellation.
    private sealed class ProcessTree : IDisposable {
        [StructLayout(LayoutKind.Sequential)] struct Basic {internal long Time1,Time2;internal uint Flags;internal UIntPtr Min,Max;internal uint Active;internal UIntPtr Affinity;internal uint Priority,Scheduling;}
        [StructLayout(LayoutKind.Sequential)] struct Io {internal ulong A,B,C,D,E,F;}
        [StructLayout(LayoutKind.Sequential)] struct Extended {internal Basic Limits;internal Io Counters;internal UIntPtr ProcessMemory,JobMemory,PeakProcess,PeakJob;}
        [DllImport("kernel32.dll",CharSet=CharSet.Unicode)] static extern IntPtr CreateJobObject(IntPtr attributes,string name);
        [DllImport("kernel32.dll")] static extern bool SetInformationJobObject(IntPtr job,int type,ref Extended info,int length);
        [DllImport("kernel32.dll")] static extern bool AssignProcessToJobObject(IntPtr job,IntPtr process);
        [DllImport("kernel32.dll")] static extern bool CloseHandle(IntPtr handle);
        private IntPtr handle;
        internal ProcessTree(Process process) {handle=CreateJobObject(IntPtr.Zero,null);var info=new Extended();info.Limits.Flags=0x2000;if(handle==IntPtr.Zero||!SetInformationJobObject(handle,9,ref info,Marshal.SizeOf(typeof(Extended)))||!AssignProcessToJobObject(handle,process.Handle)){process.Kill();Dispose();throw new IOException("Cannot own worker descendants.");}}
        public void Dispose(){if(handle!=IntPtr.Zero){CloseHandle(handle);handle=IntPtr.Zero;}}
    }
}
