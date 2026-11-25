package io.github.saefalasalynbtel198.shcport.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Map;

/**
 * Puts a {@link WineLaunchPlan}'s files in place, all inside the Wine prefix: the D: symlink to the game folder,
 * cnc-ddraw's ddraw.dll in C:\windows\syswow64 (hash-checked against the runtime's copy) and the generated
 * ddraw.ini. The game folder itself is only read.
 */
public final class GameSetup {

    public static final class Result {
        public final boolean ok;
        public final String report;

        Result(boolean ok, String report) {
            this.ok = ok;
            this.report = report;
        }
    }

    private GameSetup() {}

    public static Result prepare(WineLaunchPlan plan, CncDdrawConfig config) {
        StringBuilder r = new StringBuilder();
        boolean ok = true;
        if (!new File(plan.gameDir, plan.exeName).isFile()) {
            r.append("game executable missing: ").append(new File(plan.gameDir, plan.exeName)).append('\n');
            ok = false;
        }
        for (Map.Entry<String, String> e : plan.dosDeviceLinks.entrySet()) {
            Path link = new File(e.getKey()).toPath();
            try {
                if (Files.exists(link, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(link)) {
                    throw new IOException("exists and is not a symbolic link");
                }
                Files.deleteIfExists(link);
                Files.createSymbolicLink(link, new File(e.getValue()).toPath());
                r.append("drive link ").append(link.getFileName()).append(" -> ").append(Files.readSymbolicLink(link)).append(": ok\n");
            } catch (IOException | UnsupportedOperationException ex) {
                r.append("drive link ").append(link).append(": FAILED (").append(ex.getMessage()).append(")\n");
                ok = false;
            }
        }
        try {
            if (!plan.ddrawSource.isFile()) throw new IOException("runtime has no " + plan.ddrawSource);
            File sys = plan.ddrawTarget.getParentFile();
            if (sys == null || !sys.isDirectory()) throw new IOException("no " + sys + " (Wine prefix incomplete?)");
            Files.copy(plan.ddrawSource.toPath(), plan.ddrawTarget.toPath(), StandardCopyOption.REPLACE_EXISTING);
            String a = sha256(plan.ddrawSource), b = sha256(plan.ddrawTarget);
            if (!a.equals(b)) throw new IOException("copy differs from the runtime's file");
            r.append("cnc-ddraw ddraw.dll -> ").append(plan.ddrawTarget).append(" (SHA-256 ").append(a).append("): ok\n");
        } catch (IOException e) {
            r.append("cnc-ddraw ddraw.dll: FAILED (").append(e.getMessage()).append(")\n");
            ok = false;
        }
        try {
            File dir = plan.ddrawIni.getParentFile();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
            String ini = config.toIni();
            Files.write(plan.ddrawIni.toPath(), ini.getBytes(StandardCharsets.US_ASCII));
            if (!ini.equals(new String(Files.readAllBytes(plan.ddrawIni.toPath()), StandardCharsets.US_ASCII))) {
                throw new IOException("read-back differs");
            }
            r.append("ddraw.ini -> ").append(plan.ddrawIni).append(" (").append(WineLaunchPlan.DDRAW_INI_DOS)
                    .append(", renderer=").append(config.get("renderer")).append("): ok\n");
        } catch (IOException e) {
            r.append("ddraw.ini: FAILED (").append(e.getMessage()).append(")\n");
            ok = false;
        }
        return new Result(ok, r.toString());
    }

    public static String sha256(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            StringBuilder sb = new StringBuilder();
            for (byte x : md.digest()) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }
}
