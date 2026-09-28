package com.ysm.scg2.compat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * A probe channel that cannot be swallowed.
 *
 * <h2>Why this exists instead of logging</h2>
 * <p>Diagnosing this mod needed several rounds of guessing because every available signal was
 * ambiguous:</p>
 * <ul>
 *   <li>a Mixin config plugin runs <b>before</b> Forge configures the mod's logging, so output
 *       written there never reached {@code latest.log} - the lines are simply absent, which looks
 *       the same as "the code never ran";</li>
 *   <li>"a mixin config was prepared" does not mean "the injection applied";</li>
 *   <li>"the handler declined" and "the handler was never called" produce identical behaviour.</li>
 * </ul>
 *
 * <p>So probes write to a <b>file of their own</b>, opened directly, with no dependency on a logger,
 * on Forge, or on anything else that might not be ready yet. If a line is not in the file, the code
 * did not run - and that is precisely the fact that was missing.</p>
 *
 * <h2>Failure policy</h2>
 * <p>Every method swallows {@link Throwable}. A probe that can break the thing it measures is worse
 * than no probe. If the file cannot be written, probing turns itself off silently and the mod
 * behaves exactly as it would without it.</p>
 *
 * <h2>Location and disabling</h2>
 * <p>The file is {@code ysm_scg2_compat-probe.log} in the game directory (next to {@code logs/}),
 * truncated at the start of each run so a stale file cannot be mistaken for the current one. Pass
 * {@code -Dysm_scg2_compat.probe=off} to disable the channel entirely.</p>
 */
public final class ProbeLog {

    /** System property that turns the channel off: {@code -Dysm_scg2_compat.probe=off}. */
    public static final String DISABLE_PROPERTY = "ysm_scg2_compat.probe";

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private static final Object LOCK = new Object();

    private static volatile Path file;
    private static volatile boolean disabled;
    private static volatile boolean announced;

    /** Guards the one-time truncation in {@link #startRun(String)}. */
    private static boolean started;

    private ProbeLog() {
    }

    /**
     * Records one probe line.
     *
     * @param stage  lifecycle stage: {@code plugin}, {@code ctor}, {@code deferred}, {@code bridge},
     *               {@code guard}, {@code tick} or {@code render}
     * @param detail what was observed
     */
    public static void log(String stage, String detail) {
        if (disabled) {
            return;
        }
        try {
            if ("off".equalsIgnoreCase(System.getProperty(DISABLE_PROPERTY, "on"))) {
                disabled = true;
                return;
            }
            Path target = resolveFile();
            if (target == null) {
                return;
            }
            String line = LocalTime.now().format(TIME) + " [" + stage + "] " + detail
                    + "  (thread " + Thread.currentThread().getName() + ")" + System.lineSeparator();

            synchronized (LOCK) {
                Files.writeString(target, line, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
            announceOnce(target);
        } catch (Throwable ignored) {
            // A probe must never be able to break what it measures.
        }
    }

    /** The probe file's location, or null when nothing can be written. */
    public static Path location() {
        resolveFile();
        return file;
    }

    /**
     * Truncates the file and writes a banner, so a stale file from an earlier run cannot be
     * mistaken for this one.
     */
    public static void startRun(String banner) {
        // Truncate exactly once per JVM. Two entry points call this (the mixin plugin, then the mod
        // constructor, in that order), and truncating on the second call would erase the plugin's
        // lines - which are the ones that answer "was the plugin even loaded?".
        boolean first;
        synchronized (ProbeLog.class) {
            first = !started;
            started = true;
        }
        try {
            Path target = resolveFile();
            if (target == null) {
                return;
            }
            if (first) {
                Files.writeString(target, "===== " + banner + " =====" + System.lineSeparator(),
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            } else {
                log("probe", "second startRun ignored (already open): " + banner);
                return;
            }
            log("probe", "channel open: " + target
                    + " (disable with -D" + DISABLE_PROPERTY + "=off)");
        } catch (Throwable ignored) {
            // Probing stays off.
        }
    }

    /** A throwable reduced to something that fits on one line. */
    public static String describe(Throwable t) {
        if (t == null) {
            return "null";
        }
        String message = t.getMessage();
        return t.getClass().getSimpleName() + (message == null ? "" : "(" + message + ")");
    }

    private static void announceOnce(Path target) {
        if (announced) {
            return;
        }
        announced = true;
        try {
            // stdout as well, on the chance that this early in startup it is the visible channel.
            System.out.println("[ysm_scg2_compat] probe log -> " + target);
        } catch (Throwable ignored) {
            // Nothing further to try.
        }
    }

    /**
     * Resolves where to write. {@code user.dir} is the game directory for a normal install, which
     * puts the file next to {@code logs/}; the temp directory is the fallback for the unusual case
     * where the working directory is not writable.
     */
    private static Path resolveFile() {
        Path cached = file;
        if (cached != null) {
            return cached;
        }
        synchronized (ProbeLog.class) {
            if (file != null) {
                return file;
            }
            Path candidate = null;
            try {
                Path inGameDir = Paths.get(System.getProperty("user.dir", "."), "ysm_scg2_compat-probe.log");
                Files.writeString(inGameDir, "", StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                candidate = inGameDir;
            } catch (Throwable ignored) {
                candidate = null;
            }
            if (candidate == null) {
                try {
                    candidate = Files.createTempFile("ysm_scg2_compat-probe", ".log");
                } catch (Throwable t) {
                    disabled = true;
                    return null;
                }
            }
            file = candidate;
            return candidate;
        }
    }
}
