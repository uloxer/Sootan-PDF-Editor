using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.IO.Pipes;
using System.Linq;
using System.Security.AccessControl;
using System.Security.Principal;
using System.Text;
using System.Threading;
using System.Windows.Forms;
using System.Web.Script.Serialization;
using System.Runtime.InteropServices;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.WinForms;

internal sealed class SootanHost : Form {
    private readonly WebView2 web = new WebView2();
    private readonly string origin, data, pipeName;
    private readonly JavaScriptSerializer json = new JavaScriptSerializer();
    private readonly List<string> pending = new List<string>();
    private readonly Dictionary<string,string> grants = new Dictionary<string,string>();
    private readonly Dictionary<string,Stream> streams = new Dictionary<string,Stream>();
    private bool ready, approvedClose;
    private CoreWebView2Environment environment;
    private Process backend;
    private readonly string bundle;

    [DllImport("user32.dll")] private static extern bool SetProcessDpiAwarenessContext(IntPtr context);
    internal static void ConfigureDpi() { SetProcessDpiAwarenessContext(new IntPtr(-4)); }

    [STAThread] public static int Main(string[] args) {
        try {
            if (args.Length < 2) return 2;
            string data = Path.GetFullPath(args[1]);
            string pipe = PipeName(data);
            if (args[0] == "--activate" || args[0] == "--stop") {
                using (var client = new NamedPipeClientStream(".",pipe,PipeDirection.Out)) {
                    client.Connect(120000);
                    using(var writer = new StreamWriter(client)) writer.WriteLine(new JavaScriptSerializer().Serialize(new { stop=args[0]=="--stop", files=args.Skip(2).ToArray() }));
                }
                return 0;
            }
            string version = CoreWebView2Environment.GetAvailableBrowserVersionString();
            if (args[0] == "--runtime-version") { Console.WriteLine(version); return 0; }
            ConfigureDpi();
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            Application.Run(args[0]=="--offline"
                ? new SootanHost("https://sootan.local",data,0,args.Skip(3).ToArray(),args[2])
                : new SootanHost(args[0],data,int.Parse(args[2]),args.Skip(3).ToArray()));
            return 0;
        } catch(Exception ex) {
            MessageBox.Show("Sootan PDF Editor could not start.\n\n"+ex.Message,"Sootan PDF Editor",MessageBoxButtons.OK,MessageBoxIcon.Error);
            return 1;
        }
    }
    private static string PipeName(string directory) {
        using(var hash=System.Security.Cryptography.SHA256.Create())
            return "SootanPDF-"+BitConverter.ToString(hash.ComputeHash(Encoding.UTF8.GetBytes(directory.ToUpperInvariant()))).Replace("-","").Substring(0,24);
    }
    private SootanHost(string url,string directory,int backendPid,string[] files,string bundlePath=null) {
        bundle=bundlePath==null?null:Path.GetFullPath(bundlePath);
        origin=new Uri(url).GetLeftPart(UriPartial.Authority);
        if(bundle==null&&new Uri(origin).Host!="127.0.0.1") throw new InvalidOperationException("Only the private loopback editor is allowed.");
        data=directory;pipeName=PipeName(data);
        Directory.CreateDirectory(data);
        AutoScaleDimensions=new SizeF(96,96);AutoScaleMode=AutoScaleMode.Dpi;
        Text="Sootan PDF Editor";Width=1440;Height=960;MinimumSize=new Size(960,640);
        Icon=new Icon(Path.Combine(AppDomain.CurrentDomain.BaseDirectory,"sootan.ico"));
        web.Dock=DockStyle.Fill;Controls.Add(web);
        pending.AddRange(files);
        if(backendPid>0) {
            backend=Process.GetProcessById(backendPid);backend.EnableRaisingEvents=true;
            backend.Exited+=(sender,e)=> { if(IsHandleCreated) BeginInvoke(new Action(()=>{approvedClose=true;Close();})); };
        }
        Shown+=async(sender,e)=> {
            try {
                string debug=Environment.GetEnvironmentVariable("SOOTAN_DESKTOP_DEBUG_PORT");int debugPort;
                string options=int.TryParse(debug,out debugPort)&&debugPort>1024&&debugPort<65536 ? "--remote-debugging-address=127.0.0.1 --remote-debugging-port="+debugPort : "";
                environment=await CoreWebView2Environment.CreateAsync(null,Path.Combine(data,"WebView2"),new CoreWebView2EnvironmentOptions(options));
                await web.EnsureCoreWebView2Async(environment);
                var core=web.CoreWebView2;core.Settings.AreHostObjectsAllowed=false;core.Settings.AreBrowserAcceleratorKeysEnabled=false;core.Settings.IsStatusBarEnabled=false;core.Settings.AreDevToolsEnabled=options.Length>0;
                core.NavigationStarting+=(s,n)=> { if(!Trusted(n.Uri)) n.Cancel=true; };
                core.NewWindowRequested+=(s,n)=> {n.Handled=true;if(n.IsUserInitiated&&External(n.Uri))Process.Start(n.Uri);};
                core.PermissionRequested+=(s,p)=>p.State=CoreWebView2PermissionState.Deny;
                core.WebMessageReceived+=Message;
                if(bundle!=null) {
                    await core.AddScriptToExecuteOnDocumentCreatedAsync("window.__SOOTAN_RUNTIME__={kind:'offline-desktop',serverOperations:false,nativeFileDialogs:true,renderer:'wasm'};");
                    core.AddWebResourceRequestedFilter("*",CoreWebView2WebResourceContext.All);
                    core.WebResourceRequested+=BundledResource;
                }
                core.AddWebResourceRequestedFilter(origin+"/__sootan_selected/*",CoreWebView2WebResourceContext.All);
                core.WebResourceRequested+=SelectedResource;
                core.DownloadStarting+=(s,d)=> {
                    d.Handled=true;
                    var deferral=d.GetDeferral();
                    BeginInvoke(new Action(()=> {
                        try {
                            using(var dialog=new SaveFileDialog {FileName=Path.GetFileName(d.ResultFilePath),Filter=DownloadFilter(d.ResultFilePath),DefaultExt=Path.GetExtension(d.ResultFilePath).TrimStart('.'),OverwritePrompt=true}) {
                                if(dialog.ShowDialog(this)==DialogResult.OK)d.ResultFilePath=dialog.FileName;else d.Cancel=true;
                            }
                        }finally{deferral.Complete();}
                    }));
                };
                web.Source=new Uri(origin+"/");
                var thread=new Thread(Listen){IsBackground=true};thread.Start();
            } catch(Exception ex){MessageBox.Show(this,ex.Message,"Sootan PDF Editor",MessageBoxButtons.OK,MessageBoxIcon.Error);approvedClose=true;Close();}
        };
        FormClosing+=(sender,e)=> {
            if(ready&&!approvedClose){e.Cancel=true;web.CoreWebView2.PostWebMessageAsJson("{\"type\":\"close\"}");}
        };
        FormClosed+=(sender,e)=>{foreach(var stream in streams.Values)stream.Dispose();web.Dispose();if(backend!=null)backend.Dispose();};
    }
    protected override bool ProcessCmdKey(ref System.Windows.Forms.Message message,Keys keys) {
        if(keys==(Keys.Control|Keys.O)){OpenFiles();return true;}
        return base.ProcessCmdKey(ref message,keys);
    }
    internal static string BundledPath(string root,string address) {
        var uri=new Uri(address);
        if(uri.GetLeftPart(UriPartial.Authority)!="https://sootan.local")return null;
        string relative=Uri.UnescapeDataString(uri.AbsolutePath).TrimStart('/').Replace('/',Path.DirectorySeparatorChar);
        if(relative.Contains(":"))return null;
        string prefix=Path.GetFullPath(root).TrimEnd(Path.DirectorySeparatorChar)+Path.DirectorySeparatorChar;
        string result=Path.GetFullPath(Path.Combine(prefix,relative));
        if(!result.StartsWith(prefix,StringComparison.OrdinalIgnoreCase))return null;
        if(File.Exists(result))return result;
        return Path.HasExtension(relative)?null:Path.Combine(prefix,"index.html");
    }
    private static string ContentType(string path) {
        switch(Path.GetExtension(path).ToLowerInvariant()) {
            case ".html":return "text/html; charset=utf-8";case ".js":case ".mjs":return "text/javascript";
            case ".css":return "text/css";case ".json":return "application/json";case ".wasm":return "application/wasm";
            case ".svg":return "image/svg+xml";case ".png":return "image/png";case ".jpg":case ".jpeg":return "image/jpeg";
            case ".woff2":return "font/woff2";case ".woff":return "font/woff";default:return "application/octet-stream";
        }
    }
    private void BundledResource(object sender,CoreWebView2WebResourceRequestedEventArgs e) {
        if(Trusted(e.Request.Uri)&&new Uri(e.Request.Uri).AbsolutePath.StartsWith("/__sootan_selected/"))return;
        string path=BundledPath(bundle,e.Request.Uri);
        if(path==null||e.Request.Method!="GET"||new Uri(e.Request.Uri).AbsolutePath.StartsWith("/api/")) {
            e.Response=environment.CreateWebResourceResponse(null,404,"Offline resource unavailable","");return;
        }
        try {
            var stream=new FileStream(path,FileMode.Open,FileAccess.Read,FileShare.Read);
            e.Response=environment.CreateWebResourceResponse(stream,200,"OK","Content-Type: "+ContentType(path)+"\r\nCache-Control: no-cache\r\n");
        }
        catch(IOException){e.Response=environment.CreateWebResourceResponse(null,404,"Not Found","");}
    }
    internal static string DownloadFilter(string path) {
        string extension=Path.GetExtension(path).ToLowerInvariant();
        if(extension==".pdf")return "PDF documents (*.pdf)|*.pdf|All files (*.*)|*.*";
        if(extension==".zip")return "ZIP archives (*.zip)|*.zip|All files (*.*)|*.*";
        return "All files (*.*)|*.*";
    }
    internal static bool IsTrustedOrigin(string address, string expected) {
        Uri uri;return Uri.TryCreate(address,UriKind.Absolute,out uri)&&uri.GetLeftPart(UriPartial.Authority)==expected;
    }
    private bool Trusted(string address) { return IsTrustedOrigin(address,origin); }
    private static bool External(string address) {
        Uri uri;return Uri.TryCreate(address,UriKind.Absolute,out uri)&&(uri.Scheme=="https"||uri.Scheme=="http");
    }
    private void OpenFiles() {
        using(var dialog=new OpenFileDialog {Filter="PDF documents (*.pdf)|*.pdf",Multiselect=true,CheckFileExists=true})
            if(dialog.ShowDialog(this)==DialogResult.OK) {pending.AddRange(dialog.FileNames);SendPending();}
    }
    private void SendPending() {
        if(!ready||pending.Count==0)return;
        var selected=new List<object>();
        foreach(string path in pending.ToArray()) {
            if(!File.Exists(path)||!string.Equals(Path.GetExtension(path),".pdf",StringComparison.OrdinalIgnoreCase))continue;
            string token=Guid.NewGuid().ToString("N");grants[token]=Path.GetFullPath(path);
            selected.Add(new {name=Path.GetFileName(path),token=token,lastModified=(long)(File.GetLastWriteTimeUtc(path)-new DateTime(1970,1,1,0,0,0,DateTimeKind.Utc)).TotalMilliseconds,url=origin+"/__sootan_selected/"+token});
        }
        pending.Clear();if(selected.Count>0)web.CoreWebView2.PostWebMessageAsJson(json.Serialize(new {type="files",files=selected}));
    }
    private void Message(object sender,CoreWebView2WebMessageReceivedEventArgs e) {
        if(!Trusted(e.Source)||!Trusted(web.Source.AbsoluteUri))return;
        try {
            var message=json.Deserialize<Dictionary<string,object>>(e.WebMessageAsJson);
            string type=message.ContainsKey("type")?message["type"] as string:null;
            if(type=="ready"){ready=true;SendPending();}
            if(type=="open")BeginInvoke(new Action(OpenFiles));
            if(type=="close-approved"){approvedClose=true;BeginInvoke(new Action(Close));}
            if(type=="release"&&message.ContainsKey("token")) {
                string token=message["token"] as string;Stream stream;
                if(token!=null){grants.Remove(token);if(streams.TryGetValue(token,out stream)){streams.Remove(token);stream.Dispose();}}
            }
        }catch(Exception ex){File.AppendAllText(Path.Combine(data,"desktop.log"),ex.Message+Environment.NewLine);}
    }
    private void SelectedResource(object sender,CoreWebView2WebResourceRequestedEventArgs e) {
        string resourcePath=new Uri(e.Request.Uri).AbsolutePath;
        if(!Trusted(e.Request.Uri)||!resourcePath.StartsWith("/__sootan_selected/",StringComparison.Ordinal))return;
        string token=resourcePath.Substring("/__sootan_selected/".Length);string path;
        if(e.Request.Method!="GET"||!grants.TryGetValue(token,out path)||streams.ContainsKey(token)) {
            e.Response=environment.CreateWebResourceResponse(null,404,"Not Found","");return;
        }
        try {
            var stream=new FileStream(path,FileMode.Open,FileAccess.Read,FileShare.Read);streams[token]=stream;
            e.Response=environment.CreateWebResourceResponse(stream,200,"OK","Content-Type: application/pdf\r\nCache-Control: no-store\r\n");
        }catch {e.Response=environment.CreateWebResourceResponse(null,404,"Not Found","");}
    }
    private void Listen() {
        while(!IsDisposed) {
            try {
                var security=new PipeSecurity();security.SetAccessRuleProtection(true,false);security.AddAccessRule(new PipeAccessRule(WindowsIdentity.GetCurrent().User,PipeAccessRights.FullControl,AccessControlType.Allow));
                using(var server=new NamedPipeServerStream(pipeName,PipeDirection.In,1,PipeTransmissionMode.Byte,PipeOptions.None,4096,4096,security)) {
                    server.WaitForConnection();string line;using(var reader=new StreamReader(server))line=reader.ReadLine();
                    var message=new JavaScriptSerializer().Deserialize<Dictionary<string,object>>(line);
                    BeginInvoke(new Action(()=> {
                        if(WindowState==FormWindowState.Minimized)WindowState=FormWindowState.Normal;Activate();
                        if(message.ContainsKey("stop")&&(bool)message["stop"])Close();
                        else if(message.ContainsKey("files")){foreach(object value in (System.Collections.IEnumerable)message["files"])pending.Add((string)value);SendPending();}
                    }));
                }
            }catch(Exception){if(IsDisposed)return;}
        }
    }
}
