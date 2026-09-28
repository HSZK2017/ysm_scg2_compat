package com.ysm.scg2.compat.client;

import com.elfmcys.yesstevemodel.geckolib3.core.AnimatableEntity;
import com.elfmcys.yesstevemodel.geckolib3.core.event.predicate.AnimationEvent;
import com.elfmcys.yesstevemodel.geckolib3.core.builder.ILoopType;
import com.elfmcys.yesstevemodel.geckolib3.core.enums.PlayState;
import org.jetbrains.annotations.Nullable;
/**
 * Builds the animation names YSM uses for a held gun, and decides whether the model
 * being rendered is actually able to play them.
 *
 * <h2>Naming scheme</h2>
 * <p>The names, prefixes and suffixes are copied from YSM's own TACZ path
 * ({@code TacAnimHandler#handleTaczGunHold} / {@code #handleTaczGunAction} and the
 * {@code tac:hold:} / {@code tac:aim:} / {@code tac:run:} / {@code tac:reload:} /
 * {@code tac:melee:} prefixes it passes into them), so a model authored for TACZ needs no
 * changes to work with Scorched Guns 2:</p>
 *
 * <pre>
 *   tac:hold:&lt;type&gt;          tac:aim:&lt;type&gt;           tac:run:&lt;type&gt;
 *   tac:climb:&lt;type&gt;         tac:climbing:&lt;type&gt;
 *   tac:hold:fire:&lt;type&gt;     tac:aim:fire:&lt;type&gt;     tac:climbing:fire:&lt;type&gt;
 *   tac:reload:&lt;type&gt;        tac:melee:&lt;type&gt;
 *   &lt;prefix&gt;$&lt;namespace&gt;:&lt;path&gt;        e.g. tac:hold:rifle$scguns:musket
 * </pre>
 *
 * <p>{@code <type>} is one of {@code pistol} / {@code rifle} / {@code rpg}, exactly the
 * lower-cased {@code GunTabType} names YSM compares against.</p>
 */
public final class GunAnimationNames {

    public enum Action {
        HOLD("tac:hold:", ILoopType.EDefaultLoopTypes.LOOP),
        AIM("tac:aim:", ILoopType.EDefaultLoopTypes.LOOP),
        RUN("tac:run:", ILoopType.EDefaultLoopTypes.LOOP),
        CLIMB("tac:climb:", ILoopType.EDefaultLoopTypes.LOOP),
        CLIMBING("tac:climbing:", ILoopType.EDefaultLoopTypes.LOOP),
        HOLD_FIRE("tac:hold:fire:", ILoopType.EDefaultLoopTypes.PLAY_ONCE),
        AIM_FIRE("tac:aim:fire:", ILoopType.EDefaultLoopTypes.PLAY_ONCE),
        CLIMBING_FIRE("tac:climbing:fire:", ILoopType.EDefaultLoopTypes.PLAY_ONCE),
        RELOAD("tac:reload:", ILoopType.EDefaultLoopTypes.PLAY_ONCE),
        MELEE("tac:melee:", ILoopType.EDefaultLoopTypes.PLAY_ONCE);

        private final String prefix;
        private final ILoopType loopType;

        Action(String prefix, ILoopType loopType) {
            this.prefix = prefix;
            this.loopType = loopType;
        }

        public String prefix() {
            return this.prefix;
        }

        public ILoopType loopType() {
            return this.loopType;
        }

        /** {@code tac:hold:} + {@code rifle} to {@code tac:hold:rifle}. */
        public String forType(String gunType) {
            return this.prefix + gunType;
        }

        /**
         * {@code tac:hold:rifle} plus {@code scguns:musket} to
         * {@code tac:hold:rifle$scguns:musket}.
         *
         * <p>This mirrors YSM's own {@code ConditionTAC} vocabulary, which is otherwise
         * unreachable for SCG2 weapons because it resolves the gun id through TACZ.</p>
         */
        public String forGun(String gunType, String gunId) {
            String generic = forType(gunType);
            return generic.substring(0, generic.length() - 1) + '$' + gunId;
        }
    }

    private GunAnimationNames() {
    }

    /**
     * Resolves the animation to play, in the same order YSM's TACZ path would.
     *
     * @param event         the animation event, used only to inspect the model
     * @param action        which pose is being asked for
     * @param gunType       {@code pistol} / {@code rifle} / {@code rpg}
     * @param gunId         the weapon's registry id, or {@code null} to skip the per-gun form
     * @param perGunAllowed whether the {@code $<id>} form may be used
     * @return the first name that exists in the model, or {@code null} when none does
     */
    @Nullable
    public static String resolve(AnimationEvent<?> event,
                                 Action action,
                                 String gunType,
                                 @Nullable String gunId,
                                 boolean perGunAllowed) {
        AnimatableEntity<?> animatable = event.getAnimatable();
        if (animatable == null) {
            return null;
        }

        if (perGunAllowed && gunId != null) {
            String perGun = action.forGun(gunType, gunId);
            if (animatable.getAnimation(perGun) != null) {
                return perGun;
            }
        }

        String generic = action.forType(gunType);
        return animatable.getAnimation(generic) != null ? generic : null;
    }

    /**
     * Does the model being animated define this action for this gun type?
     *
     * <p>Separate from {@link #resolve} so a caller can test an action <em>before</em>
     * deciding to prefer it over something else, and get the same answer the play call
     * would. A {@code false} here is the signal to decline and let YSM's own predicate
     * chain continue - see the class doc of {@code TacCompatMixin} for why declining
     * matters more than matching.</p>
     */
    public static boolean exists(AnimationEvent<?> event, Action action, String gunType) {
        AnimatableEntity<?> animatable = event.getAnimatable();
        return animatable != null && animatable.getAnimation(action.forType(gunType)) != null;
    }

    /**
     * Plays {@code name} on the event's controller.
     *
     * <p>A thin wrapper so the mixin stays a decision and this stays the mechanism; it is
     * also the one place where the {@code PlayState} contract ("CONTINUE" = this predicate
     * owns the frame) is stated.</p>
     */
    public static PlayState play(AnimationEvent<?> event, String name, ILoopType loopType) {
        event.getController().setAnimation(name, loopType);
        return PlayState.CONTINUE;
    }
}
