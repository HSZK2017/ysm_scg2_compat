package com.ysm.scg2.compat;

import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.LoadingModList;

/**
 * Answers "is mod X present" in a way that is safe from every loading phase.
 *
 * <p>Three different phases ask this question - the Mixin config plugin (very early,
 * before the mod list object exists), the mod constructor, and runtime code - and each
 * needs a different source. Centralising it here means the answer is written once and the
 * fallback order is documented rather than rediscovered.</p>
 */
public final class ModPresence {

    private ModPresence() {
    }

    /**
     * Is {@code modId} present?
     *
     * <p>Prefers the fully populated {@link ModList} and falls back to
     * {@link LoadingModList}, which is the only one available while Mixin configs are
     * being processed. When neither is ready - a state that should not be reachable from
     * any of this mod's entry points - the answer is {@code false}, so the compat layer
     * stays inert rather than throwing during startup.</p>
     */
    public static boolean isLoaded(String modId) {
        try {
            ModList modList = ModList.get();
            if (modList != null && modList.getModFileById(modId) != null) {
                return true;
            }
        } catch (Throwable ignored) {
            // ModList is not initialised yet; fall through to the loading-time list.
        }

        try {
            LoadingModList loading = LoadingModList.get();
            return loading != null && loading.getModFileById(modId) != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Are the two mods this compat layer bridges both present?
     *
     * @param ysmModId     Yes Steve Model's mod id
     * @param scgunsModId  Scorched Guns 2's mod id
     */
    public static boolean bothLoaded(String ysmModId, String scgunsModId) {
        return isLoaded(ysmModId) && isLoaded(scgunsModId);
    }
}
