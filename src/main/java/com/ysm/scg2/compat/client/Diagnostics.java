package com.ysm.scg2.compat.client;

import com.ysm.scg2.compat.CompatConfig;
import com.ysm.scg2.compat.ProbeLog;
import com.ysm.scg2.compat.YsmScg2Compat;
import com.ysm.scg2.compat.compat.Scg2GunAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.event.RenderPlayerEvent;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.loading.FMLLoader;

import java.util.ArrayList;
import java.util.List;

/**
 * The "which path matched" logging this mod is required to have, and the model inspection
 * that makes the animation translation safe.
 *
 * <h2>Registered by hand, not by annotation</h2>
 * <p>This class is deliberately <b>not</b> a {@code @Mod.EventBusSubscriber}. Forge loads
 * every subscriber class in a mod during {@code FMLModContainer#constructMod} with
 * {@code Class.forName}, and the JVM verifies the loaded class eagerly - so naming a Yes
 * Steve Model type in a member signature turns into
 * {@code NoClassDefFoundError: com/elfmcys/yesstevemodel/...} at the {@code Class.forName0}
 * frame, before any of this mod's own checks run.</p>
 *
 * <p>That is exactly how version 1.0.0 of this mod failed to start. Two changes prevent a
 * repeat:</p>
 * <ul>
 *   <li>no {@code @Mod.EventBusSubscriber}: {@code YsmScg2Compat} adds an instance to the
 *       Forge bus explicitly, after it has confirmed both target mods are present;</li>
 *   <li>no YSM type appears in any signature here either - all model access goes through
 *       {@link YsmBridge}, which is reflection-only by construction.</li>
 * </ul>
 *
 * <h2>The question this answers</h2>
 * <p>Whether the model a player is wearing can play {@code tac:*} gun animations is not
 * knowable from the mod side - it is a property of the model pack. Without an answer the
 * only options are "always spoof" (which freezes models that lack the animation, see
 * {@code TacCompatMixin}) or "never spoof" (the status quo bug). This class produces the
 * answer, up front, in the log.</p>
 *
 * <p>Two independent checks run for each model, so a model that cannot play the animations
 * is reported as such rather than discovered later as a visual bug:</p>
 * <ol>
 *   <li><b>Reference check</b> - asks the model itself (the same
 *       {@code getAnimation(String)} lookup the play call performs) for the exact names the
 *       mixin would request: three gun types x ten actions.</li>
 *   <li><b>Structural listing</b> - enumerates every {@code tac:*} key in the model's
 *       animation map. This catches names the reference list does not know about (a model
 *       author's own {@code tac:something}), and it is what makes a "why is my model not
 *       animating" report answerable in one line.</li>
 * </ol>
 */
public final class Diagnostics {

    /** Actions x gun types, i.e. the names the mixin can ask for. */
    private static final List<String> REFERENCE_NAMES = buildReferenceNames();

    /** Identity of the last capability reported, so a re-report happens per model, not per frame. */
    private int lastReportedCapability;

    /** Wall-clock throttle for the render-path report; see onRenderPlayerPre. */
    private long lastReportMillis;

    public Diagnostics() {
    }

    /**
     * Runs after a resource reload, when the model packs and animation files are all loaded
     * and before the player next renders.
     *
     * <p>The report is deferred to the reload callback rather than produced here: at ADD
     * time the packs have not been applied yet and the local player may still be carrying
     * the previous model.</p>
     */
    @SubscribeEvent
    public void onAddReloadListener(AddReloadListenerEvent event) {
        if (!isEnabled()) {
            return;
        }
        event.addListener((ResourceManagerReloadListener) manager -> reportLocalPlayerModel());
    }

    /**
     * Falls back to a per-render report while the local player has no model yet.
     *
     * <p>A player joining a server has no model until the server sends it, and on a
     * single-player world the reload listener can fire before the world exists at all.
     * Reporting from the render path and rate-limiting to once per model keeps the log
     * useful without turning it into spam.</p>
     */
    @SubscribeEvent
    public void onRenderPlayerPre(RenderPlayerEvent.Pre event) {
        if (!isEnabled()) {
            return;
        }
        LocalPlayer local = Minecraft.getInstance().player;
        if (local == null || !local.equals(event.getEntity())) {
            return;
        }
        Object capability = YsmBridge.getCapability(local);
        if (capability == null) {
            return;
        }
        // The report used to be gated on "a different capability object than last time", which
        // made it fire once per capability lifetime - and a reload usually creates the capability
        // before the player is far enough along for the reload listener to run, so the report was
        // simply never printed. Rate-limit by time instead: one report every 20s at most.
        long now = System.currentTimeMillis();
        if (now - this.lastReportMillis < 20_000L) {
            return;
        }
        this.lastReportMillis = now;
        report(capability);
    }

    private static boolean isEnabled() {
        return CompatConfig.LOG_MODEL_TAC_ANIMATIONS.get()
                && FMLLoader.getDist().isClient()
                && YsmScg2Compat.isYsmPresent()
                && YsmBridge.isAvailable();
    }

