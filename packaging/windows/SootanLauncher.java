import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JOptionPane;

/** Starts the native offline window, then exits; advanced Java work is lazy. */
public class SootanLauncher {
    static Path defaultDataPath(Path localAppData) {return localAppData.resolve("Sootan PDF Editor Data");}
    public static void main(String[] args) {
        try {
            Path app=Path.of(SootanLauncher.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getParent();
            String override=System.getenv("SOOTAN_USER_DATA");
            Path data=override==null||override.isBlank()?defaultDataPath(Path.of(System.getenv("LOCALAPPDATA"))):Path.of(override).toAbsolutePath();
            Files.createDirectories(data);Path host=app.resolve("SootanHost.exe");
            if(List.of(args).contains("--stop")){new ProcessBuilder(host.toString(),"--stop",data.toString()).start();return;}
            if(Files.exists(data.resolve("port.txt"))&&!Files.exists(data.resolve("origin-migration-complete.txt"))) {
                if(!Files.exists(data.resolve("upgrade-backup.json")))throw new IllegalStateException("Your existing PDF library needs a one-time offline origin migration. Stop the old app, then preserve its Data/profile using packaging/windows/prepare_upgrade.py before upgrading. Nothing has been overwritten.");
                int code=new ProcessBuilder(host.toString(),"--migrate",data.toString(),app.resolve("migration").toString()).start().waitFor();
                if(code!=0)return;
            }
            List<String> command=new ArrayList<>(List.of(host.toString(),"--launch",data.toString(),app.resolve("ui").toString()));command.addAll(List.of(args));new ProcessBuilder(command).start();
        } catch(Exception ex){JOptionPane.showMessageDialog(null,ex.getMessage(),"Sootan PDF Editor",JOptionPane.ERROR_MESSAGE);}
    }
}
