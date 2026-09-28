# KNOWN-LIMITS.md

What is verified, what is not, and why. Required reading before treating any of this as
"done".

The short version: **everything here is compile-verified plus jar-verified. Nothing is
runtime-verified.** No Minecraft client was launched while building this mod — the
workspace has no game instance, and starting one was out of scope.

---

## Verified (repeatable commands)

| Claim | How it was checked |
|---|---|
| Compiles against Forge 47.2.0 / MC 1.20.1 / official mappings, JDK 17 | `gradlew build` → `BUILD SUCCESSFUL` |
| Shipped jar contains the mod class, config, mixin config, plugin, refmap | `jar tf build/libs/ysm_scg2_compat-1.0.0.jar` |
| `MixinConfigs: ysm_scg2_compat.mixins.json` survives reobfuscation | read `MANIFEST.MF` out of `build/reobfJar/output.jar` |
| No unresolved `${...}` placeholders in the shipped `mods.toml` / `pack.mcmeta` | read both out of the built jar |
| The two injection targets exist in the shipped OpenYSM jar **with the declared signatures** | `tools/verify-mixin-targets.ps1` (uses `javap -p -s` on `TacCompat` from `libs/ysm-2.6.5-forge+mc1.20.1.jar`) |
| The mixin config sets `defaultRequire: 0` and names a shipped plugin | same script |
| The gate refuses to apply when no mod list is observable | same script; loads the shipped plugin with a bare `URLClassLoader` and calls `shouldApplyMixin` |
| OpenYSM's `TacCompat` methods are genuinely named `handleGunHoldAnimState` / `handleGunActionAnimState` (not SRG-renamed) in the shipped jar, so `remap = false` is correct | `javap -p` on `TacCompat.class` extracted from the jar |
| SCG2's gun hierarchy is `GunItem extends net.minecraft.world.item.Item` and not `IGun` | SCG2 source: `item/GunItem.java:42`; whole-tree grep for `com.tacz` / `IGun` → no hits |
| YSM's gun gate is `instanceof IGun`, and its animation command is a non-null `PlayState.STOP` that truncates the predicate chain | YSM source: `TacAnimHandler.java:41,98-101`, `MainHandHoldPredicate.java:35-38,56-62` |
| A missing animation name clears and then freezes a controller (`clearAnimation()` before the null check; `lastRequestedAnimation` already populated) | YSM source: `AnimationControllerInstance.java:94-109`, `applyPendingAnimation()` |
| SCG2's third-person hooks target vanilla `PlayerModel#setupAnim` and vanilla `ItemInHandLayer#renderArmWithItem`, both of which YSM bypasses | SCG2 `scguns.mixins.json`; YSM `ReplacePlayerRenderEvent.java:37-38` |

Re-run the whole set:

```
gradlew build
<JDK17>/bin/pwsh -File tools/verify-mixin-targets.ps1
```

---

## NOT verified

### 1. It has never been run in a game

The two injections have been proven to *match*; they have not been observed to *work*. In
particular, nothing confirms that:

