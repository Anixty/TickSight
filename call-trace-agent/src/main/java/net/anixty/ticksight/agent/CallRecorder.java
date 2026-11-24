package net.anixty.ticksight.agent;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MutableCallSite;
import java.util.ArrayDeque;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Records exact entries and elapsed times on the owning server thread. */
public final class CallRecorder {
    private static final MethodHandle IDLE = MethodHandles.constant(CallRecorder.class, null);
    private static final MutableCallSite STATE = new MutableCallSite(IDLE);
    private static final MutableCallSite[] SITES = {STATE};
    private static final MethodHandle CURRENT = STATE.dynamicInvoker();
    private static final AtomicInteger NEXT_METHOD_ID = new AtomicInteger();
    private static final ConcurrentHashMap<Integer, Method> METHODS = new ConcurrentHashMap<>();
    private static final AtomicInteger SKIPPED_CLASSES = new AtomicInteger();
    private static final AtomicInteger SKIPPED_METHODS = new AtomicInteger();
    private static volatile boolean installed;
    private static final Object LIFECYCLE = new Object();
    private static CallRecorder recording;

    public record Method(String className, String name, String descriptor) {}
    public record Call(int parent, int method, long count, long elapsedNanos,
                       long untracedCalls, long untracedNanos) {}
    public record Tick(int number, List<Call> calls) {}
    public record Result(List<Method> methods, Map<Integer, List<Tick>> targets,
                         int skippedClasses, int skippedMethods) {}
    public record Stop(String reason, int tick, long elapsedNanos, long limit,
                       long reservedBytes, long memoryLimitBytes, long heapAvailableBytes,
                       long heapReserveBytes) {}
    private record TickWindow(int number, long started) {}

    private static final class Watchdog {
        static final ScheduledThreadPoolExecutor EXECUTOR = create();
        private static ScheduledThreadPoolExecutor create() {
            var executor = new ScheduledThreadPoolExecutor(1, task -> {
                var thread = new Thread(task, "TickSight call watchdog");
                thread.setDaemon(true);
                return thread;
            });
            executor.setRemoveOnCancelPolicy(true);
            executor.setKeepAliveTime(1, TimeUnit.SECONDS);
            executor.allowCoreThreadTimeOut(true);
            return executor;
        }
    }

    private final Thread owner = Thread.currentThread();
    private final long limitNanos;
    private final int maxRepeats;
    private final long hardLimitNanos;
    private final long memoryLimitBytes;
    private final long heapReserveBytes;
    private final AtomicReference<Stop> stopped = new AtomicReference<>();
    private final HashSet<Integer> reservedMethods = new HashSet<>();
    private volatile long reservedBytes;
    private long exportReservedBytes;
    private volatile TickWindow window;
    private ScheduledFuture<?> watchdog;
    private final Map<Integer, Target> targets = new LinkedHashMap<>();
    private final ArrayDeque<Target> suspended = new ArrayDeque<>();
    private final Paths paths;
    private Target active;
    private int tickNumber;
    private int skippedEvents;

    public CallRecorder(long limitNanos) {
        this(limitNanos, -1);
    }

    public CallRecorder(long limitNanos, int maxRepeats) {
        this(limitNanos, maxRepeats, 5_000_000_000L, 128L << 20, 256L << 20);
    }

    public CallRecorder(long limitNanos, int maxRepeats, long hardLimitNanos,
                        long memoryLimitBytes, long heapReserveBytes) {
        if (limitNanos <= 0) throw new IllegalArgumentException("The call trace tick limit must be positive");
        if (maxRepeats < -1) throw new IllegalArgumentException("The repeat limit must be -1 or nonnegative");
        if (hardLimitNanos <= 0 || memoryLimitBytes <= 0 || heapReserveBytes < 0)
            throw new IllegalArgumentException("Invalid call trace safety budget");
        this.limitNanos = limitNanos;
        this.maxRepeats = maxRepeats;
        this.hardLimitNanos = hardLimitNanos;
        this.memoryLimitBytes = memoryLimitBytes;
        this.heapReserveBytes = heapReserveBytes;
        this.paths = new Paths();
    }

