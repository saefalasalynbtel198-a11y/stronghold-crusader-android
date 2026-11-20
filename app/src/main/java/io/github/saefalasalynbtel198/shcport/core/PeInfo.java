package io.github.saefalasalynbtel198.shcport.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Facts read from a Windows PE executable header. Immutable. */
public final class PeInfo {

    /** IMAGE_FILE_MACHINE_I386 – 32-bit x86. */
    public static final int MACHINE_I386 = 0x014c;
    /** IMAGE_FILE_MACHINE_AMD64 – 64-bit x86. */
    public static final int MACHINE_AMD64 = 0x8664;

    public final int machine;
    public final long timeDateStamp;
    public final List<String> sectionNames;
    /** File version from VS_FIXEDFILEINFO as {major, minor, build, revision}, or null if absent. */
    public final int[] fileVersion;
    public final long fileSize;
    public final String sha1;
    public final String sha256;
    /** Text entries of the version resource (FileVersion, ProductVersion, ProductName, ...), may be empty. */
    public final Map<String, String> versionStrings;

    public PeInfo(int machine, long timeDateStamp, List<String> sectionNames, int[] fileVersion,
                  long fileSize, String sha1, String sha256) {
        this(machine, timeDateStamp, sectionNames, fileVersion, fileSize, sha1, sha256, new LinkedHashMap<String, String>());
    }

    public PeInfo(int machine, long timeDateStamp, List<String> sectionNames, int[] fileVersion,
                  long fileSize, String sha1, String sha256, Map<String, String> versionStrings) {
        this.machine = machine;
        this.timeDateStamp = timeDateStamp;
        this.sectionNames = Collections.unmodifiableList(sectionNames);
        this.fileVersion = fileVersion == null ? null : fileVersion.clone();
        this.fileSize = fileSize;
        this.sha1 = sha1;
        this.sha256 = sha256;
        this.versionStrings = Collections.unmodifiableMap(new LinkedHashMap<>(versionStrings));
    }

    /** UTC build date from the PE header, yyyy-MM-dd. */
    public String buildDate() {
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
        f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        return f.format(new java.util.Date(timeDateStamp * 1000L));
    }

    /** Everything needed to identify a build, in one copyable block. */
    public String fingerprint(String fileName) {
        StringBuilder sb = new StringBuilder();
        sb.append("file: ").append(fileName).append('\n');
        sb.append("size: ").append(fileSize).append('\n');
        sb.append("sha1: ").append(sha1).append('\n');
        sb.append("sha256: ").append(sha256).append('\n');
        sb.append("machine: ").append(machineName()).append('\n');
        sb.append(String.format(Locale.ROOT, "pe-timestamp: %d (%s)%n", timeDateStamp, buildDate()));
        sb.append("fixed-version: ").append(fileVersionString()).append('\n');
        for (Map.Entry<String, String> e : versionStrings.entrySet()) {
            sb.append("string ").append(e.getKey()).append(": ").append(e.getValue()).append('\n');
        }
        sb.append("sections: ").append(sectionNames).append('\n');
        return sb.toString();
    }

    public boolean hasSection(String name) {
        for (String s : sectionNames) {
            if (s.equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    public String fileVersionString() {
        if (fileVersion == null) return "unknown";
        return String.format(Locale.ROOT, "%d.%d.%d.%d",
                fileVersion[0], fileVersion[1], fileVersion[2], fileVersion[3]);
    }

    public String machineName() {
        switch (machine) {
            case MACHINE_I386: return "x86 (32-bit)";
            case MACHINE_AMD64: return "x86-64";
            default: return String.format(Locale.ROOT, "unknown (0x%04x)", machine);
        }
    }
}
