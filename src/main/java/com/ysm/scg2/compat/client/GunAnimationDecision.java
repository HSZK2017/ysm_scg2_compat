package com.ysm.scg2.compat.client;

import com.ysm.scg2.compat.CompatConfig;
import com.ysm.scg2.compat.ProbeLog;
import com.ysm.scg2.compat.YsmScg2Compat;
import com.ysm.scg2.compat.compat.Scg2GunAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.function.Function;

/**
 * The decision half of the animation bridge, shared by both build variants.
 *
 * <h2>Why every animation parameter here is {@code Object}</h2>
 * <p>Yes Steve Model ships two builds whose animation event, controller, loop type and play
 * state are <b>different runtime types</b> - the readable fork's
 * {@code geckolib3.core.event.predicate.AnimationEvent} and the official release's
 * {@code com.elfmcys.yesstevemodel.OO00O0o0OooOOOo00OO00o00}. A helper typed against one
 * namespace cannot be called from the mixin compiled against the other, so the event travels
 * as {@link Object} and every member access goes through {@link YsmBridge}'s name-based
 * reflection, which is namespace-independent. One copy of the logic, two builds - rather than
 * two copies that drift until models freeze on whichever one was last edited.</p>
 *
 * <h2>Why not spoof {@code isTaczGunItem}</h2>
 * <p>The tempting one-line fix is to make {@code TacAnimHandler#isTaczGunItem} report
 * {@code true} for {@code top.ribs.scguns.item.GunItem}. <b>That one-liner breaks the
 * model.</b> Following it through YSM:</p>
 *
 * <ol>
 *   <li>{@code isTaczGunItem} becomes {@code true}, so {@code handleTaczGunHold} runs and
 *       requests an animation name such as {@code tac:hold:rifle}.</li>
 *   <li>If the model does not contain that name,
 *       {@code AnimationControllerInstance#setAnimation} first calls
 *       {@code clearAnimation()} - dropping {@code currentAnimation} and every entry in
 *       {@code activeBoneAnimationQueues} - and then returns without setting a pending
 *       animation.</li>
 *   <li>{@code applyPendingAnimation()} therefore returns {@code false}, the controller sits
 *       in {@code IDLE} with a populated {@code lastRequestedAnimation} (so it short-circuits
 *       on every later tick), and it produces no bone transforms at all. Visually the whole
 *       model goes stiff.</li>
 * </ol>
 *
 * <p>That is the "model/skin disappears" failure mode. This class therefore only ever claims
 * a frame when the model <b>actually defines</b> the animation it is about to request:
 * {@code GunAnimationNames.resolve} is handed a predicate that performs the same lookup the
 * play call will perform, so "resolved" and "playable" cannot disagree. When nothing
 * resolves, the decision is {@code null} and the caller declines - leaving YSM's own logic
 * untouched, which is safe because that logic's answer for an SCG2 weapon is already "no gun
 * animation".</p>
 *
 * <h2>What is deliberately left inert</h2>
 * <p>The molang variables {@code tac_hold_gun}, {@code tac_gun_type}, {@code tac_is_fire} and
 * the rest stay {@code false}/{@code ""} for SCG2 weapons: they are bound in YSM's
 * {@code TacBinding} against TACZ's live API. Faking them would mean inventing aim progress,
 * fire modes, reload state and attachment data for a mod that computes all four differently.
 * Models driven by {@code tac:*} <em>animations</em> work; models that branch their whole
 * controller tree on {@code query.tac_hold_gun} do not.</p>
 */
public final class GunAnimationDecision {

    private GunAnimationDecision() {
    }

    /** Which animation to play, and the action that selected it. */
    public static final class HoldOutcome {
        public final String animation;
        public final GunAnimationNames.Action action;

        HoldOutcome(String animation, GunAnimationNames.Action action) {
            this.animation = animation;
            this.action = action;
        }
    }

    /** The {@code PlayState.CONTINUE} object for the build being run, or {@code null}. */
    @Nullable
    public static Object continueState() {
        return YsmBridge.playStateFor("CONTINUE");
    }

    /**
     * Decides the hold pose, or {@code null} to decline.
     *
     * @param stack the held item, already known to be an SCG2 weapon
     * @param event this frame's animation event (either build's type)
     */
    @Nullable
    public static HoldOutcome decideHold(ItemStack stack, Object event) {
        String gunType = Scg2GunAccess.getGunAnimationType(stack);
        String name = GunAnimationNames.resolve(
                existsIn(event),
                GunAnimationNames.Action.HOLD,
                gunType,
                gunIdOf(stack),
                CompatConfig.USE_PER_GUN_ANIMATION_OVERRIDE.get());
        if (name == null) {
            logDeclined("hold", gunType, stack);
            ProbeLog.log("guard", "decideHold DECLINED: no tac:hold:* clip resolves for type=" + gunType
                    + " item=" + describe(stack));
            diagnoseDecline(event, GunAnimationNames.Action.HOLD.forType(gunType));
            return null;
        }
        ProbeLog.log("guard", "decideHold MATCHED " + name + " for " + describe(stack));
        return new HoldOutcome(name, GunAnimationNames.Action.HOLD);
    }

