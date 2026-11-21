import io.github.saefalasalynbtel198.shcport.core.ElfInterp;
import io.github.saefalasalynbtel198.shcport.core.RuntimeArchive;
import io.github.saefalasalynbtel198.shcport.core.RuntimeLayout;

import java.io.File;

/**
 * CI helper: extracts a runtime package with the app's own extractor (core/RuntimeArchive.java).
 * With a third argument "patch-interp" it also sets Box64's ELF interpreter to the packaged loader,
 * exactly as the app's installer does (core/ElfInterp.java), so Box64 can be executed directly.
 */
public final class ExtractRuntime {
    public static void main(String[] args) throws Exception {
        File target = new File(args[1]);
        RuntimeArchive.Manifest m = RuntimeArchive.extract(new File(args[0]), target, null);
        System.out.println("extracted runtime " + m.version + ": " + m.executables.size()
                + " executables, " + m.symlinks.size() + " symlinks");
        if (args.length > 2 && "patch-interp".equals(args[2])) {
            RuntimeLayout rt = new RuntimeLayout(target);
            ElfInterp.patch(rt.box64(), rt.loader().getPath());
            System.out.println("box64 PT_INTERP = " + ElfInterp.read(rt.box64()));
        }
    }
}
