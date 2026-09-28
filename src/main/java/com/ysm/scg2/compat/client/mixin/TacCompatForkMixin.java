package com.ysm.scg2.compat.client.mixin;

import com.elfmcys.yesstevemodel.geckolib3.core.enums.PlayState;
import com.elfmcys.yesstevemodel.geckolib3.core.event.predicate.AnimationEvent;
import net.minecraft.world.item.ItemStack;
import com.ysm.scg2.compat.client.GunAnimationDecision;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The animation bridge for YSM builds that keep <b>readable</b> class names: the community
 * fork in this workspace, and ModernYSM after it.
 *
 * <h2>Why a string target instead of {@code value = TacCompat.class}</h2>
 * <p>This mod compiles against the <em>official obfuscated release</em>, where
 * {@code client.compat.gun.tacz.TacCompat} does not exist. Naming the class as a literal would
 * make the mixin annotation processor fail the build, which the reference project
 * {@code ysm_epicfight_compat} works around with
 * {@code -AMSG_MIXIN_SOFT_TARGET_NOT_FOUND=warning}. The same flag is set here.</p>
 *
 * <h2>Why {@code remap = false}, and why the descriptor is spelled out</h2>
 * <p>YSM is a third-party mod loaded as a module; its members are not remapped by
 * ForgeGradle, so the literal names from YSM's own sources are what exist at runtime. Letting
 * the refmap "translate" them would point the injection at non-existent SRG names.</p>
 *
 * <p>The descriptor is written after the method name even though name alone would do here,
 * because {@code handleGunHoldAnimState} and {@code handleGunActionAnimState} carry
 * <b>identical</b> descriptors - and because the obfuscated build reuses one method name for
 * many members, so a handler that spelled only names would be one rename away from injecting
 * into the wrong method. Name plus descriptor is the unambiguous form.</p>
 *
 * <h2>Where the logic lives</h2>
 * <p>Not here. This class extends {@link Object} - a mixin's superclass has to be in the
 * target's hierarchy, and the target's hierarchy is a mod's private business - so every
 * decision is delegated to {@link GunAnimationDecision}, which is also what the mod's own
 * report reads. Keeping one copy of the logic is what stops the guard from drifting.</p>
 */
@Mixin(targets = "com.elfmcys.yesstevemodel.client.compat.gun.tacz.TacCompat", remap = false)
public abstract class TacCompatForkMixin {

    /**
     * Feeds the {@code hold_mainhand} controller.
     *
     * <p>Declining is what keeps the fallback alive, and it is why this injection carries no
     * regression risk: declining means "do not cancel", so {@code TacCompat} runs its own
     * body, which for an SCG2 weapon returns {@code null} (its gate is {@code isTaczGunItem},
     * and an SCG2 weapon is not an {@code IGun}). {@code MainHandHoldPredicate} only bails out
     * on a non-{@code null} result, so a {@code null} lets the predicate continue into the
     * declarative {@code hold_mainhand:*} channel that handles every other item. In other
     * words: this injection only ever changes the outcome from "no gun animation" to "the
     * model's own gun animation".</p>
     */
    @Inject(
            method = "handleGunHoldAnimState(Lnet/minecraft/world/item/ItemStack;"
                    + "Lcom/elfmcys/yesstevemodel/geckolib3/core/event/predicate/AnimationEvent;)"
                    + "Lcom/elfmcys/yesstevemodel/geckolib3/core/enums/PlayState;",
            at = @At("HEAD"), cancellable = true, remap = false)
    private static void ysmScg2Compat$gunHold(ItemStack stack,
                                              AnimationEvent<?> event,
                                              CallbackInfoReturnable<PlayState> cir) {
        if (!GunAnimationDecision.isEnabled() || !GunAnimationDecision.isScg2Gun(stack)) {
            return;
        }
        try {
            GunAnimationDecision.HoldOutcome outcome = GunAnimationDecision.decideHold(stack, event);
            if (outcome != null && GunAnimationDecision.play(event, outcome, "hold", stack)) {
                cir.setReturnValue(PlayState.CONTINUE);
            }
        } catch (Throwable t) {
            // Never let a compat layer take the frame down; fall through to YSM's logic.
            GunAnimationDecision.logFailure("handleGunHoldAnimState", t);
        }
    }

    /** Feeds the {@code fire} controller. See {@code decideAction} for the branch order. */
    @Inject(
            method = "handleGunActionAnimState(Lnet/minecraft/world/item/ItemStack;"
                    + "Lcom/elfmcys/yesstevemodel/geckolib3/core/event/predicate/AnimationEvent;)"
                    + "Lcom/elfmcys/yesstevemodel/geckolib3/core/enums/PlayState;",
            at = @At("HEAD"), cancellable = true, remap = false)
    private static void ysmScg2Compat$gunAction(ItemStack stack,
                                                AnimationEvent<?> event,
                                                CallbackInfoReturnable<PlayState> cir) {
        if (!GunAnimationDecision.isEnabled() || !GunAnimationDecision.isScg2Gun(stack)) {
            return;
        }
        try {
            GunAnimationDecision.HoldOutcome outcome = GunAnimationDecision.decideAction(stack, event);
            if (outcome != null && GunAnimationDecision.play(event, outcome, outcome.action.prefix(), stack)) {
                cir.setReturnValue(PlayState.CONTINUE);
            }
        } catch (Throwable t) {
            GunAnimationDecision.logFailure("handleGunActionAnimState", t);
        }
    }
}
