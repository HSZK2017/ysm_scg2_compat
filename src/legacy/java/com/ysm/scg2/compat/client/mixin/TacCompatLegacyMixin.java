package com.ysm.scg2.compat.client.mixin;

import com.elfmcys.yesstevemodel.O0oOo0OoO0O0o0000o0O00o0;
import com.elfmcys.yesstevemodel.OO00O0o0OooOOOo00OO00o00;
import net.minecraft.world.item.ItemStack;
import com.ysm.scg2.compat.client.GunAnimationDecision;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The animation bridge for the <b>obfuscated official YSM release</b> - the build almost
 * every player has, and the counterpart of {@link TacCompatForkMixin}.
 *
 * <h2>Why this lives in its own source set</h2>
 * <p>Look at the imports: {@code O0oOo0OoO0O0o0000o0O00o0} is what the official release calls
 * {@code PlayState}, and {@code OO00O0o0OooOOOo00OO00o00} is its {@code AnimationEvent}. A
 * mixin handler's parameters <b>must match the target method's parameter types</b>, so this
 * variant cannot be written in a class compiled against the readable fork - the types it
 * needs to name do not exist there, and {@code Object} is rejected by Mixin's type check.</p>
 *
 * <p>The reference project {@code ysm_epicfight_compat} supports the same build the same way:
 * it imports obfuscated class names directly in its {@code @Mixin} annotations. That is the
 * established technique; this source set is just that technique given its own compile
 * classpath, so the readable variant is not dragged into the obfuscated namespace.</p>
 *
 * <h2>How the targets were derived</h2>
 * <p>Obfuscation renames members but does <b>not</b> rewrite descriptors, so the targets were
 * followed from the readable fork's source:</p>
 *
 * <ol>
 *   <li>Scan the official jar for classes whose constant pool mentions
 *       {@code com/tacz/guns/api/item/IGun} - the TACZ API only the bridge touches. Two
 *       match.</li>
 *   <li>The {@code TacCompat} equivalent is the one carrying the whole wrapper API, plus the
 *       {@code isGun(Player)} overload, plus the nine molang lambdas:
 *       {@code com.elfmcys.yesstevemodel.OOO0O0O0oo0ooooo00oOOOO0}.</li>
 *   <li>The two gun wrappers share one descriptor, so the descriptor cannot separate them.
 *       Each was matched to its handler by the {@code tac:*} constants of the handler method
 *       it calls: the one building {@code tac:hold:} / {@code tac:aim:} / {@code tac:run:} /
 *       {@code tac:climb:} is the hold wrapper, and the one building {@code tac:reload:} /
 *       {@code tac:melee:} / {@code tac:*:fire:} is the action wrapper.</li>
 * </ol>
 *
 * <p>The trap this avoids: the obfuscator reuses {@code Oo0Oo0o00O00Oo0OOoOOoooo} for eight
 * members of this class, most of them unrelated ({@code init}, {@code registerControllerFunctions},
 * {@code applyItemTransform}, {@code handleTaczAnimState}, {@code handleGunSound},
 * {@code handleItemSound}, and the lambdas). A bare method name would inject into the wrong
 * one, so every injection below carries the full descriptor, and
 * {@code tools/verify-mixin-targets.ps1} checks that descriptor against the real jar -
 * including the fact that it is one specific overload out of eight.</p>
 *
 * <h2>Where the logic lives</h2>
 * <p>Not here. Every decision is delegated to {@link GunAnimationDecision}, which the readable
 * variant calls too, so the model-animation guard cannot drift between builds - a guard that
 * drifted would freeze models on exactly one of them.</p>
 */
@Mixin(targets = "com.elfmcys.yesstevemodel.OOO0O0O0oo0ooooo00oOOOO0", remap = false)
public abstract class TacCompatLegacyMixin {

    /**
     * {@code TacCompat#handleGunHoldAnimState(ItemStack, AnimationEvent)} - the
     * {@code hold_mainhand} controller's question.
     *
     * <p>Declining is what keeps the fallback alive: declining means "do not cancel", so the
     * original body runs and returns {@code null} for an SCG2 weapon, which lets YSM's
     * predicate continue into the declarative {@code hold_mainhand:*} channel. This injection
     * therefore only ever changes the outcome from "no gun animation" to "the model's own gun
     * animation".</p>
     */
    @Inject(
            method = "Oo0Oo0o00O00Oo0OOoOOoooo(Lnet/minecraft/world/item/ItemStack;"
                    + "Lcom/elfmcys/yesstevemodel/OO00O0o0OooOOOo00OO00o00;)"
                    + "Lcom/elfmcys/yesstevemodel/O0oOo0OoO0O0o0000o0O00o0;",
            at = @At("HEAD"), cancellable = true, remap = false)
    private static void ysmScg2Compat$gunHold(ItemStack stack,
                                              OO00O0o0OooOOOo00OO00o00<?> event,
                                              CallbackInfoReturnable<O0oOo0OoO0O0o0000o0O00o0> cir) {
        if (!GunAnimationDecision.isEnabled() || !GunAnimationDecision.isScg2Gun(stack)) {
            return;
        }
        try {
            GunAnimationDecision.HoldOutcome outcome = GunAnimationDecision.decideHold(stack, event);
            if (outcome != null && GunAnimationDecision.play(event, outcome, "hold", stack)) {
                cir.setReturnValue((O0oOo0OoO0O0o0000o0O00o0) GunAnimationDecision.continueState());
            }
        } catch (Throwable t) {
            // Never let a compat layer take the frame down; fall through to YSM's logic.
            GunAnimationDecision.logFailure("handleGunHoldAnimState(legacy)", t);
        }
    }

    /**
     * {@code TacCompat#handleGunActionAnimState(ItemStack, AnimationEvent)} - the {@code fire}
     * controller's question.
     *
     * <p>Same descriptor as the hold wrapper; only the method name separates them, which is
     * why the name is not optional here.</p>
     */
    @Inject(
            method = "o0OOooo0o0OO00OoOOOo0o0O(Lnet/minecraft/world/item/ItemStack;"
                    + "Lcom/elfmcys/yesstevemodel/OO00O0o0OooOOOo00OO00o00;)"
                    + "Lcom/elfmcys/yesstevemodel/O0oOo0OoO0O0o0000o0O00o0;",
            at = @At("HEAD"), cancellable = true, remap = false)
    private static void ysmScg2Compat$gunAction(ItemStack stack,
                                                OO00O0o0OooOOOo00OO00o00<?> event,
                                                CallbackInfoReturnable<O0oOo0OoO0O0o0000o0O00o0> cir) {
        if (!GunAnimationDecision.isEnabled() || !GunAnimationDecision.isScg2Gun(stack)) {
            return;
        }
        try {
            GunAnimationDecision.HoldOutcome outcome = GunAnimationDecision.decideAction(stack, event);
            if (outcome != null && GunAnimationDecision.play(event, outcome, outcome.action.prefix(), stack)) {
                cir.setReturnValue((O0oOo0OoO0O0o0000o0O00o0) GunAnimationDecision.continueState());
            }
        } catch (Throwable t) {
            GunAnimationDecision.logFailure("handleGunActionAnimState(legacy)", t);
        }
    }
}