* `handleGunHoldAnimState` / `handleGunActionAnimState` are reached at all for a player
  wearing a YSM model (they are reached for maids via
  `MaidAnimationController`, and `PlayerAnimationController.java:49` registers
  `hold_mainhand` with `MainHandHoldPredicate`, but "the code path exists" and "the code
  path runs" are different claims);
* the chosen animation names are the ones real model packs use;
* the per-gun form `tac:hold:rifle$scguns:musket` is what model authors write.

**First thing to do in game:** load a world with a YSM model and an SCG2 weapon, then read
the `[ysm_scg2_compat] --- YSM model report ---` block. It prints, per model, exactly which
`tac:*` names exist and which ones the mixin will request. That single block converts every
item in this list from "unverified" to "known".

### 2. The weapon-model probe is a measurement, not a fix

The original symptom had two halves: the pose/animation half (addressed here) and "the gun
mesh does not appear in third person". **Only the first half is fixed.**

The evidence gathered about the second half:

* SCG2 registers a `BlockEntityWithoutLevelRenderer` for every gun
  (`GunItem.java:242-249` → `GunItemStackRenderer`, or `AnimatedGunItem` →
  `AnimatedGunRenderer`), so the mesh *should* arrive through
  `ItemRenderer#render` → `renderByItem`.
* `GunItemStackRenderer#renderByItem` opens with `poseStack.popPose()`, commented as a hack
  to remove the transforms `ItemRenderer#render` applies. When YSM supplies the pose stack
  instead of vanilla's `ItemInHandRenderer`, that pop removes YSM's hand-bone matrix — the
  prime suspect for a weapon drawn at the entity origin instead of in the hand.
* Forge 1.20.1's `ItemRenderer#render` wraps the `renderByItem` call in **no** try/catch
  (verified from bytecode: `getCustomRenderer` at 451-455, `renderByItem` at 470,
  `popPose` at 475, `return` at 478, no exception table), so an exception there would
  surface as a crash rather than as silence.

`RenderProbe` (`diagnostics.log_render_probe`) logs the renderer class and the pose-stack
translation/scale for the player's held SCG2 weapon, which is what distinguishes
"not drawn" from "drawn in the wrong place". **Do not change the pose stack before reading
that output** — the fix differs depending on which of the two it is, and guessing would
trade one visual bug for another.

### 3. Molang `tac_*` variables stay inert for SCG2 weapons

By design. `tac_hold_gun`, `tac_gun_type`, `tac_gun_id`, `tac_is_fire`, `tac_is_aim`,
`tac_is_reload`, `tac_is_melee`, `tac_is_draw` and `tac_fire_mode` are bound in YSM's
`TacBinding` against TACZ's live API. Faking them means inventing aim progress, fire modes,
reload state and attachment data for a mod that computes all four differently.

Consequence, stated plainly: **a model that drives its gun poses purely with `tac:*`
animations will work. A model that branches its whole controller tree on
`query.tac_hold_gun` will not.** There is no partial credit to be had here; the boundary is
where the data model stops agreeing.

### 4. Animation-state fidelity is approximate

The action state comes from SCG2's synced flags (`AIMING`, `SHOOTING`, `RELOADING`,
`MELEE`), read reflectively. Two known approximations:

* SCG2's `SHOOTING` is a monotonic sync flag rather than a per-shot pulse, so
  `tac:*:fire:*` is requested while it is set rather than once per bullet.
* A single-reload weapon's `reload_loop` phase is collapsed into one `tac:reload:*`
  request, because this mod does not model SCG2's `reload_start` / `reload_loop` /
  `reload_stop` sub-states.

If the fire animation looks "stuck on" rather than per-shot, this is why — and the fix is
to read SCG2's shoot cooldown, not to change the animation names.

### 5. Interaction with other animation-overriding mods is untested

If another mod also patches `TacCompat`'s methods, Mixin injection order decides who
reports first. `Mixin#priority` is left at the default here. A conflict would present as
"the other mod's animation wins", not as an error.

### 6. Only OpenYSM 2.6.5 is checked

`tools/verify-mixin-targets.ps1` is pinned to the jar in `libs/`. Running it against a
different YSM version is the intended way to check compatibility, and it is a one-argument
change:

```
pwsh -File tools/verify-mixin-targets.ps1 -YsmJar path/to/other/ysm.jar
```

---

## What would close the gap

1. Launch a client with SCG2 + OpenYSM + this mod and a model known to contain `tac:*`
   animations.
2. Read the model report; confirm `<=> for this weapon ... hold=tac:hold:rifle` is not `-`.
3. Hold an SCG2 rifle: expect the model's gun-hold pose instead of the generic
   item-holding pose, and no stiffness (stiffness would mean the guard failed — report it
   with the `log_animation_decisions` output).
4. Turn on `diagnostics.log_render_probe` and read the pose-stack line to settle the
   weapon-mesh question in §2.
