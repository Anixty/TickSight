package net.anixty.ticksight.agent;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.Map;
import java.util.Set;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.AdviceAdapter;
import org.objectweb.asm.commons.Method;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

public final class CallTraceTransformer implements ClassFileTransformer {
    private static final Type RECORDER = Type.getType(CallRecorder.class);
    private static final Method ENTER = new Method("enter", "(I)V");
    private static final Method EXIT = new Method("exit", "()V");
    private final Instrumentation instrumentation;

    private CallTraceTransformer(Instrumentation instrumentation) {
        this.instrumentation = instrumentation;
    }

    public static void install(Instrumentation instrumentation) {
        instrumentation.addTransformer(new CallTraceTransformer(instrumentation));
        CallRecorder.markInstalled();
    }

    @Override
    public byte[] transform(Module module, ClassLoader loader, String name, Class<?> redefining,
                            ProtectionDomain domain, byte[] bytes) {
        if (loader == null || loader == ClassLoader.getPlatformClassLoader() || name == null || redefining != null) {
            return null;
        }
        if (name.startsWith("net/anixty/ticksight/")) {
            Module recorderModule = CallRecorder.class.getModule();
            if (module != null && module.isNamed() && !module.canRead(recorderModule)) {
                instrumentation.redefineModule(module, Set.of(recorderModule), Map.of(), Map.of(), Set.of(), Map.of());
            }
            return null;
        }
        if (isLoaderInfrastructure(name)) return null;

        try {
            var reader = new ClassReader(bytes);
            var klass = new ClassNode();
            reader.accept(klass, ClassReader.EXPAND_FRAMES);
            boolean changed = false;
            String className = name.replace('/', '.');
            for (int index = 0; index < klass.methods.size(); index++) {
                MethodNode original = klass.methods.get(index);
                if (original.instructions.size() == 0) continue;
                MethodNode result = new MethodNode(original.access, original.name, original.desc,
                        original.signature, original.exceptions.toArray(String[]::new));
                int id = CallRecorder.register(className, original.name, original.desc);
                var visitor = new CallTraceMethod(original, result, id);
                original.accept(visitor);
                if (visitor.entries == 1) {
                    klass.methods.set(index, result);
                    changed = true;
                } else {
                    // Unusual constructors may initialize this along multiple paths.
                    CallRecorder.skippedMethod();
                }
            }
            if (!changed) return null;
            // Preserve the existing stack frames. Resolving common superclasses here
            // could recursively load classes through the mod loader during definition.
            var writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            klass.accept(writer);
            return writer.toByteArray();
        } catch (RuntimeException | LinkageError exception) {
            CallRecorder.skippedClass();
            System.err.println("[TickSight] Could not instrument " + name + ": " + exception);
            return null;
        }
    }

    private static boolean isLoaderInfrastructure(String name) {
        return name.startsWith("cpw/mods/")
                || name.startsWith("net/neoforged/fml/")
                || name.startsWith("net/minecraftforge/fml/")
                || name.startsWith("net/fabricmc/loader/")
                || name.startsWith("net/fabricmc/tinyremapper/")
                || name.startsWith("org/objectweb/asm/")
                || name.startsWith("org/spongepowered/asm/");
    }

    private static final class CallTraceMethod extends AdviceAdapter {
        private final Label start = new Label();
        private final int id;
        private int entries;

        CallTraceMethod(MethodNode original, MethodNode result, int id) {
            super(Opcodes.ASM9, result, original.access, original.name, original.desc);
            this.id = id;
        }

        @Override
        protected void onMethodEnter() {
            if (++entries == 1) {
                push(id);
                invokeStatic(RECORDER, ENTER);
                mark(start);
            }
        }

        @Override
        protected void onMethodExit(int opcode) {
            // ATHROW may still be handled inside the method.
            if (opcode != ATHROW) invokeStatic(RECORDER, EXIT);
        }

        @Override
        public void visitMaxs(int maxStack, int maxLocals) {
            if (entries == 1) {
                var end = new Label();
                // Added after all existing handlers so normal catches keep priority.
                mv.visitTryCatchBlock(start, end, end, null);
                mv.visitLabel(end);
                mv.visitFrame(F_NEW, 0, new Object[0], 1, new Object[] {"java/lang/Throwable"});
                mv.visitMethodInsn(INVOKESTATIC, RECORDER.getInternalName(), EXIT.getName(), EXIT.getDescriptor(), false);
                mv.visitInsn(ATHROW);
            }
            super.visitMaxs(maxStack, maxLocals);
        }
    }
}
