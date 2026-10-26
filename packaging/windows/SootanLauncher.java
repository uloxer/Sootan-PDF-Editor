import java.awt.Desktop;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
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
import javax.imageio.ImageIO;
import javax.swing.JOptionPane;

/** Owns the per-user backend process, browser entry and tray exit. */
public class SootanLauncher {
    private static Process backend;
    private static URI address;
    private static TrayIcon tray;

    public static void main(String[] args) {
        try { run(args); }
        catch (Exception ex) {
            if (backend != null) backend.destroy();
            JOptionPane.showMessageDialog(null, ex.getMessage(), "Sootan PDF Editor", JOptionPane.ERROR_MESSAGE);
        }
    }

    private static void run(String[] args) throws Exception {
        Path app = Path.of(SootanLauncher.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getParent();
        Path home = app.getParent();
        Path data = Path.of(System.getenv("LOCALAPPDATA"), "Sootan PDF Editor");
        String override = System.getenv("SOOTAN_USER_DATA");
        if (override != null && !override.isBlank()) data = Path.of(override).toAbsolutePath();
        Files.createDirectories(data);
        Path state = data.resolve("running.txt");
        if (List.of(args).contains("--stop")) {
            if (Files.exists(state)) {
                List<String> values = Files.readAllLines(state);
                long pid = Long.parseLong(values.get(1));
                ProcessHandle.of(pid).filter(p -> p.info().command().map(Path::of).map(path -> path.equals(home.resolve("runtime/bin/java.exe"))).orElse(false)).ifPresent(ProcessHandle::destroy);
            }
            return;
        }
        try (FileChannel channel = FileChannel.open(data.resolve("launcher.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            FileLock lock = channel.tryLock();
            if (lock == null) {
                if (Files.exists(state)) Desktop.getDesktop().browse(URI.create(Files.readAllLines(state).get(0)));
                return;
            }
            try (lock) {
                int port;
                try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))) { port = socket.getLocalPort(); }
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
                installTray(app);
                if (!"true".equalsIgnoreCase(env.get("SOOTAN_NO_BROWSER"))) Desktop.getDesktop().browse(address);
                backend.waitFor();
                if (tray != null) SystemTray.getSystemTray().remove(tray);
                Files.deleteIfExists(state);
            }
        }
    }

    private static void installTray(Path app) throws Exception {
        if (!SystemTray.isSupported()) return;
        Image image = ImageIO.read(app.resolve("sootan.png").toFile());
        PopupMenu menu = new PopupMenu();
        MenuItem open = new MenuItem("Open Sootan PDF Editor");
        open.addActionListener(event -> { try { Desktop.getDesktop().browse(address); } catch (Exception ignored) { } });
        MenuItem quit = new MenuItem("Quit Sootan PDF Editor");
        quit.addActionListener(event -> backend.destroy());
        menu.add(open); menu.addSeparator(); menu.add(quit);
        tray = new TrayIcon(image, "Sootan PDF Editor", menu); tray.setImageAutoSize(true);
        SystemTray.getSystemTray().add(tray);
    }
}
