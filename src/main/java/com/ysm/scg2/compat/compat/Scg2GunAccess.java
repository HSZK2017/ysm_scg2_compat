package com.ysm.scg2.compat.compat;

import com.ysm.scg2.compat.YsmScg2Compat;
import com.ysm.scg2.compat.ysm.MemberLookup;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The single place where this mod talks to Scorched Guns 2.
 *
 * <h2>Why one class instead of calls sprinkled through the mixin</h2>
 * <p>Every SCG2 type is touched here and nowhere else, so the "optional dependency"
 * reference surface is one file wide. If an SCG2 release renames something, the blast
 * radius is this class's {@code try/catch} blocks - not the render loop.</p>
 *
 * <h2>Why reflection at all, when SCG2 is a hard dependency</h2>
 * <p>Two reasons, both about failure modes rather than laziness:</p>
 * <ul>
 *   <li>{@code ModSyncedDataKeys}' constants are {@code SyncedDataKey} objects from the
 *       separate <em>framework</em> mod. Reading them by name keeps a framework version
 *       skew from becoming a {@code NoClassDefFoundError} inside YSM's animation
 *       predicate chain (which runs every frame, for every player, on the client).</li>
 *   <li>The animation-set decision asks SCG2 for a grip type. If an SCG2 addon ships a
 *       gun with incomplete weapon data that lookup can blow up; catching it here lets
 *       the caller degrade to "no gun animation" - the pre-mod behaviour - instead of
 *       taking the frame down.</li>
 * </ul>
 *
 * <h2>Why {@code Throwable} and not {@code Exception}</h2>
 * <p>{@code NoSuchMethodError} / {@code NoClassDefFoundError} / {@code LinkageError} are
 * {@link Error}s, not {@link Exception}s, and they are precisely the failures a version
 * mismatch produces.</p>
 */
public final class Scg2GunAccess {

    /** Grip type {@code MINI_GUN*} sits outside the two standard shapes, so pin the ids. */
    private static final Set<String> MINIGUN_PATHS = Set.of(
            "thunderhead", "gattaler", "weevil", "hullbreaker",
            "cr4k_mining_laser", "flayed_god", "spitfire", "cyclone"
    );

    /** Grip type {@code BAZOOKA} maps to the RPG animation set. */
    private static final Set<String> RPG_PATHS = Set.of(
            "kiln_gun", "scratches", "dozier_rl", "terra_incognita"
    );

    /** Animation sets YSM understands; the names match {@code GunTabType} lower-cased. */
    public static final String TYPE_PISTOL = "pistol";
    public static final String TYPE_RIFLE = "rifle";
    public static final String TYPE_RPG = "rpg";

    private static final String GUN_ITEM_CLASS = "top.ribs.scguns.item.GunItem";
    private static final String SYNCED_KEYS_CLASS = "top.ribs.scguns.init.ModSyncedDataKeys";

    // Cached reflection targets. ConcurrentHashMap because a resource reload can race
    // the render thread on first use.
    private static final Map<String, Method> METHOD_CACHE = new ConcurrentHashMap<>();

    private static final SyncedReader KEY_AIMING = resolveSyncedKey("AIMING");
    private static final SyncedReader KEY_SHOOTING = resolveSyncedKey("SHOOTING");
    private static final SyncedReader KEY_RELOADING = resolveSyncedKey("RELOADING");
    private static final SyncedReader KEY_MELEE = resolveSyncedKey("MELEE");

    /** One-shot guards so a broken install logs once instead of once per frame. */
    private static volatile boolean gunClassWarningLogged;
    private static volatile boolean gripTypeWarningLogged;

    private Scg2GunAccess() {
    }

    // ------------------------------------------------------------------
    // Identity
    // ------------------------------------------------------------------

    /**
     * Is this stack one of Scorched Guns 2's weapons?
     *
     * <p>The test is the item <em>type</em>, not the namespace. SCG2 loads gun definitions
     * from {@code data/<addon modid>/guns/}, so an addon's guns live in the addon's own
     * namespace and a namespace whitelist would miss every one of them. SCG2 itself
     * decides the same way - it walks the whole item registry filtering on
     * {@code instanceof GunItem}.</p>
     */
    public static boolean isScg2Gun(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        try {
            Class<?> gunItem = Class.forName(GUN_ITEM_CLASS);
            return gunItem.isInstance(stack.getItem());
        } catch (Throwable t) {
            if (!gunClassWarningLogged) {
                gunClassWarningLogged = true;
                YsmScg2Compat.LOGGER.warn("[{}] could not resolve {} - SCG2 weapon detection is OFF ({})",
                        YsmScg2Compat.MOD_ID, GUN_ITEM_CLASS, t.toString());
            }
            return false;
        }
    }

