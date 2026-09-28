package com.ysm.scg2.compat.ysm;

import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;

/**
 * Finds the public method that a reflective call is actually able to invoke.
 *
 * <h2>The bug this exists to fix</h2>
 * <p>Reflection by <em>runtime class</em> is not the same thing as reflection by <em>declared
 * type</em>. {@link Class#getMethod(String, Class[])} matches parameter types exactly, so
 * asking for the type of the value you are holding misses every method whose parameter is a
 * <b>supertype</b> of it - which is the normal case for an interface parameter and one of its
 * implementations, and for a generic method whose parameter is erased to its bound:</p>
 *
 * <pre>
 *   PredicateBasedController#setAnimation(String, ILoopType)   // declared type: the INTERFACE
 *   YsmBridge asks for (String, loopType.getClass())           // ...but passes EDefaultLoopTypes
 *   -&gt; NoSuchMethodException, silently, every frame
 *
 *   SyncedDataKey#getValue(E extends Entity)                   // erases to (Entity)Object
 *   Scg2GunAccess asked for (Player)Object                     // ...the generic ARGUMENT, not the erasure
 *   -&gt; NoSuchMethodException, and every SCG2 weapon state read as 'off'
 * </pre>
 *
 * <p>Both of those were real, silent failures in this mod: the first released the gun pose
 * after one play-through of the clip (see {@code GunAnimationDecision}), the second disabled
 * every firing and reloading animation. A wrong lookup that returns {@code null} is
 * indistinguishable from "the model has no such animation", so nothing downstream could
 * report it - hence a resolver that matches by <b>assignability</b>, with each argument
 * accepted by the parameter that would have received it in Java source.</p>
 *
 * <h2>Rules, in order</h2>
 * <ol>
 *   <li>Same name, same arity, public, not a bridge or synthetic method.</li>
 *   <li>Every parameter must accept its argument: {@code parameter.isInstance(argument)}.
 *       A {@code null} argument matches any reference parameter and no primitive one.</li>
 *   <li>Among the candidates that accept, the one with the most <b>exact</b> parameter types
 *       wins; ties go to the declaration lowest in the hierarchy (the override).</li>
 * </ol>
 *
 * <p>This class deliberately has no dependency on Forge, Minecraft or Yes Steve Model: it is
 * the piece the offline harness drives directly, with YSM's real {@code ILoopType} loaded out
 * of the shipped jar.</p>
 */
public final class MemberLookup {

    private MemberLookup() {
    }

    /**
     * The public method {@code type.name(...)} that accepts {@code args}, or {@code null}.
     *
     * @param names candidate names, tried in order - a build may rename its members
     *              (see {@link YsmMemberNames})
     */
    @Nullable
    public static Method findAny(Class<?> type, String[] names, Object... args) {
        for (String name : names) {
            Method found = find(type, name, args);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** The public method with this exact name that accepts {@code args}, or {@code null}. */
    @Nullable
    public static Method find(Class<?> type, String name, Object... args) {
        Method best = null;
        int bestScore = -1;
        for (Method candidate : type.getMethods()) {
            if (!candidate.getName().equals(name)
                    || candidate.isBridge()
                    || candidate.isSynthetic()) {
                continue;
            }
            Class<?>[] parameters = candidate.getParameterTypes();
            if (parameters.length != args.length) {
                continue;
            }
            int score = score(parameters, args);
            if (score < 0) {
                continue;
            }
            if (score > bestScore || (score == bestScore && isOverride(best, candidate))) {
                best = candidate;
                bestScore = score;
            }
        }
        return best;
    }

    /**
     * How exactly this signature accepts these arguments, or {@code -1} when it does not.
     *
     * <p>The score counts parameters whose declared type is the argument's own class, so an
     * exact overload beats one that only accepts by inheritance.</p>
     */
    private static int score(Class<?>[] parameters, Object[] args) {
        int score = 0;
        for (int i = 0; i < parameters.length; i++) {
            Object argument = args[i];
            if (argument == null) {
                if (parameters[i].isPrimitive()) {
                    return -1;
                }
                continue;
            }
            if (!parameters[i].isInstance(argument)) {
                return -1;
            }
            if (parameters[i] == argument.getClass()) {
                score++;
            }
        }
        return score;
    }

    /** Whether {@code candidate} overrides {@code current}, i.e. is declared further down. */
    private static boolean isOverride(@Nullable Method current, Method candidate) {
        return current != null
                && !current.getDeclaringClass().equals(candidate.getDeclaringClass())
                && current.getDeclaringClass().isAssignableFrom(candidate.getDeclaringClass());
    }
}
