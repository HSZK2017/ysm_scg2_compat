# YSM x Scorched Guns 2 Compat

> **v0.1.0 — preview.** Confirmed working in game on the obfuscated official Yes Steve Model
> release with Scorched Guns 2. The other two YSM builds are verified offline; see
> [SUPPORT-MATRIX.md](SUPPORT-MATRIX.md) for exactly what that does and does not cover, and
> [KNOWN-LIMITS.md](KNOWN-LIMITS.md) for the full honesty ledger.

A small **client-side** Forge mod for Minecraft 1.20.1 that makes **Yes Steve Model** play a
model's built-in `tac:*` gun animations while the player holds a **Scorched Guns 2** weapon.

It exists because the two mods look at each other through TACZ, and neither of them is TACZ.

* License: [MIT](LICENSE)
* Provenance of every borrowed idea: [CREDITS.md](CREDITS.md)
* Supported YSM builds: [SUPPORT-MATRIX.md](SUPPORT-MATRIX.md)

---

## The problem in one paragraph

Yes Steve Model decides "is the item in this entity's hand a gun?" with a single test, inside
`com.elfmcys.yesstevemodel.client.compat.gun.tacz.TacAnimHandler`:

```java
public static boolean isTaczGunItem(ItemStack stack) {
    return stack.getItem() instanceof IGun;   // com.tacz.guns.api.item.IGun
}
```

Scorched Guns 2's `top.ribs.scguns.item.GunItem` extends `net.minecraft.world.item.Item`. It is
**not** an `IGun`, and SCG2 has no TACZ dependency anywhere — not in its sources, not in its
`mods.toml`. So for an SCG2 weapon YSM answers "no gun" at the very first question, and:

* the model never enters its `tac:hold:*` / `tac:aim:*` poses and settles on the generic
  `hold_mainhand:*` **item-holding pose**;
* the molang variables `tac_hold_gun`, `tac_gun_type`, `tac_is_fire`, … stay `false` / `""`;
* SCG2's own third-person presentation (its `ItemInHandLayer` mixin and its
  `PlayerModel#setupAnim` arm pose) never runs either, because YSM cancels
  `RenderPlayerEvent.Pre` and draws the player itself.

---

## What this mod does

It answers YSM's two gun questions for SCG2 weapons — the same "spoof the TACZ shape" idea the
sibling mod `scg2_maid_compat` uses for Touhou Little Maid — by patching YSM's own compat entry
points:

| Patched | Effect |
|---|---|
| `TacCompat#handleGunHoldAnimState` | the `hold_mainhand` controller gets `tac:hold:<type>` (or `tac:aim:` / `tac:run:` / `tac:climb:*`) |
| `TacCompat#handleGunActionAnimState` | the `fire` controller gets `tac:reload:<type>`, `tac:melee:<type>` or `tac:*:fire:<type>` |

`<type>` is `pistol` / `rifle` / `rpg`, chosen from the weapon's **grip type** first (pistols and
stocks decide the pose), with miniguns mapped to `rifle` and bazookas to `rpg`. Models can override
per weapon with `tac:hold$scguns:musket` (YSM's own convention: the action prefix without its
colon, then `$<gun id>`).

Each request carries the loop type YSM's own TACZ path uses for that action - `LOOP` for the
hold/aim/run/climb family, `PLAY_ONCE` for firing, reloading and melee. That is not cosmetic:
`tac:hold:rpg` in the model packs declares no `loop` of its own, so an animation request that
loses the loop type plays once, runs its ending transition, and puts the weapon down for good.
See [KNOWN-LIMITS.md](KNOWN-LIMITS.md) section 0.1.1.

### The guard is the actual feature

The obvious one-line fix — spoof `isTaczGunItem` to return `true` for SCG2 items — **breaks the
model**. In YSM's `AnimationControllerInstance#setAnimation`:

```java
clearAnimation();                                        // drops currentAnimation
this.lastRequestedAnimation = new Pair<>(loopType, animationName);
Animation animation = this.animatable.getAnimation(animationName);
if (animation == null) {
    return;                                              // ...and sets no pending animation
}
```