    /**
     * Decides the action pose (reload / melee / firing), or {@code null} to decline.
     *
     * <p>Mirrors YSM's own precedence in {@code TacAnimHandler#handleTaczGunAction} - a reload
     * outranks a melee which outranks firing - with one change: each branch is taken only when
     * the model actually defines that animation, so a model with, say, only
     * {@code tac:hold:fire:rifle} keeps firing through a reload instead of having its
     * controller cleared for the duration.</p>
     */
    @Nullable
    public static HoldOutcome decideAction(ItemStack stack, Object event) {
        LivingEntity entity = entityOf(event);
        if (entity == null) {
            return null;
        }

        String gunType = Scg2GunAccess.getGunAnimationType(stack);
        GunAnimationNames.Action action = pickAction(event, entity, gunType);
        if (action == null) {
            if (CompatConfig.LOG_ANIMATION_DECISIONS.get()) {
                YsmScg2Compat.LOGGER.debug("[{}] action: no SCG2 state active ({} on {})",
                        YsmScg2Compat.MOD_ID, gunType, entity.getName().getString());
            }
            return null;
        }

        String name = GunAnimationNames.resolve(
                existsIn(event),
                action,
                gunType,
                gunIdOf(stack),
                CompatConfig.USE_PER_GUN_ANIMATION_OVERRIDE.get());
        if (name == null) {
            logDeclined(action.prefix(), gunType, stack);
            ProbeLog.log("guard", "decideAction DECLINED: no clip resolves for " + action + " type=" + gunType);
            return null;
        }
        ProbeLog.log("guard", "decideAction MATCHED " + name + " (" + action + ") for " + describe(stack));
        return new HoldOutcome(name, action);
    }

