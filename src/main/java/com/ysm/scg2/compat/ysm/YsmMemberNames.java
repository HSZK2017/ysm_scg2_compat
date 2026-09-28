package com.ysm.scg2.compat.ysm;

import com.ysm.scg2.compat.ProbeLog;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Member names for the YSM build that is installed.
 *
 * <h2>The bug this exists to fix</h2>
 * <p>Obfuscation renames the <b>members</b> of YSM's own classes, not just their names. The version
 * of this mod that first worked had resolved the wrapper class and method correctly, and then called
 * {@code event.getAnimatable()} by name - which is why a probe reported, on every single call:</p>
 *
 * <pre>
 * [guard] decline diagnosis for 'tac:hold:rifle' -&gt; event=com.elfmcys...OO00O0o0OooOOOo00OO00o00
 *                                                    animatable=null
 * </pre>
 *
 * <p>The event class was found; its accessor was not. In the official release {@code getAnimatable()}
 * is {@code o0OOooo0o0OO00OoOOOo0o0O()}, {@code getEntity()} is
 * {@code OO00OOOOo0Ooo0oo0o0Oo0OO()} and the animation lookup is
 * {@code OOOOo0O0oO0OOo0O0O0Oo0O0(String)} - none of which a name-based call can guess.</p>
 *
 * <h2>How the names were derived, and how they stay honest</h2>
 * <p>Every obfuscated name below was read off {@code javap} output for the official 2.6.5 release
 * jar, matched to its readable counterpart by <b>descriptor</b> - obfuscation renames members but
 * never rewrites descriptors, which is what makes the mapping derivable at all. The pairs are
 * asserted by {@code tools/verify-mixin-targets.ps1} against the real jar, so a future
 * re-obfuscation turns into a red build rather than a silent null.</p>
 *
 * <p>Where a readable name is unique by its erased signature the table is not needed - for instance
 * a public {@code (String, ILoopType)} return {@code void} can only be {@code setAnimation} - but
 * the table is used anyway so that the resolution path is one code path with one failure mode.</p>
 */
public final class YsmMemberNames {

    /** The wrapper and handler methods, per build, plus the member accessors this mod calls. */
    public enum Member {
        // --- on the animation event ---
        /** {@code AnimationEvent#getAnimatable()}. */
        EVENT_ANIMATABLE("getAnimatable", "o0OOooo0o0OO00OoOOOo0o0O"),
        /** {@code AnimationEvent#getController()}. */
        EVENT_CONTROLLER("getController", "o0OOO0o0o0OOo000oO00o00O"),
        /** {@code AnimationEvent#getLimbSwingAmount()}. */
        EVENT_LIMB_SWING_AMOUNT("getLimbSwingAmount", "oOOOo0OOO0ooooo0O00OO0o0"),

        // --- on the animatable ---
        /** {@code AnimatableEntity#getEntity()}. */
        ANIMATABLE_ENTITY("getEntity", "OO00OOOOo0Ooo0oo0o0Oo0OO"),
        /** {@code AnimatableEntity#getAnimation(String)}; the clip lookup, and the whole guard. */
        ANIMATABLE_ANIMATION("getAnimation", "OOOOo0O0oO0OOo0O0O0Oo0O0"),

        // --- on the controller ---
        /** {@code PredicateBasedController#setAnimation(String, ILoopType)}. */
        CONTROLLER_SET_ANIMATION("setAnimation", "Oo0Oo0o00O00Oo0OOoOOoooo");

        private final String readable;
        private final String obfuscated;

        Member(String readable, String obfuscated) {
            this.readable = readable;
            this.obfuscated = obfuscated;
        }
    }

    private static final Map<String, Method> CACHE = new ConcurrentHashMap<>();

    /**
     * Signatures already reported as unresolved, and as resolved-through-a-supertype.
     *
     * <p>A single global "already reported" flag - which is what this used to be - hides every
     * failure after the first one, and the first one is not always the interesting one: the
     * loop-type failure below was reported once, minutes before it mattered, and the shape of a
     * <em>later</em> failure would have been swallowed. Each distinct signature reports once, and
     * the set is capped so a genuinely broken build cannot spam the log.</p>
     */
    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

    private static final int REPORT_LIMIT = 8;

    private YsmMemberNames() {
    }

    /**
     * The name to call on the installed build.
     *
     * @param obfuscated whether the build renames members (true only for the official release)
     */
    public static String nameFor(Member member, boolean obfuscated) {
        return obfuscated ? member.obfuscated : member.readable;
    }

    /**
     * Resolves a method on {@code type} under whichever name the installed build uses, walking up
     * the hierarchy.
     *
     * <p>Falls back to the readable name when the obfuscated one is absent, so a partially
     * re-obfuscated or hand-built jar that keeps some names still works.</p>
     *
     * @return the method, or null when neither name resolves
     */
    public static Method resolve(Class<?> type, Member member, Class<?>... parameterTypes) {
        String cacheKey = cacheKey(type, member, parameterTypes.length, false);
        Method cached = CACHE.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        String[] names = namePair(member);
        Method found = find(type, names[0], parameterTypes);
        if (found == null) {
            found = find(type, names[1], parameterTypes);
        }
        if (found != null) {
            CACHE.put(cacheKey, found);
            return found;
        }

        reportUnresolved(member, type, names, signature(parameterTypes));
        return null;
    }

