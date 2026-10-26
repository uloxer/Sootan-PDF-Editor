using System;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Text;

class OcrHelper {
    static string Quote(string arg) {
        var output = new StringBuilder("\"");
        int slashes = 0;
        foreach (char c in arg) {
            if (c == '\\') { slashes++; continue; }
            if (c == '"') output.Append('\\', slashes * 2 + 1);
            else output.Append('\\', slashes);
            output.Append(c); slashes = 0;
        }
        output.Append('\\', slashes * 2).Append('"');
        return output.ToString();
    }
    static int Main(string[] args) {
        var python = Path.GetFullPath(Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "..", "python", "python.exe"));
        var start = new ProcessStartInfo(python, "-m ocrmypdf " + string.Join(" ", args.Select(Quote))) {
            UseShellExecute = false, CreateNoWindow = true, RedirectStandardOutput = true, RedirectStandardError = true
        };
        using (var process = Process.Start(start)) {
            var output = process.StandardOutput.BaseStream.CopyToAsync(Console.OpenStandardOutput());
            var error = process.StandardError.BaseStream.CopyToAsync(Console.OpenStandardError());
            process.WaitForExit(); System.Threading.Tasks.Task.WaitAll(output, error);
            return process.ExitCode;
        }
    }
}
