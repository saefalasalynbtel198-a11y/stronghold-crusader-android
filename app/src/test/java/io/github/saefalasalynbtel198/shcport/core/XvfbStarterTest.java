package io.github.saefalasalynbtel198.shcport.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** The Xvfb readiness protocol (-displayfd 1) and display selection, with scripted processes. */
public class XvfbStarterTest {

    // ---- parsing -------------------------------------------------------------------------------------------

    @Test
    public void displayNumberIsDigitsOnly() {
        assertEquals(37, XvfbStarter.parseDisplayNumber("37"));
        assertEquals(0, XvfbStarter.parseDisplayNumber("0"));
        assertEquals(65535, XvfbStarter.parseDisplayNumber("65535"));
        for (String bad : new String[] {null, "", " 37", "37 ", "37\r", "+37", "-1", "3 7", "37a", "123456", "0x25", "٣٧"}) {
            assertEquals(String.valueOf(bad), -1, XvfbStarter.parseDisplayNumber(bad));
        }
    }

    @Test
    public void readerReturnsTheLineAndSeesStdoutClose() throws Exception {
        XvfbStarter.ReadyReader r = reader("37\n");
        assertEquals(XvfbStarter.Outcome.READY, r.await(2000));
        assertEquals("37", r.lineText());
        assertTrue(r.awaitClosed(2000));
        assertEquals(0, r.extraBytes());
        assertEquals("closed after the display number", r.afterLineSummary());
    }

    @Test
    public void readerRejectsAnythingButOneNumberLine() throws Exception {
        assertEquals(XvfbStarter.Outcome.INVALID, reader("hello\n37\n").await(2000));
        assertEquals(XvfbStarter.Outcome.INVALID, reader("37\r\n").await(2000));
        assertEquals(XvfbStarter.Outcome.INVALID, reader("37").await(2000));           // no "\n", then EOF
        assertEquals(XvfbStarter.Outcome.INVALID, reader("\n").await(2000));           // empty line
        XvfbStarter.ReadyReader longLine = reader("11111111111111111111111111111111111111");
        assertEquals(XvfbStarter.Outcome.INVALID, longLine.await(2000));
        assertTrue(longLine.lineText().length() <= XvfbStarter.MAX_LINE + 1);
        assertEquals(XvfbStarter.Outcome.CLOSED, reader("").await(2000));
    }

    @Test
    public void readerRecordsOutputAfterTheLine() throws Exception {
        XvfbStarter.ReadyReader r = reader("38\nunexpected\n");
        assertEquals(XvfbStarter.Outcome.READY, r.await(2000));
        assertTrue(r.awaitClosed(2000));
        assertEquals(11, r.extraBytes());
        assertTrue(r.afterLineSummary(), r.afterLineSummary().contains("UNEXPECTED 11 byte(s)"));
        assertTrue(r.afterLineSummary(), r.afterLineSummary().contains("unexpected\\n"));
    }

    @Test
    public void readerTimesOutWhenNothingArrives() throws Exception {
        PipedOutputStream w = new PipedOutputStream();
        XvfbStarter.ReadyReader r = new XvfbStarter.ReadyReader(new PipedInputStream(w), "t");
        long t0 = System.currentTimeMillis();
        assertEquals(XvfbStarter.Outcome.TIMEOUT, r.await(300));
        assertTrue(System.currentTimeMillis() - t0 >= 250);
        assertFalse(r.closed());
        w.write("37\n".getBytes(StandardCharsets.US_ASCII));   // a late line is still read
        w.flush();
        assertEquals(XvfbStarter.Outcome.READY, r.await(2000));
        w.close();
    }

    @Test
    public void keyLineIsTheFatalError() {
        assertEquals("Cannot write display number to fd 3",
                XvfbStarter.keyLine("(EE)\nFatal server error:\n(EE) Cannot write display number to fd 3\n(EE)\n"));
        assertEquals("Linking lock file (./.X37-lock) in place failed: Permission denied",
                XvfbStarter.keyLine("Fatal server error:\n(EE) Linking lock file (./.X37-lock) in place failed: Permission denied\n"));
        assertEquals("", XvfbStarter.keyLine(""));
    }

    // ---- start() ---------------------------------------------------------------------------------------------

    private static final String IN_USE = "_XSERVTransSocketUNIXCreateListener: ...SocketCreateListener() failed\n"
            + "_XSERVTransMakeAllCOTSServerListeners: server already running\n(EE)\nFatal server error:\n"
            + "(EE) Cannot establish any listening sockets - Make sure an X server isn't already running(EE)\n";

