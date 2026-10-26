import java.nio.file.Path;
import java.util.Locale;

/** Checks the Windows default even when path spelling differs in case or separator. */
public class SootanLauncherPathTest {
    private static String normalized(Path path) {
        return path.toAbsolutePath().normalize().toString().replace('\\', '/').toLowerCase(Locale.ROOT);
    }
    public static void main(String[] args) {
        for (String base : new String[] {"C:\\Users\\Example User\\AppData\\Local", "c:/users/EXAMPLE USER/AppData/Local/"}) {
            Path local = Path.of(base);
            String data = normalized(SootanLauncher.defaultDataPath(local));
            String program = normalized(local.resolve("Sootan PDF Editor"));
            if (!data.endsWith("/sootan pdf editor data") || data.equals(program) || data.startsWith(program + "/")) {
                throw new AssertionError("Writable data must be outside installer-owned directory");
            }
        }
        System.out.println("Default data path is outside installer tree (Windows case/separator variants): PASS");
    }
}
