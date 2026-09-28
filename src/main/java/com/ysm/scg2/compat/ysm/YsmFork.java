package com.ysm.scg2.compat.ysm;

import com.ysm.scg2.compat.ProbeLog;
import com.ysm.scg2.compat.YsmScg2Compat;
import net.minecraftforge.fml.ModList;
import org.jetbrains.annotations.Nullable;

/**
 * Which Yes Steve Model build is loaded, and the concrete names needed to talk to it.
 *
 * <h2>The problem this solves</h2>
 * <p>YSM ships in three layouts that all declare {@code modId = "yes_steve_model"} and the
 * same version, so neither Forge's mod list nor a version range can tell them apart - yet
 * their internals are named completely differently:</p>
 *
 * <table border="1">
 *   <caption>the three builds</caption>
 *   <tr><th></th><th>TACZ wrapper</th><th>capability provider</th><th>capability field</th></tr>
 *   <tr><td>{@code LEGACY_YSM} (official release)</td>
 *       <td>{@code OOO0O0O0oo0ooooo00oOOOO0}</td>
 *       <td>found by scan</td><td>found by scan</td></tr>
 *   <tr><td>{@code OPEN_YSM} (the fork in this workspace)</td>
 *       <td>{@code client.compat.gun.tacz.TacCompat}</td>
 *       <td>{@code capability.PlayerCapabilityProvider}</td><td>{@code PLAYER_CAP}</td></tr>
 *   <tr><td>{@code MODERN_YSM} (OpenYSM's successor)</td>
 *       <td>{@code client.compat.gun.tacz.TacCompat}</td>
 *       <td>{@code forge.capability.PlayerCapabilityProvider}</td><td>{@code PLAYER_CAP}</td></tr>
 * </table>
 *
 * <p>Version 1.0.0 of this mod hardcoded the OPEN_YSM column. On the official release every
 * name missed, the mixin silently did not apply and the reflection bridge went quiet - the
 * exact "silently rots" failure the {@code ysm_epicfight_compat} project warns about. This
 * class is that project's answer, adapted: identify the build from
 * <b>YSM's own class list</b> (what it declares, not what it is called), then hand out the
 * names that build actually uses.</p>
 *
 * <h2>Why the scan decides, and the fingerprints only back it up</h2>
 * <p>The obfuscated original obfuscates its whole root package, but it still ships a few
 * readable classes of its own - its {@code mixin} package and the mod class - so "some
 * names are readable" is true for every build and cannot decide anything. What does decide
 * it: whether a readable player-capability class exists at all (the obfuscated original has
 * none), and where its provider sits (the {@code forge} subpackage is ModernYSM's
 * multi-platform layout).</p>
 */
public final class YsmFork {

    public static final String YSM_MOD_ID = "yes_steve_model";

    // ------------------------------------------------------------------
    // Fingerprints, verified against the real jars
    // ------------------------------------------------------------------

    /** The fork's TACZ bridge wrapper. */
    private static final String OPEN_TAC_COMPAT =
            "com.elfmcys.yesstevemodel.client.compat.gun.tacz.TacCompat";

    /**
     * The obfuscated original's equivalent of {@code TacCompat}.
     *
     * <p>Identified by API shape against the official 2.6.5 release jar: it is the class
     * carrying all six wrapper methods ({@code init}, {@code registerControllerFunctions},
     * {@code applyItemTransform}, {@code handleTaczAnimState}, {@code handleGunHoldAnimState},
     * {@code handleGunActionAnimState}, {@code handleGunSound}, {@code handleItemSound},
     * {@code getGunTexture}, {@code isLoaded}) plus the {@code isGun(Player)} overload plus
     * the nine molang lambdas - and its only YSM-typed parameter is the animation event.</p>
     */
    public static final String LEGACY_TAC_COMPAT = "com.elfmcys.yesstevemodel.OOO0O0O0oo0ooooo00oOOOO0";

