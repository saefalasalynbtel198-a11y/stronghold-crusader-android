import io.github.saefalasalynbtel198.shcport.core.RuntimeArchive;

import java.io.File;

/** CI helper: extracts a runtime package with the app's own extractor (core/RuntimeArchive.java). */
public final class ExtractRuntime {
    public static void main(String[] args) throws Exception {
        RuntimeArchive.Manifest m = RuntimeArchive.extract(new File(args[0]), new File(args[1]), null);
        System.out.println("extracted runtime " + m.version + ": " + m.executables.size()
                + " executables, " + m.symlinks.size() + " symlinks");
    }
}
