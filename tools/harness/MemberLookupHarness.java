import com.elfmcys.yesstevemodel.geckolib3.core.builder.ILoopType;
import com.elfmcys.yesstevemodel.geckolib3.core.builder.ILoopType.EDefaultLoopTypes;
import com.ysm.scg2.compat.client.GunAnimationNames;
import com.ysm.scg2.compat.ysm.MemberLookup;
import com.ysm.scg2.compat.ysm.YsmMemberNames;

import java.lang.reflect.Method;

/**
 * Runtime proof for the two reflective lookups that were silently wrong.
 *
 * <p>Driven by {@code tools/verify-member-lookup.ps1}. It puts three things on one classpath -
 * the built compat jar, Yes Steve Model's own jar, and this file - so the checks run against
 * YSM's <b>real</b> {@code ILoopType} and the mod's <b>real</b> resolver, not against a
 * paraphrase of them:</p>
 *
 * <ol>
 *   <li>the exact-type lookup the mod used to perform <b>cannot</b> find
 *       {@code setAnimation(String, ILoopType)} when the value held is an
 *       {@code EDefaultLoopTypes} constant - the reason the requested loop type was dropped,
 *       which is what released the gun pose after one play-through of a {@code tac:hold:rpg}
 *       clip that declares no {@code loop} of its own;</li>
 *   <li>the shipped {@code YsmMemberNames.resolveCallable} <b>does</b> find it, and the loop
 *       type arrives at the controller;</li>
 *   <li>the same rule repairs the generic erasure that made every Scorched Guns 2 weapon state
 *       read as 'off' ({@code getValue(E extends Entity)}, called with a {@code Player}).</li>
 * </ol>
 *
 * <p>Exit code 0 = every check passed.</p>
 */
public final class MemberLookupHarness {

    private static int checks;
    private static int failures;

    public static void main(String[] args) {
        System.out.println("member lookup harness");
        System.out.println("  YSM ILoopType : " + ILoopType.class.getName() + " from "
                + whereIs(ILoopType.class));
        System.out.println("  resolver      : " + YsmMemberNames.class.getName() + " from "
                + whereIs(YsmMemberNames.class));
        System.out.println();

        System.out.println("1. the lookup that was wrong (exact parameter types)");
        checkOldLookupCannotMatch();
        System.out.println();

        System.out.println("2. the shipped resolver (YsmMemberNames.resolveCallable)");
        checkShippedResolverCarriesTheLoopType();
        System.out.println();

        System.out.println("3. the same rule for a generic erasure (SyncedDataKey#getValue)");
        checkGenericErasure();
        System.out.println();

        System.out.println("4. the per-gun animation name convention");
        checkPerGunNames();
        System.out.println();

        System.out.println(failures == 0
                ? "All " + checks + " checks passed."
                : failures + " of " + checks + " checks FAILED.");
        System.exit(failures == 0 ? 0 : 1);
    }

    // ------------------------------------------------------------------
    // 1. the old lookup
    // ------------------------------------------------------------------

    private static void checkOldLookupCannotMatch() {
        Object loop = EDefaultLoopTypes.LOOP;
        try {
            Method found = Controller.class.getMethod("setAnimation", String.class, loop.getClass());
            fail("Class.getMethod(String, String.class, " + loop.getClass().getSimpleName()
                    + ") unexpectedly matched " + found
                    + " - the premise of this harness is wrong");
        } catch (NoSuchMethodException expected) {
            ok("Class.getMethod(String, " + loop.getClass().getSimpleName()
                    + ") throws NoSuchMethodException: the declared parameter is the interface "
                    + ILoopType.class.getSimpleName() + ", and exact lookup cannot see that");
        }
        try {
            Method declared = Controller.class.getMethod("setAnimation", String.class, ILoopType.class);
            ok("the 2-arg overload really is declared as " + signature(declared)
                    + " (same shape as YSM's PredicateBasedController)");
        } catch (NoSuchMethodException e) {
            fail("Controller has no setAnimation(String, ILoopType): " + e);
        }
    }

    // ------------------------------------------------------------------
    // 2. the fix
    // ------------------------------------------------------------------

    private static void checkShippedResolverCarriesTheLoopType() {
        Object loop = EDefaultLoopTypes.LOOP;
        Method resolved;
        try {
            resolved = YsmMemberNames.resolveCallable(Controller.class,
                    YsmMemberNames.Member.CONTROLLER_SET_ANIMATION, "tac:hold:rpg", loop);
        } catch (Throwable t) {
            fail("YsmMemberNames.resolveCallable threw " + t
                    + " (it is expected to run outside Forge: an unknown YSM build is a valid state)");
            return;
        }
        if (resolved == null) {
            fail("resolveCallable returned null for (String, EDefaultLoopTypes) on "
                    + Controller.class.getName() + " - the loop type would be dropped again");
            return;
        }
        ok("resolveCallable -> " + resolved.getDeclaringClass().getSimpleName() + '.'
                + resolved.getName() + signature(resolved));

        Controller controller = new Controller();
        try {
            resolved.invoke(controller, "tac:hold:rpg", loop);
        } catch (Throwable t) {
            fail("invoking the resolved method threw " + t);
            return;
        }
        if (controller.arity != 2) {
            fail("the controller saw a " + controller.arity + "-argument call; the loop type did NOT travel");
        } else if (controller.loop != loop) {
            fail("the controller received " + controller.loop + " instead of the requested loop type");
        } else {
            ok("the controller received setAnimation(\"tac:hold:rpg\", LOOP) - arity 2, loop type " + controller.loop);
        }
    }