    /** The weapon's registry id, used for {@code tac:...$<id>} per-gun overrides. */
    @Nullable
    public static ResourceLocation getGunId(ItemStack stack) {
        try {
            return ForgeRegistries.ITEMS.getKey(stack.getItem());
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Animation set selection
    // ------------------------------------------------------------------

    /**
     * Which of YSM's three gun animation sets this weapon should use.
     *
     * <h3>Primary criterion: how it is held, not what it is</h3>
     * <p>The pose is decided by the grip, so the grip decides the animation set:</p>
     * <table border="1">
     *   <caption>grip to animation set</caption>
     *   <tr><th>GripType id</th><th>set</th></tr>
     *   <tr><td>{@code one_handed}, {@code one_handed_2}, {@code dual_wield}</td><td>{@code pistol}</td></tr>
     *   <tr><td>{@code two_handed}, {@code two_handed_shotgun}, {@code two_handed_smg}</td><td>{@code rifle}</td></tr>
     *   <tr><td>{@code mini_gun} ... {@code mini_gun_5}</td><td>{@code rifle}</td></tr>
     *   <tr><td>{@code bazooka}</td><td>{@code rpg}</td></tr>
     * </table>
     *
     * <p>Grip-first also covers the cases a weapon-category test gets wrong, e.g. a pistol
     * with a stock fitted becomes {@code two_handed} and correctly receives the rifle
     * pose. The two special cases are needed because {@code MINI_GUN*} and {@code BAZOOKA}
     * are neither one-handed nor two-handed.</p>
     *
     * <h3>Fallback</h3>
     * <p>If the grip cannot be read (missing weapon data, an addon that omits fields), the
     * answer is {@code rifle} - the most conservative generic pose, and the same choice
     * YSM's own TACZ path makes for an unrecognised tab type.</p>
     */
    public static String getGunAnimationType(ItemStack stack) {
        String path = pathOf(stack);
        if (path != null) {
            // Special cases first: these grips are outside the two standard groups.
            if (MINIGUN_PATHS.contains(path)) {
                return TYPE_RIFLE;
            }
            if (RPG_PATHS.contains(path)) {
                return TYPE_RPG;
            }
        }

        String gripId = getGripTypeId(stack);
        if (gripId == null) {
            return TYPE_RIFLE;
        }
        return switch (gripId) {
            case "one_handed", "one_handed_2", "dual_wield" -> TYPE_PISTOL;
            case "two_handed", "two_handed_shotgun", "two_handed_smg" -> TYPE_RIFLE;
            case "mini_gun", "mini_gun_2", "mini_gun_3", "mini_gun_4", "mini_gun_5" -> TYPE_RIFLE;
            case "bazooka" -> TYPE_RPG;
            default -> TYPE_RIFLE;
        };
    }

    /**
     * The {@code one_handed}-style id of the weapon's grip type, or {@code null} when it
     * cannot be determined.
     *
     * <p>Reads the grip's {@link ResourceLocation} rather than comparing {@code GripType}
     * constant identity: those constants ({@code MINI_GUN_3} and friends) are an
     * implementation detail that has changed between SCG2 releases, while the serialised
     * ids are part of the gun data format that resource packs are written against.</p>
     */
    @Nullable
    private static String getGripTypeId(ItemStack stack) {
        try {
            Object gunItem = stack.getItem();
            Object gun = invoke(gunItem, "getModifiedGun", ItemStack.class, stack);
            if (gun == null) {
                return null;
            }
            Object general = invoke(gun, "getGeneral");
            if (general == null) {
                return null;
            }
            Object gripType = invoke(general, "getGripType", ItemStack.class, stack);
            if (gripType == null) {
                return null;
            }
            Object id = invoke(gripType, "id");
            return id instanceof ResourceLocation location ? location.getPath() : null;
        } catch (Throwable t) {
            if (!gripTypeWarningLogged) {
                gripTypeWarningLogged = true;
                YsmScg2Compat.LOGGER.warn("[{}] could not read an SCG2 grip type; every such weapon will use the '{}' animation set ({})",
                        YsmScg2Compat.MOD_ID, TYPE_RIFLE, t.toString());
            }
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Action state
    // ------------------------------------------------------------------

    /**
     * Is this entity currently aiming down sights with an SCG2 weapon?
     *
     * <p>Reads SCG2's own synced {@code aiming} flag, which the mod writes server-side
     * every tick and replicates. That makes it valid for <em>remote</em> players too, and
     * is why this does not go through a client-only handler.</p>
     */
    public static boolean isAiming(LivingEntity entity) {
        return readSyncedBoolean(KEY_AIMING, entity);
    }

    /** Is this entity mid-shot / on shoot cooldown? Drives {@code tac:*:fire:*}. */
    public static boolean isFiring(LivingEntity entity) {
        return readSyncedBoolean(KEY_SHOOTING, entity);
    }

    /** Is this entity reloading? Drives {@code tac:reload:*} / {@code tac:reload:*:*}. */
    public static boolean isReloading(LivingEntity entity) {
        return readSyncedBoolean(KEY_RELOADING, entity);
    }

    /** Is this entity doing a bayonet / gun-butt melee? Drives {@code tac:melee:*}. */
    public static boolean isMelee(LivingEntity entity) {
        return readSyncedBoolean(KEY_MELEE, entity);
    }

    /**
     * Reads a {@code SyncedDataKey<Player, Boolean>}.
     *
     * <p>SCG2 declares these keys against {@code SyncedClassKey.PLAYER} and the values are
     * only defined for players, so a non-player entity (a maid wearing a YSM model, for
     * instance) has no state and correctly reads {@code false} - which lets the caller
     * fall through to the neutral hold animation.</p>
     */
    private static boolean readSyncedBoolean(@Nullable SyncedReader reader, LivingEntity entity) {
        if (reader == null || !(entity instanceof Player)) {
            return false;
        }
        return reader.readBoolean(entity);
    }

    // ------------------------------------------------------------------
    // Reflection plumbing
    // ------------------------------------------------------------------

    @Nullable
    private static String pathOf(ItemStack stack) {
        ResourceLocation id = getGunId(stack);
        return id == null ? null : id.getPath();
    }

    /**
     * Invokes a public no-arg method, or a single-arg method when a parameter type and
     * value are supplied. Method lookups are cached per {@code Class#method} pair.
     */
    @Nullable
    private static Object invoke(Object target, String name, Object... signature) throws Throwable {
        Class<?>[] parameterTypes = new Class<?>[signature.length / 2];
        Object[] arguments = new Object[signature.length / 2];
        for (int i = 0; i < parameterTypes.length; i++) {
            parameterTypes[i] = (Class<?>) signature[i * 2];
            arguments[i] = signature[i * 2 + 1];
        }

        Class<?> type = target.getClass();
        String cacheKey = type.getName() + '#' + name + '/' + parameterTypes.length;
        Method method = METHOD_CACHE.get(cacheKey);
        if (method == null) {
            method = findPublicMethod(type, name, parameterTypes);
            if (method == null) {
                throw new NoSuchMethodException(type.getName() + '.' + name);
            }
            METHOD_CACHE.put(cacheKey, method);
        }
        return method.invoke(target, arguments);
    }

    @Nullable
    private static Method findPublicMethod(Class<?> type, String name, Class<?>[] parameterTypes) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                return current.getMethod(name, parameterTypes);
            } catch (NoSuchMethodException ignored) {
                // Walk up: getModifiedGun is declared on GunItem, getGripType on a nested
                // General class, and neither is guaranteed to stay where it is today.
            }
        }
        return null;
    }

    /**
     * One SCG2 synced flag: the {@code SyncedDataKey} object plus its value accessor.
     *
     * <h3>Why the accessor is not looked up with an exact parameter type</h3>
     * <p>{@code SyncedDataKey} is a generic record - {@code <E extends Entity, T> T getValue(E)} -
     * so the <b>declared</b> parameter type is the erasure of the bound, {@code Entity}, and the
     * generic argument ({@code Player} here) never appears in the signature. Asking for
     * {@code getValue(Player)} therefore threw {@code NoSuchMethodException} on every launch, all
     * four of these keys resolved to {@code null}, and the mod's reading was:</p>
     *
     * <pre>
     * SCG2 synced key AIMING is unavailable; that part of the weapon state will read as 'off'
     * </pre>
     *
     * <p>The consequence was invisible rather than loud: every firing, reloading and melee
     * animation was declined for SCG2 weapons, with no error anywhere, because "the state is
     * off" and "the accessor is missing" produce the same answer. The accessor is now matched
     * against the receiver by assignability ({@link MemberLookup}) and cached.</p>
     */
    private static final class SyncedReader {

        private final String fieldName;
        private final Object key;

        /** Resolved on first read, when the entity's own class is available to match against. */
        @Nullable
        private volatile Method accessor;

        private SyncedReader(String fieldName, Object key) {
            this.fieldName = fieldName;
            this.key = key;
        }

        private boolean readBoolean(LivingEntity entity) {
            try {
                Method method = accessor;
                if (method == null || !method.getParameterTypes()[0].isInstance(entity)) {
                    method = MemberLookup.find(key.getClass(), "getValue", entity);
                    if (method == null) {
                        return false;
                    }
                    accessor = method;
                }
                Object value = method.invoke(key, entity);
                return value instanceof Boolean flag && flag;
            } catch (Throwable t) {
                return false;
            }
        }

        @Override
        public String toString() {
            return fieldName;
        }
    }

    /**
     * Resolves a {@code SyncedDataKey} constant into a ready-to-use reader, or {@code null}
     * (with one warning) when the field has moved.
     */
    @Nullable
    private static SyncedReader resolveSyncedKey(String fieldName) {
        try {
            Class<?> keys = Class.forName(SYNCED_KEYS_CLASS);
            Field field = keys.getField(fieldName);
            Object key = field.get(null);
            if (key == null) {
                throw new IllegalStateException("field " + fieldName + " is null");
            }
            return new SyncedReader(fieldName, key);
        } catch (Throwable t) {
            YsmScg2Compat.LOGGER.warn("[{}] SCG2 synced key {} is unavailable; that part of the weapon state will read as 'off' ({})",
                    YsmScg2Compat.MOD_ID, fieldName, t.toString());
            return null;
        }
    }
}
