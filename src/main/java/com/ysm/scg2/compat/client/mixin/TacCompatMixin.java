package com.ysm.scg2.compat.client.mixin;

import com.elfmcys.yesstevemodel.client.compat.gun.tacz.TacCompat;
import com.elfmcys.yesstevemodel.geckolib3.core.event.predicate.AnimationEvent;
import com.elfmcys.yesstevemodel.geckolib3.core.enums.PlayState;
import com.elfmcys.yesstevemodel.client.entity.LivingAnimatable;
import com.ysm.scg2.compat.CompatConfig;
import com.ysm.scg2.compat.YsmScg2Compat;
import com.ysm.scg2.compat.client.GunAnimationNames;
import com.ysm.scg2.compat.compat.Scg2GunAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Teaches Yes Steve Model's TACZ compatibility layer about Scorched Guns 2 weapons.
 *
 * <h2>Why these two methods and not {@code TacAnimHandler#isTaczGunItem}</h2>
 * <p>The tempting one-line fix is to spoof {@code TacAnimHandler#isTaczGunItem} so it
 * also reports {@code true} for {@code top.ribs.scguns.item.GunItem}. <b>That one-liner
 * breaks the model.</b> Following it through YSM:</p>
 *
 * <ol>
 *   <li>{@code isTaczGunItem} becomes {@code true}, so
 *       {@code TacAnimHandler#handleTaczGunHold} runs and requests an animation name
 *       such as {@code tac:hold:rifle}.</li>
 *   <li>If the model does not contain that name,
 *       {@code AnimationControllerInstance#setAnimation} first calls
 *       {@code clearAnimation()} - which drops {@code currentAnimation} and every entry in
 *       {@code activeBoneAnimationQueues} - and then returns without setting a pending
 *       animation.</li>
 *   <li>{@code applyPendingAnimation()} therefore returns {@code false}, the controller
 *       sits in {@code IDLE} with a populated {@code lastRequestedAnimation} (so it
 *       short-circuits on every later tick), and it produces no bone transforms at all.
 *       Visually the whole model goes stiff.</li>
 * </ol>
 *
 * <p>That is the "model/skin disappears" failure mode, and it is why this mixin patches
 * the two <em>entry points</em> instead. Both of them expose the {@link AnimationEvent},
 * which is the only thing that can answer "does this model actually have the animation we
 * are about to ask for". When the answer is no, the injection simply does not cancel and
 * YSM's original logic runs unchanged - the weapon stays on the generic item-holding pose
 * it has today, and nothing regresses.</p>
 *
 * <h2>Why not patch the {@code isTaczGunItem} call sites on {@code TacCompat}</h2>
 * <p>{@code TacCompat#handleGunHoldAnimState} calls {@code isTaczGunItem} <em>before</em>
 * {@code handleTaczGunHold}, so an override limited to {@code handleTaczGunHold} would
 * never be reached. {@code handleGunActionAnimState} and {@code handleTaczAnimState} do
 * receive the event.</p>
 *
 * <h2>What is left on the TACZ path</h2>
 * <p>{@code tac_hold_gun}, {@code tac_gun_type} and the rest of the molang variables stay
 * {@code false}/{@code ""} for SCG2 weapons, because they are bound in
 * {@code TacBinding} against TACZ's own API. Models that drive gun poses with
 * {@code tac:*} <em>animations</em> work; models that branch their whole controller tree
 * on {@code query.tac_hold_gun} do not. That is deliberate: pretending to TACZ's API
 * would mean faking aim progress, fire modes and attachment data for a mod that has its
 * own, different notions of all three.</p>
 *
 * <h2>Mapping discipline</h2>
 * <p>The target is a third-party mod class, so every injection uses
 * {@code remap = false} with the literal method name that appears in OpenYSM's own
 * sources. Combined with {@code "defaultRequire": 0} in the mixin config, a future YSM
 * rename degrades to "no gun animations plus one Mixin warning", never to a boot
 * failure.</p>
 */
@Mixin(value = TacCompat.class, remap = false)
public abstract class TacCompatMixin {

    /**
     * {@code TacCompat#handleGunHoldAnimState} feeds YSM's {@code hold_mainhand}
     * controller.
     *
     * <p>Declining here is what keeps the fallback alive, and it is why this injection has
     * no regression risk. Declining means "do not cancel", so
     * {@code TacCompat#handleGunHoldAnimState} runs its own body, which for an SCG2 weapon
     * returns {@code null} (its gate is {@code isTaczGunItem}, and an SCG2 weapon is not
     * an {@code IGun}). {@code MainHandHoldPredicate} only bails out when the result is
     * non-{@code null}, so a {@code null} lets the predicate continue into the declarative
     * {@code hold_mainhand:*} condition channel that handles every other item. In other
     * words: this injection only ever changes the outcome from "no gun animation" to
     * "the model's own gun animation", and never from one animation to a worse one.</p>
     */
    @Inject(method = "handleGunHoldAnimState", at = @At("HEAD"), cancellable = true, remap = false)
    private static void ysmScg2Compat$gunHold(ItemStack stack,
                                              AnimationEvent<? extends LivingAnimatable<?>> event,
                                              CallbackInfoReturnable<PlayState> cir) {
        if (!CompatConfig.ENABLE_GUN_ANIMATION.get()) {
            return;
        }
        if (!YsmScg2Compat.isYsmPresent() || !Scg2GunAccess.isScg2Gun(stack)) {
            return;
        }

        try {
            String gunType = Scg2GunAccess.getGunAnimationType(stack);
            String name = GunAnimationNames.resolve(event, GunAnimationNames.Action.HOLD, gunType,
                    gunIdOf(stack), CompatConfig.USE_PER_GUN_ANIMATION_OVERRIDE.get());
            if (name == null) {
                // The model has no tac:hold:<type>. Decline rather than request a name the
                // model cannot resolve - see the class doc, step 2.
                logDeclined("hold", gunType, stack);
                return;
            }
            logMatched("hold", gunType, name, stack);
            cir.setReturnValue(GunAnimationNames.play(event, name, GunAnimationNames.Action.HOLD.loopType()));
        } catch (Throwable t) {
            // Never let a compat layer take the frame down; fall through to YSM's logic.
            logFailure("handleGunHoldAnimState", t);
        }
    }

    /**
     * {@code TacCompat#handleGunActionAnimState} feeds YSM's {@code fire} controller.
     *
     * <h3>Ordering of the branches</h3>
     * <p>Mirrors YSM's own precedence in {@code TacAnimHandler#handleTaczGunAction} - a
     * reload outranks a melee which outranks firing - with one change: each branch is only
     * taken when the model actually defines that animation. YSM's version requests the
     * name unconditionally, which for a model without (say) a reload clip means the
     * controller is cleared for the duration of the reload. Here a model with only
     * {@code tac:hold:fire:rifle} keeps firing correctly during a reload instead of
     * freezing.</p>
     */
    @Inject(method = "handleGunActionAnimState", at = @At("HEAD"), cancellable = true, remap = false)
    private static void ysmScg2Compat$gunAction(ItemStack stack,
                                                AnimationEvent<? extends LivingAnimatable<?>> event,
                                                CallbackInfoReturnable<PlayState> cir) {
        if (!CompatConfig.ENABLE_GUN_ANIMATION.get()) {
            return;
        }
        if (!YsmScg2Compat.isYsmPresent() || !Scg2GunAccess.isScg2Gun(stack)) {
            return;
        }

        try {
            LivingEntity entity = entityOf(event);
            if (entity == null) {
                return;
            }

            String gunType = Scg2GunAccess.getGunAnimationType(stack);
            ResourceLocation gunId = Scg2GunAccess.getGunId(stack);
            boolean perGun = CompatConfig.USE_PER_GUN_ANIMATION_OVERRIDE.get();
            String gunIdString = gunId == null ? null : gunId.toString();

            // 1. Reloading wins over everything else.
            if (Scg2GunAccess.isReloading(entity)
                    && GunAnimationNames.exists(event, GunAnimationNames.Action.RELOAD, gunType)) {
                playResolved(event, cir, "reload", GunAnimationNames.Action.RELOAD, gunType, gunIdString, perGun);
                return;
            }

            // 2. Bayonet / gun-butt melee.
            if (Scg2GunAccess.isMelee(entity)
                    && GunAnimationNames.exists(event, GunAnimationNames.Action.MELEE, gunType)) {
                playResolved(event, cir, "melee", GunAnimationNames.Action.MELEE, gunType, gunIdString, perGun);
                return;
            }

            // 3. Firing. Only reachable while SCG2 reports the trigger is held / the shot
            //    is on cooldown; the swimming-pose branch matches YSM's own behaviour.
            if (Scg2GunAccess.isFiring(entity)) {
                boolean swimming = !entity.isSwimming() && entity.getPose() == Pose.SWIMMING;
                boolean aiming = Scg2GunAccess.isAiming(entity);

                GunAnimationNames.Action action;
                if (swimming && Math.abs(event.getLimbSwingAmount()) <= 0.05d) {
                    action = GunAnimationNames.Action.CLIMBING_FIRE;
                } else if (aiming) {
                    action = GunAnimationNames.Action.AIM_FIRE;
                } else {
                    action = GunAnimationNames.Action.HOLD_FIRE;
                }

                playResolved(event, cir, "fire/" + action.prefix(), action, gunType, gunIdString, perGun);
                return;
            }

            // 4. Nothing to do: leave the frame to the other controllers.
            if (CompatConfig.LOG_ANIMATION_DECISIONS.get()) {
                YsmScg2Compat.LOGGER.debug("[{}] action: no SCG2 state active ({} on {})",
                        YsmScg2Compat.MOD_ID, gunType, entity.getName().getString());
            }
        } catch (Throwable t) {
            logFailure("handleGunActionAnimState", t);
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static void playResolved(AnimationEvent<?> event,
                                     CallbackInfoReturnable<PlayState> cir,
                                     String what,
                                     GunAnimationNames.Action action,
                                     String gunType,
                                     @Nullable String gunId,
                                     boolean perGun) {
        String name = GunAnimationNames.resolve(event, action, gunType, gunId, perGun);
        if (name == null) {
            logDeclined(what, gunType, null);
            return;
        }
        logMatched(what, gunType, name, null);
        cir.setReturnValue(GunAnimationNames.play(event, name, action.loopType()));
    }

    @Nullable
    private static LivingEntity entityOf(AnimationEvent<? extends LivingAnimatable<?>> event) {
        LivingAnimatable<?> animatable = event.getAnimatable();
        return animatable == null ? null : animatable.getEntity();
    }

    @Nullable
    private static String gunIdOf(ItemStack stack) {
        ResourceLocation id = Scg2GunAccess.getGunId(stack);
        return id == null ? null : id.toString();
    }

    private static void logMatched(String what, String gunType, String name, @Nullable ItemStack stack) {
        if (!CompatConfig.LOG_ANIMATION_DECISIONS.get()) {
            return;
        }
        YsmScg2Compat.LOGGER.debug("[{}] {}: matched '{}' (type={}, item={})",
                YsmScg2Compat.MOD_ID, what, name, gunType, describe(stack));
    }

    private static void logDeclined(String what, String gunType, @Nullable ItemStack stack) {
        if (!CompatConfig.LOG_ANIMATION_DECISIONS.get()) {
            return;
        }
        YsmScg2Compat.LOGGER.debug("[{}] {}: model has no tac:* animation for type={} (item={}) - "
                        + "declined, YSM's generic item-holding path continues",
                YsmScg2Compat.MOD_ID, what, gunType, describe(stack));
    }

    private static void logFailure(String method, Throwable t) {
        YsmScg2Compat.LOGGER.warn("[{}] {} failed; falling back to YSM's own behaviour ({})",
                YsmScg2Compat.MOD_ID, method, t.toString());
    }

    private static String describe(@Nullable ItemStack stack) {
        if (stack == null) {
            return "-";
        }
        ResourceLocation id = Scg2GunAccess.getGunId(stack);
        return id == null ? stack.getItem().toString() : id.toString();
    }
}
