package com.ysm.scg2.compat.ysm;

import com.ysm.scg2.compat.YsmScg2Compat;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.forgespi.language.ModFileScanData;
import org.jetbrains.annotations.Nullable;

import java.lang.module.ModuleReader;
import java.lang.module.ResolvedModule;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The class names Yes Steve Model actually ships, taken from what the mod loader already
 * knows about it - its scan data first, its module second.
 *
 * <h2>Why this exists</h2>
 * <p>Yes Steve Model is distributed in two forms that both claim
 * {@code modId = "yes_steve_model"} and the same version number, but whose internals are
 * named completely differently:</p>
 *
 * <ul>
 *   <li>the <b>official release</b>, which is obfuscated - every class in
 *       {@code com.elfmcys.yesstevemodel} has a name like
 *       {@code OOO0O0O0oo0ooooo00oOOOO0};</li>
 *   <li>the <b>community fork</b> (the {@code OpenYSM} checkout next to this project, and
 *       ModernYSM after it), which is readable - {@code TacCompat},
 *       {@code capability.PlayerCapabilityProvider} and so on.</li>
 * </ul>
 *
 * <p>Naming those classes in source means a mapping table per build that silently rots -
 * which is exactly what happened to this mod's first version, where every hardcoded
 * readable name missed on the obfuscated build and the feature went quiet instead of
 * failing loudly.</p>
 *
 * <p>Asking the loader for YSM's class list instead lets a class be found by <i>what it
 * is</i> - which fields it declares, what generic type it holds - rather than by its name,
 * so an obfuscated build, a repackaged fork and a future relocation all resolve the same
 * way.</p>
 *
 * <p>Technique ported from {@code ysm_epicfight_compat}'s {@code YsmClasses} (MIT), which
 * uses it for the same reason against the same three builds.</p>
 *
 * <h2>Failure policy</h2>
 * <p>All loader access is reflective and tolerant: the {@code ClassData} accessors are named
 * differently across loader builds ({@code clazz()} returns ASM {@code Type} on Forge
 * 1.20.1; some builds expose bean-style names), so every accessor is tried by name and every
 * route may fail quietly. A build where nothing resolves simply yields no names and the
 * caller falls back to its fingerprint table.</p>
 */
public final class YsmClasses {

    private static final String YSM_MOD_ID = "yes_steve_model";
    private static final String YSM_PACKAGE = "com.elfmcys.";

    /**
     * YSM's player capability class - one of the few names stable across the readable
     * builds, and the anchor for finding the provider: the class declaring a
     * {@code Capability<PlayerCapability>} field is the provider, whatever package it was
     * put in.
     *
     * <p>The obfuscated original has no such class under a readable name at all, which is
     * what makes this a reliable fork discriminator.</p>
     */
    public static final String CAPABILITY_CLASS =
            "com.elfmcys.yesstevemodel.capability.PlayerCapability";

    private static volatile List<String> names;
    private static volatile String route = "none";

    private YsmClasses() {
    }

    /**
     * Every YSM class the loader knows about, or an empty list when the loader's scan data
     * is unavailable.
     *
     * <p>Cached after the first successful listing; a failed listing is not remembered,
     * because it may simply be too early in startup.</p>
     */
    public static List<String> names() {
        List<String> cached = names;
        if (cached != null) {
            return cached;
        }
        synchronized (YsmClasses.class) {
            if (names != null) {
                return names;
            }
            List<String> found = fromScan();
            String how = "scan";
            if (found.isEmpty()) {
                found = fromModule();
                how = "module";
            }
            if (found.isEmpty()) {
                return Collections.emptyList();
            }
            route = how;
            names = Collections.unmodifiableList(found);
            YsmScg2Compat.LOGGER.info("[{}] listed {} Yes Steve Model classes (via {}) - "
                            + "classes are now addressed by what they are rather than by name",
                    YsmScg2Compat.MOD_ID, found.size(), how);
            return names;
        }
    }

    /** How the class list was obtained ({@code scan}, {@code module} or {@code none}). */
    public static String route() {
        names();
        return route;
    }

