package com.ysm.scg2.compat;

import com.ysm.scg2.compat.ysm.YsmFork;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Applies the animation mixin that matches the installed Yes Steve Model build - and only that
 * one.
 *
 * <h2>Why this has to be a decision rather than one annotation</h2>
 * <p>Mixin matches a target by <b>name</b>, and the two YSM distributions name the same class and
 * the same methods completely differently:</p>
 *
 * <table border="1">
 *   <caption>the same class, two names</caption>
 *   <tr><th>build</th><th>class</th><th>hold method</th></tr>
 *   <tr><td>readable (OpenYSM / ModernYSM)</td>
 *       <td>{@code client.compat.gun.tacz.TacCompat}</td>
 *       <td>{@code handleGunHoldAnimState}</td></tr>
 *   <tr><td>obfuscated (official release)</td>
 *       <td>{@code com.elfmcys.yesstevemodel.OOO0O0O0oo0ooooo00oOOOO0}</td>
 *       <td>{@code Oo0Oo0o00O00Oo0OOoOOoooo}</td></tr>
 * </table>
 *
 * <h2>THE RULE THIS CLASS EXISTS TO RESPECT</h2>
 * <p><b>A mixin config plugin must not reference anything that can fail to load.</b> Mixin
 * instantiates this class and calls it during startup, before Forge has finished wiring its own
 * services, and an exception thrown from {@link #shouldApplyMixin} does not degrade gracefully - it
 * takes the injection with it.</p>
 *
 * <p>That is not hypothetical. A version of this class logged its decision through
 * {@code YsmScg2Compat.LOGGER}, which drags in {@code YsmScg2Compat} and therefore Forge's config
 * classes. At plugin time those are not on the classpath, so the call threw
 * {@code NoClassDefFoundError: net/minecraftforge/fml/config/IConfigSpec} <b>from inside the apply
 * decision</b>, silently preventing the injection.</p>
 *
 * <p>Every outward call here is therefore chosen for what it does when it <em>fails</em>:
 * {@link ProbeLog} (a plain file, no logger), {@link ModPresence} and {@link YsmFork} (self-contained,
 * and they swallow their own failures), and no reference to the mod's main class.</p>
 */
public class YsmForkMixinPlugin implements IMixinConfigPlugin {

    /** Target and mixin for YSM builds that keep readable names. */
    private static final String READABLE_TARGET = "com.elfmcys.yesstevemodel.client.compat.gun.tacz.TacCompat";
    private static final String READABLE_MIXIN = "TacCompatForkMixin";

    /** Target and mixin for the obfuscated official release. */
    private static final String OBFUSCATED_TARGET = YsmFork.LEGACY_TAC_COMPAT;
    private static final String OBFUSCATED_MIXIN = "TacCompatLegacyMixin";

    private List<String> selected = List.of();
    private String reason = "not initialised";

    @Override
    public void onLoad(String mixinPackage) {
        ProbeLog.startRun("ysm_scg2_compat probe log");
        ProbeLog.log("plugin", "onLoad called with package '" + mixinPackage + "' - the plugin IS loaded");
        try {
            boolean ysmPresent = ModPresence.isLoaded(YsmFork.YSM_MOD_ID);
            boolean scgunsPresent = ModPresence.isLoaded("scguns");
            ProbeLog.log("plugin", "mod presence at plugin time: yes_steve_model=" + ysmPresent
                    + ", scguns=" + scgunsPresent);

            if (!ysmPresent) {
                this.reason = "yes_steve_model not visible at plugin time";
                return;
            }
            if (!scgunsPresent) {
                this.reason = "scguns not visible at plugin time";
                return;
            }

            YsmFork.Info fork = YsmFork.info();
            ProbeLog.log("plugin", "YsmFork says: present=" + fork.present() + ", build=" + fork.build()
                    + ", obfuscated=" + fork.obfuscated() + ", wrapper=" + fork.tacCompatClass()
                    + ", holdMethod=" + fork.methodHoldGun() + ", evidence=" + fork.evidence());
            if (!fork.present()) {
                this.reason = "no YSM build identified";
                return;
            }

            if (fork.obfuscated()) {
                this.selected = List.of(OBFUSCATED_MIXIN);
                this.reason = "obfuscated release -> " + OBFUSCATED_MIXIN + " targeting " + OBFUSCATED_TARGET;
            } else {
                this.selected = List.of(READABLE_MIXIN);
                this.reason = fork.build() + " -> " + READABLE_MIXIN + " targeting " + READABLE_TARGET;
            }
        } catch (Throwable t) {
            this.selected = List.of();
            this.reason = "identification threw " + ProbeLog.describe(t);
        }
        ProbeLog.log("plugin", "selection: " + this.selected + " (" + this.reason + ")");
    }

    /**
     * The mixins this config should carry - one, chosen by build.
     *
     * <p>{@code getMixins} runs before the config's own list is resolved, so the unselected variant
     * is never prepared.</p>
     */
    @Override
    public List<String> getMixins() {
        ProbeLog.log("plugin", "getMixins() -> " + this.selected);
        return this.selected;
    }

    /**
     * The apply decision.
     *
     * <p>Deliberately total: it answers from fields computed in {@link #onLoad} and cannot throw. An
     * exception here is fatal to the injection, and an injection that silently does not apply is the
     * hardest kind of failure to see - so the reporting is wrapped too.</p>
     */
    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        boolean apply = false;
        try {
            String simpleName = mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1);
            apply = this.selected.contains(simpleName);
            ProbeLog.log("plugin", "shouldApplyMixin(" + simpleName + ") -> " + (apply ? "APPLY" : "SKIP")
                    + "  target=" + targetClassName + "  reason=" + this.reason);
        } catch (Throwable t) {
            ProbeLog.log("plugin", "shouldApplyMixin threw " + ProbeLog.describe(t) + "; not applying");
        }
        return apply;
    }

    /** Confirms the class actually reached the JVM's class loader. */
    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        ProbeLog.log("plugin", "preApply  mixin=" + mixinClassName + "  target=" + targetClassName);
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        ProbeLog.log("plugin", "postApply mixin=" + mixinClassName + "  target=" + targetClassName
                + "  methods now=" + (targetClass.methods == null ? -1 : targetClass.methods.size()));
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }
}