    @Test
    public void readyOnTheFirstDisplay() throws Exception {
        FakeHost h = new FakeHost();
        h.script(37, FakeProcess.ready("37\n"));
        XvfbStarter.Result r = XvfbStarter.start(h, 37, 40, 2000);
        assertNull(r.error);
        assertEquals(37, r.display);
        assertTrue(r.process.isAlive());
        assertEquals(List.of(37), h.launched);
        assertTrue(((FakeProcess) r.process).stdinClosed);
        assertTrue(r.stdout.awaitClosed(2000));
        assertTrue(XvfbStarter.stop(r.process));
        assertFalse(r.process.isAlive());
    }

    @Test
    public void skipsDisplaysOwnedByOtherServers() throws Exception {
        FakeHost h = new FakeHost();
        h.answering.add(37);
        h.answering.add(38);
        h.script(39, FakeProcess.ready("39\n"));
        XvfbStarter.Result r = XvfbStarter.start(h, 37, 40, 2000);
        assertEquals(39, r.display);
        assertEquals(List.of(39), h.launched);           // 37 and 38 were never started
        assertTrue(r.attempts.get(0).contains(":37 already answers"));
    }

    @Test
    public void movesOnWhenXvfbRefusesABusyDisplay() throws Exception {
        FakeHost h = new FakeHost();
        h.script(37, FakeProcess.exited(1, IN_USE));
        h.script(38, FakeProcess.exited(1, "(EE) Server is already active for display 38\n"));
        h.script(39, FakeProcess.ready("39\n"));
        XvfbStarter.Result r = XvfbStarter.start(h, 37, 40, 2000);
        assertNull(r.error);
        assertEquals(39, r.display);
        assertEquals(List.of(37, 38, 39), h.launched);
    }

    @Test
    public void allDisplaysBusy() throws Exception {
        FakeHost h = new FakeHost();
        h.answering.add(38);
        h.script(37, FakeProcess.exited(1, IN_USE));
        h.script(39, FakeProcess.exited(1, IN_USE));
        h.script(40, FakeProcess.exited(1, IN_USE));
        XvfbStarter.Result r = XvfbStarter.start(h, 37, 40, 2000);
        assertEquals("no free display between :37 and :40", r.error);
        assertEquals(List.of(37, 39, 40), h.launched);
        assertEquals(4, r.attempts.size());
        assertNull(r.process);
    }

    @Test
    public void otherFailuresAreReportedNotSkipped() throws Exception {
        for (String log : new String[] {
                "Fatal server error:\n(EE) Linking lock file (./.X37-lock) in place failed: Permission denied\n",
                "Fatal server error:\n(EE) Cannot write display number to fd 3\n"}) {
            FakeHost h = new FakeHost();
            h.script(37, FakeProcess.exited(1, log));
            XvfbStarter.Result r = XvfbStarter.start(h, 37, 40, 2000);
            assertTrue(r.error, r.error.startsWith("Xvfb exited with code 1"));
            assertTrue(r.error, r.error.contains(XvfbStarter.keyLine(log)));
            assertEquals(List.of(37), h.launched);         // :38 not tried: this is not a busy display
        }
    }

    @Test
    public void wrongNumberOrGarbageOnStdoutStopsXvfb() throws Exception {
        String[][] cases = {{"38\n", "reported display 38"}, {"Xvfb starting\n", "unexpected output"},
                {"37\r\n", "unexpected output"}, {"99999\n", "reported display 99999"}};
        for (String[] c : cases) {
            FakeHost h = new FakeHost();
            FakeProcess p = FakeProcess.ready(c[0]);
            h.script(37, p);
            XvfbStarter.Result r = XvfbStarter.start(h, 37, 40, 2000);
            assertTrue(c[0] + " -> " + r.error, r.error.contains(c[1]));
            assertTrue(p.destroyed);
            assertFalse(p.isAlive());
            assertEquals(List.of(37), h.launched);
        }
    }

    @Test
    public void timeoutStopsXvfb() throws Exception {
        FakeHost h = new FakeHost();
        FakeProcess p = FakeProcess.silent();
        h.script(37, p);
        XvfbStarter.Result r = XvfbStarter.start(h, 37, 40, 300);
        assertTrue(r.error, r.error.startsWith("Xvfb did not report ready"));
        assertTrue(p.destroyed);
        assertFalse(p.isAlive());
    }

    @Test
    public void readyButNotConnectableIsAnError() throws Exception {
        FakeHost h = new FakeHost();
        h.connectableWhenRunning = false;
        FakeProcess p = FakeProcess.ready("37\n");
        h.script(37, p);
        XvfbStarter.Result r = XvfbStarter.start(h, 37, 40, 2000);
        assertTrue(r.error, r.error.contains("cannot connect"));
        assertFalse(p.isAlive());
    }

    @Test
    public void launchFailureIsReported() throws Exception {
        FakeHost h = new FakeHost();
        XvfbStarter.Result r = XvfbStarter.start(h, 37, 40, 2000);   // nothing scripted: launch throws
        assertTrue(r.error, r.error.startsWith("could not start Xvfb"));
    }

