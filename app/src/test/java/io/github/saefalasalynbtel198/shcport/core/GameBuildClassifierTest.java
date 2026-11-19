package io.github.saefalasalynbtel198.shcport.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;

import org.junit.Test;

public class GameBuildClassifierTest {

    private static PeInfo pe(int machine, String sha1, int[] version, String... sections) {
        return new PeInfo(machine, 0, Arrays.asList(sections), version, 1000, sha1, "x");
    }

    @Test
    public void verifiedHashIsSupported() {
        GameBuildClassifier.Result r = GameBuildClassifier.classify(
                pe(PeInfo.MACHINE_I386, GameBuildClassifier.SHA1_HD_141_LATIN.toUpperCase(), null, ".text"));
        assertEquals(GameBuildClassifier.Support.SUPPORTED, r.support);
        assertTrue(r.canAttemptLaunch());
    }

    @Test
    public void secuRomBuildIsUnsupported() {
        GameBuildClassifier.Result r = GameBuildClassifier.classify(
                pe(PeInfo.MACHINE_I386, "abc", new int[] {1, 1, 0, 1}, ".text", ".data", ".securom"));
        assertEquals(GameBuildClassifier.Support.UNSUPPORTED, r.support);
        assertFalse(r.canAttemptLaunch());
        assertTrue(r.label.contains("1.1.0.1"));
    }

    @Test
    public void hdVersionResourceIsLikelySupported() {
        GameBuildClassifier.Result r = GameBuildClassifier.classify(
                pe(PeInfo.MACHINE_I386, "abc", new int[] {1, 41, 0, 0}, ".text", ".rsrc"));
        assertEquals(GameBuildClassifier.Support.LIKELY_SUPPORTED, r.support);
    }

    @Test
    public void nonX86IsUnsupported() {
        GameBuildClassifier.Result r = GameBuildClassifier.classify(
                pe(PeInfo.MACHINE_AMD64, "abc", null, ".text"));
        assertEquals(GameBuildClassifier.Support.UNSUPPORTED, r.support);
    }

    @Test
    public void otherBuildsAreUnknown() {
        GameBuildClassifier.Result r = GameBuildClassifier.classify(
                pe(PeInfo.MACHINE_I386, "abc", new int[] {1, 2, 0, 0}, ".text"));
        assertEquals(GameBuildClassifier.Support.UNKNOWN, r.support);
        assertTrue(r.canAttemptLaunch());
    }
}