    /*
     * The obfuscated original's wrapper method names, mapped from the fork's source.
     *
     * Both gun-entry methods call their handler with the SAME descriptor
     * ((ItemStack, AnimationEvent) -> PlayState), so a descriptor alone cannot separate
     * them; the name is what does. The mapping was read off the bytecode of
     * OOO0O0O0oo0ooooo00oOOOO0 in the official release jar, cross-checked against the
     * tac:* string constants of the handler each one calls:
     *
     *   Oo0Oo0o00O00Oo0OOoOOoooo(ItemStack, AnimationEvent) -> calls the handler method
     *       that builds "tac:hold:" / "tac:aim:" / "tac:run:" / "tac:climb:"  => handleTaczGunHold
     *   o0OOooo0o0OO00OoOOOo0o0O(ItemStack, AnimationEvent) -> calls the handler method
     *       that builds "tac:reload:" / "tac:melee:" / "tac:*:fire:"           => handleTaczGunAction
     *
     * The obfuscator reuses Oo0Oo0o00O00Oo0OOoOOoooo for many unrelated members in the same
     * package, so every injection must carry the full descriptor - a bare name would inject
     * into the wrong method.
     */
    public static final String LEGACY_METHOD_HOLD_GUN = "Oo0Oo0o00O00Oo0OOoOOoooo";
    public static final String LEGACY_METHOD_GUN_ACTION = "o0OOooo0o0OO00OoOOOo0o0O";
    public static final String LEGACY_METHOD_GUN_TEXTURE = "o0OOooo0o0OO00OoOOOo0o0O";

    private static final String OPEN_CAPABILITY_PROVIDER =
            "com.elfmcys.yesstevemodel.capability.PlayerCapabilityProvider";

    /**
     * ModernYSM moved the Forge-facing classes into a {@code forge} subpackage, so this probe is
     * what separates it from OpenYSM.
     *
     * <p>Deliberately NOT {@code config.GeneralConfig} or anything else that both readable builds
     * ship: using a shared class as a ModernYSM marker classifies OpenYSM as ModernYSM. The
     * reference project {@code ysm_epicfight_compat} records that exact trap, and it is why the
     * marker below is a class only ModernYSM has.</p>
     */
    private static final String MODERN_FORGE_RENDER_HOOK =
            "com.elfmcys.yesstevemodel.forge.event.ReplacePlayerRenderForgeHook";
    private static final String MODERN_CAPABILITY_PROVIDER =
            "com.elfmcys.yesstevemodel.forge.capability.PlayerCapabilityProvider";
    private static final String UNOBFUSCATED_RENDER_EVENT =
            "com.elfmcys.yesstevemodel.client.event.ReplacePlayerRenderEvent";

    /** The field holding the {@code Capability<PlayerCapability>} in the readable builds. */
    public static final String PLAYER_CAPABILITY_FIELD = "PLAYER_CAP";

    /**
     * The obfuscated release's capability provider, under the obfuscated name it is compiled with.
     *
     * <p>Not a fingerprint - it never matches a readable build - but kept here so this mod has one
     * definition site per layout, and so the obfuscated build gets a direct lookup instead of
     * relying on the structural scan. Verified against the official 2.6.5 release jar: the class
     * exists and declares its {@code Capability} field under {@link #LEGACY_PLAYER_CAPABILITY_FIELD}.</p>
     */
    public static final String LEGACY_CAPABILITY_PROVIDER =
            "com.elfmcys.yesstevemodel.O0OooOo0oOOoOoOoOooO000o";

    /** The obfuscated release names the capability field differently from every readable build. */
    public static final String LEGACY_PLAYER_CAPABILITY_FIELD = "Oo0Oo0o00O00Oo0OOoOOoooo";

    /**
     * Field names that may hold the {@code Capability}, tried in order, before the structural scan.
     *
     * <p>A list rather than one name because the two readable builds agree on {@code PLAYER_CAP}
     * while the obfuscated one does not, and a future build could differ again.</p>
     */
    public static final String[] CAPABILITY_FIELD_CANDIDATES = {
            PLAYER_CAPABILITY_FIELD,
            LEGACY_PLAYER_CAPABILITY_FIELD,
    };