    @Test
    public void stopEscalatesToKill() throws Exception {
        FakeProcess p = FakeProcess.ready("37\n");
        p.ignoresTerm = true;
        assertTrue(XvfbStarter.stop(p));
        assertTrue(p.destroyed);
        assertTrue(p.killed);
        assertFalse(p.isAlive());
    }

    @Test
    public void repeatedStartStopCycles() throws Exception {
        FakeHost h = new FakeHost();
        for (int round = 0; round < 5; round++) {
            h.script(37, FakeProcess.ready("37\n"));
            XvfbStarter.Result r = XvfbStarter.start(h, 37, 40, 2000);
            assertEquals(37, r.display);
            assertTrue(XvfbStarter.stop(r.process));
            assertTrue(h.running.isEmpty());
        }
        assertEquals(5, h.launched.size());
    }

    // ---- fakes -----------------------------------------------------------------------------------------------

    private static XvfbStarter.ReadyReader reader(String s) {
        return new XvfbStarter.ReadyReader(new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8)), "test");
    }

    /** Displays that answer: those owned by "other servers" plus our own running processes. */
    static final class FakeHost implements XvfbStarter.Host {
        final Set<Integer> answering = new HashSet<>();
        final Map<Integer, FakeProcess> scripted = new HashMap<>();
        final Map<Integer, FakeProcess> running = new HashMap<>();
        final List<Integer> launched = new ArrayList<>();
        boolean connectableWhenRunning = true;

        void script(int d, FakeProcess p) { scripted.put(d, p); }

        @Override public boolean displayAnswers(int d) {
            FakeProcess p = running.get(d);
            if (p != null && !p.isAlive()) running.remove(d);
            return answering.contains(d) || (connectableWhenRunning && running.containsKey(d) && running.get(d).isAlive());
        }

        @Override public Process launch(int d) throws IOException {
            FakeProcess p = scripted.remove(d);
            if (p == null) throw new IOException("error=2, No such file or directory");
            launched.add(d);
            running.put(d, p);
            p.onEnd = () -> running.remove(d);
            return p;
        }

        @Override public String errorLog(int d) {
            FakeProcess p = running.get(d);
            return p == null ? lastLog : p.stderr;
        }

        String lastLog = "";

        @Override public void log(String line) { }
    }

    /** A process whose stdout, stderr, lifetime and signal handling are scripted. */
    static final class FakeProcess extends Process {
        private final InputStream stdout;
        final String stderr;
        private boolean alive;
        private int exit;
        boolean destroyed, killed, ignoresTerm, stdinClosed;
        Runnable onEnd = () -> { };

        private FakeProcess(InputStream stdout, String stderr, boolean alive, int exit) {
            this.stdout = stdout;
            this.stderr = stderr;
            this.alive = alive;
            this.exit = exit;
        }

        /** Writes the given stdout bytes, then closes stdout and keeps running (like Xvfb after -displayfd). */
        static FakeProcess ready(String out) {
            return new FakeProcess(new ByteArrayInputStream(out.getBytes(StandardCharsets.UTF_8)), "", true, 0);
        }

        /** Closes stdout without output and has already exited with the given code and stderr. */
        static FakeProcess exited(int code, String err) {
            return new FakeProcess(new ByteArrayInputStream(new byte[0]), err, false, code);
        }

        /** Keeps stdout open without writing anything. */
        static FakeProcess silent() {
            return new FakeProcess(new InputStream() {
                @Override public int read() throws IOException {
                    try { Thread.sleep(60_000); } catch (InterruptedException e) { throw new IOException(e); }
                    return -1;
                }
            }, "", true, 0);
        }

        private synchronized void end(int code) {
            if (!alive) return;
            alive = false;
            exit = code;
            onEnd.run();
            notifyAll();
        }

        @Override public OutputStream getOutputStream() {
            return new ByteArrayOutputStream() {
                @Override public void close() { stdinClosed = true; }
            };
        }

        @Override public InputStream getInputStream() { return stdout; }

        @Override public InputStream getErrorStream() { return new ByteArrayInputStream(stderr.getBytes(StandardCharsets.UTF_8)); }

        @Override public synchronized int waitFor() throws InterruptedException {
            while (alive) wait();
            return exit;
        }

        @Override public synchronized boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            long deadline = System.currentTimeMillis() + unit.toMillis(timeout);
            while (alive) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) return false;
                wait(left);
            }
            return true;
        }

        @Override public synchronized int exitValue() {
            if (alive) throw new IllegalThreadStateException("running");
            return exit;
        }

        @Override public synchronized boolean isAlive() { return alive; }

        @Override public void destroy() {
            destroyed = true;
            if (!ignoresTerm) end(143);
        }

        @Override public Process destroyForcibly() {
            killed = true;
            end(137);
            return this;
        }
    }
}
