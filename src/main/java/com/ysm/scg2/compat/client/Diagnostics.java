package com.ysm.scg2.compat.client;

import com.elfmcys.yesstevemodel.capability.PlayerCapability;
import com.elfmcys.yesstevemodel.capability.PlayerCapabilityProvider;
import com.elfmcys.yesstevemodel.geckolib3.core.builder.Animation;
import com.elfmcys.yesstevemodel.client.entity.LivingAnimatable;
import com.ysm.scg2.compat.CompatConfig;
import com.ysm.scg2.compat.YsmScg2Compat;
import com.ysm.scg2.compat.compat.Scg2GunAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderPlayerEvent;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The "which path matched" logging this mod is required to have, and the model
 * inspection that makes the animation translation safe.
 *
 * <h2>The question this answers</h2>
 * <p>Whether the model a player is wearing can play {@code tac:*} gun animations is not
 * knowable from the mod side - it is a property of the model pack. Without an answer, the
 * only options are "always spoof" (which freezes models that lack the animation, see
 * {@code TacCompatMixin}) or "never spoof" (which is the status quo bug). This class
 * produces the answer, up front, in the log.</p>
 *
 * <p>Two independent checks run on every resource reload, so a model that cannot play the
 * animations is reported as such rather than discovered as a visual bug:</p>
 * <ol>
 *   <li><b>Reference check</b> - asks the model itself
 *       ({@code AnimatableEntity#getAnimation}) for the exact names the mixin would
 *       request. This is the same lookup the play call performs, so it cannot disagree
 *       with the decision; it covers the three gun types x ten actions.</li>
 *   <li><b>Structural listing</b> - enumerates every {@code tac:*} key in the model's arm
 *       animation map, via the model bundle. This catches names the reference list does
 *       not know about (a model author's own {@code tac:something}), and it is the part
 *       that makes a "why is my model not animating" report answerable in one line.</li>
 * </ol>
 * <p>Check 2 needs the model bundle, which is reached reflectively and is allowed to
 * fail - if it does, the log says so and check 1 still stands.</p>
 */
@Mod.EventBusSubscriber(modid = YsmScg2Compat.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class Diagnostics {

    /** Actions x gun types, i.e. the names the mixin can ask for (30 combinations). */
    private static final List<String> REFERENCE_NAMES = buildReferenceNames();

    private static long lastModelReport;

    private Diagnostics() {
    }

    /**
     * Runs after a resource reload, when the model packs and animation files are all
     * loaded and before the player next renders.
     */
    @SubscribeEvent
    public static void onAddReloadListener(AddReloadListenerEvent event) {
        if (!CompatConfig.LOG_MODEL_TAC_ANIMATIONS.get() || !YsmScg2Compat.isYsmPresent()) {
            return;
        }
        // Deferred: at ADD time the packs have not been applied yet and the local player's
        // model may still be the previous one.
        event.addListener((net.minecraft.server.packs.resources.ResourceManagerReloadListener) manager -> reportModel(null));
    }

    /**
     * Falls back to a per-render report while the local player has no capability yet.
     *
     * <p>A player joining a server has no model until the server sends it, and on a
     * single-player world the reload listener can fire before the world exists at all.
     * Reporting from the render path and rate-limiting to once per model keeps the log
     * useful without turning it into spam.</p>
     */
    @SubscribeEvent
    public static void onRenderPlayerPre(RenderPlayerEvent.Pre event) {
        if (!CompatConfig.LOG_MODEL_TAC_ANIMATIONS.get() || !YsmScg2Compat.isYsmPresent()) {
            return;
        }
        LocalPlayer local = Minecraft.getInstance().player;
        if (local == null || !local.equals(event.getEntity())) {
            return;
        }
        event.getEntity().getCapability(PlayerCapabilityProvider.PLAYER_CAP).ifPresent(cap -> {
            if (cap.hashCode() != lastModelReport || !cap.isModelActive()) {
                lastModelReport = cap.hashCode();
                reportModel(cap);
            }
        });
    }

    /**
     * Logs what the local player's model can and cannot do, then - if the player is
     * holding an SCG2 weapon - whether the translation is currently allowed to fire.
     */
    private static void reportModel(PlayerCapability capability) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }

        PlayerCapability cap = capability;
        if (cap == null) {
            cap = player.getCapability(PlayerCapabilityProvider.PLAYER_CAP).orElse(null);
        }
        if (cap == null || !cap.isModelActive()) {
            return;
        }

        ItemStack held = player.getMainHandItem();
        boolean scg2 = Scg2GunAccess.isScg2Gun(held);
        String gunType = scg2 ? Scg2GunAccess.getGunAnimationType(held) : "-";

        List<String> present = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String name : REFERENCE_NAMES) {
            if (hasAnimationDirect(cap, name)) {
                present.add(name);
            } else {
                missing.add(name);
            }
        }

        YsmScg2Compat.LOGGER.info("[{}] --- YSM model report -------------------------------------", YsmScg2Compat.MOD_ID);
        YsmScg2Compat.LOGGER.info("[{}] model id            : {}", YsmScg2Compat.MOD_ID, describeModel(cap));
        YsmScg2Compat.LOGGER.info("[{}] held item          : {} (SCG2 weapon: {}{})",
                YsmScg2Compat.MOD_ID, describeItem(held), scg2,
                scg2 ? ", animation set: " + gunType : "");
        YsmScg2Compat.LOGGER.info("[{}] tac:* animations    : {} of {} reference names present",
                YsmScg2Compat.MOD_ID, present.size(), REFERENCE_NAMES.size());
        if (present.isEmpty()) {
            YsmScg2Compat.LOGGER.info("[{}]   => translation INERT: this model has no tac:* gun animations at all, "
                    + "so SCG2 weapons keep YSM's generic item-holding pose (the pre-mod behaviour).", YsmScg2Compat.MOD_ID);
        } else {
            YsmScg2Compat.LOGGER.info("[{}]   present: {}", YsmScg2Compat.MOD_ID, present);
            if (!missing.isEmpty()) {
                YsmScg2Compat.LOGGER.debug("[{}]   missing: {}", YsmScg2Compat.MOD_ID, missing);
            }
            if (scg2) {
                YsmScg2Compat.LOGGER.info("[{}]   => for this weapon ({} set) the mixin will request: hold={}, aim={}, reload={}",
                        YsmScg2Compat.MOD_ID, gunType,
                        lookup(cap, GunAnimationNames.Action.HOLD, gunType),
                        lookup(cap, GunAnimationNames.Action.AIM, gunType),
                        lookup(cap, GunAnimationNames.Action.RELOAD, gunType));
            }
        }

        logStructuralListing(cap);
        YsmScg2Compat.LOGGER.info("[{}] ---------------------------------------------------------", YsmScg2Compat.MOD_ID);
    }

    /**
     * Enumerates {@code tac:*} keys in the model's arm animation map.
     *
     * <p>Reflective and failure-tolerant on purpose: {@code PlayerCapability} is a stable
     * public type, but the path from it to the animation map goes through the model
     * assembly, which is internal to YSM and has been renamed before. A failure here costs
     * one log line, not the feature.</p>
     */
    private static void logStructuralListing(PlayerCapability cap) {
        try {
            Object assembly = cap.getClass().getMethod("getModelAssembly").invoke(cap);
            Object bundle = assembly.getClass().getMethod("getAnimationBundle").invoke(assembly);

            List<String> tacAnimations = new ArrayList<>();
            collectTacKeys(bundle, tacAnimations);

            if (tacAnimations.isEmpty()) {
                YsmScg2Compat.LOGGER.info("[{}] structural scan      : no tac:* entries visible in the model bundle "
                        + "(either the model has none, or YSM changed the bundle layout)", YsmScg2Compat.MOD_ID);
            } else {
                tacAnimations.sort(String::compareTo);
                YsmScg2Compat.LOGGER.info("[{}] structural scan      : {} tac:* animation key(s): {}",
                        YsmScg2Compat.MOD_ID, tacAnimations.size(), tacAnimations);
            }
        } catch (Throwable t) {
            YsmScg2Compat.LOGGER.info("[{}] structural scan      : unavailable on this YSM build ({})",
                    YsmScg2Compat.MOD_ID, t.getClass().getSimpleName());
        }
    }

    /** Pulls {@code tac:*} keys out of whichever animation maps the bundle exposes. */
    private static void collectTacKeys(Object bundle, List<String> into) {
        for (String getter : new String[]{"getArmAnimations", "getMainAnimations"}) {
            try {
                Object map = bundle.getClass().getMethod(getter).invoke(bundle);
                if (map instanceof Map<?, ?> animations) {
                    for (Object key : animations.keySet()) {
                        if (key instanceof String name && name.startsWith("tac:") && !into.contains(name)) {
                            into.add(name);
                        }
                    }
                }
            } catch (Throwable ignored) {
                // Try the next getter.
            }
        }
    }

    /**
     * Direct model lookup, bypassing the animation event.
     *
     * <p>{@code LivingAnimatable#getAnimation} is the same method the play decision uses,
     * so this check and the mixin cannot disagree about whether a name exists.</p>
     */
    private static boolean hasAnimationDirect(LivingAnimatable<?> animatable, String name) {
        try {
            Animation animation = animatable.getAnimation(name);
            return animation != null;
        } catch (Throwable t) {
            return false;
        }
    }

    private static String lookup(LivingAnimatable<?> animatable, GunAnimationNames.Action action, String gunType) {
        String name = action.forType(gunType);
        return hasAnimationDirect(animatable, name) ? name : "-";
    }

    private static String describeModel(PlayerCapability cap) {
        try {
            Object id = cap.getClass().getMethod("getSelectedModelId").invoke(cap);
            return id == null ? "-" : id.toString();
        } catch (Throwable t) {
            return "(unavailable)";
        }
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