    public enum Build {
        /** The official obfuscated release. */
        LEGACY_YSM,
        /** The community de-obfuscation (the {@code OpenYSM} checkout). */
        OPEN_YSM,
        /** OpenYSM's modernized successor (architectury multi-platform layout). */
        MODERN_YSM,
        /** YSM is not installed. */
        NONE
    }

    /** The verdict plus the names it resolves to. */
    public static final class Info {

        private final Build build;
        private final String evidence;
        private final String version;

        Info(Build build, String evidence, String version) {
            this.build = build;
            this.evidence = evidence;
            this.version = version;
        }

        public Build build() {
            return this.build;
        }

        /** The concrete class/resource that decided the verdict, for the startup line. */
        public String evidence() {
            return this.evidence;
        }

        public String version() {
            return this.version;
        }

        public boolean present() {
            return this.build != Build.NONE;
        }

        /** Whether every YSM class name is obfuscated - the official release's signature. */
        public boolean obfuscated() {
            return this.build == Build.LEGACY_YSM;
        }

        /**
         * The class carrying YSM's TACZ bridge wrappers, or {@code null} when the build is
         * unknown.
         *
         * <p>This is the class the mixin targets and the reflection bridge resolves
         * against. It is a fingerprint rather than a scan result because the target of a
         * mixin must be known before classes load, and because a scan can find the holder
         * of a capability but not "the class with these six methods" without a bytecode
         * reader.</p>
         */
        @Nullable
        public String tacCompatClass() {
            return switch (this.build) {
                case LEGACY_YSM -> LEGACY_TAC_COMPAT;
                case OPEN_YSM, MODERN_YSM -> OPEN_TAC_COMPAT;
                case NONE -> null;
            };
        }

        /** The wrapper method feeding the {@code hold_mainhand} controller. */
        @Nullable
        public String methodHoldGun() {
            return switch (this.build) {
                case LEGACY_YSM -> LEGACY_METHOD_HOLD_GUN;
                case OPEN_YSM, MODERN_YSM -> "handleGunHoldAnimState";
                case NONE -> null;
            };
        }

        /**
         * The class holding the {@code Capability<PlayerCapability>} attribute, under the readable
         * name each build ships it as.
         *
         * <p>ModernYSM relocates it to the {@code forge} subpackage, OpenYSM keeps it in
         * {@code capability}, and the obfuscated release has no readable name at all (null here;
         * the structural scan covers it).</p>
         */
        @Nullable
        public String capabilityProviderClass() {
            return switch (this.build) {
                case MODERN_YSM -> MODERN_CAPABILITY_PROVIDER;
                case OPEN_YSM -> OPEN_CAPABILITY_PROVIDER;
                case LEGACY_YSM, NONE -> null;
            };
        }

        /** The wrapper method feeding the {@code fire} controller. */
        @Nullable
        public String methodGunAction() {
            return switch (this.build) {
                case LEGACY_YSM -> LEGACY_METHOD_GUN_ACTION;
                case OPEN_YSM, MODERN_YSM -> "handleGunActionAnimState";
                case NONE -> null;
            };
        }
    }

    private static final Info ABSENT = new Info(Build.NONE, "mod 'yes_steve_model' is not loaded", "");

    private static volatile Info info;