With no pending animation, `applyPendingAnimation()` returns `false`, the controller sits in `IDLE`
with a populated `lastRequestedAnimation` (so it short-circuits on every later tick) and produces
**no bone transforms at all**. If the model does not contain the requested `tac:*` clip, the whole
model goes stiff — which is what "the skin disappeared" looks like.

So this mod only reports a match when the model **actually contains** the animation it is about to
request, checked through the same `AnimatableEntity#getAnimation` lookup the play call performs.
When nothing resolves, it declines and YSM's own logic runs unchanged. Declining is always safe,
because YSM's own answer for an SCG2 weapon is already "no animation".

### What is deliberately not done

`tac_hold_gun` and friends stay `false` for SCG2 weapons. Those molang variables are bound in YSM's
`TacBinding` against TACZ's live API (aim progress, fire mode, reload state, attachment data).
Faking them would mean inventing a second truth about state SCG2 computes differently. Models
driven by `tac:*` **animations** work; models that branch their whole controller tree on
`query.tac_hold_gun` do not. That boundary is documented rather than papered over.

---

## Install

1. Minecraft 1.20.1 + Forge 47.x
2. [Scorched Guns 2](https://www.curseforge.com/minecraft/mc-mods/scorched-guns-2) (hard
   dependency) and its own requirements (Framework, GeckoLib, Curios)
3. Yes Steve Model — any of the three builds, see [SUPPORT-MATRIX.md](SUPPORT-MATRIX.md)
4. this jar

---

## Supported Yes Steve Model builds

YSM ships in three distributions that all declare `modId = "yes_steve_model"`, so this mod
identifies which one is installed and adapts. Details and the full fingerprint table:
[SUPPORT-MATRIX.md](SUPPORT-MATRIX.md).

| Build | Identified as | Mixin used | Status |
|---|---|---|---|
| official obfuscated release | `LEGACY_YSM` | `TacCompatLegacyMixin` | **works in game** |
| community fork (OpenYSM) | `OPEN_YSM` | `TacCompatForkMixin` | verified offline |
| OpenYSM's successor (ModernYSM) | `MODERN_YSM` | `TacCompatForkMixin` | verified offline |

Unknown layout fails closed: no mixin is listed, the bridge logs one warning naming what it could
not resolve, and the game starts with SCG2 weapons on YSM's generic pose. Nothing throws.

---

## Configuration

`config/ysm_scg2_compat-common.toml`

| Key | Default | Meaning |
|---|---|---|
| `animation.enable_gun_animation` | `true` | master switch for the translation |
| `animation.use_per_gun_animation_override` | `true` | allow `tac:hold:rifle$scguns:musket` |
| `animation.log_model_tac_animations` | `true` | print the model report below |
| `animation.log_animation_decisions` | `false` | per-frame decision log (verbose) |
| `diagnostics.log_render_probe` | `false` | render-side measurement |

### The model report

On every resource reload and periodically afterwards, this mod prints what the local player's model
can actually do:

```
[ysm_scg2_compat] --- YSM model report -------------------------------------
[ysm_scg2_compat] model id         : wine_fox
[ysm_scg2_compat] held item        : scguns:gale (SCG2 weapon: true, animation set: rifle)
[ysm_scg2_compat] tac:* in ARM     : [...]
[ysm_scg2_compat] tac:* in MAIN    : [...]   <-- THIS is what decides the third-person body pose
[ysm_scg2_compat] reference names  : N of 30 resolve through getAnimation() (arm bundle)
```

If it reports no `tac:*` clips in the **main** bundle, the model pack cannot do third-person gun
poses — that is a model-pack matter, not a mod bug.

### Probes

The mod keeps a separate probe file, `ysm_scg2_compat-probe.log` next to `logs/`, written directly
rather than through a logger. That is not a debugging leftover: a mixin config plugin runs before
Forge configures mod logging, so anything it logs through the usual channels vanishes, and "the
line was dropped" then looks identical to "the code never ran". The file is truncated once per
launch and can be disabled with `-Dysm_scg2_compat.probe=off`.

