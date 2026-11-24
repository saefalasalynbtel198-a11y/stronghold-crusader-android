package io.github.saefalasalynbtel198.shcport.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the game's Wine log (WINEDEBUG with +relay for {@link WineLaunchPlan#RELAY_INCLUDE}, +seh, +loaddll, err)
 * and answers the questions a clean but early exit raises: did the game call ExitProcess (with which code, from
 * where), did it create a window, did it show a message box, which files could it not open, which exceptions
 * happened, and whether executable sections could not be mapped (noexec).
 */
public final class GameExitAnalysis {

    private static final Pattern CALL = Pattern.compile("^([0-9a-fA-F]{4}):Call ([\\w.]+)\\((.*)\\) ret=([0-9a-fA-F]+)\\s*$");
    private static final Pattern RET = Pattern.compile("^([0-9a-fA-F]{4}):Ret  ([\\w.]+)\\(\\) retval=([0-9a-fA-F]+) ret=([0-9a-fA-F]+)\\s*$");
    private static final Pattern QUOTED = Pattern.compile("(L?\"(?:[^\"\\\\]|\\\\.)*\")");

    public final List<String> exits = new ArrayList<>();
    public final List<String> messageBoxes = new ArrayList<>();
    public final List<String> failedOpens = new ArrayList<>();
    public final List<String> exceptions = new ArrayList<>();
    public final List<String> processes = new ArrayList<>();
    public final List<String> box64 = new ArrayList<>();
    public int windowsCreated, windowsShown, relayCalls, noexecSections;
    public boolean relayActive;

    private GameExitAnalysis() {}

    public static GameExitAnalysis parse(String log) {
        GameExitAnalysis a = new GameExitAnalysis();
        if (log == null) return a;
        Map<String, String> lastCall = new HashMap<>();   // thread -> "function(args)" of its last relay call
        Set<String> seenExc = new LinkedHashSet<>();
        for (String raw : log.split("\n")) {
            String l = raw.trim();
            Matcher c = CALL.matcher(l);
            if (c.matches()) {
                a.relayActive = true;
                a.relayCalls++;
                String fn = shortName(c.group(2)), args = c.group(3);
                lastCall.put(c.group(1), fn + "(" + args + ")");
                switch (fn) {
                    case "ExitProcess":
                    case "TerminateProcess":
                        add(a.exits, 10, "thread " + c.group(1) + ": " + fn + "(" + args + ") called from " + c.group(4));
                        break;
                    case "MessageBoxA":
                    case "MessageBoxW":
                    case "MessageBoxExA":
                    case "MessageBoxExW":
                        add(a.messageBoxes, 10, fn + ": " + quoted(args));
                        break;
                    case "CreateWindowExA":
                    case "CreateWindowExW":
                        a.windowsCreated++;
                        break;
                    case "ShowWindow":
                        a.windowsShown++;
                        break;
                    case "CreateProcessA":
                    case "CreateProcessW":
                    case "WinExec":
                    case "ShellExecuteA":
                        add(a.processes, 10, fn + ": " + quoted(args));
                        break;
                    default:
                        break;
                }
                continue;
            }
            Matcher r = RET.matcher(l);
            if (r.matches()) {
                String fn = shortName(r.group(2)), call = lastCall.get(r.group(1));
                if ((fn.equals("CreateFileA") || fn.equals("CreateFileW")) && r.group(3).equalsIgnoreCase("ffffffff") && call != null) {
                    add(a.failedOpens, 40, quoted(call.substring(call.indexOf('(') + 1)));
                }
                continue;
            }
            String low = l.toLowerCase(Locale.ROOT);
            if (low.contains(":seh:") && (low.contains("code=") || low.contains("exception"))) {
                String key = l.replaceAll("^[0-9a-fA-F]{4}:", "");
                if (seenExc.add(key)) add(a.exceptions, 15, l);
            } else if (low.contains("noexec filesystem")) {
                a.noexecSections++;
            } else if (low.startsWith("[box64]") || low.contains("unimplemented opcode") || low.contains("sigsegv") || low.contains("sigill")) {
                add(a.box64, 15, l);
            }
        }
        return a;
    }

    /** The lines for the report. */
    public List<String> lines() {
        List<String> out = new ArrayList<>();
        out.add("Executable sections without execute permission (noexec storage): " + noexecSections);
        if (!relayActive) {
            out.add("Relay trace: none in the log (cannot tell ExitProcess, windows or message boxes)");
        } else {
            out.add("ExitProcess/TerminateProcess calls: " + (exits.isEmpty() ? "none seen" : String.join(" | ", exits)));
            out.add("Windows created: " + windowsCreated + ", ShowWindow calls: " + windowsShown);
            out.add("Message boxes: " + (messageBoxes.isEmpty() ? "none" : String.join(" | ", messageBoxes)));
            out.add("Processes started by the game: " + (processes.isEmpty() ? "none" : String.join(" | ", processes)));
            out.add("Files the game could not open (" + failedOpens.size() + (failedOpens.size() >= 40 ? "+" : "") + "): "
                    + (failedOpens.isEmpty() ? "none" : String.join(", ", failedOpens)));
        }
        out.add("Exceptions (+seh): " + (exceptions.isEmpty() ? "none" : exceptions.size() + "\n    " + String.join("\n    ", exceptions)));
        if (!box64.isEmpty()) out.add("Box64 messages:\n    " + String.join("\n    ", box64));
        return out;
    }

    private static String shortName(String f) {
        int i = f.lastIndexOf('.');
        return i >= 0 ? f.substring(i + 1) : f;
    }

    private static String quoted(String args) {
        Matcher m = QUOTED.matcher(args);
        List<String> q = new ArrayList<>();
        while (m.find()) q.add(m.group(1));
        String s = q.isEmpty() ? args : String.join(" ", q);
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }

    private static void add(List<String> l, int max, String s) {
        if (l.size() < max) l.add(s);
    }
}