    /**
     * Set once {@link net.minecraftforge.fml.event.lifecycle.FMLLoadCompleteEvent} has fired,
     * after which "is YSM loaded" is a settled question.
     *
     * <p>Before that, a negative answer must not be cached. Forge populates {@code ModList}
     * progressively while mods are loaded in parallel, so during mod construction
     * {@code isLoaded("yes_steve_model")} can be {@code false} for a mod that is in fact
     * present - and this mod's constructor runs in exactly that window. Caching that {@code
     * false} produced the observed contradiction in a real launch:</p>
     *
     * <pre>
     * [ysm_scg2_compat] path: active. Yes Steve Model + Scorched Guns 2 both present.
     * [ysm_scg2_compat] no YSM build found (mod 'yes_steve_model' is not loaded) ...
     * </pre>
     *
     * <p>Two milliseconds apart, disagreeing. The consequence was not cosmetic: a cached
     * {@code NONE} disabled the whole diagnostic layer (model report, render probe) for the
     * rest of the session, which is precisely the telemetry needed to debug the remaining
     * third-person problem.</p>
     */
    private static volatile boolean loadComplete;

    private YsmFork() {
    }

    /** Called once mod loading has finished, so the verdict may now be cached. */
    public static void onLoadComplete() {
        loadComplete = true;
        // Re-detect in case an earlier answer was taken during the parallel-load window.
        synchronized (YsmFork.class) {
            info = null;
        }
        YsmClasses.invalidate();
        reportAtStartup();
    }

    /**
     * The identified build. Never null.
     *
     * <p>An answer is only cached once it is trustworthy: a <em>positive</em> identification
     * is stable whenever it is obtained, but a negative one is re-tested until mod loading has
     * finished, because {@code ModList} is still filling in during mod construction. See
     * {@link #loadComplete}.</p>
     */
    public static Info info() {
        Info cached = info;
        if (cached != null) {
            return cached;
        }
        synchronized (YsmFork.class) {
            if (info == null) {
                Info detected = identify();
                if (detected.present() || loadComplete) {
                    info = detected;
                }
                return detected;
            }
            return info;
        }
    }

    public static Build build() {
        return info().build();
    }

    /**
     * Runs detection now and emits the one-line verdict, so the answer is in the log before
     * any render path asks for it.
     */
    public static Info reportAtStartup() {
        Info result = info();
        if (result.present()) {
            YsmScg2Compat.LOGGER.info("[{}] YSM build: {} (version '{}'; evidence: {}; class listing via {}). "
                            + "TACZ bridge class: {}",
                    YsmScg2Compat.MOD_ID, result.build(), result.version(), result.evidence(),
                    YsmClasses.route(), result.tacCompatClass());
        } else {
            YsmScg2Compat.LOGGER.info("[{}] no YSM build found ({}) - the animation bridge is inactive.",
                    YsmScg2Compat.MOD_ID, result.evidence());
        }
        return result;
    }

    private static Info identify() {
        // Presence is asked of LoadingModList and ModList together, and it must be asked this way.
        //
        // A probe run proved the distinction is not academic: at MIXIN PLUGIN time
        // ModPresence (which consults LoadingModList) reported yes_steve_model=true while this
        // method, reading ModList alone, reported "not loaded" - because ModList is still being
        // populated then. The plugin therefore skipped both mixins and the injection never
        // happened, while 34 seconds later the same call answered correctly.
        //
        // LoadingModList is built during mod discovery and is authoritative from the earliest
        // phase; ModList is only complete after mod construction.
        boolean present = presentAnywhere();
        ProbeLog.log("fork", "identify: present=" + present
                + " [ModList=" + modListHas() + ", LoadingModList=" + loadingModListHas() + "]");
        if (!present) {
            return ABSENT;
        }
        String version = version();

        // The class list is ground truth, so ask it first: a build whose layout moved is
        // still identified. The probes below are only the fallback for a build whose scan
        // data is unavailable.
        Info byScan = identifyByScan(version);
        if (byScan != null) {
            return byScan;
        }

        if (classExists(MODERN_CAPABILITY_PROVIDER) || classExists(MODERN_FORGE_RENDER_HOOK)) {
            return new Info(Build.MODERN_YSM, "ModernYSM forge subpackage (probe)", version);
        }
        if (classExists(OPEN_CAPABILITY_PROVIDER)) {
            return new Info(Build.OPEN_YSM, OPEN_CAPABILITY_PROVIDER + " (probe)", version);
        }
        if (classExists(UNOBFUSCATED_RENDER_EVENT)) {
            return new Info(Build.OPEN_YSM, UNOBFUSCATED_RENDER_EVENT + " (probe)", version);
        }
        // Present, but no readable marker exists: every name is obfuscated, which is the
        // signature of the official release.
        return new Info(Build.LEGACY_YSM,
                "no readable YSM class names present (obfuscated build, probe)", version);
    }

