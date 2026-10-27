package me.kall.quietit.agent;

import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;

/**
 * Entry point of the QuietIt java agent.
 * <p>
 * The agent is installed before the JVM reaches {@code main}, i.e. before ModLauncher builds its
 * module layers, so it can rewrite {@code net.minecraftforge.eventbus.EventSubclassTransformer}
 * while that class is still being defined. {@link #agentmain(String, Instrumentation)} additionally
 * supports late attachment: if the class is already loaded it is retransformed.
 */
public final class QuietItAgent {
    static final String PREFIX = "[QuietIt] ";

    private static volatile Instrumentation instrumentation;
    private static volatile boolean installed;

    private QuietItAgent() {
    }

    public static void premain(String agentArgs, Instrumentation inst) {
        install(inst);
        log("Java agent active (premain); target: " + EventSubclassTransformerPatcher.TARGET_CLASS_NAME);
    }

    public static void agentmain(String agentArgs, Instrumentation inst) {
        install(inst);
        log("Java agent active (agentmain); target: " + EventSubclassTransformerPatcher.TARGET_CLASS_NAME);
        retransformIfAlreadyLoaded(inst);
    }

    private static synchronized void install(Instrumentation inst) {
        if (installed) {
            return;
        }
        // canRetransform = true, so agentmain/dynamic attach can patch an already loaded class.
        inst.addTransformer(new EventSubclassTransformerPatcher(), true);
        instrumentation = inst;
        installed = true;
        if (!inst.isRetransformClassesSupported()) {
            log("note: this JVM does not support class retransformation; the class can only be patched at load time.");
        }
    }

    private static void retransformIfAlreadyLoaded(Instrumentation inst) {
        Class<?> target = null;
        for (Class<?> loaded : inst.getAllLoadedClasses()) {
            if (EventSubclassTransformerPatcher.TARGET_CLASS_NAME.equals(loaded.getName())) {
                target = loaded;
                break;
            }
        }

        if (target == null) {
            log("target class is not loaded yet; it will be patched when it is defined.");
            return;
        }
        if (!inst.isModifiableClass(target)) {
            log("target class is already loaded and not modifiable; nothing to retransform.");
            return;
        }

        try {
            inst.retransformClasses(target);
            log("requested retransformation of " + target.getName() + " (" + target.getClassLoader() + ").");
        } catch (UnmodifiableClassException | RuntimeException e) {
            log("retransformation of " + target.getName() + " failed: " + e);
        }
    }

    static void log(String message) {
        System.out.println(PREFIX + message);
    }
}