    /**
     * Resolves a member <b>for a specific set of argument values</b>.
     *
     * <h2>Why this overload has to exist</h2>
     * <p>{@link #resolve(Class, Member, Class[])} matches parameter types exactly, which is
     * correct only when the caller knows the <em>declared</em> parameter types. A caller that
     * holds values does not: the declared type is routinely a supertype of the value's class.
     * The two cases that broke this mod were both of that shape - YSM declares
     * {@code setAnimation(String, ILoopType)} and hands around an {@code EDefaultLoopTypes}
     * constant, and the framework declares {@code getValue(E extends Entity)} which erases to
     * {@code (Entity)} while the call site holds a {@code Player}. Exact lookup missed both,
     * silently, and a silent null here is indistinguishable from "the model has no such
     * animation" or "the weapon is not firing".</p>
     *
     * <p>So: try the exact form first (cheap, and the common case), then fall back to matching
     * each argument against each parameter by {@code isInstance} - see {@link MemberLookup}.
     * A widened match is reported once, because "resolved, but through a supertype" is exactly
     * the fact that was missing while this bug was live.</p>
     *
     * @return the invocable method, or {@code null} when neither form matches
     */
    @Nullable
    public static Method resolveCallable(Class<?> type, Member member, Object... args) {
        String cacheKey = cacheKey(type, member, args.length, true);
        Method cached = CACHE.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        String[] names = namePair(member);
        Class<?>[] exactTypes = exactTypesOf(args);
        Method found = null;
        if (exactTypes != null) {
            found = find(type, names[0], exactTypes);
            if (found == null) {
                found = find(type, names[1], exactTypes);
            }
        }
        if (found == null) {
            found = MemberLookup.findAny(type, names, args);
            if (found != null) {
                reportWidened(member, type, found, args);
            }
        }
        if (found == null) {
            reportUnresolved(member, type, names,
                    exactTypes != null ? signature(exactTypes) : signatureOf(args));
            return null;
        }
        CACHE.put(cacheKey, found);
        return found;
    }

    /** The build's name for the member, then the other build's, as the fallback order. */
    private static String[] namePair(Member member) {
        boolean obfuscated = YsmFork.info().obfuscated();
        return new String[]{nameFor(member, obfuscated), nameFor(member, !obfuscated)};
    }

    private static String cacheKey(Class<?> type, Member member, int arity, boolean byArguments) {
        return type.getName() + '#' + member.name() + '/' + arity + (byArguments ? "!" : "");
    }

    /**
     * The parameter types of the values' own classes, or {@code null} when that question has no
     * answer - a {@code null} argument has no class, and guessing one would produce a lookup
     * that matches the wrong overload.
     */
    @Nullable
    private static Class<?>[] exactTypesOf(Object[] args) {
        Class<?>[] types = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) {
            if (args[i] == null) {
                return null;
            }
            types[i] = args[i] instanceof String ? String.class : args[i].getClass();
        }
        return types;
    }

    private static String signature(Class<?>[] parameterTypes) {
        StringBuilder out = new StringBuilder();
        for (Class<?> parameterType : parameterTypes) {
            out.append(out.length() == 0 ? "" : ", ").append(parameterType.getSimpleName());
        }
        return out.toString();
    }

    private static String signatureOf(Object[] args) {
        StringBuilder out = new StringBuilder();
        for (Object argument : args) {
            out.append(out.length() == 0 ? "" : ", ")
                    .append(argument == null ? "null" : argument.getClass().getSimpleName());
        }
        return out.toString();
    }

    private static Method find(Class<?> type, String name, Class<?>... parameterTypes) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Method method = current.getMethod(name, parameterTypes);
                if (method.isBridge() || method.isSynthetic()) {
                    continue;
                }
                return method;
            } catch (NoSuchMethodException ignored) {
                // Keep walking up.
            }
        }
        return null;
    }

    /**
     * Says so, once per signature, when a member cannot be resolved.
     *
     * <p>The failure mode this whole class exists to remove was a silent {@code null} that looked
     * exactly like "the model has no such animation". A warning naming the member, the class and
     * both candidate names is what turns that into a five-second diagnosis.</p>
     */
    private static void reportUnresolved(Member member, Class<?> type, String[] names, String signature) {
        String key = member.name() + '|' + type.getName() + '(' + signature + ')';
        if (!markReported(key)) {
            return;
        }
        report("could not resolve YSM member " + member.name()
                + " on " + type.getName() + " (tried '" + names[0] + "' and '" + names[1] + "', ("
                + signature + ")) - the animation translation cannot work without it");
    }

    /**
     * Says so, once per signature, when a member only resolves through a widened parameter type.
     *
     * <p>This is not a warning - it is the normal outcome for an interface parameter. It is
     * reported because it is the exact line whose absence hid the released-gun-pose bug: the
     * animation played, the loop type was dropped, and nothing anywhere said so.</p>
     */
    private static void reportWidened(Member member, Class<?> type, Method method, Object[] args) {
        String key = "widened|" + member.name() + '|' + type.getName() + '(' + signatureOf(args) + ')';
        if (!markReported(key)) {
            return;
        }
        ProbeLog.log("member", "resolved YSM member " + member.name() + " on " + type.getName()
                + " as " + method.getName() + '(' + signature(method.getParameterTypes()) + ')'
                + " for arguments ('" + signatureOf(args) + "') - the declared parameter type is a"
                + " supertype of the value passed, so the call carries its full meaning");
    }

    private static boolean markReported(String key) {
        if (REPORTED.contains(key)) {
            return false;
        }
        if (REPORTED.size() >= REPORT_LIMIT) {
            return false;
        }
        return REPORTED.add(key);
    }

    private static void report(String message) {
        ProbeLog.log("member", message);
        com.ysm.scg2.compat.YsmScg2Compat.LOGGER.warn("[{}] {}",
                com.ysm.scg2.compat.YsmScg2Compat.MOD_ID, message);
    }
}
