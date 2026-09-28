package com.ysm.scg2.compat;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * Server-agnostic (COMMON) configuration.
 *
 * <p>All of this mod's work is client-side, but the config is declared COMMON so the
 * file is also readable on a dedicated server where the mod may be installed by
 * accident. Every option defaults to the safe/quiet value.</p>
 */
public final class CompatConfig {

    public static final ForgeConfigSpec SPEC;

    /**
     * Master switch for the animation translation.
     *
     * <p>When {@code false} the mod still loads and still logs, but it never rewrites a
     * YSM gun-animation result - useful for A/B testing whether a given model looks
     * better with or without the translation.</p>
     */
    public static final ForgeConfigSpec.BooleanValue ENABLE_GUN_ANIMATION;

    /**
     * Use the model's {@code tac:<action>:<type>$<namespace>:<path>} per-gun override
     * before the generic {@code tac:<action>:<type>} name.
     *
     * <p>Mirrors YSM's own {@code ConditionTAC} lookup, which is dead for SCG2 items
     * because TACZ is not the mod that owns them. Turning this off makes every SCG2
     * weapon of the same grip type share one animation.</p>
     */
    public static final ForgeConfigSpec.BooleanValue USE_PER_GUN_ANIMATION_OVERRIDE;

    /**
     * Print one line per resource reload listing every {@code tac:*} animation found in
     * the model the local player is currently wearing.
     *
     * <p>This is the single most useful diagnostic for this mod: it answers "does my YSM
     * model actually contain the tac:* animations" without any guesswork. It is what
     * decides whether the translation is allowed to fire at all.</p>
     */
    public static final ForgeConfigSpec.BooleanValue LOG_MODEL_TAC_ANIMATIONS;

    /**
     * Log each animation decision (match / no match / which name was chosen) at DEBUG.
     *
     * <p>Kept off by default because it runs per frame per player; turn it on when
     * reproducing a bug.</p>
     */
    public static final ForgeConfigSpec.BooleanValue LOG_ANIMATION_DECISIONS;

    /**
     * Log a one-line render-side probe (which BEWLR drew the held SCG2 weapon) at DEBUG.
     *
     * <p>Exists to settle the open question from the investigation: whether SCG2's own
     * {@code GunItemStackRenderer} / {@code AnimatedGunRenderer} is still reaching the
     * item render pipeline while a YSM model is drawn.</p>
     */
    public static final ForgeConfigSpec.BooleanValue LOG_RENDER_PROBE;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

        builder.comment(
                "Translation of Scorched Guns 2 weapons into the TACZ-shaped data that",
                "Yes Steve Model's gun animation pipeline expects.",
                "",
                "Background: YSM decides 'is this a gun?' purely with",
                "  stack.getItem() instanceof com.tacz.guns.api.item.IGun",
                "and SCG2's GunItem is not a TACZ gun (SCG2 has no TACZ dependency at all).",
                "So a YSM model never plays its built-in tac:* gun animations for an SCG2",
                "weapon, and the held weapon falls back to a plain item-holding pose.",
                "",
                "This mod answers the same question for SCG2 guns, but ONLY when the model",
                "being rendered actually contains the tac:* animation that would be chosen.",
                "That guard is not optional: YSM clears and then freezes a controller whose",
                "requested animation does not exist in the model (see",
                "AnimationControllerInstance#setAnimation), which visually looks like the",
                "whole model going stiff."
        );
        builder.push("animation");

        ENABLE_GUN_ANIMATION = builder
                .comment("Translate SCG2 guns into YSM's tac:* gun animations.",
                        "Set to false to disable all of this mod's animation changes.")
                .define("enable_gun_animation", true);

        USE_PER_GUN_ANIMATION_OVERRIDE = builder
                .comment("Prefer tac:<action>:<type>$<gun id> over tac:<action>:<type> when the model defines it.")
                .define("use_per_gun_animation_override", true);

        LOG_MODEL_TAC_ANIMATIONS = builder
                .comment("On every resource reload, log the tac:* animations of the local player's model.",
                        "Use this to check whether a model is even capable of the gun animations.")
                .define("log_model_tac_animations", true);

        LOG_ANIMATION_DECISIONS = builder
                .comment("Log every gun-animation decision. Very verbose (runs every frame).")
                .define("log_animation_decisions", false);

        builder.pop();

        builder.push("diagnostics");

        LOG_RENDER_PROBE = builder
                .comment("Log which custom item renderer drew the held SCG2 weapon (DEBUG level).",
                        "Diagnostic for the 'weapon model does not appear in third person' symptom.")
                .define("log_render_probe", false);

        builder.pop();

        SPEC = builder.build();
    }

    private CompatConfig() {
    }
}
