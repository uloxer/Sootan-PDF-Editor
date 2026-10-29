using System;
using System.IO;
using System.Threading.Tasks;
using System.Windows.Forms;
using Microsoft.Web.WebView2.WinForms;
using Microsoft.Web.WebView2.Core;
using System.Web.Script.Serialization;

/** One-time offline origin migration; pages have no native filesystem or processing bridge. */
internal sealed class SootanMigration : Form {
    private readonly WebView2 view=new WebView2();
    private readonly string data,assets,legacy;
    private bool complete;
    internal int Result=1;
    internal SootanMigration(string directory,string bundle) {
        data=Path.GetFullPath(directory);assets=Path.GetFullPath(bundle);
        int port;if(!int.TryParse(File.ReadAllText(Path.Combine(data,"port.txt")).Trim(),out port)||port<1024||port>65535)throw new IOException("Invalid legacy origin port.");
        legacy="http://127.0.0.1:"+port;
        if(!File.Exists(Path.Combine(data,"upgrade-backup.json")))throw new IOException("Preserve the old Data/profile with prepare_upgrade.py before migration.");
        Text="Sootan library migration";Width=720;Height=320;view.Dock=DockStyle.Fill;Controls.Add(view);
        Shown+=async(s,e)=> {
            try {
                var env=await CoreWebView2Environment.CreateAsync(null,Path.Combine(data,"WebView2"));
                await view.EnsureCoreWebView2Async(env);var core=view.CoreWebView2;
                core.Settings.AreHostObjectsAllowed=false;core.Settings.AreDevToolsEnabled=false;
                core.PermissionRequested+=(sender,p)=>p.State=CoreWebView2PermissionState.Deny;
                core.NewWindowRequested+=(sender,p)=>p.Handled=true;
                core.NavigationStarting+=(sender,n)=>{if(n.Uri!=legacy+"/__sootan_migrate_source")n.Cancel=true;};
                core.AddWebResourceRequestedFilter("*",CoreWebView2WebResourceContext.All);
                core.WebResourceRequested+=(sender,r)=>{
                    string file=r.Request.Uri==legacy+"/__sootan_migrate_source"?"migration-source.html":r.Request.Uri=="https://sootan.local/__sootan_migrate_target"?"migration-target.html":null;
                    r.Response=env.CreateWebResourceResponse(file==null?null:File.OpenRead(Path.Combine(assets,file)),file==null?403:200,file==null?"Blocked":"OK","Content-Type: text/html; charset=utf-8\r\nCache-Control: no-store\r\n");
                };
                await core.AddScriptToExecuteOnDocumentCreatedAsync("window.__SOOTAN_LEGACY_ORIGIN__="+new JavaScriptSerializer().Serialize(legacy)+";");
                core.WebMessageReceived+=(sender,m)=>{
                    if(m.Source!=legacy+"/__sootan_migrate_source")return;
                    var json=new JavaScriptSerializer();var report=json.DeserializeObject(m.WebMessageAsJson) as System.Collections.Generic.Dictionary<string,object>;
                    if(report==null||!report.ContainsKey("success"))return;
                    bool success=report["success"] is bool&&(bool)report["success"];
                    File.WriteAllText(Path.Combine(data,"origin-migration-report.json"),m.WebMessageAsJson);
                    if(success){File.WriteAllText(Path.Combine(data,"origin-migration-complete.txt"),legacy+" -> https://sootan.local\n");Result=0;complete=true;Close();}
                    else {MessageBox.Show(this,"Migration did not complete. Original data remains intact. See origin-migration-report.json.","Sootan PDF Editor");complete=true;Close();}
                };
                core.Navigate(legacy+"/__sootan_migrate_source");
            }catch(Exception ex){MessageBox.Show(this,ex.Message,"Sootan migration");complete=true;Close();}
        };
        FormClosing+=(s,e)=>{if(!complete&&MessageBox.Show(this,"Cancel migration? Original data is unchanged; a later run resumes matching records.","Sootan migration",MessageBoxButtons.YesNo)!=DialogResult.Yes)e.Cancel=true;};
        FormClosed+=(s,e)=>view.Dispose();
    }
}