    public static boolean isInstalled() { return installed; }
    public static int apiVersion() { return 4; }
    static void markInstalled() { installed = true; }
    static void skippedClass() { SKIPPED_CLASSES.incrementAndGet(); }
    static void skippedMethod() { SKIPPED_METHODS.incrementAndGet(); }

    static int register(String className, String method, String descriptor) {
        int id = NEXT_METHOD_ID.getAndIncrement();
        METHODS.put(id, new Method(className, method, descriptor));
        return id;
    }

    public void start() {
        synchronized (LIFECYCLE) {
            if (recording != null) throw new IllegalStateException("Another call recording is active");
            recording = this;
            if (stopped.get() == null) {
                setState(MethodHandles.constant(CallRecorder.class, this));
                watchdog = Watchdog.EXECUTOR.scheduleAtFixedRate(this::watch, 10, 10, TimeUnit.MILLISECONDS);
            }
        }
    }

    public void stop() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Only the recording owner can finalize its data");
        synchronized (LIFECYCLE) {
            if (watchdog != null) watchdog.cancel(false);
            if (recording == this) {
                setState(IDLE);
                recording = null;
            }
        }
        // Only the owner closes frames or mutates collected data. The watchdog never does.
        finishStoppedPaths();
        window = null;
    }

    private static void setState(MethodHandle state) {
        STATE.setTarget(state);
        MutableCallSite.syncAll(SITES);
    }

    private static CallRecorder current() {
        try {
            return (CallRecorder) CURRENT.invokeExact();
        } catch (Throwable error) {
            throw new AssertionError("Call recorder state must be a constant", error);
        }
    }

    public void beginServerTick() {
        beginServerTick(tickNumber + 1);
    }

    public void beginServerTick(int number) {
        tickNumber = number;
        if (stopped.get() != null) return;
        synchronized (LIFECYCLE) {
            if (stopped.get() == null) window = new TickWindow(number, System.nanoTime());
        }
    }

    public long endServerTick() {
        TickWindow current = window;
        long now = System.nanoTime();
        if (current != null) expired(now);
        finishStoppedPaths();
        synchronized (LIFECYCLE) { if (window == current) window = null; }
        return stopped.get() == null ? 0 : current == null ? stopped.get().elapsedNanos() : now - current.started();
    }

    public boolean beginTarget(int id, int tick) {
        if (window == null || stopped.get() != null || active != null && active.skippedDepth > 0) return false;
        long now = System.nanoTime();
        if (expired(now)) return false;
        Target target = targets.get(id);
        if (target == null) {
            // Includes the profiler's object metadata and later export maps/lists.
            if (!reserve(4096, 4096)) return false;
            int root = paths.create(0, -1);
            if (root == 0) return false;
            target = new Target(root);
            targets.put(id, target);
        }
        // Reentering the same object's tick retains the existing invocation stack.
        if (target.active) return false;
        if (target.tick == null || target.tick.number != tick) {
            if (!reserve(1024, 1024)) return false;
            target.tick = new TickData(tick);
            target.ticks.add(target.tick);
        }
        int slot = touch(target, target.root);
        if (slot < 0) return false;
        if (active != null) suspended.push(active);
        target.active = true;
        target.cursor = target.root;
        target.skippedDepth = 0;
        target.tick.counts[slot]++;
        paths.startedAt[target.root] = now;
        active = target;
        return true;
    }

    public void endTarget() {
        Target target = active;
        if (target == null) return;
        long now = System.nanoTime();
        if (expired(now)) return;
        closePath(target, now);
        active = suspended.pollFirst();
    }

    public boolean hasExceededLimit() { return stopped.get() != null; }
    public Stop stopInformation() { return stopped.get(); }
    public long reservedBytes() { return reservedBytes; }

    private static long heapAvailable() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory());
    }

    private void watch() {
        TickWindow current = window;
        if (current == null || stopped.get() != null) return;
        long now = System.nanoTime();
        if (now - current.started() >= hardLimitNanos) {
            abort("hard_tick_time", current, now, hardLimitNanos);
        } else if (heapAvailable() < heapReserveBytes) {
            abort("heap", current, now, heapReserveBytes);
        }
    }

    private void abort(String reason, TickWindow current, long now, long limit) {
        synchronized (LIFECYCLE) {
            if (current != window || stopped.get() != null) return;
            var stop = new Stop(reason, current == null ? tickNumber : current.number(),
                    current == null ? 0 : Math.max(0, now - current.started()), limit,
                    reservedBytes, memoryLimitBytes, heapAvailable(), heapReserveBytes);
            if (stopped.compareAndSet(null, stop)) {
                if (watchdog != null) watchdog.cancel(false);
                if (recording == this) setState(IDLE);
            }
        }
    }

    /** Checks before allocation; charges array growth AND reserved export storage. */
    private boolean reserve(long bytes) {
        return reserve(bytes, 0);
    }

    private boolean reserve(long bytes, long exportBytes) {
        return reserve(bytes, exportBytes, true);
    }

    private boolean reserve(long bytes, long exportBytes, boolean allocating) {
        if (stopped.get() != null) return false;
        if (exportBytes > (128L << 20) - exportReservedBytes) {
            abort("report_size", window, System.nanoTime(), 128L << 20);
            return false;
        }
        if (bytes < 0 || bytes > memoryLimitBytes - reservedBytes) {
            abort("memory", window, System.nanoTime(), memoryLimitBytes);
            return false;
        }
        if (allocating && heapAvailable() < heapReserveBytes + bytes) {
            abort("heap", window, System.nanoTime(), heapReserveBytes);
            return false;
        }
        reservedBytes += bytes;
        exportReservedBytes += exportBytes;
        return true;
    }

    private boolean reserveMethod(int method) {
        if (method < 0 || reservedMethods.contains(method)) return true;
        Method info = METHODS.get(method);
        long characters = info == null ? 0 : (long) info.className().length() + info.name().length() + info.descriptor().length();
        if (!reserve(256 + 12 * characters, 256 + 12 * characters)) return false;
        reservedMethods.add(method);
        return true;
    }

    private boolean expired(long now) {
        TickWindow current = window;
        if (stopped.get() == null && current != null) {
            if (now - current.started() >= hardLimitNanos) abort("hard_tick_time", current, now, hardLimitNanos);
            else if (now - current.started() > limitNanos) abort("tick_time", current, now, limitNanos);
        }
        if (stopped.get() == null) return false;
        finishStoppedPaths();
        return true;
    }

    private void finishStoppedPaths() {
        Stop stop = stopped.get();
        TickWindow current = window;
        if (stop == null || current == null) return;
        long duration = stop.reason().equals("tick_time") || stop.reason().equals("hard_tick_time")
                ? stop.limit() : stop.elapsedNanos();
        long deadline = current.started() + duration;
        if (active != null) closePath(active, deadline);
        for (Target target : suspended) closePath(target, deadline);
        active = null;
        suspended.clear();
    }

    private void closePath(Target target, long end) {
        int node = target.cursor;
        if (target.skippedDepth > 0) {
            target.tick.untracedTimes[paths.slots[node]] += Math.max(0, end - paths.startedAt[node]);
        }
        for (; node != 0; node = paths.parents[node]) {
            target.tick.times[paths.slots[node]] += Math.max(0, end - paths.startedAt[node]);
        }
        target.active = false;
        target.cursor = 0;
        target.skippedDepth = 0;
    }

    private int touch(Target target, int node) {
        if (paths.ticks[node] != target.tick.number) {
            int parent = paths.parents[node];
            int slot = target.tick.add(node, parent == 0 ? -1 : paths.slots[parent]);
            if (slot < 0) return -1;
            paths.ticks[node] = target.tick.number;
            paths.slots[node] = slot;
        }
        return paths.slots[node];
    }

    public static void enter(int method) {
        CallRecorder recorder = current();
        if (recorder == null || recorder.owner != Thread.currentThread()) return;
        Target target = recorder.active;
        if (target == null) return;
        if (target.skippedDepth > 0) {
            target.skippedDepth++;
            // Even an omitted subtree still observes the whole-tick deadline.
            if ((++recorder.skippedEvents & 63) == 0) recorder.expired(System.nanoTime());
            return;
        }
        long now = System.nanoTime();
        if (recorder.expired(now)) return;
        int node = recorder.paths.child(target.cursor, method);
        if (node == 0) { recorder.finishStoppedPaths(); return; }
        int slot = recorder.touch(target, node);
        if (slot < 0) { recorder.finishStoppedPaths(); return; }
        long count = ++target.tick.counts[slot];
        recorder.paths.startedAt[node] = now;
        target.cursor = node;
        if (recorder.maxRepeats >= 0 && count > recorder.maxRepeats) {
            if (!target.tick.omit(slot)) { recorder.finishStoppedPaths(); return; }
            target.skippedDepth = 1;
        }
    }

    public static void exit() {
        CallRecorder recorder = current();
        if (recorder == null || recorder.owner != Thread.currentThread()) return;
        Target target = recorder.active;
        if (target == null) return;
        if (target.skippedDepth > 1) {
            target.skippedDepth--;
            if ((++recorder.skippedEvents & 63) == 0) recorder.expired(System.nanoTime());
            return;
        }
        int node = target.cursor;
        if (node == target.root) return;
        long now = System.nanoTime();
        if (recorder.expired(now)) return;
        int slot = recorder.paths.slots[node];
        long elapsed = now - recorder.paths.startedAt[node];
        target.tick.times[slot] += elapsed;
        if (target.skippedDepth == 1) {
            target.tick.untracedTimes[slot] += elapsed;
            target.skippedDepth = 0;
        }
        target.cursor = recorder.paths.parents[node];
    }

    public Result result() {
        synchronized (LIFECYCLE) {
            if (recording == this) throw new IllegalStateException("Stop the recording before exporting its data");
        }
        // Read-only views after owner stop: never materialize a second full call tree.
        var methods = new ArrayList<Method>();
        var indices = new HashMap<Integer, Integer>();
        for (int method : reservedMethods) {
            indices.put(method, methods.size());
            methods.add(METHODS.get(method));
        }
        var result = new LinkedHashMap<Integer, List<Tick>>();
        for (var target : targets.entrySet()) {
            var ticks = new ArrayList<Tick>(target.getValue().ticks.size());
            for (TickData tick : target.getValue().ticks) {
                var calls = new AbstractList<Call>() {
                    @Override public int size() { return tick.size; }
                    @Override public Call get(int slot) {
                        if (slot < 0 || slot >= tick.size) throw new IndexOutOfBoundsException(slot);
                        int method = paths.methods[tick.nodes[slot]];
                        return new Call(tick.parents[slot], method < 0 ? -1 : indices.get(method),
                                tick.counts[slot], tick.times[slot],
                                tick.untracedCounts == null ? 0 : tick.untracedCounts[slot],
                                tick.untracedTimes == null ? 0 : tick.untracedTimes[slot]);
                    }
                };
                ticks.add(new Tick(tick.number, calls));
            }
            result.put(target.getKey(), ticks);
        }
        return new Result(methods, result, SKIPPED_CLASSES.get(), SKIPPED_METHODS.get());
    }

    private static final class Target {
        final int root;
        final List<TickData> ticks = new ArrayList<>();
        TickData tick;
        int cursor;
        int skippedDepth;
        boolean active;

        Target(int root) { this.root = root; }
    }

    private final class TickData {
        final int number;
        int size;
        int[] nodes = new int[16];
        int[] parents = new int[16];
        long[] counts = new long[16];
        long[] times = new long[16];
        long[] untracedCounts;
        long[] untracedTimes;

        TickData(int number) { this.number = number; }

        int add(int node, int parent) {
            // Agent DTO + Kotlin DTO + list/map storage during bounded, streamed export.
            if (!reserve(256, 256, false)) return -1;
            if (size == nodes.length) {
                int length = size * 2;
                if (!reserve(256L + (untracedCounts == null ? 24L : 40L) * length)) return -1;
                nodes = Arrays.copyOf(nodes, length);
                parents = Arrays.copyOf(parents, length);
                counts = Arrays.copyOf(counts, length);
                times = Arrays.copyOf(times, length);
                if (untracedCounts != null) {
                    untracedCounts = Arrays.copyOf(untracedCounts, length);
                    untracedTimes = Arrays.copyOf(untracedTimes, length);
                }
            }
            nodes[size] = node;
            parents[size] = parent;
            return size++;
        }

        boolean omit(int slot) {
            if (untracedCounts == null) {
                if (!reserve(128L + 16L * nodes.length)) return false;
                untracedCounts = new long[nodes.length];
                untracedTimes = new long[nodes.length];
            }
            untracedCounts[slot]++;
            return true;
        }
    }

    /** Interns paths once per profile, independently of per-tick measurements. */
    private final class Paths {
        int size = 1; // Zero is the empty cursor and hash-table sentinel.
        int[] parents = new int[2];
        int[] methods = new int[2];
        int[] lastChildren = new int[2];
        int[] ticks = new int[2];
        int[] slots = new int[2];
        long[] startedAt = new long[2];
        long[] keys = new long[4];
        int[] children = new int[4];
        int edges;

        Paths() { reserve(1024); }

        int create(int parent, int method) {
            if (!reserveMethod(method)) return 0;
            if (size == parents.length) {
                int length = size * 2;
                if (!reserve(384L + 28L * length)) return 0;
                parents = Arrays.copyOf(parents, length);
                methods = Arrays.copyOf(methods, length);
                lastChildren = Arrays.copyOf(lastChildren, length);
                ticks = Arrays.copyOf(ticks, length);
                slots = Arrays.copyOf(slots, length);
                startedAt = Arrays.copyOf(startedAt, length);
            }
            parents[size] = parent;
            methods[size] = method;
            return size++;
        }

        int child(int parent, int method) {
            int last = lastChildren[parent];
            if (last != 0 && methods[last] == method) return last;
            long key = (long) parent << 32 | method & 0xffffffffL;
            int position = hash(key) & (keys.length - 1);
            while (children[position] != 0) {
                if (keys[position] == key) return lastChildren[parent] = children[position];
                position = (position + 1) & (keys.length - 1);
            }
            if ((edges + 1) * 2 >= keys.length) {
                if (!growIndex()) return 0;
                position = hash(key) & (keys.length - 1);
                while (children[position] != 0) position = (position + 1) & (keys.length - 1);
            }
            int child = create(parent, method);
            if (child == 0) return 0;
            keys[position] = key;
            children[position] = child;
            edges++;
            return lastChildren[parent] = child;
        }

        private boolean growIndex() {
            if (keys.length > Integer.MAX_VALUE / 2) {
                abort("memory", window, System.nanoTime(), memoryLimitBytes);
                return false;
            }
            if (!reserve(128L + 24L * keys.length)) return false;
            long[] oldKeys = keys;
            int[] oldChildren = children;
            keys = new long[keys.length * 2];
            children = new int[keys.length];
            for (int index = 0; index < oldKeys.length; index++) {
                if (oldChildren[index] == 0) continue;
                int position = hash(oldKeys[index]) & (keys.length - 1);
                while (children[position] != 0) position = (position + 1) & (keys.length - 1);
                keys[position] = oldKeys[index];
                children[position] = oldChildren[index];
            }
            return true;
        }

        private static int hash(long key) {
            key ^= key >>> 33;
            key *= 0xff51afd7ed558ccdL;
            key ^= key >>> 33;
            key *= 0xc4ceb9fe1a85ec53L;
            key ^= key >>> 33;
            return (int) key;
        }
    }
}
