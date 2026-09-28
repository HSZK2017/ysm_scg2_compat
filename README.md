# YSM x Scorched Guns 2 Compat

A small **client-side** Forge mod for Minecraft 1.20.1 that makes **Yes Steve Model
(OpenYSM)** play a model's built-in `tac:*` gun animations while the player holds a
**Scorched Guns 2** weapon.

It exists because the two mods look at each other through TACZ, and neither of them is
TACZ.

---

## The problem in one paragraph

Yes Steve Model decides "is the item in this entity's hand a gun?" with a single test,
inside `com.elfmcys.yesstevemodel.client.compat.gun.tacz.TacAnimHandler`:

```java
public static boolean isTaczGunItem(ItemStack stack) {
    return stack.getItem() instanceof IGun;   // com.tacz.guns.api.item.IGun
}
```

Scorched Guns 2's `top.ribs.scguns.item.GunItem` extends `net.minecraft.world.item.Item`.
It is **not** an `IGun`, and SCG2 has no TACZ dependency anywhere — not in its sources,
not in its `mods.toml`. So for an SCG2 weapon YSM answers "no gun" at the very first
question, and:

* the model never enters its `tac:hold:*` / `tac:aim:*` poses and settles on the generic
  `hold_mainhand:*` **item-holding pose**;
* the molang variables `tac_hold_gun`, `tac_gun_type`, `tac_is_fire`, … stay
  `false` / `""`;
* SCG2's own third-person presentation (its `ItemInHandLayer` mixin and its
  `PlayerModel#setupAnim` arm pose) never runs either, because YSM cancels
  `RenderPlayerEvent.Pre` and draws the player itself.

---

## What this mod does

It answers YSM's two gun questions for SCG2 weapons — the same "spoof the TACZ shape"
trick `scg2_maid_compat` uses for Touhou Little Maid — by patching YSM's own compat entry
points:

| Patched | Effect |
|---|---|
| `TacCompat#handleGunHoldAnimState` | the `hold_mainhand` controller gets `tac:hold:<type>` (or `tac:aim:` / `tac:run:` / `tac:climb:*`) |
| `TacCompat#handleGunActionAnimState` | the `fire` controller gets `tac:reload:<type>`, `tac:melee:<type>` or `tac:*:fire:<type>` |

`<type>` is `pistol` / `rifle` / `rpg`, chosen from the weapon's **grip type** first
(pistols and stocks decide the pose), with miniguns mapped to `rifle` and bazookas to
`rpg`. Models can override per weapon with `tac:hold:rifle$scguns:musket`.

### The guard is the actual feature

The obvious one-line fix — spoof `isTaczGunItem` to return `true` for SCG2 items — **breaks
the model**. In YSM's `AnimationControllerInstance#setAnimation`:

```java
clearAnimation();                                        // drops currentAnimation
this.lastRequestedAnimation = new Pair<>(loopType, animationName);
Animation animation = this.animatable.getAnimation(animationName);
if (animation == null) {
    return;                                              // ...and sets no pending animation
}
```

With no pending animation, `applyPendingAnimation()` returns `false`, the controller sits
in `IDLE` with a populated `lastRequestedAnimation` (so it short-circuits on every later
tick) and produces **no bone transforms at all**. If the model does not contain the
requested `tac:*` clip, the whole model goes stiff — which is what "the skin disappeared"
looks like.

So this mod only reports a match when the model **actually contains** the animation it is
about to request, checked through the same `AnimatableEntity#getAnimation` lookup the play
call performs. When the answer is no, the injection declines and YSM's original logic runs
unchanged: the weapon keeps the generic item-holding pose it has today, and nothing
regresses. Declining is always safe because YSM's own answer for an SCG2 weapon is already
"no animation".

### What is deliberately *not* done

`tac_hold_gun` and friends stay `false` for SCG2 weapons. Those molang variables are bound
in YSM's `TacBinding` against TACZ's live API (aim progress, fire mode, reload state,
attachment data). Faking them would mean inventing a second truth about state that SCG2
computes differently. Models driven by `tac:*` **animations** work; models that branch
their whole controller tree on `query.tac_hold_gun` do not. That boundary is intentional
and documented rather than papered over.

