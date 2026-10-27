package me.kall.quietit.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.lang.instrument.ClassFileTransformer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Removes every {@code org.apache.logging.log4j.Logger#error(...)} invocation from
 * {@code net.minecraftforge.eventbus.EventSubclassTransformer}.
 * <p>
 * In Forge 1.20.1 (eventbus 6.0.5) that class contains exactly two such calls:
 * <ul>
 *     <li>{@code transform}: "An error occurred building event handler"</li>
 *     <li>{@code buildEvents}: "Could not find parent {} for class {} in classloader {} on thread {}"</li>
 * </ul>
 * The bytes handed to an agent transformer are the original jar bytes, so the patch does not depend on
 * which class loader (ModLauncher's PLUGIN {@code ModuleClassLoader}) defines the class.
 * <p>
 * Removal strategy: the invocation consumes the logger receiver plus its arguments, therefore those
 * stack values are popped in place of the call. This keeps the stack depth at every instruction - and
 * hence every existing stack map frame - valid without recomputing frames or touching {@code max_locals}.
 */
public final class EventSubclassTransformerPatcher implements ClassFileTransformer {
    public static final String TARGET_CLASS_NAME = "net.minecraftforge.eventbus.EventSubclassTransformer";
    private static final String TARGET_INTERNAL_NAME = "net/minecraftforge/eventbus/EventSubclassTransformer";
    private static final String LOGGER_INTERNAL_NAME = "org/apache/logging/log4j/Logger";
    private static final String ERROR_METHOD_NAME = "error";
    private static final String DUMP_DIR_PROPERTY = "quietit.dump.dir";

    private static final AtomicInteger patchedClassCount = new AtomicInteger();
    private static final AtomicInteger removedCallCount = new AtomicInteger();

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (classfileBuffer == null || !TARGET_INTERNAL_NAME.equals(className)) {
            return null;
        }
        try {
            return patch(classfileBuffer);
        } catch (Throwable failure) {
            // Never break the game because of this agent: leave the class untouched on failure.
            QuietItAgent.log("could not patch " + TARGET_CLASS_NAME + ", leaving it untouched: " + failure);
            failure.printStackTrace(System.out);
            return null;
        }
    }

    private static byte[] patch(byte[] originalBytes) {
        ClassReader reader = new ClassReader(originalBytes);
        ClassNode classNode = new ClassNode();
        reader.accept(classNode, 0);

        int removed = 0;
        for (MethodNode method : classNode.methods) {
            List<MethodInsnNode> calls = findLoggerErrorCalls(method);
            for (MethodInsnNode call : calls) {
                replaceWithPops(method, call);
                removed++;
                QuietItAgent.log("removed Logger#error" + call.desc + " from " + classNode.name + "#" + method.name + method.desc);
            }
        }

        if (removed == 0) {
            QuietItAgent.log("no Logger#error calls found in " + TARGET_CLASS_NAME + "; nothing to do.");
            return null;
        }

        int remaining = countLoggerErrorCalls(classNode);
        if (remaining != 0) {
            throw new IllegalStateException("patch incomplete, " + remaining + " Logger#error call(s) remain");
        }

        ClassWriter writer = new ClassWriter(reader, 0);
        classNode.accept(writer);
        byte[] patchedBytes = writer.toByteArray();

        patchedClassCount.incrementAndGet();
        removedCallCount.addAndGet(removed);
        QuietItAgent.log("patched " + TARGET_CLASS_NAME + ": removed " + removed + " Logger#error call(s), "
                + remaining + " remaining (class load #" + patchedClassCount.get() + ", "
                + "calls removed in total: " + removedCallCount.get() + ").");
        dumpForInspection(patchedBytes);
        return patchedBytes;
    }

    private static List<MethodInsnNode> findLoggerErrorCalls(MethodNode method) {
        List<MethodInsnNode> calls = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (insn.getOpcode() != Opcodes.INVOKEINTERFACE) {
                continue;
            }
            MethodInsnNode call = (MethodInsnNode) insn;
            if (LOGGER_INTERNAL_NAME.equals(call.owner)
                    && ERROR_METHOD_NAME.equals(call.name)
                    && Type.getReturnType(call.desc).getSort() == Type.VOID) {
                calls.add(call);
            }
        }
        return calls;
    }

    private static int countLoggerErrorCalls(ClassNode classNode) {
        int count = 0;
        for (MethodNode method : classNode.methods) {
            count += findLoggerErrorCalls(method).size();
        }
        return count;
    }

    /**
     * Replaces {@code receiver.logger(...)} with pops for the receiver and the arguments.
     * Arguments are pushed left-to-right, so they are popped in reverse order.
     */
    private static void replaceWithPops(MethodNode method, MethodInsnNode call) {
        InsnList cleanup = new InsnList();
        Type[] arguments = Type.getArgumentTypes(call.desc);
        for (int i = arguments.length - 1; i >= 0; i--) {
            cleanup.add(new InsnNode(arguments[i].getSize() == 2 ? Opcodes.POP2 : Opcodes.POP));
        }
        // The Logger instance is the receiver of the interface call.
        cleanup.add(new InsnNode(Opcodes.POP));

        method.instructions.insertBefore(call, cleanup);
        method.instructions.remove(call);
    }

    /**
     * Debug aid: {@code -Dquietit.dump.dir=<dir>} writes the patched class so it can be inspected with
     * {@code javap -p -c}. Disabled unless the property is set.
     */
    private static void dumpForInspection(byte[] patchedBytes) {
        String directory = System.getProperty(DUMP_DIR_PROPERTY);
        if (directory == null || directory.isBlank()) {
            return;
        }
        try {
            Path target = Path.of(directory, TARGET_INTERNAL_NAME + ".class");
            Files.createDirectories(target.getParent());
            Files.write(target, patchedBytes);
            QuietItAgent.log("wrote patched class to " + target.toAbsolutePath());
        } catch (Exception e) {
            QuietItAgent.log("could not dump the patched class: " + e);
        }
    }
}