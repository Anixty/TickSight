package net.anixty.ticksight;

import net.anixty.ticksight.server.Profiler;

import java.util.concurrent.atomic.AtomicReference;

public final class Props {
    private Props() {}

    public static final AtomicReference<Profiler.SampleTarget> currentTarget = new AtomicReference<>();
}
