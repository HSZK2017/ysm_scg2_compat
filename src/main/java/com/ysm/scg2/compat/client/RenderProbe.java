package com.ysm.scg2.compat.client;

import com.elfmcys.yesstevemodel.capability.PlayerCapability;
import com.elfmcys.yesstevemodel.capability.PlayerCapabilityProvider;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.PoseStack.Pose;
import com.ysm.scg2.compat.CompatConfig;
import com.ysm.scg2.compat.YsmScg2Compat;
import com.ysm.scg2.compat.compat.Scg2GunAccess;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderPlayerEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Vector3f;

import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Render-side probe for the one symptom this mod does not fix: the Scorched Guns 2 weapon
 * model itself not appearing in third person while a YSM model is drawn.
 *
 * <h2>What it measures</h2>
 * <p>Two facts per player per frame, both of which narrow the cause without guessing:</p>
 * <ol>
 *   <li><b>Is YSM actually replacing the render?</b> YSM cancels
 *       {@code RenderPlayerEvent.Pre} and draws the player itself, which also means SCG2's
 *       own third-person hooks (its {@code ItemInHandLayer} mixin and its
 *       {@code PlayerModel#setupAnim} arm pose) are never reached.</li>
 *   <li><b>Does the pose stack the item layer will use already have a translation?</b>
 *       SCG2's {@code GunItemStackRenderer#renderByItem} opens with
 *       {@code poseStack.popPose()} - a hack whose comment says it removes the transforms
 *       {@code ItemRenderer#render} applies. When YSM supplies the pose stack instead, that
 *       pop removes <em>YSM's</em> hand-bone matrix, which is the prime suspect for a
 *       weapon that is drawn but not in the hand. Reporting the stack translation here
 *       (before YSM's own layer runs) makes that visible.</li>
 * </ol>
 *
 * <p>Off by default, DEBUG level when on. It is a measurement, not a fix: changing the
 * pose stack from the outside would be guesswork until someone has read these two lines.</p>
 */
@Mod.EventBusSubscriber(modid = YsmScg2Compat.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class RenderProbe {

    /** Sampled at most once per second per player; a per-frame log is unreadable. */
    private static final long SAMPLE_INTERVAL_MS = 1000L;

    private static final Map<Player, Long> LAST_SAMPLE = new IdentityHashMap<>();

    private RenderProbe() {
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onRenderPlayerPre(RenderPlayerEvent.Pre event) {
        if (!CompatConfig.LOG_RENDER_PROBE.get() || !YsmScg2Compat.isYsmPresent()) {
            return;
        }

        Player player = event.getEntity();
        ItemStack held = player.getMainHandItem();
        if (!Scg2GunAccess.isScg2Gun(held)) {
            return;
        }

        long now = System.currentTimeMillis();
        Long last = LAST_SAMPLE.get(player);
        if (last != null && now - last < SAMPLE_INTERVAL_MS) {
            return;
        }
        LAST_SAMPLE.put(player, now);

        PoseStack poseStack = event.getPoseStack();
        Pose pose = poseStack.last();
        Vector3f translation = pose.pose().getTranslation(new Vector3f());
        Vector3f scale = pose.pose().getScale(new Vector3f());

        boolean ysmWillReplace = isYsmModelActive(player);

        YsmScg2Compat.LOGGER.debug("[{}] render probe: player={} item={} ysmModelActive={} "
                        + "renderer={} poseTranslation=({}, {}, {}) poseScale=({}, {}, {})",
                YsmScg2Compat.MOD_ID,
                player.getName().getString(),
                Scg2GunAccess.getGunId(held),
                ysmWillReplace,
                event.getRenderer().getClass().getSimpleName(),
                round(translation.x), round(translation.y), round(translation.z),
                round(scale.x), round(scale.y), round(scale.z));
    }

    /**
     * Does this player currently have an active YSM model?
     *
     * <p>Read from the same capability YSM's {@code ReplacePlayerRenderEvent} consults
     * before it cancels the vanilla render, so a {@code true} here means "YSM is about to
     * take this render over - SCG2's own third-person hooks will not run".</p>
     */
    private static boolean isYsmModelActive(Player player) {
        try {
            return player.getCapability(PlayerCapabilityProvider.PLAYER_CAP)
                    .map(PlayerCapability::isModelActive)
                    .orElse(false);
        } catch (Throwable t) {
            // A capability that is not attached yet must not be reported as "YSM inactive"
            // without saying so.
            YsmScg2Compat.LOGGER.debug("[{}] render probe: model state unavailable ({})",
                    YsmScg2Compat.MOD_ID, t.toString());
            return false;
        }
    }

    private static String round(float value) {
        return String.format(java.util.Locale.ROOT, "%.3f", value);
    }
}