---

## Install

1. Minecraft 1.20.1 + Forge 47.x
2. [Scorched Guns 2](https://www.curseforge.com/minecraft/mc-mods/scorched-guns-2) (hard
   dependency) and its own requirements (Framework, GeckoLib, Curios)
3. [Yes Steve Model / OpenYSM](https://github.com/OpenYSM) 2.x — client side
4. this jar

A YSM model that actually contains `tac:*` animations. Models without them are detected
and reported (see below) instead of being silently left stiff.

---

## Configuration

`config/ysm_scg2_compat-common.toml`

| Key | Default | Meaning |
|---|---|---|
| `animation.enable_gun_animation` | `true` | master switch for the translation |
| `animation.use_per_gun_animation_override` | `true` | allow `tac:hold:rifle$scguns:musket` |
| `animation.log_model_tac_animations` | `true` | print the model report below on every reload |
| `animation.log_animation_decisions` | `false` | per-frame decision log (verbose) |
| `diagnostics.log_render_probe` | `false` | reserved for the render-side investigation |

### The model report

The single most useful thing in the log. On every resource reload (and once per model
change) this mod prints what the local player's model can actually do:

```
[ysm_scg2_compat] --- YSM model report -------------------------------------
[ysm_scg2_compat] model id            : wine_fox
[ysm_scg2_compat] held item          : scguns:musket (SCG2 weapon: true, animation set: rifle)
[ysm_scg2_compat] tac:* animations    : 8 of 30 reference names present
[ysm_scg2_compat]   present: [tac:hold:rifle, tac:aim:rifle, tac:run:rifle, ...]
[ysm_scg2_compat]   => for this weapon (rifle set) the mixin will request: hold=tac:hold:rifle, aim=tac:aim:rifle, reload=-
[ysm_scg2_compat] structural scan      : 9 tac:* animation key(s): [...]
[ysm_scg2_compat] ---------------------------------------------------------
```

If it says `translation INERT`, the model has no `tac:*` gun animations at all — that is a
model-pack problem, not a bug in this mod.

The mod also logs which of its three presence paths it took
(`path: active` / `path: no-ysm` / `path: no-scguns`), so "YSM not installed" and
"injection did not apply" are distinguishable in a bug report instead of both looking like
silence.

---

## Failure policy

Three independent soft gates; no single missing piece can take the game down:

1. `YsmScg2MixinPlugin` refuses to apply any mixin unless **both** `yes_steve_model` and
   `scguns` are loaded, so a game without YSM never resolves a YSM class at all.
2. The mixin config sets `"defaultRequire": 0` and every injection uses
   `remap = false` with the literal method name from OpenYSM's sources, so a future YSM
   rename degrades to *no gun animations + one Mixin warning* rather than a boot failure.
3. Every SCG2 read goes through `Scg2GunAccess`, which catches `Throwable` — including
   `NoSuchMethodError` and `NoClassDefFoundError`, which are `Error`s, not `Exception`s —
   and falls back to a conservative value (grip type unknown → `rifle` set).

---

## Building

```
./gradlew build
```

Requires JDK 17. `libs/ysm-2.6.5-forge+mc1.20.1.jar` must be present; see
`libs/README-libs.txt`. Scorched Guns 2, Framework and GeckoLib come from Cursemaven.

**A successful compile is not verification.** See `KNOWN-LIMITS.md` for exactly which
claims in this repository are static-analysis-backed and which need a running client.

---

## Layout

```
src/main/java/com/ysm/scg2/compat/
  YsmScg2Compat.java              mod entry point, presence logging
  CompatConfig.java               Forge COMMON config
  ModPresence.java                loading-phase-safe "is mod X present"
  compat/Scg2GunAccess.java       the ONLY place SCG2 is touched (reflection, hard fallbacks)
  client/GunAnimationNames.java   tac:* name construction, model capability probe
  client/Diagnostics.java         the model report
  client/mixin/TacCompatMixin.java        the two injections
  client/mixin/YsmScg2MixinPlugin.java    the presence gate
```
