package io.github.saefalasalynbtel198.shcport.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Starts Xvfb on the first usable display of a range and waits until it is ready, without a lock file
 * (BUILD 3 step 3.2). Plain Java: the app, the unit tests and the CI qemu job run this same code.
 *
 * <p>Protocol: Xvfb is started directly (no shell) with {@code -displayfd 1}. When it accepts connections it
 * writes the display number and "\n" to its stdout and closes stdout (os/connection.c NotifyParentProcess).
 * stdout is therefore reserved for that line: Xvfb's messages go to stderr, which the caller keeps separate.
 * Anything else on stdout (another text, a number for another display, an over-long line, a number without
 * "\n") is rejected and the process stopped; output after the line is recorded for the report.
 *
 * <p>Display choice without lock files: a display whose socket already answers is skipped; if Xvfb itself refuses
 * the display as in use (bind failed), the next one is tried; every other failure is reported and stops the search.
 */
public final class XvfbStarter {
    /** Bound on the readiness line: a display number has at most 5 digits. */
    public static final int MAX_LINE = 16;
    /** Bound on output recorded after the readiness line. */
    public static final int MAX_EXTRA = 2048;

    /** What the starter needs from its environment (Android app, CI command line, unit tests). */
    public interface Host {
        /** True if a client can already connect to the display (another X server owns it). */
        boolean displayAnswers(int display);

        /** Starts Xvfb for the display: stdout must be a pipe (Process#getInputStream), stderr kept separately. */
        Process launch(int display) throws IOException;

        /** Xvfb's stderr output of the attempt on this display. */
        String errorLog(int display);

        void log(String line);
    }

    public enum Outcome { READY, INVALID, CLOSED, TIMEOUT }

    /** Reads Xvfb's stdout on its own thread: the readiness line, then whatever follows until end of stream. */
    public static final class ReadyReader {
        private final ByteArrayOutputStream line = new ByteArrayOutputStream();
        private final ByteArrayOutputStream extra = new ByteArrayOutputStream();
        private boolean lineDone;      // "\n" seen, or the line grew too long
        private boolean tooLong;
        private boolean eof;
        private long extraBytes;
        private String readError;

        public ReadyReader(InputStream in, String threadName) {
            Thread t = new Thread(() -> run(in), threadName);
            t.setDaemon(true);
            t.start();
        }

        private void run(InputStream in) {
            byte[] buf = new byte[256];
            try {
                int n;
                while ((n = in.read(buf)) >= 0) {
                    synchronized (this) {
                        for (int i = 0; i < n; i++) take(buf[i]);
                        notifyAll();
                    }
                }
            } catch (IOException e) {
                synchronized (this) { readError = e.getMessage(); }
            } finally {
                try { in.close(); } catch (IOException ignored) { }
                synchronized (this) { eof = true; notifyAll(); }
            }
        }

        private void take(byte b) {
            if (!lineDone) {
                if (b == '\n') { lineDone = true; return; }
                line.write(b);
                if (line.size() > MAX_LINE) { lineDone = true; tooLong = true; }
                return;
            }
            extraBytes++;
            if (extra.size() < MAX_EXTRA) extra.write(b);
        }