    /**
     * Whether a class with this exact name is in YSM's listing. Cheap, and unlike
     * {@code Class.forName} it cannot trigger a linkage error.
     */
    public static boolean has(String className) {
        return names().contains(className);
    }

    /**
     * The capability provider YSM registers its player animatable under, found by <b>structure</b>
     * instead of by package or by name.
     *
     * <p>Two routes, because the capability class itself may be obfuscated:</p>
     * <ol>
     *   <li>{@code capabilityFqn} known (the readable builds) - look for the static field whose
     *       generic argument is exactly that class. Precise.</li>
     *   <li>{@code capabilityFqn} unknown (the obfuscated release) - look for a static field whose
     *       <em>raw</em> type is named {@code Capability}. Less precise, but a Forge capability
     *       provider is exactly "a class holding static {@code Capability} fields", and the generic
     *       argument cannot be named when its class is renamed.</li>
     * </ol>
     *
     * <p>Route 2 is why this works on the build that actually ships: previously a {@code null}
     * anchor returned early, so the obfuscated release resolved no holder, no capability token, and
     * the animation guard stayed off for the whole session.</p>
     *
     * @param capabilityFqn the player-capability class name, or null when it is obfuscated
     * @return the holder, or null when nothing matches
     */
    public static Holder findCapabilityHolder(@Nullable String capabilityFqn) {
        for (String className : names()) {
            if (!className.startsWith(YSM_PACKAGE)) {
                continue;
            }
            Holder holder = probeHolder(className, capabilityFqn);
            if (holder != null) {
                return holder;
            }
        }
        return null;
    }

    /**
     * @param className the class declaring the capability field
     * @param fieldName the static field holding the {@code Capability}
     */
    public record Holder(String className, String fieldName) {
    }

    /**
     * Whether {@code className} declares a static {@code Capability} field, optionally requiring
     * its generic argument to be a specific class. Never throws and never initializes the class.
     */
    private static Holder probeHolder(String className, @Nullable String capabilityFqn) {
        try {
            Class<?> type = Class.forName(className, false, YsmClasses.class.getClassLoader());
            for (Field field : type.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                if (!isCapabilityField(field)) {
                    continue;
                }
                if (capabilityFqn == null || capabilityFqn.equals(capabilityArgumentOf(field.getGenericType()))) {
                    return new Holder(className, field.getName());
                }
            }
        } catch (Throwable ignored) {
            // Not loadable (missing optional dependency, wrong side, ...) - skip it.
        }
        return null;
    }

    /**
     * Is this field a Forge {@code Capability}?
     *
     * <p>Checked on the raw type's simple name, because on the obfuscated build the capability
     * class itself is renamed: the field's declared type reads
     * {@code net.minecraftforge.common.capabilities.Capability<com.elfmcys.yesstevemodel.O0oOo…>},
     * and only the raw part is readable. That is enough - a Forge capability provider is precisely
     * a class holding static {@code Capability} fields.</p>
     *
     * <p>Without this fallback the obfuscated build resolved no holder, therefore no capability
     * token, therefore {@code YsmBridge.isAvailable()} stayed false and the animation guard
     * declined on every frame.</p>
     */
    private static boolean isCapabilityField(Field field) {
        for (Class<?> current = field.getType(); current != null; current = current.getSuperclass()) {
            if ("Capability".equals(current.getSimpleName())) {
                return true;
            }
        }
        return false;
    }

