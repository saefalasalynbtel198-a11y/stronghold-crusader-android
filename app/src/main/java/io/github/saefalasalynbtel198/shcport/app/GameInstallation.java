package io.github.saefalasalynbtel198.shcport.app;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import io.github.saefalasalynbtel198.shcport.core.GameBuildClassifier;
import io.github.saefalasalynbtel198.shcport.core.GameDirectoryValidator;
import io.github.saefalasalynbtel198.shcport.core.PeInfo;
import io.github.saefalasalynbtel198.shcport.core.PeInspector;

/** Snapshot of what is currently imported. Building one reads and hashes the executables (slow: call off the UI thread). */
public final class GameInstallation {

    public static final class Executable {
        public final String name;
        public final PeInfo pe;
        public final GameBuildClassifier.Result classification;
        public final String error;

        Executable(String name, PeInfo pe, GameBuildClassifier.Result classification, String error) {
            this.name = name;
            this.pe = pe;
            this.classification = classification;
            this.error = error;
        }
    }

    public final boolean present;
    public final GameDirectoryValidator.Report report;
    public final List<Executable> executables;

    private GameInstallation(boolean present, GameDirectoryValidator.Report report, List<Executable> exes) {
        this.present = present;
        this.report = report;
        this.executables = exes;
    }

    public static GameInstallation inspect(File gameDir) {
        if (!gameDir.isDirectory()) return new GameInstallation(false, null, new ArrayList<Executable>());
        GameDirectoryValidator.Report report = GameDirectoryValidator.validate(GameDirectoryValidator.fileTree(gameDir));
        List<Executable> exes = new ArrayList<>();
        for (String name : report.executables) {
            try {
                PeInfo pe = PeInspector.inspect(new File(gameDir, name));
                exes.add(new Executable(name, pe, GameBuildClassifier.classify(pe), null));
            } catch (Exception e) {
                exes.add(new Executable(name, null, null, e.getMessage()));
            }
        }
        return new GameInstallation(true, report, exes);
    }

    /** First executable the runtime could attempt to start, or null. */
    public Executable launchCandidate() {
        if (report == null || !report.isValid()) return null;
        for (Executable e : executables) {
            if (e.classification != null && e.classification.canAttemptLaunch()) return e;
        }
        return null;
    }

    public String describe() {
        if (!present) return "No game imported yet.";
        StringBuilder sb = new StringBuilder();
        for (Executable e : executables) {
            sb.append("• ").append(e.name).append(": ");
            if (e.error != null) {
                sb.append("cannot read (").append(e.error).append(")\n");
            } else {
                sb.append(e.classification.label).append(" — ").append(e.classification.support).append('\n');
                sb.append("   ").append(e.classification.explanation).append('\n');
                sb.append("   SHA-1 ").append(e.pe.sha1).append('\n');
            }
        }
        sb.append('\n').append("Asset check: ").append(report.isValid() ? "complete" : "incomplete").append('\n');
        for (String err : report.errors) sb.append("  ✗ ").append(err).append('\n');
        for (String w : report.warnings) sb.append("  ! ").append(w).append('\n');
        return sb.toString().trim();
    }
}