        /** Waits for the readiness line, end of stream or the timeout. */
        public synchronized Outcome await(long timeoutMs) throws InterruptedException {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (!lineDone && !eof) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) return Outcome.TIMEOUT;
                wait(Math.min(left, 200));
            }
            if (lineDone) return !tooLong && parseDisplayNumber(lineText()) >= 0 ? Outcome.READY : Outcome.INVALID;
            return line.size() > 0 ? Outcome.INVALID : Outcome.CLOSED;   // bytes without "\n", or nothing at all
        }

        public synchronized String lineText() { return new String(line.toByteArray(), StandardCharsets.UTF_8); }

        /** Waits up to timeoutMs for end of stream (Xvfb closes stdout after the line); true if reached. */
        public synchronized boolean awaitClosed(long timeoutMs) throws InterruptedException {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (!eof) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) return false;
                wait(Math.min(left, 200));
            }
            return true;
        }

        public synchronized boolean closed() { return eof; }

        public synchronized long extraBytes() { return extraBytes; }

        public synchronized String extraText() { return printable(new String(extra.toByteArray(), StandardCharsets.UTF_8)); }

        public synchronized String readError() { return readError; }

        /** One line for the report: what happened on stdout after the display number. */
        public synchronized String afterLineSummary() {
            String s = eof ? "closed after the display number" : "still open";
            if (extraBytes > 0) s += "; UNEXPECTED " + extraBytes + " byte(s) after the display number: \"" + extraText() + "\"";
            if (readError != null) s += "; read error: " + readError;
            return s;
        }
    }

    /** Result of {@link #start}: either a running, ready Xvfb or the reason why there is none. */
    public static final class Result {
        public final int display;
        public final Process process;
        public final ReadyReader stdout;
        public final long readyMs;
        public final String error;
        /** One entry per display looked at, in order (for the report). */
        public final List<String> attempts;

        Result(int display, Process process, ReadyReader stdout, long readyMs, String error, List<String> attempts) {
            this.display = display;
            this.process = process;
            this.stdout = stdout;
            this.readyMs = readyMs;
            this.error = error;
            this.attempts = attempts;
        }

        public boolean ok() { return error == null; }
    }

    private XvfbStarter() {}

    /** The display number in a -displayfd line: digits only (no sign, spaces or "\r"), at most 5, else -1. */
    public static int parseDisplayNumber(String line) {
        if (line == null || line.isEmpty() || line.length() > 5) return -1;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c < '0' || c > '9') return -1;
        }
        return Integer.parseInt(line);
    }

    public static Result start(Host host, int first, int last, long timeoutMs) throws InterruptedException {
        List<String> attempts = new ArrayList<>();
        for (int d = first; d <= last; d++) {
            if (host.displayAnswers(d)) {
                attempts.add(":" + d + " already answers (another X server) - skipped");
                continue;
            }
            long t0 = System.currentTimeMillis();
            Process p;
            try {
                p = host.launch(d);
            } catch (IOException e) {
                attempts.add(":" + d + " could not be started: " + e.getMessage());
                return fail("could not start Xvfb: " + e.getMessage(), attempts);
            }
            closeQuietly(p.getOutputStream());   // Xvfb does not read stdin
            ReadyReader r = new ReadyReader(p.getInputStream(), "xvfb-stdout-" + d);
            Outcome o = r.await(timeoutMs);
            long ms = System.currentTimeMillis() - t0;
            switch (o) {
                case READY: {
                    int n = parseDisplayNumber(r.lineText());
                    if (n != d || n < first || n > last) {
                        stop(p);
                        attempts.add(":" + d + " reported display \"" + n + "\"");
                        return fail("Xvfb reported display " + n + " on stdout, expected " + d + " (" + first + "-" + last + ")", attempts);
                    }
                    if (!host.displayAnswers(d)) {
                        stop(p);
                        attempts.add(":" + d + " reported ready, no connection possible");
                        return fail("Xvfb reported display :" + d + " ready, but a client cannot connect to it", attempts);
                    }
                    attempts.add(":" + d + " ready after " + ms + " ms");
                    host.log("Xvfb reported display \"" + n + "\" on stdout after " + ms + " ms");
                    return new Result(d, p, r, ms, null, attempts);
                }
                case INVALID:
                    stop(p);
                    attempts.add(":" + d + " unexpected stdout");
                    return fail("unexpected output on Xvfb's stdout instead of the display number: \""
                            + printable(r.lineText()) + "\"", attempts);
                case TIMEOUT:
                    stop(p);
                    attempts.add(":" + d + " no display number within " + timeoutMs + " ms");
                    return fail("Xvfb did not report ready within " + timeoutMs / 1000 + " s", attempts);
                case CLOSED:
                default: {
                    // stdout closed without a display number: Xvfb ended (or is ending)
                    if (!p.waitFor(5, TimeUnit.SECONDS)) {
                        stop(p);
                        attempts.add(":" + d + " closed stdout without a display number");
                        return fail("Xvfb closed its stdout without reporting a display number", attempts);
                    }
                    int code = p.exitValue();
                    String err = host.errorLog(d);
                    if (X11DisplayPlan.classify(err) == X11DisplayPlan.StartFailure.DISPLAY_IN_USE) {
                        attempts.add(":" + d + " in use (Xvfb could not bind its socket) - next display");
                        continue;
                    }
                    String why = keyLine(err);
                    attempts.add(":" + d + " Xvfb exited with code " + code);
                    return fail("Xvfb exited with code " + code + " (" + RuntimeProbe.describeExit(code) + ")"
                            + (why.isEmpty() ? "" : ": " + why), attempts);
                }
            }
        }
        return fail("no free display between :" + first + " and :" + last, attempts);
    }

    /** SIGTERM, then SIGKILL if it has not ended after 3 s; true if the process has ended. */
    public static boolean stop(Process p) throws InterruptedException {
        if (p == null) return true;
        p.destroy();
        if (p.waitFor(3, TimeUnit.SECONDS)) return true;
        p.destroyForcibly();
        return p.waitFor(2, TimeUnit.SECONDS);
    }

    /** The most telling line of an Xvfb log: the line after "Fatal server error:", else the last (EE) line. */
    static String keyLine(String log) {
        if (log == null) return "";
        String[] lines = log.split("\n");
        for (int i = 0; i < lines.length - 1; i++) {
            if (lines[i].contains("Fatal server error")) return clean(lines[i + 1]);
        }
        for (int i = lines.length - 1; i >= 0; i--) {
            String l = clean(lines[i]);
            if (!l.isEmpty() && lines[i].startsWith("(EE)")) return l;
        }
        return "";
    }

    private static String clean(String l) { return l.replace("(EE)", "").trim(); }

    static String printable(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c == '\n') b.append("\\n");
            else if (c == '\r') b.append("\\r");
            else if (c < 0x20 || c == 0x7f) b.append(String.format("\\x%02x", (int) c));
            else b.append(c);
        }
        return b.toString();
    }

    private static Result fail(String why, List<String> attempts) { return new Result(-1, null, null, -1, why, attempts); }

    private static void closeQuietly(OutputStream o) {
        try { if (o != null) o.close(); } catch (IOException ignored) { }
    }
}
