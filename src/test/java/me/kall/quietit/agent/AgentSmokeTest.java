package me.kall.quietit.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Differential self-check for the agent, run against the real Forge eventbus class.
 * <p>
 * With {@code -javaagent:quietit-agent.jar} the class must load, verify, and contain no
 * {@code Logger#error} invocation; without the agent the same code path prints the two Forge messages
 * ("An error occurred building event handler" / "Could not find parent ...").
 */
public final class AgentSmokeTest {
    private static final String TARGET_CLASS_NAME = "net.minecraftforge.eventbus.EventSubclassTransformer";
    private static final String TARGET_INTERNAL_NAME = "net/minecraftforge/eventbus/EventSubclassTransformer";
    private static final String LOGGER_INTERNAL_NAME = "org/apache/logging/log4j/Logger";

    private AgentSmokeTest() {
    }

    public static void main(String[] args) throws Exception {
        boolean expectPatch = Boolean.parseBoolean(System.getProperty("quietit.smoke.expectPatch", "true"));

        Class<?> target = Class.forName(TARGET_CLASS_NAME);
        System.out.println("[smoke] loaded " + target.getName() + " from "
                + target.getProtectionDomain().getCodeSource().getLocation());

        Object transformer = target.getDeclaredConstructor().newInstance();
        ClassNode eventLikeClass = new ClassNode();
        eventLikeClass.version = Opcodes.V17;
        eventLikeClass.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER;
        eventLikeClass.name = "quietit/smoke/EventWithoutParent";
        // The missing super class forces the ClassNotFoundException branch in buildEvents(),
        // i.e. both Logger#error call sites are reached (BuildEvents logs, then transform catches).
        eventLikeClass.superName = "quietit/smoke/MissingParent";

        Method transform = target.getDeclaredMethod("transform", ClassNode.class, Type.class);
        Object result = transform.invoke(transformer, eventLikeClass, Type.getObjectType(eventLikeClass.name));
        require(result instanceof Optional<?> && ((Optional<?>) result).isPresent(),
                "transform() no longer returns the class node: " + result);

        if (expectPatch) {
            Path dump = dumpFile();
            require(Files.isRegularFile(dump), "the agent did not dump the patched class to " + dump);
            ClassNode patched = new ClassNode();
            new ClassReader(Files.readAllBytes(dump)).accept(patched, 0);
            require(TARGET_INTERNAL_NAME.equals(patched.name), "unexpected class in dump: " + patched.name);

            int remaining = 0;
            for (MethodNode method : patched.methods) {
                remaining += countErrorCalls(method);
            }
            require(remaining == 0, "patched class still contains " + remaining + " Logger#error call(s)");
            System.out.println("[smoke] patched class dumped (" + Files.size(dump) + " bytes), "
                    + "0 Logger#error calls remain");
        }

        System.out.println("[smoke] PASSED (expectPatch=" + expectPatch + ")");
    }

    private static Path dumpFile() {
        String directory = System.getProperty("quietit.dump.dir");
        require(directory != null && !directory.isBlank(), "-Dquietit.dump.dir is not set");
        return Path.of(directory, TARGET_INTERNAL_NAME + ".class");
    }

    private static int countErrorCalls(MethodNode method) {
        int count = 0;
        for (AbstractInsnNode insn : method.instructions) {
            if (insn.getOpcode() == Opcodes.INVOKEINTERFACE) {
                MethodInsnNode call = (MethodInsnNode) insn;
                if (LOGGER_INTERNAL_NAME.equals(call.owner) && "error".equals(call.name)) {
                    count++;
                }
            }
        }
        return count;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}