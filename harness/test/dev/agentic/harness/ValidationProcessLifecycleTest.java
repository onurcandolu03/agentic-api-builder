package dev.agentic.harness;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import java.util.stream.Stream;
import static dev.agentic.harness.RuntimeTest.check;

/** Deterministic lifecycle edge cases; no launch-policy overrides and no external processes. */
public final class ValidationProcessLifecycleTest {
    public static void main(String[] args) throws Exception {
        retainedAfterExit(); replacement(); unavailable(); boundedDiscovery(); blockedPipe();
        System.out.println("PASS 5 validation lifecycle checks: retained handles, replacement, enumeration failure, bounds, blocked pipe");
    }
    private static void retainedAfterExit() throws Exception {
        StubProcess parent = new StubProcess(); Handle child = new Handle(), grandchild = new Handle();
        parent.handle.children = () -> Stream.of(child);
        child.children = () -> parent.isAlive() ? Stream.empty() : Stream.of(grandchild);
        parent.exitOnWait = true;
        var tree = new ValidationRuntime.ProcessTree(parent);
        check(tree.await(1), "normal parent exit");
        check(!parent.isAlive() && child.isAlive(), "child survives parent before terminal cleanup");
        tree.cleanup();
        check(!child.isAlive() && !grandchild.isAlive(), "retained/reparented tree cleaned after parent exit");
        check(tree.view().get("cleanupFailure") == null, "successful retained tree cleanup");
    }
    private static void replacement() throws Exception {
        StubProcess parent = new StubProcess(); Handle original = new Handle(), replacement = new Handle();
        parent.handle.children = () -> Stream.of(original.isAlive() ? original : replacement);
        var tree = new ValidationRuntime.ProcessTree(parent);
        check(!tree.await(0), "timeout fixture");
        tree.cleanup();
        check(!original.isAlive() && !replacement.isAlive() && !parent.isAlive(), "replacement discovered during cleanup");
        check(tree.view().get("observedDescendants").equals(2), "both retained");
    }
    private static void unavailable() throws Exception {
        StubProcess parent = new StubProcess();
        parent.handle.children = () -> { throw new IllegalStateException("raw host secret must not escape"); };
        var tree = new ValidationRuntime.ProcessTree(parent);
        try { tree.await(1); throw new AssertionError("unavailable enumeration accepted"); }
        catch (IllegalStateException expected) {
            check(expected.getMessage().equals("VALIDATION_DESCENDANT_OBSERVATION_UNAVAILABLE"), "sanitized enumeration failure");
        }
        tree.cleanup();
        check(!parent.isAlive(), "parent termination despite unavailable enumeration");
        check(tree.view().get("cleanupFailure").equals("VALIDATION_DESCENDANT_OBSERVATION_UNAVAILABLE"), "failure retained");
        check(!Json.write(tree.view()).contains("raw host"), "no raw exception evidence");
    }
    private static void boundedDiscovery() throws Exception {
        StubProcess parent = new StubProcess(); parent.handle.children = () -> Stream.generate(Handle::new);
        var tree = new ValidationRuntime.ProcessTree(parent);
        try { tree.await(1); throw new AssertionError("unbounded descendants accepted"); }
        catch (IllegalStateException expected) { check(expected.getMessage().equals("VALIDATION_DESCENDANT_LIMIT"), "bounded discovery"); }
        long start = System.nanoTime(); tree.cleanup();
        check(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(3), "cleanup bounded");
        check(tree.view().get("observedDescendants").equals(128), "retention bounded");
    }
    private static void blockedPipe() throws Exception {
        CountDownLatch inspected = new CountDownLatch(1);
        InputStream pipe = new InputStream() {
            @Override public int available() { inspected.countDown(); return 0; }
            @Override public int read() throws IOException { throw new AssertionError("blocking read attempted"); }
            @Override public void close() throws IOException { /* The held writer is modeled by available() == 0. */ }
        };
        var capture = new ValidationRuntime.Capture(pipe); capture.start();
        check(inspected.await(1, TimeUnit.SECONDS), "reader inspected pipe");
        long start = System.nanoTime(); capture.finish(false);
        check(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(3), "descendant-held pipe cannot block reader cleanup");
        check(!capture.isAlive(), "output reader exited");
        check(Boolean.FALSE.equals(capture.view("VALIDATION_STDOUT").get("complete")), "open pipe cannot support success");
    }
    private static final class StubProcess extends Process {
        final Handle handle = new Handle(); boolean exitOnWait;
        @Override public ProcessHandle toHandle() { return handle; }
        @Override public boolean isAlive() { return handle.isAlive(); }
        @Override public boolean waitFor(long timeout, TimeUnit unit) { if (exitOnWait) handle.alive = false; return !isAlive(); }
        @Override public int waitFor() { handle.alive = false; return 0; }
        @Override public int exitValue() { if (isAlive()) throw new IllegalThreadStateException(); return 0; }
        @Override public void destroy() { handle.alive = false; }
        @Override public Process destroyForcibly() { destroy(); return this; }
        @Override public OutputStream getOutputStream() { return OutputStream.nullOutputStream(); }
        @Override public InputStream getInputStream() { return InputStream.nullInputStream(); }
        @Override public InputStream getErrorStream() { return InputStream.nullInputStream(); }
    }
    private static final class Handle implements ProcessHandle {
        static long next; final long id = ++next; boolean alive = true;
        Supplier<Stream<ProcessHandle>> children = Stream::empty;
        @Override public long pid() { return id; }
        @Override public boolean isAlive() { return alive; }
        @Override public Stream<ProcessHandle> descendants() { return children.get(); }
        @Override public Stream<ProcessHandle> children() { return descendants(); }
        @Override public Optional<ProcessHandle> parent() { return Optional.empty(); }
        @Override public boolean destroyForcibly() { alive = false; return true; }
        @Override public boolean destroy() { return destroyForcibly(); }
        @Override public boolean supportsNormalTermination() { return true; }
        @Override public int compareTo(ProcessHandle other) { return Long.compare(id, other.pid()); }
        @Override public Info info() { throw new UnsupportedOperationException(); }
        @Override public CompletableFuture<ProcessHandle> onExit() { throw new UnsupportedOperationException(); }
    }
}