    /**
     * Identify the build from YSM's own class list.
     *
     * @return the verdict, or null when the class list is unavailable
     */
    @Nullable
    private static Info identifyByScan(String version) {
        java.util.List<String> listed;
        try {
            listed = YsmClasses.names();
        } catch (Throwable t) {
            return null;
        }
        if (listed.isEmpty()) {
            return null;
        }

        YsmClasses.Holder holder;
        try {
            holder = YsmClasses.findCapabilityHolder(YsmClasses.CAPABILITY_CLASS);
        } catch (Throwable t) {
            holder = null;
        }

        String evidence = "scan of " + listed.size() + " YSM classes";
        if (holder == null) {
            // No readable player capability anywhere in the jar, and the jar's own mixin
            // classes are readable in every build - so this is the obfuscated original.
            return new Info(Build.LEGACY_YSM,
                    evidence + "; no readable player capability class (obfuscated build)", version);
        }

        // Two independent signals for "this is ModernYSM": where the provider sits, and whether
        // the forge-only render hook exists. Either is sufficient, and requiring both would
        // misclassify a build that renamed one of them.
        boolean forgeSubpackage = holder.className().startsWith("com.elfmcys.yesstevemodel.forge.");
        boolean forgeRenderHook = classExists(MODERN_FORGE_RENDER_HOOK);
        boolean modern = forgeSubpackage || forgeRenderHook;
        return new Info(modern ? Build.MODERN_YSM : Build.OPEN_YSM,
                evidence + "; capability provider at " + holder.className()
                        + (forgeSubpackage ? " (forge subpackage)" : "")
                        + (forgeRenderHook ? " (forge render hook present)" : ""),
                version);
    }

    /** One source of truth for "is YSM installed", asked in the order that actually works. */
    private static boolean presentAnywhere() {
        return loadingModListHas() || modListHas();
    }

    /** The early, always-available view. Authoritative from mod discovery onwards. */
    private static boolean loadingModListHas() {
        try {
            net.minecraftforge.fml.loading.LoadingModList loading =
                    net.minecraftforge.fml.loading.LoadingModList.get();
            return loading != null && loading.getModFileById(YSM_MOD_ID) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /** The late view. Complete only after mod construction, which is why it cannot be trusted alone. */
    private static boolean modListHas() {
        try {
            ModList modList = ModList.get();
            return modList != null && modList.isLoaded(YSM_MOD_ID);
        } catch (Throwable t) {
            return false;
        }
    }

    /** YSM's reported version, or an empty string when unavailable. */
    private static String version() {
        try {
            ModList modList = ModList.get();
            if (modList != null) {
                return modList.getModContainerById(YSM_MOD_ID)
                        .map(container -> container.getModInfo().getVersion().toString())
                        .orElse("");
            }
        } catch (Throwable ignored) {
            // Fall through to the loading view.
        }
        try {
            net.minecraftforge.fml.loading.LoadingModList loading =
                    net.minecraftforge.fml.loading.LoadingModList.get();
            if (loading == null) {
                return "";
            }
            return loading.getMods().stream()
                    .filter(mod -> YSM_MOD_ID.equals(mod.getModId()))
                    .map(mod -> mod.getVersion().toString())
                    .findFirst()
                    .orElse("");
        } catch (Throwable t) {
            return "";
        }
    }

    private static boolean classExists(String name) {
        try {
            Class.forName(name, false, YsmFork.class.getClassLoader());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