---

## Building

```
gradlew build
powershell -File tools/verify-mixin-targets.ps1 -YsmJar "<a YSM jar>" [-LegacyYsmJar "<release jar>"]
powershell -File tools/verify-member-lookup.ps1 [-YsmJar "<a YSM jar>"]
```

Requires JDK 17. See [libs/README-libs.txt](libs/README-libs.txt) for which YSM jars go in `libs/`
and where to get them.

`verify-mixin-targets.ps1` is not a smoke test. It classifies whichever jar it is handed, then
checks the shipped `@Mixin` targets, the compiled `@Inject` descriptors, the per-build member names
and the fork fingerprints against that jar, and finally loads the shipped plugin with a bare
`URLClassLoader` to prove the gate closes cleanly. Several of this project's real bugs were found by
it rather than by a launch.

`verify-member-lookup.ps1` verifies the other half - the reflection that actually hands an animation
to Yes Steve Model. It compiles `tools/harness/MemberLookupHarness.java` against the built jar and
Yes Steve Model's own jar and runs it in a bare JVM: no Minecraft, no Forge. It proves, with Yes
Steve Model's real `ILoopType` in hand, that an exact-type lookup cannot find
`setAnimation(String, ILoopType)` while the shipped resolver can, that the loop type reaches the
controller, and that the same rule repairs the `getValue(E extends Entity)` erasure. Both of those
misses were silent before they were diagnosed, so they are now checks rather than stories.

**A successful compile is not verification.** See [KNOWN-LIMITS.md](KNOWN-LIMITS.md).

---

## Credits

This project contains **no source code copied from the mods it bridges**. Every borrowed idea, the
file it landed in, and the license that applies is listed in [CREDITS.md](CREDITS.md). In short:

* **Yes Steve Model / OpenYSM / ModernYSM** (MIT) — interface knowledge read from source and
  bytecode; no source copied.
* **ysm_epicfight_compat** (MIT, © 2026 HSZK2017) — one technique ported, credited in-file:
  resolving a target mod's classes from the mod loader's scan data instead of hardcoding names,
  plus the three-branch model and its two documented fingerprinting traps.
* **Scorched Guns 2** (GPLv3) — reflection target only. Its two class names appear as string
  constants, never as imports, so this project is not a derivative of it.
* **TACZ** — interface shape consulted. **Touhou Little Maid** (MIT / CC BY-NC-SA) and
  **scg2_maid_compat** (GPLv3) — structural reference only.

---

## Layout

```
src/main/java/com/ysm/scg2/compat/
  YsmScg2Compat.java                  mod entry point, startup verdict
  CompatConfig.java                   Forge COMMON config
  ModPresence.java                    loading-phase-safe "is mod X present"
  DeferredInit.java                   isolated, deferred client setup
  LoadCompleteHandler.java            load-complete + tick + render fallbacks
  ProbeLog.java                       the logger-independent probe channel
  YsmForkMixinPlugin.java             picks the mixin variant per YSM build
  compat/Scg2GunAccess.java           the ONLY place SCG2 is touched (reflection)
  client/YsmBridge.java               the ONLY place YSM types are touched (reflection)
  client/GunAnimationNames.java       tac:* name construction
  client/GunAnimationDecision.java    the guard: match only what the model really has
  client/Diagnostics.java             the model report
  client/RenderProbe.java             render-side measurement
  ysm/YsmFork.java                    three-branch identification
  ysm/YsmClasses.java                 class discovery from loader scan data (MIT port)
  ysm/YsmMemberNames.java             per-build member names
  ysm/MemberLookup.java               resolves a member by assignability, not by class identity
  client/mixin/TacCompatForkMixin.java    readable builds
  client/mixin/TacCompatLegacyMixin.java  obfuscated release (separate source set)

src/legacy/java/...                   the obfuscated variant's source set
tools/verify-mixin-targets.ps1        contract verification against real jars
tools/verify-member-lookup.ps1        reflection verification against real jars
tools/harness/MemberLookupHarness.java  the checks the script runs
```
