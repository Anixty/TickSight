package net.anixty.ticksight.agent;

import java.lang.instrument.Instrumentation;
import java.nio.file.Path;
import java.util.jar.JarFile;

public final class CallTraceAgent {
    private CallTraceAgent() {}

    public static void premain(String options, Instrumentation instrumentation) throws Exception {
        var location = CallTraceAgent.class.getProtectionDomain().getCodeSource().getLocation();
        // Minecraft launchers use their own class loaders. All transformed classes
        // must reach the same recorder, including classes in named mod modules.
        instrumentation.appendToBootstrapClassLoaderSearch(new JarFile(Path.of(location.toURI()).toFile()));
        CallTraceTransformer.install(instrumentation);
    }
}
