package com.ysm.scg2.compat.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.PoseStack.Pose;
import com.ysm.scg2.compat.CompatConfig;
import com.ysm.scg2.compat.ProbeLog;
import com.ysm.scg2.compat.YsmScg2Compat;
import com.ysm.scg2.compat.compat.Scg2GunAccess;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.event.RenderPlayerEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.joml.Vector3f;

import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Render-side probe for the one symptom this mod does not fix: the Scorched Guns 2 weapon
 * model itself not appearing in third person while a YSM model is drawn.
 *
 * <h2>Registered by hand, not by annotation</h2>
 * <p>Same rule as {@link Diagnostics}: no {@code @Mod.EventBusSubscriber}, and no Yes Steve
 * Model type in any signature. YSM is reached through {@link YsmBridge} only. See that
 * class for why a signature-level reference is fatal at startup.</p>
 *
 * <h2>What it measures</h2>
 * <p>Two facts per player per second, both of which narrow the cause without guessing:</p>
 * <ol>
 *   <li><b>Is YSM actually replacing the render?</b> YSM cancels
 *       {@code RenderPlayerEvent.Pre} and draws the player itself, which also means SCG2's
 *       own third-person hooks (its {@code ItemInHandLayer} mixin and its
 *       {@code PlayerModel#setupAnim} arm pose) are never reached.</li>
 *   <li><b>Does the pose stack the item layer will use already carry a translation?</b>
 *       SCG2's {@code GunItemStackRenderer#renderByItem} opens with
 *       {@code poseStack.popPose()}, commented as a hack to remove the transforms
 *       {@code ItemRenderer#render} applies. When YSM supplies the pose stack instead of
 *       vanilla's {@code ItemInHandRenderer}, that pop removes <em>YSM's</em> hand-bone
 *       matrix - the prime suspect for a weapon that is drawn but not in the hand.
 *       Reporting the stack translation here, before YSM's own layer runs, makes it
 *       visible.</li>
 * </ol>
 *
 * <p>Off by default, DEBUG level when on. It is a measurement, not a fix: changing the pose
 * stack from the outside would be guesswork until these lines have been read.</p>
 */
public final class RenderProbe {

    /** Sampled at most once per second per player; a per-frame log is unreadable. */
    private static final long SAMPLE_INTERVAL_MS = 1000L;

    private final Map<Player, Long> lastSample = new IdentityHashMap<>();

    public RenderProbe() {
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onRenderPlayerPre(RenderPlayerEvent.Pre event) {
        if (!isEnabled()) {
            return;
        }

        Player player = event.getEntity();
        ItemStack held = player.getMainHandItem();
        if (!Scg2GunAccess.isScg2Gun(held)) {
            return;
        }

        long now = System.currentTimeMillis();
        Long last = this.lastSample.get(player);
        if (last != null && now - last < SAMPLE_INTERVAL_MS) {
            return;
        }
        this.lastSample.put(player, now);

        PoseStack poseStack = event.getPoseStack();
        Pose pose = poseStack.last();
        Vector3f translation = pose.pose().getTranslation(new Vector3f());
        Vector3f scale = pose.pose().getScale(new Vector3f());

        // Only inside the existing 1s sample window: a probe write per frame per player would be
        // its own kind of problem.
        ProbeLog.log("probe-sample", "RenderPlayerEvent.Pre: renderer=" + event.getRenderer().getClass().getName()
                + " ysmModelActive=" + YsmBridge.isModelActive(YsmBridge.getCapability(player)));
        YsmScg2Compat.LOGGER.debug("[{}] render probe: player={} item={} ysmModelActive={} "
                        + "renderer={} poseTranslation=({}, {}, {}) poseScale=({}, {}, {})",
                YsmScg2Compat.MOD_ID,
                player.getName().getString(),
                Scg2GunAccess.getGunId(held),
                YsmBridge.isModelActive(YsmBridge.getCapability(player)),
                event.getRenderer().getClass().getSimpleName(),
                round(translation.x), round(translation.y), round(translation.z),
                round(scale.x), round(scale.y), round(scale.z));
    }

    private static boolean isEnabled() {
        return CompatConfig.LOG_RENDER_PROBE.get()
                && YsmScg2Compat.isYsmPresent()
                && YsmBridge.isAvailable();
    }

    private static String round(float value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }
}
