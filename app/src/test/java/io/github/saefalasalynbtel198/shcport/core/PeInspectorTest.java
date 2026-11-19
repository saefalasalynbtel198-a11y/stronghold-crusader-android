package io.github.saefalasalynbtel198.shcport.core;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

import org.junit.Test;

public class PeInspectorTest {

    /** Builds a tiny but structurally valid PE image with the given sections. */
    static byte[] syntheticPe(int machine, String[] sections, int[] version) {
        int peOffset = 0x40;
        int sectionTable = peOffset + 24;
        int dataStart = 0x200;
        int rsrcSize = 0x100;
        ByteBuffer b = ByteBuffer.allocate(dataStart + rsrcSize * sections.length).order(ByteOrder.LITTLE_ENDIAN);
        b.put(0, (byte) 'M').put(1, (byte) 'Z');
        b.putInt(0x3C, peOffset);
        b.put(peOffset, (byte) 'P').put(peOffset + 1, (byte) 'E');
        b.putShort(peOffset + 4, (short) machine);
        b.putShort(peOffset + 6, (short) sections.length);
        b.putInt(peOffset + 8, 0x48289A0B);
        b.putShort(peOffset + 20, (short) 0); // no optional header in this synthetic image
        for (int i = 0; i < sections.length; i++) {
            int base = sectionTable + i * 40;
            byte[] name = sections[i].getBytes();
            for (int j = 0; j < Math.min(8, name.length); j++) b.put(base + j, name[j]);
            int raw = dataStart + i * rsrcSize;
            b.putInt(base + 16, rsrcSize);
            b.putInt(base + 20, raw);
            if (sections[i].equals(".rsrc") && version != null) {
                int p = raw + 0x20;
                b.putInt(p, 0xFEEF04BD);
                b.putInt(p + 4, 0x00010000);
                b.putInt(p + 8, (version[0] << 16) | version[1]);
                b.putInt(p + 12, (version[2] << 16) | version[3]);
            }
        }
        return b.array();
    }

    static File write(byte[] data) throws IOException {
        File f = File.createTempFile("pe-test", ".exe");
        f.deleteOnExit();
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(data);
        }
        return f;
    }

    @Test
    public void readsMachineSectionsAndVersion() throws IOException {
        File f = write(syntheticPe(PeInfo.MACHINE_I386, new String[] {".text", ".rsrc"}, new int[] {1, 41, 0, 0}));
        PeInfo info = PeInspector.inspect(f);
        assertEquals(PeInfo.MACHINE_I386, info.machine);
        assertEquals(Arrays.asList(".text", ".rsrc"), info.sectionNames);
        assertArrayEquals(new int[] {1, 41, 0, 0}, info.fileVersion);
        assertEquals("1.41.0.0", info.fileVersionString());
        assertEquals(40, info.sha1.length());
        assertEquals(64, info.sha256.length());
        assertTrue(info.hasSection(".RSRC"));
        assertFalse(info.hasSection(".securom"));
    }

    @Test
    public void missingVersionResourceGivesNull() throws IOException {
        File f = write(syntheticPe(PeInfo.MACHINE_I386, new String[] {".text"}, null));
        PeInfo info = PeInspector.inspect(f);
        assertNull(info.fileVersion);
        assertEquals("unknown", info.fileVersionString());
    }

    @Test
    public void rejectsNonPeFiles() throws IOException {
        byte[] junk = new byte[256];
        junk[0] = 'Z';
        try {
            PeInspector.inspect(write(junk));
            fail("expected PeFormatException");
        } catch (PeInspector.PeFormatException expected) {
            // ok
        }
    }

    @Test
    public void rejectsTruncatedSectionTable() throws IOException {
        byte[] pe = syntheticPe(PeInfo.MACHINE_I386, new String[] {".text"}, null);
        byte[] truncated = Arrays.copyOf(pe, 0x50);
        try {
            PeInspector.inspect(write(truncated));
            fail("expected PeFormatException");
        } catch (PeInspector.PeFormatException expected) {
            // ok
        }
    }

    @Test
    public void hexEncodesBytes() {
        assertEquals("00ff10", PeInspector.hex(new byte[] {0, (byte) 0xFF, 0x10}));
    }
}
