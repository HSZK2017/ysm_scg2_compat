package com.ysm.scg2.compat.ysm;

import com.ysm.scg2.compat.ProbeLog;

import java.lang.reflect.Method;
import java.util.Map;
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

    private static volatile boolean reportedUnresolved;

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
        boolean obfuscated = YsmFork.info().obfuscated();
        String primary = nameFor(member, obfuscated);
        String secondary = nameFor(member, !obfuscated);

        String cacheKey = type.getName() + '#' + member.name();
        Method cached = CACHE.get(cacheKey);
        if (cached != null && cached.getParameterCount() == parameterTypes.length) {
            return cached;
        }

        Method found = find(type, primary, parameterTypes);
        if (found == null) {
            found = find(type, secondary, parameterTypes);
        }
        if (found != null) {
            CACHE.put(cacheKey, found);
            return found;
        }

        reportUnresolved(member, type, primary, secondary, parameterTypes);
        return null;
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
     * Says so, once, when a member cannot be resolved.
     *
     * <p>The failure mode this whole class exists to remove was a silent {@code null} that looked
     * exactly like "the model has no such animation". A warning naming the member, the class and
     * both candidate names is what turns that into a five-second diagnosis.</p>
     */
    private static void reportUnresolved(Member member, Class<?> type, String primary, String secondary,
                                         Class<?>[] parameterTypes) {
        if (reportedUnresolved) {
            return;
        }
        reportedUnresolved = true;
        StringBuilder signature = new StringBuilder();
        for (Class<?> parameterType : parameterTypes) {
            signature.append(signature.length() == 0 ? "" : ", ").append(parameterType.getSimpleName());
        }
        String message = "could not resolve YSM member " + member.name()
                + " on " + type.getName() + " (tried '" + primary + "' and '" + secondary + "', ("
                + signature + ")) - the animation translation cannot work without it";
        ProbeLog.log("member", message);
        com.ysm.scg2.compat.YsmScg2Compat.LOGGER.warn("[{}] {}",
                com.ysm.scg2.compat.YsmScg2Compat.MOD_ID, message);
    }
}
