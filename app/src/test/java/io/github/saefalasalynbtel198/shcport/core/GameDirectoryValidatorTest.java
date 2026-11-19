package io.github.saefalasalynbtel198.shcport.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

public class GameDirectoryValidatorTest {

    /** In-memory tree: directories map to child names. */
    static final class MemTree implements GameDirectoryValidator.Tree {
        final Map<String, List<String>> children = new HashMap<>();
        final Set<String> dirs = new HashSet<>();

        MemTree() { dirs.add(""); children.put("", new ArrayList<String>()); }

        MemTree file(String path) { add(path, false); return this; }
        MemTree dir(String path) { add(path, true); return this; }

        MemTree files(String dir, String prefix, String ext, int count) {
            for (int i = 0; i < count; i++) file(dir + "/" + prefix + i + ext);
            return this;
        }

        private void add(String path, boolean isDir) {
            int slash = path.lastIndexOf('/');
            String parent = slash < 0 ? "" : path.substring(0, slash);
            if (!dirs.contains(parent)) add(parent, true);
            String name = slash < 0 ? path : path.substring(slash + 1);
            List<String> list = children.get(parent);
            if (!list.contains(name)) list.add(name);
            if (isDir) {
                dirs.add(path);
                if (!children.containsKey(path)) children.put(path, new ArrayList<String>());
            }
        }

        @Override public List<String> list(String relDir) {
            List<String> l = children.get(relDir);
            return l == null ? Collections.<String>emptyList() : l;
        }

        @Override public boolean isDirectory(String relPath) { return dirs.contains(relPath); }
    }

    static MemTree completeInstall() {
        return new MemTree()
                .file("Stronghold Crusader.exe")
                .file("Stronghold_Crusader_Extreme.exe")
                .file("binkw32.dll")
                .file("Mss32.dll")
                .files("gm", "anim_", ".gm1", 150)
                .files("gfx", "tile_", ".tgx", 120)
                .files("fx", "sfx_", ".wav", 300)
                .files("fx/music", "track", ".raw", 10)
                .files("fx/speech", "line", ".wav", 10)
                .files("maps", "map", ".map", 20)
                .files("mapsextreme", "x", ".map", 20)
                .files("binks", "intro", ".bik", 3)
                .files("aiv", "castle", ".aiv", 5);
    }

    @Test
    public void completeInstallIsValidWithoutWarnings() {
        GameDirectoryValidator.Report r = GameDirectoryValidator.validate(completeInstall());
        assertTrue(r.toString(), r.isValid());
        assertEquals(2, r.executables.size());
        assertTrue(r.warnings.isEmpty());
    }

    @Test
    public void lookupsAreCaseInsensitive() {
        MemTree t = new MemTree()
                .file("STRONGHOLD CRUSADER.EXE").file("BINKW32.DLL").file("mss32.dll")
                .files("GM", "a", ".GM1", 100).files("Gfx", "b", ".TGX", 100)
                .files("FX", "c", ".WAV", 100).files("Maps", "d", ".MAP", 1);
        GameDirectoryValidator.Report r = GameDirectoryValidator.validate(t);
        assertTrue(r.toString(), r.isValid());
        assertEquals("STRONGHOLD CRUSADER.EXE", r.executables.get(0));
        assertFalse(r.warnings.isEmpty()); // optional folders are missing
    }

    @Test
    public void missingExecutableAndAssetsAreErrors() {
        MemTree t = new MemTree().file("binkw32.dll").files("gm", "a", ".gm1", 5);
        GameDirectoryValidator.Report r = GameDirectoryValidator.validate(t);
        assertFalse(r.isValid());
        assertTrue(r.errors.size() >= 4);
    }

    @Test
    public void directoryNamedLikeExecutableIsIgnored() {
        MemTree t = completeInstall();
        MemTree bad = new MemTree().dir("Stronghold Crusader.exe").file("binkw32.dll").file("Mss32.dll")
                .files("gm", "a", ".gm1", 100).files("gfx", "b", ".tgx", 100)
                .files("fx", "c", ".wav", 100).files("maps", "d", ".map", 1);
        assertTrue(GameDirectoryValidator.validate(t).isValid());
        assertFalse(GameDirectoryValidator.validate(bad).isValid());
    }

    @Test
    public void resolveDirHandlesNestedPaths() {
        MemTree t = completeInstall();
        assertEquals("fx/music", GameDirectoryValidator.resolveDir(t, "FX/Music"));
        assertNull(GameDirectoryValidator.resolveDir(t, "fx/none"));
    }
}
