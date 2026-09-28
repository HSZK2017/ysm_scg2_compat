package com.ysm.scg2.compat;

import com.mojang.logging.LogUtils;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.loading.FMLLoader;
import org.slf4j.Logger;

/**
 * Yes Steve Model x Scorched Guns 2 compatibility.
 *
 * <h2>What this mod is for</h2>
 * <p>Yes Steve Model decides "is the item in this entity's hand a gun?" with a single
 * test - {@code stack.getItem() instanceof com.tacz.guns.api.item.IGun} - inside
 * {@code TacAnimHandler#isTaczGunItem}. Scorched Guns 2's {@code GunItem} is not a TACZ
 * gun: it extends {@code net.minecraft.world.item.Item}, and SCG2 has no TACZ dependency
 * anywhere in its sources or metadata.</p>
 *
 * <p>Consequence, in YSM's own code:</p>
 * <ol>
 *   <li>{@code MainHandHoldPredicate} asks
 *       {@code TacCompat#handleGunHoldAnimState}, which answers {@code null} for an SCG2
 *       weapon, so the model never enters its {@code tac:hold:*}/{@code tac:aim:*} gun
 *       poses and settles on the generic {@code hold_mainhand:*} item-holding pose.</li>
 *   <li>The molang variables {@code tac_hold_gun}, {@code tac_gun_type},
 *       {@code tac_is_fire}, ... stay {@code false}/{@code ""}.</li>
 *   <li>SCG2's own third-person presentation - its {@code ItemInHandLayer} mixin and its
 *       {@code PlayerModel#setupAnim} arm pose - is bypassed entirely, because YSM cancels
 *       {@code RenderPlayerEvent.Pre} and draws the player itself.</li>
 * </ol>
 *
 * <h2>What this mod does</h2>
 * <p>It answers YSM's two questions for SCG2 weapons - the same "spoof the TACZ shape"
 * trick that {@code scg2_maid_compat} uses for Touhou Little Maid - by patching YSM's own
 * compat entry points. See {@code client.mixin.TacCompatMixin}, which also documents why
 * the obvious one-line fix (spoofing {@code isTaczGunItem}) breaks the model instead.</p>
 *
 * <h2>Failure policy</h2>
 * <p>Three independent soft gates, so no single missing piece can take the game down:</p>
 * <ul>
 *   <li>{@code client.mixin.YsmScg2MixinPlugin} refuses to apply anything unless both YSM
 *       and SCG2 are loaded, so a game without YSM never resolves a YSM class;</li>
 *   <li>the mixin config sets {@code "defaultRequire": 0}, so a future YSM rename
 *       degrades to "no gun animations plus one Mixin warning" rather than a boot
 *       failure;</li>
 *   <li>every SCG2 API read goes through {@link com.ysm.scg2.compat.compat.Scg2GunAccess},
 *       which catches {@link Throwable} and falls back to a conservative value.</li>
 * </ul>
 *
 * <p>Each of the three logs which path it took, so "YSM not installed" and "injection did
 * not apply" are distinguishable in a bug report instead of both looking like silence.</p>
 */
@Mod(YsmScg2Compat.MOD_ID)
public class YsmScg2Compat {

    public static final String MOD_ID = "ysm_scg2_compat";

    /** Yes Steve Model's mod id. */
    public static final String YSM_MOD_ID = "yes_steve_model";

    /** Scorched Guns 2's mod id. */
    public static final String SCGUNS_MOD_ID = "scguns";

    public static final Logger LOGGER = LogUtils.getLogger();

    public YsmScg2Compat() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, CompatConfig.SPEC);

        if (!FMLLoader.getDist().isClient()) {
            LOGGER.info("[{}] dedicated server detected - this is a client-side compat layer, nothing to do.", MOD_ID);
            return;
        }

        boolean ysm = ModPresence.isLoaded(YSM_MOD_ID);
        boolean scguns = ModPresence.isLoaded(SCGUNS_MOD_ID);

        if (!scguns) {
            // Declared mandatory in mods.toml, so reaching this means a loader-level anomaly.
            LOGGER.warn("[{}] path: no-scguns. Scorched Guns 2 is missing; the compatibility layer is idle.", MOD_ID);
            return;
        }
        if (!ysm) {
            // The interesting half of the "which path matched" question. Not an error:
            // mods.toml declares yes_steve_model optional.
            LOGGER.info("[{}] path: no-ysm. Yes Steve Model is not installed; nothing to patch.", MOD_ID);
            return;
        }

        LOGGER.info("[{}] path: active. Yes Steve Model + Scorched Guns 2 both present; "
                        + "the mixin plugin will patch {} (guarded by 'does the model have the animation').",
                MOD_ID, "com.elfmcys.yesstevemodel.client.compat.gun.tacz.TacCompat");
    }

    /**
     * Is Yes Steve Model present?
     *
     * <p>Delegates to {@link ModPresence} rather than caching the answer in a static field.
     * A static initializer would freeze whatever the mod list looked like at the moment
     * this class happened to be loaded - and the class is loaded by the Mixin plugin
     * during startup, which is exactly the phase where that answer is not final yet.</p>
     */
    public static boolean isYsmPresent() {
        return ModPresence.isLoaded(YSM_MOD_ID);
    }

    /** Is Scorched Guns 2 present? Hard dependency, but the answer is still asked politely. */
    public static boolean isScgunsPresent() {
        return ModPresence.isLoaded(SCGUNS_MOD_ID);
    }
}
