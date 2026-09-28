package com.ysm.scg2.compat;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs deferred initialisation steps, each isolated from the others.
 *
 * <h2>Why the isolation matters</h2>
 * <p>Every step here touches either a Yes Steve Model type or a type whose reachability depends
 * on YSM. A failure in one is not a reason for the others to be skipped, and it is certainly
 * not a reason to abort the game: this mod exists to bridge two other mods, so at worst it
 * should be inert. Each step is therefore wrapped in {@code catch (Throwable)} - {@code
 * NoClassDefFoundError} and {@code NoSuchMethodError} are {@link Error}s, not {@link
 * Exception}s, and a classpath surprise is exactly what they look like.</p>
 *
 * <h2>Why the lambdas are created in the mod constructor but run later</h2>
 * <p>A lambda body does not resolve the classes it names until it runs, so a step can name a
 * YSM type without dragging that type in at construction - which is the load-time verification
 * trap that killed version 1.0.0 (see {@code YsmBridge}).</p>
 */
public final class DeferredInit {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final List<Step> STEPS = new ArrayList<>();

    private record Step(String name, Runnable action) {
    }

    private DeferredInit() {
    }

    /** Queues a step to run during {@link #runAll()}. */
    public static synchronized void add(String name, Runnable action) {
        STEPS.add(new Step(name, action));
    }

    /**
     * Runs every queued step, logging one line per step with its outcome.
     *
     * <p>The log line is the point: "the diagnostic layer is available" and "it is not, because
     * X" have to be distinguishable, or a silent compat layer is indistinguishable from a
     * broken one.</p>
     */
    public static void runAll() {
        List<Step> steps;
        synchronized (DeferredInit.class) {
            steps = List.copyOf(STEPS);
            STEPS.clear();
        }

        ProbeLog.log("deferred", "running " + steps.size() + " deferred step(s)");
        for (Step step : steps) {
            try {
                step.action().run();
                ProbeLog.log("deferred", "step OK: " + step.name());
            } catch (Throwable t) {
                LOGGER.warn("[{}] deferred step '{}' failed and was skipped ({})",
                        YsmScg2Compat.MOD_ID, step.name(), t.toString());
                ProbeLog.log("deferred", "step FAILED: " + step.name() + " -> " + ProbeLog.describe(t));
            }
        }

        reportInjectionState();
    }

    /**
     * Answers "is the animation mixin actually attached?" from the loaded class itself, because
     * every indirect signal proved ambiguous.
     *
     * <p>A mixin plugin's own output can be lost before Forge's logging is configured; a prepared
     * config does not prove the injection applied; and a declined guard and a missing injection
     * produce the same behaviour. Mixin appends a {@code CallbackInfoReturnable} parameter to the
     * handler it generates, so the target's method arity is the one fact that distinguishes
     * them - and it is the fact that decides whether to look at the guard or at the injection.</p>
     */
    private static void reportInjectionState() {
        try {
            com.ysm.scg2.compat.ysm.YsmFork.Info fork = com.ysm.scg2.compat.ysm.YsmFork.info();
            if (!fork.present()) {
                return;
            }
            // The target's parameter count cannot answer this: Mixin adds its callback parameter
            // to the HANDLER in the mixin class, not to the target, so the target keeps its own
            // signature whether or not it was injected. An earlier version of this check reasoned
            // from that arity and reported NOT ATTACHED on a working injection - a false negative
            // that cost a debugging round.
            //
            // The reliable signal is behavioural: GunAnimationDecision logs REACHED the first time
            // the injected code runs. This line therefore reports what is knowable, and names the
            // line to look for.
            LOGGER.info("[{}] animation mixin: expecting handler {} on {} (build {}); look for the "
                            + "'handler REACHED' line to confirm the injection is live",
                    YsmScg2Compat.MOD_ID, fork.methodHoldGun(), fork.tacCompatClass(), fork.build());
        } catch (Throwable t) {
            LOGGER.warn("[{}] could not determine whether the animation mixin is attached ({})",
                    YsmScg2Compat.MOD_ID, t.toString());
        }
    }
}
