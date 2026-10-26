import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.swing.JOptionPane;

/** Owns one private backend and its desktop window; window exit stops the server. */
public class SootanLauncher {
    private static Process backend;
    private static URI address;

    public static void main(String[] args) {
        try { run(args); }
        catch (Exception ex) {
            if (backend != null) backend.destroy();
            JOptionPane.showMessageDialog(null, ex.getMessage(), "Sootan PDF Editor", JOptionPane.ERROR_MESSAGE);
        }
    }

    static Path defaultDataPath(Path localAppData) {
        return localAppData.resolve("Sootan PDF Editor Data");
    }

    private static void run(String[] args) throws Exception {
        Path app = Path.of(SootanLauncher.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getParent();
        Path home = app.getParent();
        Path data = defaultDataPath(Path.of(System.getenv("LOCALAPPDATA")));
        String override = System.getenv("SOOTAN_USER_DATA");
        if (override != null && !override.isBlank()) data = Path.of(override).toAbsolutePath();
        Files.createDirectories(data);
        Path state = data.resolve("running.txt");
        Path host = app.resolve("SootanHost.exe");
        if (List.of(args).contains("--stop")) {
            if (Files.exists(state)) new ProcessBuilder(host.toString(), "--stop", data.toString()).start().waitFor();
            return;
        }
        try (FileChannel channel = FileChannel.open(data.resolve("launcher.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            FileLock lock = channel.tryLock();
            if (lock == null) {
                List<String> activate = new ArrayList<>(List.of(host.toString(), "--activate", data.toString()));
                activate.addAll(List.of(args));
                new ProcessBuilder(activate).start().waitFor();
                return;
            }
            try (lock) {
                int port;
                Path portFile = data.resolve("port.txt");
                int preferred = Files.exists(portFile) ? Integer.parseInt(Files.readString(portFile).trim()) : 0;
                try (ServerSocket socket = new ServerSocket(preferred, 0, InetAddress.getByName("127.0.0.1"))) { port = socket.getLocalPort(); }
                Files.writeString(portFile, String.valueOf(port));
                address = URI.create("http://127.0.0.1:" + port + "/");
                Path temp = data.resolve("temp"); Files.createDirectories(temp);
                List<String> command = new ArrayList<>(List.of(home.resolve("runtime/bin/java.exe").toString(),
                    "--enable-native-access=ALL-UNNAMED", "-Djava.io.tmpdir=" + temp,
                    "-jar", app.resolve("sootan.jar").toString(), "--server.address=127.0.0.1", "--server.port=" + port,
                    "--system.tessdataDir=" + app.resolve("helpers/tesseract/tessdata"),
                    "--system.customPaths.operations.ocrmypdf=" + app.resolve("helpers/bin/ocrmypdf.exe")));
                ProcessBuilder process = new ProcessBuilder(command);
                process.directory(data.toFile());
                var env = process.environment();
                env.put("STIRLING_BASE_PATH", data.toString());
                env.put("STIRLING_TEMPFILES_DIRECTORY", temp.toString());
                env.put("PYTHONDONTWRITEBYTECODE", "1");
                env.put("TESSDATA_PREFIX", app.resolve("helpers/tesseract/tessdata").toString());
                env.put("PATH", app.resolve("helpers/bin") + ";" + app.resolve("helpers/tesseract") + ";" + app.resolve("helpers/python") + ";" + env.getOrDefault("PATH", ""));
                process.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(data.resolve("application.log").toFile()));
                backend = process.start();
                Files.writeString(state, address + "\n" + backend.pid() + "\n");
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    backend.descendants().forEach(ProcessHandle::destroy);
                    backend.destroy();
                    try { backend.waitFor(8, TimeUnit.SECONDS); Files.deleteIfExists(state); }
                    catch (Exception ignored) { }
                }));
                HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
                long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
                boolean ready = false;
                while (backend.isAlive() && System.nanoTime() < deadline) {
                    try {
                        var response = client.send(HttpRequest.newBuilder(address.resolve("api/v1/info/status")).timeout(Duration.ofSeconds(2)).build(), HttpResponse.BodyHandlers.discarding());
                        if (response.statusCode() == 200) { ready = true; break; }
                    } catch (Exception ignored) { }
                    TimeUnit.MILLISECONDS.sleep(200);
                }
                if (!ready) throw new IllegalStateException("The local PDF server did not start. See " + data.resolve("application.log"));
                List<String> desktop = new ArrayList<>(List.of(host.toString(), address.toString(), data.toString(), String.valueOf(backend.pid())));
                desktop.addAll(List.of(args));
                Process window = new ProcessBuilder(desktop).start();
                window.waitFor();
                backend.descendants().forEach(ProcessHandle::destroy);
                backend.destroy();
                if (!backend.waitFor(8, TimeUnit.SECONDS)) backend.destroyForcibly();
                Files.deleteIfExists(state);
            }
        }
    }

}
