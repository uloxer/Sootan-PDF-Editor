using System;
using Microsoft.Web.WebView2.Core;
class SootanHostTests {
    static void Main() {
        string origin="http://127.0.0.1:12345";
        if(!SootanHost.IsTrustedOrigin(origin+"/editor",origin))throw new Exception("Trusted editor blocked");
        foreach(string candidate in new[]{"https://example.com/", "file:///C:/Windows/", "http://localhost:12345/", "http://127.0.0.1:54321/", "data:text/html,hello"})
            if(SootanHost.IsTrustedOrigin(candidate,origin))throw new Exception("Untrusted origin accepted");
        Console.WriteLine("Native origin boundary: PASS; shared WebView2 runtime="+CoreWebView2Environment.GetAvailableBrowserVersionString());
    }
}
