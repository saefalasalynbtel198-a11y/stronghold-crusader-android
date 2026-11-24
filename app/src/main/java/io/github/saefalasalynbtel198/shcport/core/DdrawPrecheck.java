package io.github.saefalasalynbtel198.shcport.core;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Verdict of the DirectDraw pre-check (the runtime's shc-gui-smoke.exe in "ddraw" mode, run with the game's Wine
 * setup). The whole log is read line by line, however large it is (0.3.7 read only its last 2 MB and missed the
 * +loaddll line). PASS needs all of:
 * <ul>
 *   <li>exit code 42 (the program's success code: it ran its frame loop to the end);</li>
 *   <li>Wine's +loaddll line for ddraw.dll ending in ": native" (the file in the prefix, i.e. cnc-ddraw; with
 *       ddraw=n Wine's builtin cannot be used);</li>
 *   <li>the program's own report: ddraw.dll loaded from the 32-bit system directory, DirectDrawCreate done,
 *       SetDisplayMode and CreateSurface(primary) returning 0x00000000, at least one frame written;</li>
 *   <li>its four colours seen in Xvfb's framebuffer (checked by the caller).</li>
 * </ul>
 */
public final class DdrawPrecheck {

    private static final Pattern FRAMES = Pattern.compile("gui-smoke: done, (\\d+) frames written");

    public boolean ok;
    public int exitCode;
    public boolean pixelsSeen;
    public String pixels = "not seen";
    /** Wine's +loaddll line for ddraw.dll (null if none), its line number, and whether it says native. */
    public String loadLine;
    public long loadLineNumber;
    public boolean nativeLoad;
    /** Path reported by the program (GetModuleFileName of the loaded ddraw.dll), or "". */
    public String programDdrawPath = "";
    public boolean createSurfaceOk, displayModeOk;
    public int frames;
    public long logBytes, logLines;
    /** Every "gui-smoke:" line (the program's stdout) and every err: line from Wine (at most 40). */
    public final List<String> programLines = new ArrayList<>();
    public final List<String> wineErrors = new ArrayList<>();
    public final List<String> missing = new ArrayList<>();

    private DdrawPrecheck() {}

    public static DdrawPrecheck evaluate(File log, int exitCode, boolean pixelsSeen, String pixels) {
        DdrawPrecheck r = new DdrawPrecheck();
        r.exitCode = exitCode;
        r.pixelsSeen = pixelsSeen;
        if (pixels != null) r.pixels = pixels;
        r.logBytes = log == null ? 0 : log.length();
        if (log != null && log.isFile()) {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(log), StandardCharsets.UTF_8), 1 << 16)) {
                String line;
                while ((line = in.readLine()) != null) r.line(line);
            } catch (IOException e) {
                r.missing.add("log not readable: " + e.getMessage());
            }
        }
        r.decide();
        return r;
    }

    /** Same verdict from log text (unit tests). */
    public static DdrawPrecheck evaluate(String logText, int exitCode, boolean pixelsSeen, String pixels) {
        DdrawPrecheck r = new DdrawPrecheck();
        r.exitCode = exitCode;
        r.pixelsSeen = pixelsSeen;
        if (pixels != null) r.pixels = pixels;
        r.logBytes = logText == null ? 0 : logText.length();
        if (logText != null) for (String l : logText.split("\n")) r.line(l);
        r.decide();
        return r;
    }

    private void line(String raw) {
        logLines++;
        String l = raw.trim();
        int g = l.indexOf("gui-smoke:");
        if (g >= 0) {
            l = l.substring(g);
            if (programLines.size() < 40) programLines.add(l);
            if (l.startsWith("gui-smoke: ddraw.dll = ")) programDdrawPath = l.substring("gui-smoke: ddraw.dll = ".length()).trim();
            if (l.startsWith("gui-smoke: CreateSurface(primary) 0x00000000")) createSurfaceOk = true;
            if (l.startsWith("gui-smoke: SetDisplayMode(1024,768,16) 0x00000000")) displayModeOk = true;
            Matcher m = FRAMES.matcher(l);
            if (m.find()) frames = Integer.parseInt(m.group(1));
            return;
        }
        String low = l.toLowerCase(Locale.ROOT);
        if (loadLine == null || !nativeLoad) {
            String one = WineLaunchPlan.loadLine(l, "ddraw.dll", false);
            if (one != null) {
                loadLine = one;
                loadLineNumber = logLines;
                nativeLoad = low.endsWith(": native");
            }
        }
        if (low.contains(":err:") && wineErrors.size() < 40) wineErrors.add(l);
    }

    private void decide() {
        if (exitCode != 42) missing.add("exit code 42 (got " + exitCode + ")");
        if (loadLine == null) missing.add("Wine +loaddll line for ddraw.dll");
        else if (!nativeLoad) missing.add("native load of ddraw.dll (Wine: " + loadLine + ")");
        String p = programDdrawPath.toLowerCase(Locale.ROOT);
        if (!(p.endsWith("\\system32\\ddraw.dll") || p.endsWith("\\syswow64\\ddraw.dll"))) {
            missing.add("ddraw.dll from the 32-bit system directory (program reported: " + (p.isEmpty() ? "nothing" : programDdrawPath) + ")");
        }
        if (!displayModeOk) missing.add("SetDisplayMode(1024,768,16) = 0x00000000");
        if (!createSurfaceOk) missing.add("CreateSurface(primary) = 0x00000000");
        if (frames <= 0) missing.add("frames written > 0");
        if (!pixelsSeen) missing.add("the four DirectDraw colours in the framebuffer");
        ok = missing.isEmpty();
    }

    /** One line for the report and the screen. */
    public String summary() {
        return (ok ? "PASS" : "FAILED") + String.format(Locale.ROOT,
                " (exit %d; Wine: %s; program: ddraw.dll = %s, SetDisplayMode %s, CreateSurface(primary) %s, %d frames; pixels: %s)",
                exitCode,
                loadLine == null ? "NO +loaddll line for ddraw.dll in the log" : nativeLoad ? "loaded native (cnc-ddraw)" : "loaded NOT native",
                programDdrawPath.isEmpty() ? "not reported" : programDdrawPath,
                displayModeOk ? "OK" : "not OK", createSurfaceOk ? "OK" : "not OK", frames, pixels)
                + (ok ? "" : "; missing: " + String.join("; ", missing));
    }

    /** Detail lines for the report: the evidence behind the verdict. */
    public List<String> details() {
        List<String> d = new ArrayList<>();
        d.add("Log: " + logBytes + " bytes, " + logLines + " lines (read completely)");
        d.add("Wine load line for ddraw.dll" + (loadLine == null ? ": none" : " (line " + loadLineNumber + "): " + loadLine));
        d.add("Program output:");
        for (String l : programLines) d.add("  " + l);
        if (programLines.isEmpty()) d.add("  (none)");
        d.add("Wine errors: " + (wineErrors.isEmpty() ? "none" : wineErrors.size()));
        for (String l : wineErrors) d.add("  " + l);
        return d;
    }
}