    // ------------------------------------------------------------------
    // 3. generic erasure
    // ------------------------------------------------------------------

    private static void checkGenericErasure() {
        // SyncedDataKey is <E extends Entity, T> T getValue(E); the erased parameter is the
        // BOUND (Entity), so getValue(Player) does not exist in the class file. The real
        // framework type is checked by javap in the driver script; this mirrors the shape.
        try {
            Key.class.getMethod("getValue", Player.class);
            fail("Key#getValue(Player) unexpectedly exists - the erasure premise is wrong");
        } catch (NoSuchMethodException expected) {
            ok("Key#getValue(Player) does not exist: the erased signature is getValue(Entity), "
                    + "which is why 'SCG2 synced key AIMING is unavailable' was logged");
        }

        Key<Player, Boolean> key = new Key<>();
        Method resolved = MemberLookup.find(key.getClass(), "getValue", new Player());
        if (resolved == null) {
            fail("MemberLookup.find did not match getValue(Entity) for a Player argument");
            return;
        }
        ok("MemberLookup.find -> " + signature(resolved));
        try {
            @SuppressWarnings("unchecked")
            Object value = resolved.invoke(key, new Player());
            ok("invoking it with a Player returns " + value + " (the real accessor reads the synced value)");
        } catch (Throwable t) {
            fail("invoking the resolved accessor threw " + t);
        }
    }

    // ------------------------------------------------------------------
    // 4. naming
    // ------------------------------------------------------------------

    private static void checkPerGunNames() {
        String gun = "scguns:terra_incognita";
        expect("tac:hold:rpg", GunAnimationNames.Action.HOLD.forType("rpg"));
        expect("tac:hold$" + gun, GunAnimationNames.Action.HOLD.forGun(gun));
        expect("tac:aim$" + gun, GunAnimationNames.Action.AIM.forGun(gun));
        expect("tac:hold:fire$" + gun, GunAnimationNames.Action.HOLD_FIRE.forGun(gun));
        expect("tac:hold:rifle", GunAnimationNames.Action.HOLD.forType("rifle"));

        if (GunAnimationNames.Action.HOLD.forGun(gun).equals("tac:hold:rp$" + gun)) {
            fail("forGun still builds the old name (type included, last letter eaten)");
        } else {
            ok("the per-gun form matches YSM's ConditionTAC convention (tac:hold$<gun id>, no type)");
        }
        expect("LOOP", GunAnimationNames.Action.HOLD.loopKind());
        expect("PLAY_ONCE", GunAnimationNames.Action.HOLD_FIRE.loopKind());
    }

    private static void expect(String wanted, String actual) {
        if (wanted.equals(actual)) {
            ok("name: " + actual);
        } else {
            fail("name: expected '" + wanted + "' but got '" + actual + "'");
        }
    }

    // ------------------------------------------------------------------
    // fixtures - the shape YSM and the framework actually have
    // ------------------------------------------------------------------

    /** Stands in for {@code PredicateBasedController}: the same overload pair, YSM's real ILoopType. */
    public static final class Controller {
        int arity;
        Object loop;

        public void setAnimation(String name) {
            this.arity = 1;
            this.loop = null;
        }

        public void setAnimation(String name, ILoopType loopType) {
            this.arity = 2;
            this.loop = loopType;
        }
    }

    /** Stands in for {@code net.minecraft.world.entity.Entity}. */
    public static class Entity {
    }

    /** Stands in for {@code net.minecraft.world.entity.player.Player}. */
    public static final class Player extends Entity {
    }

    /** Stands in for {@code SyncedDataKey<E extends Entity, T>}: erased parameter is the bound. */
    public static final class Key<E extends Entity, T> {
        @SuppressWarnings("unchecked")
        public T getValue(E entity) {
            return (T) Boolean.TRUE;
        }
    }

    // ------------------------------------------------------------------
    // plumbing
    // ------------------------------------------------------------------

    private static String signature(Method method) {
        StringBuilder out = new StringBuilder("(");
        Class<?>[] parameters = method.getParameterTypes();
        for (int i = 0; i < parameters.length; i++) {
            out.append(i == 0 ? "" : ", ").append(parameters[i].getSimpleName());
        }
        return out.append(')').toString();
    }

    private static String whereIs(Class<?> type) {
        try {
            return type.getProtectionDomain().getCodeSource().getLocation().toString();
        } catch (Throwable t) {
            return "unknown";
        }
    }

    private static void ok(String message) {
        checks++;
        System.out.println("  [ok] " + message);
    }

    private static void fail(String message) {
        checks++;
        failures++;
        System.out.println("  [FAIL] " + message);
    }
}
