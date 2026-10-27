using System;
using Microsoft.Web.WebView2.Core;
class SootanHostTests {
    static void Main() {
        string origin="http://127.0.0.1:12345";
        string root=System.IO.Path.GetFullPath("bundle");
        foreach(string candidate in new[]{"https://example.com/private.txt","https://sootan.local/%2e%2e%5cprivate.txt","https://sootan.local/C:%5cWindows%5cwin.ini"})
            if(SootanHost.BundledPath(root,candidate)!=null)throw new Exception("Bundled route escaped root");
        if(SootanHost.BundledPath(root,"https://sootan.local/read")!=System.IO.Path.Combine(root,"index.html"))throw new Exception("SPA route unavailable");
        if(!SootanHost.DownloadFilter("sidecar.zip").StartsWith("ZIP archives"))throw new Exception("ZIP offered as PDF");
        if(!SootanHost.DownloadFilter("document.pdf").StartsWith("PDF documents"))throw new Exception("PDF filter missing");
        if(SootanHost.DownloadFilter("text.txt").Contains("*.pdf"))throw new Exception("Non-PDF extension changed");
        if(!SootanHost.IsTrustedOrigin(origin+"/editor",origin))throw new Exception("Trusted editor blocked");
        foreach(string candidate in new[]{"https://example.com/", "file:///C:/Windows/", "http://localhost:12345/", "http://127.0.0.1:54321/", "data:text/html,hello"})
            if(SootanHost.IsTrustedOrigin(candidate,origin))throw new Exception("Untrusted origin accepted");
        Console.WriteLine("Native origin boundary: PASS; shared WebView2 runtime="+CoreWebView2Environment.GetAvailableBrowserVersionString());
    }
}
