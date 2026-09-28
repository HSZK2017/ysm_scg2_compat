package com.ysm.scg2.compat.client;

import com.ysm.scg2.compat.ProbeLog;
import com.ysm.scg2.compat.YsmScg2Compat;
import com.ysm.scg2.compat.ysm.YsmClasses;
import com.ysm.scg2.compat.ysm.YsmFork;
import com.ysm.scg2.compat.ysm.YsmMemberNames;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The only place in this mod that touches a Yes Steve Model type - and it does so
 * <b>entirely through reflection, with no YSM type in any signature</b>.
 *
 * <h2>Why that constraint is not paranoia</h2>
 * <p>Forge scans every {@code @Mod.EventBusSubscriber} class in a mod during
 * {@code FMLModContainer#constructMod}, with {@code Class.forName(name)}, and the JVM
 * verifies the loaded class eagerly. A class whose <em>member signatures</em> mention a
 * type that cannot be resolved therefore fails to load - with
 * {@code NoClassDefFoundError} reported at the {@code Class.forName0} frame, i.e. nowhere
 * near the line that is actually at fault.</p>
 *
 * <p>That is exactly how the first build of this mod failed to start:</p>
 * <pre>
 * java.lang.NoClassDefFoundError: com/elfmcys/yesstevemodel/client/entity/LivingAnimatable
 *   at java.lang.Class.forName0(Native Method)
 *   at net.minecraftforge.fml.javafmlmod.AutomaticEventSubscriber.lambda$inject$6
 * </pre>
 * <p>No YSM type may appear in a signature, a field, an annotation, or a supertype of a
 * class Forge loads, because any of those can be resolved at load time. Method bodies are
 * only resolved when they execute, so that is where the calls live.</p>
 *
 * <h2>What this buys</h2>
 * <p>The compat layer now degrades instead of aborting the game: if a YSM type is missing
 * or renamed, {@link #checkAvailable()} logs one line and every entry point becomes a
 * no-op. The mod's whole reason to exist is to bridge two other mods; it must never be the
 * reason a world will not open.</p>
 */
public final class YsmBridge {

    private static final String CAPABILITY_CLASS =
            "com.elfmcys.yesstevemodel.capability.PlayerCapability";
    private static final String ANIMATABLE_CLASS =
            "com.elfmcys.yesstevemodel.client.entity.LivingAnimatable";
    private static final String FALLBACK_CAPABILITY_PROVIDER =
            "com.elfmcys.yesstevemodel.capability.PlayerCapabilityProvider";

    /** Distinguishes "not probed yet" from "probed, unavailable". */
    private static volatile Boolean available;

    /** The capability {@code Capability} token, resolved once. */
    private static volatile Object capabilityToken;

    private static final Map<String, Method> METHOD_CACHE = new ConcurrentHashMap<>();

    private YsmBridge() {
    }

    // ------------------------------------------------------------------
    // Availability
    // ------------------------------------------------------------------

    /**
     * Resolves YSM's core client types once and remembers the answer.
     *
     * <p>Called from the mod constructor. On failure this is a one-line warning, not an
     * exception: the animation mixin is gated separately by {@code YsmScg2MixinPlugin}, and
     * every method here re-checks {@link #isAvailable()} before doing anything.</p>
     *
     * <h3>Which build</h3>
     * <p>Nothing here names the capability <em>provider</em>, because on the obfuscated
     * official release it has no readable name. The provider is found by structure - the YSM
     * class declaring a {@code Capability<PlayerCapability>} field - and the capability
     * token is then read through the provider's own static field. That also means the
     * obfuscated field name never has to be known.</p>
     */
    public static boolean checkAvailable() {
        Boolean cached = available;
        if (cached != null) {
            return cached;
        }
        return refreshAvailability();
    }

    /**
     * Re-resolves the capability token and the wrapper class, discarding any earlier negative
     * answer, and reports whether the bridge is usable.
     *
     * <p>Called after mod loading completes. During construction Forge is still filling its mod
     * list, so a first attempt can legitimately fail on a machine where everything is in order;
     * caching that failure left the diagnostics permanently off in a real launch. Resolution
     * here is idempotent, so re-running it is free.</p>
     */
    public static boolean refreshAvailability() {
        capabilityToken = null;
        Boolean cached = available;
        if (cached != null && cached) {
            // A resolved answer cannot become wrong, so keep it rather than re-doing IO.
            return true;
        }

        YsmFork.Info fork = YsmFork.info();
        if (!fork.present()) {
            available = false;
            return false;
        }

        boolean ok;
        try {
            // Deliberately NO Class.forName on a NAMED YSM class.
            //
            // This used to probe `client.entity.LivingAnimatable`, a *readable* name - and the
            // obfuscated official release renames it. The probe therefore threw
            // ClassNotFoundException and declared the bridge unavailable on the very build that
            // needed it. The effect: the animation guard declined every frame, so the
            // third-person gun pose never played, while first person (YSM's own path) worked.
            //
            // Nothing here needs that class by name. Every member access in this class is
            // name-based reflection (`getAnimatable`, `getAnimation`, `getController`,
            // `setAnimation`), which obfuscation preserves because it renames members but not
            // their descriptors. The capability token is resolved from the provider field's
            // generic signature, i.e. by structure; the wrapper class is named by YsmFork, which
            // knows which build is installed.
            ProbeLog.log("bridge", "resolving: build=" + fork.build() + " wrapper=" + fork.tacCompatClass());
            resolveCapabilityToken();
            ProbeLog.log("bridge", "capability token resolved: " + (capabilityToken != null
                    ? capabilityToken.getClass().getName() : "NULL - this is why the guard stays off"));
            String wrapper = fork.tacCompatClass();
            if (wrapper != null) {
                Class.forName(wrapper, false, YsmBridge.class.getClassLoader());
            }
            ok = capabilityToken != null;
            if (!ok) {
                YsmScg2Compat.LOGGER.warn("[{}] could not resolve YSM's player capability token on the {} build; "
                                + "the model report and render probe stay disabled. The animation mixin is gated "
                                + "separately and is unaffected.",
                        YsmScg2Compat.MOD_ID, fork.build());
            }
        } catch (Throwable t) {
            YsmScg2Compat.LOGGER.warn("[{}] YSM is not reachable on the {} build ({}); diagnostics and the render "
                            + "probe stay disabled. The animation mixin is gated separately and is unaffected.",
                    YsmScg2Compat.MOD_ID, fork.build(), t.toString());
            ok = false;
        }
        available = ok;
        return ok;
    }

    /**
     * Finds the {@code Capability} token, by structure rather than by name.
     *
     * <p>Tries the readable provider first (cheap, and it is what the fork and ModernYSM
     * ship), then the scan-based holder, which is the only route on the obfuscated
     * release.</p>
     */
    private static void resolveCapabilityToken() {
        if (capabilityToken != null) {
            return;
        }
        // Ask the build where its provider is. ModernYSM relocated it into a `forge` subpackage,
        // OpenYSM keeps it under `capability`, and the obfuscated release has no readable name -
        // a single hardcoded path is wrong for two of the three builds.
        // Try the provider this build is known to ship, then the readable fallback, then the
        // obfuscated one - each with every candidate field name, since the obfuscated release
        // names the field differently from the readable builds.
        String[] providers = {
                YsmFork.info().capabilityProviderClass(),
                FALLBACK_CAPABILITY_PROVIDER,
                YsmFork.LEGACY_CAPABILITY_PROVIDER,
        };
        for (String provider : providers) {
            if (provider == null) {
                continue;
            }
            for (String field : YsmFork.CAPABILITY_FIELD_CANDIDATES) {
                capabilityToken = tokenFromHolder(provider, field);
                if (capabilityToken != null) {
                    ProbeLog.log("bridge", "capability token via " + provider + "#" + field);
                    return;
                }
            }
        }
        // The obfuscated build has no readable capability class, so no anchor can be named for
        // the generic argument - pass null and let the scan fall back to "the static field whose
        // raw type is named Capability". Returning early here is what left the token null on the
        // build that needed it, which in turn kept the animation guard off for the whole session.
        String anchor = YsmClasses.has(CAPABILITY_CLASS) ? CAPABILITY_CLASS : null;
        YsmClasses.Holder holder = YsmClasses.findCapabilityHolder(anchor);
        if (holder != null) {
            capabilityToken = tokenFromHolder(holder.className(), holder.fieldName());
        }
    }

    /** Reads a static {@code Capability} field from a named class, or null. */
    @Nullable
    private static Object tokenFromHolder(String className, String fieldName) {
        try {
            Class<?> provider = Class.forName(className, false, YsmBridge.class.getClassLoader());
            return provider.getField(fieldName).get(null);
        } catch (Throwable t) {
            return null;
        }
    }

    public static boolean isAvailable() {
        Boolean cached = available;
        return cached != null && cached;
    }

    // ------------------------------------------------------------------
    // Injection self-check
    // ------------------------------------------------------------------

    /**
     * The only reliable signal that the animation mixin is live: the target's method table.
     *
     * <p>Every log-based inference failed to answer this. "The mixin plugin never printed",
     * "the config was prepared", "the guard declined" are all consistent with several different
     * causes, and a Mixin config plugin's own output can be lost before Forge's logging is wired.
     * Mixin appends the {@code CallbackInfoReturnable} parameter to the handler it generates, so
     * asking the loaded target how many parameters its gun-hold method has settles it in one
     * line:</p>
     *
     * <ul>
     *   <li><b>2</b> - {@code (ItemStack, AnimationEvent)}: YSM's own method. Not injected.</li>
     *   <li><b>3</b> - {@code (…, CallbackInfoReturnable)}: our handler is in place.</li>
     * </ul>
     *
     * @param wrapperClass the TACZ bridge class name for the installed build
     * @param holdMethod   the gun-hold method name for that build
     * @return {@code true} when a handler is attached, {@code false} when not, {@code null} when
     *         the question cannot be answered (class or method not loadable)
     */
    @Nullable
    public static Boolean isInjectionAttached(@Nullable String wrapperClass, @Nullable String holdMethod) {
        if (wrapperClass == null || holdMethod == null) {
            return null;
        }
        try {
            Class<?> target = Class.forName(wrapperClass, false, YsmBridge.class.getClassLoader());
            for (Method method : target.getDeclaredMethods()) {
                if (!method.getName().equals(holdMethod)) {
                    continue;
                }
                if (method.getParameterCount() >= 3
                        && "CallbackInfoReturnable".equals(
                                method.getParameterTypes()[method.getParameterCount() - 1].getSimpleName())) {
                    return true;
                }
            }
            // The method exists but carries no handler; distinguish "found the method" from
            // "found nothing" so a wrong name is not reported as "not injected".
            for (Method method : target.getDeclaredMethods()) {
                if (method.getName().equals(holdMethod)) {
                    return false;
                }
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }
    // ------------------------------------------------------------------
    // Capability
    // ------------------------------------------------------------------

    /**
     * The player's YSM model capability, or {@code null}.
     *
     * <p>Obtained through {@code Capability#getCapability(Object, Object)} with the resolved
     * token, so neither the capability's own class name nor the provider's field name has to
     * be known - which is what makes this work on the obfuscated build.</p>
     */
    @Nullable
    public static Object getCapability(Object player) {
        Object token = capabilityToken;
        if (!isAvailable() || token == null || player == null) {
            return null;
        }
        try {
            Method getCapability = findMethod(token.getClass(), "getCapability", Object.class, Object.class);
            if (getCapability == null) {
                return null;
            }
            Object lazyOptional = getCapability.invoke(token, player, null);
            if (lazyOptional == null) {
                return null;
            }
            Method orElse = findMethod(lazyOptional.getClass(), "orElse", Object.class);
            return orElse == null ? null : orElse.invoke(lazyOptional, (Object) null);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Does this player currently have an active YSM model?
     *
     * <p>Read from the same capability YSM's {@code ReplacePlayerRenderEvent} consults
     * before it cancels the vanilla render, so {@code true} means "YSM is about to take
     * this render over - SCG2's own third-person hooks will not run".</p>
     */
    public static boolean isModelActive(@Nullable Object capability) {
        return capability != null && invokeBoolean(capability, "isModelActive");
    }

    /** The player's currently selected YSM model id, for the diagnostics header. */
    @Nullable
    public static String getSelectedModelId(@Nullable Object capability) {
        Object value = invoke(capability, "getSelectedModelId");
        return value == null ? null : value.toString();
    }

    // ------------------------------------------------------------------
    // Animations
    // ------------------------------------------------------------------

    /**
     * Does the model behind this capability define the named animation?
     *
     * <p>Uses {@code AnimatableEntity#getAnimation(String)}, the same lookup the play
     * decision performs, so this check and the mixin cannot disagree. Lives on
     * {@code PlayerGeoEntity} for players, which is why the search walks the hierarchy.</p>
     *
     * <p>Takes the capability rather than an animation event, which is the form the model
     * report needs; {@link #hasAnimation(Object, String)} further down is the animation-event
     * form the mixin needs, and the two are deliberately distinct overloads - collapsing them
     * would invite passing the wrong object, which would silently answer "no animation" and
     * disable the whole feature.</p>
     */
    public static boolean modelHasAnimation(@Nullable Object capability, String animationName) {
        if (capability == null || animationName == null) {
            return false;
        }
        Method method = findMethod(capability.getClass(), "getAnimation", String.class);
        if (method == null) {
            return false;
        }
        try {
            return method.invoke(capability, animationName) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Every {@code tac:*} animation key the model defines, or an empty list.
     *
     * <p>Reads the arm animation map directly rather than probing a fixed name list: the
     * map is what {@code getAnimation} consults for players, and enumerating it is the
     * only way to report names this mod does not know about (a model author's own
     * {@code tac:something}).</p>
     */
    public static List<String> listTacAnimations(@Nullable Object capability) {
        return listTacAnimations(capability, "getArmAnimations");
    }

    /**
     * EVERY {@code tac:*} key the model defines, from the <b>main</b> animation bundle.
     *
     * <p>Separate from {@link #listTacAnimations} because the two bundles decide different
     * things and the difference is not academic: for a player, {@code AnimatableEntity
     * #getAnimation} resolves through {@code PlayerGeoEntity} to {@code getArmAnimations()},
     * while the third-person body animation is built from {@code getMainAnimations()}. A model
     * can legitimately hold its {@code tac:*} clips in only one of them - the model this was
     * written against has all 34 in the main bundle and none in the arm bundle - so a report
     * that merged them would overstate the arm and understate the main.</p>
     */
    public static List<String> listMainTacAnimations(@Nullable Object capability) {
        return listTacAnimations(capability, "getMainAnimations");
    }

    private static List<String> listTacAnimations(@Nullable Object capability, String bundleGetter) {
        if (capability == null) {
            return List.of();
        }
        Object bundle = animationBundleOf(capability);
        if (bundle == null) {
            return List.of();
        }

        Set<String> found = new java.util.TreeSet<>();
        Object map = invoke(bundle, bundleGetter);
        if (map instanceof Map<?, ?> animations) {
            for (Object key : animations.keySet()) {
                if (key instanceof String name && name.startsWith("tac:")) {
                    found.add(name);
                }
            }
        }
        return List.copyOf(found);
    }

    /**
     * The model's animation bundle, reached either directly or through the model assembly.
     *
     * <p>Two routes because {@code PlayerGeoEntity} exposes the bundle directly while older
     * builds only expose the assembly that holds it.</p>
     */
    @Nullable
    private static Object animationBundleOf(Object capability) {
        Object bundle = invoke(capability, "getAnimationBundle");
        if (bundle == null) {
            Object assembly = invoke(capability, "getModelAssembly");
            bundle = invoke(assembly, "getAnimationBundle");
        }
        return bundle;
    }

    /**
     * Does the model define this animation in its <b>main</b> bundle?
     *
     * <p>The third-person half of the guard. {@link #modelHasAnimation} asks the arm bundle
     * (which is where {@code getAnimation} resolves for a player), so it cannot answer this -
     * and the third-person pose is exactly the question that matters when a weapon animates in
     * first person but not in third.</p>
     */
    public static boolean mainBundleHasAnimation(@Nullable Object capability, String animationName) {
        return listMainTacAnimations(capability).contains(animationName);
    }

    // ------------------------------------------------------------------
    // Animation-event access, for the mixins
    //
    // Parameters are Object on purpose. The two YSM builds have DIFFERENT runtime types for
    // the animation event, the controller, the loop type and the play state, so a helper
    // typed against one namespace cannot be called from the mixin compiled against the
    // other. Reflection over member NAMES is namespace-independent, which is what makes one
    // copy of the decision logic serve both builds.
    //
    // Clips are identified by OBJECT IDENTITY, not by name: obfuscation gives every
    // animation the same obfuscated member name, so resolving them by name would be wrong.
    // The mixin holds the real objects and passes them through.
    // ------------------------------------------------------------------

    /**
     * Does the model behind this animation event define the named animation?
     *
     * <p>This is the guard the whole mod rests on: it performs the same lookup the play call
     * will perform, so a name that resolves here resolves there. The call chain matches YSM's
     * own for a player - {@code AnimationEvent#getAnimatable()} then
     * {@code AnimatableEntity#getAnimation(String)} - both resolved by name so the obfuscated
     * build works too.</p>
     */
    public static boolean hasAnimation(@Nullable Object animationEvent, String animationName) {
        Object animatable = animatableOf(animationEvent);
        return animationOf(animatable, animationName) != null;
    }

    /**
     * The animatable behind an animation event, resolving the accessor by build.
     *
     * <p>This call is where the mod was broken: {@code getAnimatable()} does not exist under that
     * name in the official release, so a name-based lookup returned null and every guard decision
     * became "no such animation".</p>
     */
    @Nullable
    public static Object animatableOf(@Nullable Object animationEvent) {
        if (animationEvent == null) {
            return null;
        }
        Method method = YsmMemberNames.resolve(animationEvent.getClass(),
                YsmMemberNames.Member.EVENT_ANIMATABLE);
        return method == null ? null : invokeMethod(method, animationEvent);
    }

    /**
     * The animation clip for a name, resolving {@code getAnimation(String)} by build.
     *
     * <p>Deliberately reads the <b>main</b> bundle: for a player, {@code AnimatableEntity
     * #getAnimation} resolves through {@code PlayerGeoEntity}, and the third-person body animation
     * is built from the main bundle. The arm bundle holds only the first-person arm clips.</p>
     */
    @Nullable
    public static Object animationOf(@Nullable Object animatable, String animationName) {
        if (animatable == null) {
            return null;
        }
        Method method = YsmMemberNames.resolve(animatable.getClass(),
                YsmMemberNames.Member.ANIMATABLE_ANIMATION, String.class);
        return method == null ? null : invokeMethod(method, animatable, animationName);
    }

    /**
     * Hands an animation to the event's controller.
     *
     * @param animationEvent the event
     * @param loopType       the loop type object, or null to use the clip's own
     * @return true when the controller accepted it
     */
    public static boolean playAnimation(@Nullable Object animationEvent, String animationName, @Nullable Object loopType) {
        Object controller = controllerOf(animationEvent);
        if (controller == null) {
            return false;
        }
        if (loopType != null) {
            Method withLoop = YsmMemberNames.resolve(controller.getClass(),
                    YsmMemberNames.Member.CONTROLLER_SET_ANIMATION, String.class, loopType.getClass());
            if (withLoop != null) {
                try {
                    withLoop.invoke(controller, animationName, loopType);
                    return true;
                } catch (Throwable ignored) {
                    // Fall through to the single-argument overload.
                }
            }
        }
        Method plain = YsmMemberNames.resolve(controller.getClass(),
                YsmMemberNames.Member.CONTROLLER_SET_ANIMATION, String.class);
        if (plain == null) {
            return false;
        }
        try {
            plain.invoke(controller, animationName);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Diagnostic: where exactly did the animation lookup fail?
     *
     * <p>A guard that declines is not informative on its own - "the clip is not in the model",
     * "the animatable could not be reached", and "the lookup ran against the arm bundle while the
     * third-person body animation lives in the main bundle" all produce the same null. This walks
     * the chain one step at a time and reports the class, the bundle sizes and each candidate so
     * the failing step is named rather than inferred.</p>
     */
    public static String describeAnimationLookup(@Nullable Object animationEvent, String wanted) {
        StringBuilder out = new StringBuilder();
        try {
            Object animatable = invoke(animationEvent, "getAnimatable");
            out.append("event=").append(animationEvent == null ? "null" : animationEvent.getClass().getName());
            out.append(" animatable=").append(animatable == null ? "null" : animatable.getClass().getName());
            if (animatable == null) {
                return out.toString();
            }

            Object viaGetAnimation = invoke(animatable, "getAnimation", wanted);
            out.append(" getAnimation(").append(wanted).append(")=")
                    .append(viaGetAnimation == null ? "null" : viaGetAnimation.getClass().getSimpleName());

            Object bundle = animationBundleOf(animatable);
            out.append(" bundle=").append(bundle == null ? "null" : bundle.getClass().getSimpleName());
            if (bundle == null) {
                return out.toString();
            }

            for (String getter : new String[]{"getMainAnimations", "getArmAnimations"}) {
                Object map = invoke(bundle, getter);
                if (map instanceof Map<?, ?> animations) {
                    out.append(' ').append(getter).append('=').append(animations.size());
                    if (animations.containsKey(wanted)) {
                        out.append("(HAS ").append(wanted).append(')');
                    }
                } else {
                    out.append(' ').append(getter).append("=unavailable");
                }
            }

            Object main = invoke(bundle, "getMainAnimations");
            if (main instanceof Map<?, ?> map && !map.isEmpty()) {
                Set<String> sample = new java.util.TreeSet<>();
                for (Object key : map.keySet()) {
                    if (key instanceof String name && name.startsWith("tac:")) {
                        sample.add(name);
                    }
                }
                out.append(" main tac:* keys=").append(sample.isEmpty() ? "(none)" : sample.toString());
            }
        } catch (Throwable t) {
            out.append(" lookup diagnosis threw ").append(t);
        }
        return out.toString();
    }
    /** The controller behind an animation event, resolving the accessor by build. */
    @Nullable
    public static Object controllerOf(@Nullable Object animationEvent) {
        if (animationEvent == null) {
            return null;
        }
        Method method = YsmMemberNames.resolve(animationEvent.getClass(),
                YsmMemberNames.Member.EVENT_CONTROLLER);
        return method == null ? null : invokeMethod(method, animationEvent);
    }

    /** The living entity behind an animation event, or null. */
    @Nullable
    public static Object entityOf(@Nullable Object animationEvent) {
        Object animatable = animatableOf(animationEvent);
        if (animatable == null) {
            return null;
        }
        Method method = YsmMemberNames.resolve(animatable.getClass(),
                YsmMemberNames.Member.ANIMATABLE_ENTITY);
        return method == null ? null : invokeMethod(method, animatable);
    }

    /** The loop type object for a loop-style name, or null. */
    @Nullable
    public static Object loopTypeFor(String loopKind) {
        return loopType(loopKind);
    }

    // ------------------------------------------------------------------
    // Reflection plumbing
    // ------------------------------------------------------------------

    private static final String LOOP_TYPE_CLASS =
            "com.elfmcys.yesstevemodel.geckolib3.core.builder.ILoopType$EDefaultLoopTypes";

    /**
     * Resolves a loop-style name ({@code LOOP}, {@code PLAY_ONCE},
     * {@code HOLD_ON_LAST_FRAME}) to YSM's {@code ILoopType}, or {@code null}.
     *
     * <p>Returns {@link Object} rather than {@code ILoopType} for the load-time reason this
     * whole class exists: a declared YSM type would have to be resolved when this class
     * loads.</p>
     */
    @Nullable
    public static Object loopType(String loopKind) {
        if (!isAvailable() || loopKind == null) {
            return null;
        }
        try {
            Class<?> loopTypes = Class.forName(LOOP_TYPE_CLASS);
            if (!loopTypes.isEnum()) {
                return null;
            }
            for (Object constant : loopTypes.getEnumConstants()) {
                if (constant instanceof Enum<?> enumConstant && enumConstant.name().equals(loopKind)) {
                    return constant;
                }
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Invokes a resolved method, swallowing anything it throws. */
    @Nullable
    public static Object invokeMethod(Method method, Object target, Object... args) {
        try {
            return method.invoke(target, args);
        } catch (Throwable t) {
            return null;
        }
    }

    @Nullable
    private static Object invoke(@Nullable Object target, String name, Object... args) {
        if (target == null) {
            return null;
        }
        Class<?>[] parameterTypes = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) {
            // With one String argument the only plausible overload takes a String, and
            // naming String.class is what lets a null-tolerant lookup stay possible.
            parameterTypes[i] = args[i] instanceof String ? String.class : args[i].getClass();
        }
        Method method = findMethod(target.getClass(), name, parameterTypes);
        if (method == null) {
            return null;
        }
        try {
            return method.invoke(target, args);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean invokeBoolean(@Nullable Object target, String name) {
        Object value = invoke(target, name);
        return value instanceof Boolean flag && flag;
    }

    /**
     * Walks up the hierarchy looking for a public method.
     *
     * <p>Needed because the interesting accessors are declared on different generations of
     * YSM's entity classes: {@code getSelectedModelId} on {@code CustomPlayerEntity},
     * {@code getAnimation} on {@code PlayerGeoEntity}, {@code isModelActive} on
     * {@code LivingAnimatable}. Where a method lives is an implementation detail; that it
     * exists at all is the contract.</p>
     *
     * <p>Bridge and synthetic methods are skipped: a generic accessor such as
     * {@code getAnimation(String)} compiles to a real method <em>and</em> a synthetic bridge
     * with an erased signature, and invoking the bridge through reflection without the exact
     * static type can fail.</p>
     */
    @Nullable
    private static Method findMethod(Class<?> type, String name, Class<?>... parameterTypes) {
        for (int i = 0; i < parameterTypes.length; i++) {
            if (parameterTypes[i] == null) {
                return null;
            }
        }
        String cacheKey = type.getName() + '#' + name + '/' + parameterTypes.length;
        Method cached = METHOD_CACHE.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Method method = current.getMethod(name, parameterTypes);
                if (method.isBridge() || method.isSynthetic()) {
                    continue;
                }
                METHOD_CACHE.put(cacheKey, method);
                return method;
            } catch (NoSuchMethodException ignored) {
                // Keep walking up.
            }
        }
        return null;
    }

    /** The {@code PlayState} constant with this name for the build being run, or null. */
    @Nullable
    public static Object playStateFor(String constantName) {
        return enumConstant(PLAY_STATE_CLASS_CANDIDATES, constantName);
    }

    /** The limb-swing amount of an animation event, or null when it cannot be read. */
    @Nullable
    public static Double limbSwingAmountOf(@Nullable Object animationEvent) {
        if (animationEvent == null) {
            return null;
        }
        Method method = YsmMemberNames.resolve(animationEvent.getClass(),
                YsmMemberNames.Member.EVENT_LIMB_SWING_AMOUNT);
        Object value = method == null ? null : invokeMethod(method, animationEvent);
        return value instanceof Number number ? number.doubleValue() : null;
    }

    /**
     * The play-state enum class, by either build's name.
     *
     * <p>Listed rather than probed by shape because it is a two-constant enum whose whole
     * meaning is its constants - there is no structure to key on, and the two names are the
     * complete set of builds that exist.</p>
     */
    private static final String[] PLAY_STATE_CLASS_CANDIDATES = {
            "com.elfmcys.yesstevemodel.geckolib3.core.enums.PlayState",
            "com.elfmcys.yesstevemodel.O0oOo0OoO0O0o0000o0O00o0",
    };

    @Nullable
    private static Object enumConstant(String[] classNames, String constantName) {
        for (String className : classNames) {
            try {
                Class<?> type = Class.forName(className, false, YsmBridge.class.getClassLoader());
                if (!type.isEnum()) {
                    continue;
                }
                for (Object constant : type.getEnumConstants()) {
                    if (constant instanceof Enum<?> named && named.name().equals(constantName)) {
                        return constant;
                    }
                }
            } catch (Throwable ignored) {
                // Try the next name.
            }
        }
        return null;
    }
}