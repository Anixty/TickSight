package net.anixty.ticksight.server;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MutableCallSite;

public final class ProfilingGate {
    private static final MethodHandle IDLE = MethodHandles.constant(boolean.class, false);
    private static final MethodHandle RUNNING = MethodHandles.constant(boolean.class, true);
    private static final MutableCallSite STATE = new MutableCallSite(IDLE);
    private static final MutableCallSite[] SITES = {STATE};
    private static final MethodHandle CHECK = STATE.dynamicInvoker();

    private ProfilingGate() {}

    /** HotSpot can fold the stable target into the tick caller between profiles. */
    public static boolean isRunning() {
        try {
            return (boolean) CHECK.invokeExact();
        } catch (Throwable error) {
            throw new AssertionError("Profiling state must be a boolean constant", error);
        }
    }

    /** Called on the server thread only when a profiling session starts or stops. */
    public static void setRunning(boolean running) {
        STATE.setTarget(running ? RUNNING : IDLE);
        MutableCallSite.syncAll(SITES);
    }
}
