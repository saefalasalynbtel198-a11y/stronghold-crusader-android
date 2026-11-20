package io.github.saefalasalynbtel198.shcport.core;

/**
 * Decides whether an imported game executable is a build this port targets.
 *
 * <p>The port targets the DRM-free Stronghold Crusader HD 1.41 release (GOG / Steam). That is also
 * the reference binary of the OpenSHC reimplementation project, which matters for future work.
 * Disc-era builds wrapped in SecuROM cannot run under the Wine/Box64 runtime because the copy
 * protection depends on Windows kernel drivers; the launcher reports them as unsupported and does
 * not attempt to work around the protection.
 */
public final class GameBuildClassifier {

    public enum Support {
        /** Exact, hash-verified target build. */
        SUPPORTED,
        /** Looks like an HD 1.4x build but the hash is not in the verified list yet. */
        LIKELY_SUPPORTED,
        /** Valid 32-bit Windows executable of unknown provenance; may or may not work. */
        UNKNOWN,
        /** Recognised but cannot run on this port. */
        UNSUPPORTED
    }

    public static final class Result {
        public final Support support;
        public final String label;
        public final String explanation;

        Result(Support support, String label, String explanation) {
            this.support = support;
            this.label = label;
            this.explanation = explanation;
        }

        public boolean canAttemptLaunch() {
            return support == Support.SUPPORTED || support == Support.LIKELY_SUPPORTED
                    || support == Support.UNKNOWN;
        }
    }

    /**
     * SHA-1 of "Stronghold Crusader.exe" 1.41 (Latin), as published by the OpenSHC project as its
     * reference binary.
     */
    public static final String SHA1_HD_141_LATIN = "012e9d55dac04b23ed9a334c975d3a5b6287020b";

    /**
     * Executables whose fingerprints were reported from an owner's GOG installation but could not be
     * matched against any public, independent record. They are recognised by name only and stay UNKNOWN.
     * Key: SHA-256. Value: label.
     */
    static final java.util.Map<String, String> OWNER_REPORTED = new java.util.HashMap<>();
    static {
        OWNER_REPORTED.put("d32e983d52a4f6dd7ef10007872513be6cb87261b6c0c69660bebc1df411e89e",
                "GOG installation as reported by owner: Stronghold Crusader.exe, built 2012-10-02 (pre-1.4 HD release)");
        OWNER_REPORTED.put("7e9b8450ed822272bb2a7004b599fe3fbff25dd636cf8da905ccffa80646eea7",
                "GOG installation as reported by owner: Stronghold_Crusader_Extreme.exe, built 2012-10-02 (pre-1.4 HD release)");
    }

    private GameBuildClassifier() {}

    public static Result classify(PeInfo pe) {
        if (pe.machine != PeInfo.MACHINE_I386) {
            return new Result(Support.UNSUPPORTED, "Not a 32-bit x86 executable",
                    "Expected a 32-bit Windows executable; found machine type " + pe.machineName() + ".");
        }
        if (SHA1_HD_141_LATIN.equalsIgnoreCase(pe.sha1)) {
            return new Result(Support.SUPPORTED, "Stronghold Crusader HD 1.41 (Latin)",
                    "Hash matches the OpenSHC reference build. This is the build the port targets.");
        }
        String reported = pe.sha256 == null ? null : OWNER_REPORTED.get(pe.sha256.toLowerCase(java.util.Locale.ROOT));
        if (reported != null && !pe.hasSection(".securom")) {
            return new Result(Support.UNKNOWN, reported + " — not independently verified",
                    "These hashes have no public, independent reference, so the build is not marked verified. Its build "
                            + "date (2012) and version strings (1, 1, 0, 1) indicate GOG's original 2012 HD release, not the "
                            + "current 1.41(a). Updating the game in GOG (Galaxy or the latest offline installer) gives 1.41.");
        }
        if (pe.hasSection(".securom")) {
            return new Result(Support.UNSUPPORTED,
                    "Disc-era build with SecuROM sections (file version " + pe.fileVersionString() + ", built " + pe.buildDate() + ")",
                    "This executable belongs to the SecuROM-protected 2008 disc release. Its copy "
                            + "protection relies on Windows kernel components that Wine cannot provide, "
                            + "so it is not a supported target. Install the DRM-free Stronghold Crusader "
                            + "HD 1.41 from GOG or Steam and import that folder instead.");
        }
        String textVersion = pe.versionStrings.get("FileVersion");
        if (looksLikeHd14(pe.fileVersion, textVersion)) {
            return new Result(Support.LIKELY_SUPPORTED,
                    "Stronghold Crusader HD " + describeVersion(pe, textVersion) + " (unverified hash)",
                    "Version information indicates an HD 1.4x build, but this exact file is not in the verified "
                            + "list yet. Please send its build fingerprint (Copy build fingerprint) so it can be verified.");
        }
        return new Result(Support.UNKNOWN,
                "DRM-free build, not yet verified (" + describeVersion(pe, textVersion) + ", built " + pe.buildDate() + ")",
                "No copy protection sections were found, but this exact executable has not been verified. Use "
                        + "Copy build fingerprint and send it to the developers so the build can be identified.");
    }

    static boolean looksLikeHd14(int[] fixed, String text) {
        if (fixed != null && fixed[0] == 1 && fixed[1] >= 4) return true;
        if (text == null) return false;
        String t = text.replace(" ", "").replace(',', '.');
        return t.startsWith("1.4");
    }

    private static String describeVersion(PeInfo pe, String textVersion) {
        String fixed = "file version " + pe.fileVersionString();
        return textVersion == null || textVersion.isEmpty() ? fixed : fixed + ", text version \"" + textVersion + "\"";
    }
}