    private void reportLocalPlayerModel() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            ProbeLog.log("render", "model report requested before a local player exists; deferring");
            return;
        }
        Object capability = YsmBridge.getCapability(player);
        if (capability == null) {
            ProbeLog.log("render", "YSM capability is NULL for the local player - the model report cannot run");
            return;
        }
        ProbeLog.log("render", "YSM capability resolved: " + capability.getClass().getName());
        this.lastReportedCapability = System.identityHashCode(capability);
        report(capability);
    }

    /**
     * Logs what the local player's model can and cannot do, then - if the player is holding
     * an SCG2 weapon - whether the translation is currently allowed to fire.
     *
     * <h3>Two bundles, reported separately</h3>
     * <p>The animation map has two halves and they decide different things, so one count would
     * mislead:</p>
     * <ul>
     *   <li><b>arm</b> - {@code getArmAnimations()}: {@code arm.animation.json} and
     *       {@code fp.arm.animation.json}. This is what {@code AnimatableEntity#getAnimation}
     *       resolves for a player, so it is what the reference count measures. It drives the
     *       first-person arm.</li>
     *   <li><b>main</b> - {@code getMainAnimations()}: {@code main.animation.json} plus the
     *       model's {@code tac.animation.json}. This is the <b>third-person body</b>, and the
     *       bundle that decides whether a gun pose can appear there.</li>
     * </ul>
     * <p>In the model this was written against, the {@code tac:*} clips live only in the main
     * bundle - so an arm-only reading would understate what the model can do, which is exactly
     * the kind of number that sends an investigation the wrong way.</p>
     */
    private void report(Object capability) {
        if (!YsmBridge.isModelActive(capability)) {
            return;
        }

        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        ItemStack held = player.getMainHandItem();
        boolean scg2 = Scg2GunAccess.isScg2Gun(held);
        String gunType = scg2 ? Scg2GunAccess.getGunAnimationType(held) : "-";

        List<String> present = new ArrayList<>();
        for (String name : REFERENCE_NAMES) {
            if (YsmBridge.modelHasAnimation(capability, name)) {
                present.add(name);
            }
        }

        // The bundle that decides third person, read directly: getAnimation() resolves to the
        // ARM bundle for a player, so it cannot answer this question.
        List<String> mainTac = YsmBridge.listMainTacAnimations(capability);
        List<String> armTac = YsmBridge.listTacAnimations(capability);

        ProbeLog.log("render", "model report: modelId=" + describeModel(capability)
                + " held=" + describeItem(held) + " scg2=" + scg2 + " type=" + gunType
                + " armTac=" + armTac.size() + " mainTac=" + mainTac.size());
        YsmScg2Compat.LOGGER.info("[{}] --- YSM model report -------------------------------------", YsmScg2Compat.MOD_ID);
        YsmScg2Compat.LOGGER.info("[{}] model id         : {}", YsmScg2Compat.MOD_ID, describeModel(capability));
        YsmScg2Compat.LOGGER.info("[{}] held item        : {} (SCG2 weapon: {}{})",
                YsmScg2Compat.MOD_ID, describeItem(held), scg2,
                scg2 ? ", animation set: " + gunType : "");
        YsmScg2Compat.LOGGER.info("[{}] tac:* in ARM     : {}", YsmScg2Compat.MOD_ID,
                armTac.isEmpty() ? "(none)" : armTac);
        YsmScg2Compat.LOGGER.info("[{}] tac:* in MAIN    : {}   <-- THIS is what decides the third-person body pose",
                YsmScg2Compat.MOD_ID, mainTac.isEmpty() ? "(none)" : mainTac);
        YsmScg2Compat.LOGGER.info("[{}] reference names  : {} of {} resolve through getAnimation() (arm bundle)",
                YsmScg2Compat.MOD_ID, present.size(), REFERENCE_NAMES.size());

        if (mainTac.isEmpty()) {
            YsmScg2Compat.LOGGER.info("[{}]   => third-person gun poses are NOT possible for this model: no tac:* "
                            + "clips in the main bundle. That is a model-pack matter, not a mod bug.",
                    YsmScg2Compat.MOD_ID);
        } else if (scg2) {
            YsmScg2Compat.LOGGER.info("[{}]   => for the {} set, the mixin will request hold='{}' and aim='{}' "
                            + "(checked against the MAIN bundle)",
                    YsmScg2Compat.MOD_ID, gunType,
                    found(mainTac, GunAnimationNames.Action.HOLD.forType(gunType)),
                    found(mainTac, GunAnimationNames.Action.AIM.forType(gunType)));
        }
        YsmScg2Compat.LOGGER.info("[{}] ---------------------------------------------------------", YsmScg2Compat.MOD_ID);
    }

    private static String found(List<String> mainTac, String name) {
        return mainTac.contains(name) ? name : "MISSING(" + name + ")";
    }

    private static String describeModel(Object capability) {
        String id = YsmBridge.getSelectedModelId(capability);
        return id == null ? "(unavailable)" : id;
    }

    private static String describeItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "(empty)";
        }
        ResourceLocation id = Scg2GunAccess.getGunId(stack);
        return id == null ? "unknown" : id.toString();
    }

    private static List<String> buildReferenceNames() {
        List<String> names = new ArrayList<>();
        for (GunAnimationNames.Action action : GunAnimationNames.Action.values()) {
            for (String type : new String[]{Scg2GunAccess.TYPE_PISTOL, Scg2GunAccess.TYPE_RIFLE, Scg2GunAccess.TYPE_RPG}) {
                names.add(action.forType(type));
            }
        }
        return List.copyOf(names);
    }
}
