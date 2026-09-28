package com.ysm.scg2.compat.client;

import org.jetbrains.annotations.Nullable;

import java.util.function.Function;

/**
 * Builds the animation names YSM uses for a held gun, and decides whether the model being
 * rendered is able to play them.
 *
 * <h2>No Yes Steve Model types here - on purpose</h2>
 * <p>This class is reached from {@code Diagnostics}, which Forge loads during mod
 * construction, so a YSM type in any signature here is resolved at load time and aborts
 * startup. See {@link YsmBridge} for the full explanation. Everything below is strings;
 * the type-touching steps are delegated to the caller ({@code TacCompatMixin}, exempt
 * because it is gated on YSM being present) or to {@link YsmBridge} (reflection-only).</p>
 *
 * <h2>Naming scheme</h2>
 * <p>The names, prefixes and suffixes are copied from YSM's own TACZ path
 * ({@code TacAnimHandler#handleTaczGunHold} / {@code #handleTaczGunAction} and the
 * prefixes it passes into them), so a model authored for TACZ needs no changes to work
 * with Scorched Guns 2:</p>
 *
 * <pre>
 *   tac:hold:&lt;type&gt;          tac:aim:&lt;type&gt;           tac:run:&lt;type&gt;
 *   tac:climb:&lt;type&gt;         tac:climbing:&lt;type&gt;
 *   tac:hold:fire:&lt;type&gt;     tac:aim:fire:&lt;type&gt;     tac:climbing:fire:&lt;type&gt;
 *   tac:reload:&lt;type&gt;        tac:melee:&lt;type&gt;
 *   &lt;action prefix without its colon&gt;$&lt;namespace&gt;:&lt;path&gt;    e.g. tac:hold$scguns:musket
 * </pre>
 *
 * <p>{@code <type>} is one of {@code pistol} / {@code rifle} / {@code rpg}, exactly the
 * lower-cased {@code GunTabType} names YSM compares against. The per-gun form carries
 * <b>no</b> type: YSM hands {@code ConditionTAC} the action prefix alone, so the name it
 * builds is {@code "tac:hold:"} minus its colon plus {@code $<gun id>}. The model packs
 * agree - the per-gun clips they ship are {@code tac:hold$tacz:minigun},
 * {@code tac:aim:fire$tacz:minigun} and so on.</p>
 */
public final class GunAnimationNames {

    /**
     * One gun action: its animation-name prefix and how the clip should loop.
     *
     * <p>The loop style is a plain string rather than YSM's {@code ILoopType} for the
     * load-time reason above. {@link YsmBridge#loopType(String)} resolves it at the point
     * of use.</p>
     */
    public enum Action {
        HOLD("tac:hold:", "LOOP"),
        AIM("tac:aim:", "LOOP"),
        RUN("tac:run:", "LOOP"),
        CLIMB("tac:climb:", "LOOP"),
        CLIMBING("tac:climbing:", "LOOP"),
        HOLD_FIRE("tac:hold:fire:", "PLAY_ONCE"),
        AIM_FIRE("tac:aim:fire:", "PLAY_ONCE"),
        CLIMBING_FIRE("tac:climbing:fire:", "PLAY_ONCE"),
        RELOAD("tac:reload:", "PLAY_ONCE"),
        MELEE("tac:melee:", "PLAY_ONCE");

        private final String prefix;
        private final String loopKind;

        Action(String prefix, String loopKind) {
            this.prefix = prefix;
            this.loopKind = loopKind;
        }

        public String prefix() {
            return this.prefix;
        }

        /**
         * The name of the {@code ILoopType.EDefaultLoopTypes} constant to use.
         *
         * <p>Spelled as a string so this class carries no YSM type; resolved through
         * {@link YsmBridge#loopType(String)} where it is actually needed.</p>
         */
        public String loopKind() {
            return this.loopKind;
        }

        /** {@code tac:hold:} + {@code rifle} to {@code tac:hold:rifle}. */
        public String forType(String gunType) {
            return this.prefix + gunType;
        }

        /**
         * {@code tac:hold:} plus {@code scguns:musket} to {@code tac:hold$scguns:musket}.
         *
         * <p>Mirrors YSM's own {@code ConditionTAC#doTest}, which is handed the action
         * <em>prefix</em> ({@code "tac:hold:"}), strips its last character and appends
         * {@code $<gun id>} - so the type is not part of the per-gun name. This method used to
         * append the id to the resolved generic name instead, producing
         * {@code tac:hold:rp$scguns:terra_incognita}: a name no model can define, which made
         * the per-gun override option inert rather than wrong-looking.</p>
         *
         * <p>The convention is YSM's, so it is otherwise unreachable for SCG2 weapons only
         * because YSM resolves the gun id through TACZ.</p>
         */
        public String forGun(String gunId) {
            return this.prefix.substring(0, this.prefix.length() - 1) + '$' + gunId;
        }
    }

    private GunAnimationNames() {
    }

    /**
     * Resolves the animation to play, in the same order YSM's TACZ path would.
     *
     * <p>Takes the "does this name exist" test as a function rather than an
     * {@code AnimationEvent}, which is what keeps YSM types out of this class. The caller
     * passes the same lookup the play call will perform, so the two cannot disagree.</p>
     *
     * @param existsInModel the model probe, e.g. {@code name -> getAnimation(name) != null}
     * @param action        which pose is being asked for
     * @param gunType       {@code pistol} / {@code rifle} / {@code rpg}
     * @param gunId         the weapon's registry id, or {@code null} to skip the per-gun form
     * @param perGunAllowed whether the {@code $<id>} form may be used
     * @return the first name that exists in the model, or {@code null} when none does
     */
    @Nullable
    public static String resolve(Function<String, Boolean> existsInModel,
                                 Action action,
                                 String gunType,
                                 @Nullable String gunId,
                                 boolean perGunAllowed) {
        if (perGunAllowed && gunId != null) {
            String perGun = action.forGun(gunId);
            if (Boolean.TRUE.equals(existsInModel.apply(perGun))) {
                return perGun;
            }
        }
        String generic = action.forType(gunType);
        return Boolean.TRUE.equals(existsInModel.apply(generic)) ? generic : null;
    }

    /**
     * Does the model define this action for this gun type?
     *
     * <p>Separate from {@link #resolve} so a caller can test an action <em>before</em>
     * deciding to prefer it over something else, and get the same answer the play call
     * would. A {@code false} here is the signal to decline and let YSM's own predicate
     * chain continue - see the class doc of {@code TacCompatMixin} for why declining
     * matters more than matching.</p>
     */
    public static boolean exists(Function<String, Boolean> existsInModel, Action action, String gunType) {
        return Boolean.TRUE.equals(existsInModel.apply(action.forType(gunType)));
    }
}
