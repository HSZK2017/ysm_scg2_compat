package com.ysm.scg2.compat.client.mixin;

import com.ysm.scg2.compat.ModPresence;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Applies the client mixins only when both halves of the bridge are installed.
 *
 * <h2>Why a plugin instead of {@code Mixins.addConfiguration} in the mod constructor</h2>
 * <p>Registering the config from code makes when it becomes visible depend on Forge's mod
 * construction order relative to Mixin's transformation phase. A mixin config plugin has
 * no such ambiguity: {@link #shouldApplyMixin} is consulted for every mixin of this config
 * right before it is applied, and it is where "the target mod is not installed" is meant
 * to be answered.</p>
 *
 * <p>The practical difference: with the config gated here, a game without Yes Steve Model
 * never has {@code TacCompat} resolved at all, so it cannot fail on a missing class. The
 * config stays {@code "required": true} - a plugin that rejects every mixin is still a
 * satisfied config - which keeps genuine problems (a broken refmap, a bad config) loud.</p>
 *
 * <h2>Why the class name is spelled out instead of imported</h2>
 * <p>This class is instantiated while Mixin is parsing configs, before mod construction.
 * It must not reference any Yes Steve Model or Scorched Guns 2 type, or it would drag
 * those classes in during the very phase it exists to protect.</p>
 *
 * <h2>Failure policy</h2>
 * <p>{@link #shouldApplyMixin} catches {@link Throwable} and returns {@code false}: an
 * unanswerable presence check means "stay out of the way", and the two mixins here
 * degrade to a no-op rather than a crash.</p>
 */
public class YsmScg2MixinPlugin implements IMixinConfigPlugin {

    private static final String YSM_MOD_ID = "yes_steve_model";
    private static final String SCGUNS_MOD_ID = "scguns";

    private boolean applicable;

    @Override
    public void onLoad(String mixinPackage) {
        try {
            this.applicable = ModPresence.bothLoaded(YSM_MOD_ID, SCGUNS_MOD_ID);
        } catch (Throwable t) {
            this.applicable = false;
        }
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return this.applicable;
    }

    @Override
    public String getRefMapperConfig() {
        // Keep the generated refmap name (yes_steve_model style targets still need remapping).
        return null;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