    /**
     * Which action the weapon is in, or {@code null} when none applies.
     *
     * <p>Reload and melee are gated on the model defining their clip, so a weapon that is
     * reloading while the model has no reload animation falls through to the firing branch
     * rather than clearing the controller.</p>
     */
    @Nullable
    private static GunAnimationNames.Action pickAction(Object event, LivingEntity entity, String gunType) {
        if (Scg2GunAccess.isReloading(entity)
                && GunAnimationNames.exists(existsIn(event), GunAnimationNames.Action.RELOAD, gunType)) {
            return GunAnimationNames.Action.RELOAD;
        }
        if (Scg2GunAccess.isMelee(entity)
                && GunAnimationNames.exists(existsIn(event), GunAnimationNames.Action.MELEE, gunType)) {
            return GunAnimationNames.Action.MELEE;
        }
        if (!Scg2GunAccess.isFiring(entity)) {
            return null;
        }
        if (isClimbingPose(event, entity)) {
            return GunAnimationNames.Action.CLIMBING_FIRE;
        }
        if (Scg2GunAccess.isAiming(entity)) {
            return GunAnimationNames.Action.AIM_FIRE;
        }
        if (entity.onGround() && entity.isSprinting()) {
            return GunAnimationNames.Action.RUN;
        }
        return GunAnimationNames.Action.HOLD_FIRE;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * The model probe, bound to this frame's event.
     *
     * <p>This is the whole safety mechanism: {@code getAnimation} is the same lookup the play
     * call performs, so a name that resolves here resolves there. Returning {@code false} is
     * what makes the caller decline instead of clearing a controller it cannot refill.</p>
     */
    private static Function<String, Boolean> existsIn(Object event) {
        return name -> YsmBridge.hasAnimation(event, name);
    }

    /** The swimming/climbing pose, which YSM treats as its own gun-pose family. */
    private static boolean isClimbingPose(Object event, LivingEntity entity) {
        if (entity.getPose() != Pose.SWIMMING) {
            return false;
        }
        Double limbSwingAmount = YsmBridge.limbSwingAmountOf(event);
        return limbSwingAmount == null || Math.abs(limbSwingAmount) <= 0.05d;
    }

    @Nullable
    private static LivingEntity entityOf(Object event) {
        Object entity = YsmBridge.entityOf(event);
        return entity instanceof LivingEntity living ? living : null;
    }

    public static boolean isScg2Gun(ItemStack stack) {
        return Scg2GunAccess.isScg2Gun(stack);
    }

    /**
     * Set the first time this class's decision code runs, i.e. the first time the injected
     * handler is actually reached.
     *
     * <p>This is the one signal that cannot be misread. Mixin keeps the target method's own
     * signature when it injects (the {@code CallbackInfoReturnable} is added to the <em>handler
     * method in the mixin</em>, not to the target), so inspecting the target's parameter count
     * proves nothing - a check that reported "NOT ATTACHED" from that reasoning was simply
     * wrong.</p>
     *
     * <p>Behaviour, by contrast, is not ambiguous: this code only runs if a handler is attached
     * and reached. Logging it once answers "is the injection live?" directly, and if it never
     * appears then the handler was never called - which is a different problem from the guard
     * declining, and the two need different fixes.</p>
     */
    private static volatile boolean reached;

    public static boolean isEnabled() {
        if (!reached) {
            reached = true;
            YsmScg2Compat.LOGGER.info("[{}] injected gun-animation handler REACHED for the first time "
                            + "(mixin is attached and being called). ENABLE_GUN_ANIMATION={}",
                    YsmScg2Compat.MOD_ID, CompatConfig.ENABLE_GUN_ANIMATION.get());
            ProbeLog.log("guard", "REACHED: the injected handler is attached and was called."
                    + " enabled=" + CompatConfig.ENABLE_GUN_ANIMATION.get()
                    + " ysmPresent=" + YsmScg2Compat.isYsmPresent()
                    + " bridgeAvailable=" + YsmBridge.isAvailable());
        }
        return CompatConfig.ENABLE_GUN_ANIMATION.get() && YsmScg2Compat.isYsmPresent();
    }

    /** Whether the injected handler has ever run. Read by the heartbeat. */
    public static boolean hasBeenReached() {
        return reached;
    }

    /** Caps the expensive per-call diagnosis so the probe file stays readable. */
    private static final java.util.concurrent.atomic.AtomicInteger DECLINE_DIAGNOSES =
            new java.util.concurrent.atomic.AtomicInteger();

    /** Diagnose only the first few declines; the same answer repeats thousands of times. */
    private static void diagnoseDecline(Object event, String wanted) {
        if (DECLINE_DIAGNOSES.incrementAndGet() > 6) {
            return;
        }
        ProbeLog.log("guard", "decline diagnosis for '" + wanted + "' -> "
                + YsmBridge.describeAnimationLookup(event, wanted));
    }

    @Nullable
    private static String gunIdOf(ItemStack stack) {
        ResourceLocation id = Scg2GunAccess.getGunId(stack);
        return id == null ? null : id.toString();
    }

    /**
     * Hands the decided animation to the controller and claims the frame.
     *
     * <p>Both variants end here, so "the guard passed" and "the animation reached the
     * controller" cannot diverge between builds.</p>
     *
     * @return true when the animation was accepted and the callback should be cancelled
     */
    public static boolean play(Object event, HoldOutcome outcome, String what, ItemStack stack) {
        Object loopType = YsmBridge.loopTypeFor(outcome.action.loopKind());
        ProbeLog.log("guard", "play(" + outcome.animation + ", loop=" + loopType + ")");
        if (!YsmBridge.playAnimation(event, outcome.animation, loopType)) {
            // The controller refused it. Declining is the safe answer: YSM's own path runs
            // and the weapon keeps the generic item-holding pose.
            YsmScg2Compat.LOGGER.warn("[{}] {}: the controller rejected '{}'; leaving YSM's own path in charge",
                    YsmScg2Compat.MOD_ID, what, outcome.animation);
            return false;
        }
        logMatched(what, outcome.animation, stack);
        return true;
    }

    public static void logMatched(String what, String animation, ItemStack stack) {
        if (!CompatConfig.LOG_ANIMATION_DECISIONS.get()) {
            return;
        }
        YsmScg2Compat.LOGGER.debug("[{}] {}: matched '{}' (item={})",
                YsmScg2Compat.MOD_ID, what, animation, describe(stack));
    }

    private static void logDeclined(String what, String gunType, ItemStack stack) {
        if (!CompatConfig.LOG_ANIMATION_DECISIONS.get()) {
            return;
        }
        YsmScg2Compat.LOGGER.debug("[{}] {}: model has no tac:* animation for type={} (item={}) - "
                        + "declined, YSM's generic item-holding path continues",
                YsmScg2Compat.MOD_ID, what, gunType, describe(stack));
    }

    public static void logFailure(String method, Throwable t) {
        YsmScg2Compat.LOGGER.warn("[{}] {} failed; falling back to YSM's own behaviour ({})",
                YsmScg2Compat.MOD_ID, method, t.toString());
    }

    private static String describe(ItemStack stack) {
        ResourceLocation id = Scg2GunAccess.getGunId(stack);
        return id == null ? stack.getItem().toString() : id.toString();
    }
}