    /** The {@code X} in a {@code Capability<X>} type, or null for anything else. */
    private static String capabilityArgumentOf(java.lang.reflect.Type genericType) {
        if (!(genericType instanceof java.lang.reflect.ParameterizedType parameterized)) {
            return null;
        }
        if (!(parameterized.getRawType() instanceof Class<?> raw)
                || !"Capability".equals(raw.getSimpleName())) {
            return null;
        }
        java.lang.reflect.Type[] arguments = parameterized.getActualTypeArguments();
        if (arguments.length != 1) {
            return null;
        }
        if (arguments[0] instanceof Class<?> capability) {
            return capability.getName();
        }
        if (arguments[0] instanceof java.lang.reflect.ParameterizedType nested
                && nested.getRawType() instanceof Class<?> nestedRaw) {
            return nestedRaw.getName();
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Loader access
    // ------------------------------------------------------------------

    /** One scan entry: the class and its direct parent, reduced to names. */
    private record ClassInfo(String name, String parent) {
    }

    private static List<String> fromScan() {
        List<String> found = new ArrayList<>();
        for (ClassInfo info : scan()) {
            if (info.name() != null && info.name().startsWith(YSM_PACKAGE)) {
                found.add(info.name());
            }
        }
        return found;
    }

    private static List<ClassInfo> scan() {
        List<ClassInfo> found = new ArrayList<>();
        try {
            Object modFile = modFile();
            if (modFile == null) {
                return found;
            }
            Object scanResult = invokeNoArg(modFile, "getScanResult");
            if (!(scanResult instanceof ModFileScanData data) || data.getClasses() == null) {
                return found;
            }
            for (ModFileScanData.ClassData entry : data.getClasses()) {
                String name = typeNameOf(entry, "clazz", "getClazz");
                if (name == null) {
                    continue;
                }
                found.add(new ClassInfo(name, typeNameOf(entry, "parent", "getParent")));
            }
        } catch (Throwable t) {
            YsmScg2Compat.LOGGER.debug("[{}] YSM scan data unavailable", YsmScg2Compat.MOD_ID, t);
        }
        return found;
    }

    private static Object modFile() {
        try {
            ModList modList = ModList.get();
            if (modList == null) {
                return null;
            }
            Object fileInfo = modList.getModFileById(YSM_MOD_ID);
            return fileInfo == null ? null : invokeNoArg(fileInfo, "getFile");
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object invokeNoArg(Object target, String methodName) {
        try {
            Method method = target.getClass().getMethod(methodName);
            return method.invoke(target);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * The class name inside a scan entry. The accessor returns ASM's {@code Type} on Forge
     * 1.20.1, so it is unwrapped through {@code getClassName()} by reflection rather than by
     * casting - this mod has no ASM compile dependency.
     */
    private static String typeNameOf(ModFileScanData.ClassData data, String... accessors) {
        for (String accessor : accessors) {
            Object type = invokeNoArg(data, accessor);
            if (type == null) {
                continue;
            }
            if (type instanceof String name) {
                return name;
            }
            Object className = invokeNoArg(type, "getClassName");
            if (className instanceof String name) {
                return name;
            }
        }
        return null;
    }

    /**
     * Fallback listing through the module system, for a loader build whose scan data is not
     * exposed.
     */
    private static List<String> fromModule() {
        List<String> found = new ArrayList<>();
        try {
            Class<?> sample = Class.forName("com.elfmcys.yesstevemodel.YesSteveModel",
                    false, YsmClasses.class.getClassLoader());
            Module module = sample.getModule();
            if (!module.isNamed() || module.getLayer() == null) {
                return found;
            }
            Optional<ResolvedModule> resolved = module.getLayer().configuration().findModule(module.getName());
            if (resolved.isEmpty()) {
                return found;
            }
            try (ModuleReader reader = resolved.get().reference().open();
                 Stream<String> entries = reader.list()) {
                for (String entry : (Iterable<String>) entries::iterator) {
                    if (!entry.endsWith(".class") || entry.equals("module-info.class")) {
                        continue;
                    }
                    String className = entry.substring(0, entry.length() - 6).replace('/', '.');
                    if (className.startsWith(YSM_PACKAGE)) {
                        found.add(className);
                    }
                }
            }
        } catch (Throwable t) {
            YsmScg2Compat.LOGGER.debug("[{}] YSM module listing unavailable", YsmScg2Compat.MOD_ID, t);
        }
        return found;
    }

    /** Drop the cached listing (world leave / resource reload). */
    public static synchronized void invalidate() {
        names = null;
        route = "none";
    }
}
