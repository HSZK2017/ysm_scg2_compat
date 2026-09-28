# KNOWN-LIMITS.md

What is verified, what is not, and why. Required reading before treating any of this as
"done".

## STATUS: works in game on ModernYSM; 0.1.1 fixes the released RPG pose, pending confirmation

The instance runs `openysm-forge-2.6.6.6.jar` (classified `MODERN_YSM`). The 0.1.0 launch log
and probe file show the chain working end to end for that build: the mixin applied, the
injected handler was reached, and the hold decision matched the model's own clip
(`decideHold MATCHED tac:hold:rifle for scguns:astella`, `... tac:hold:rpg for
scguns:terra_incognita`).

What 0.1.0 got wrong is the last step of that chain - the animation was handed to the
controller **without the loop type** - and the visible symptom was reported by the player for
the bazooka-class weapon: the model raises the weapon, then puts it down and keeps it down.
Root cause and evidence: section 0.1.1 below. That is fixed in 0.1.1; whether the fixed build
holds the pose is the one thing still waiting on a launch (see NOT verified).

The two problems that produced 1.0.0 and 1.0.1 are recorded below too, because together they
explain why this mod now looks up class names, ships two mixin variants, and has a verifier
that reads its own compiled annotations.

---

## 0.1.1: the pose was released after one play-through (RPG weapons)

**Symptom (player report).** With `scguns:terra_incognita` in hand - a four-barrel rocket
launcher, grip type `scguns:bazooka`, animation set `rpg` - the model raises the weapon and
then lowers it, permanently. Rifle and pistol weapons look correct.

**Root cause: the requested loop type never reached the controller.** Three separate
reflective misses, each of which returned a bare `null` that was indistinguishable from "the
model cannot do this":

| # | Call | Declared in the target | Asked for | Effect |
|---|---|---|---|---|
| 1 | `PredicateBasedController#setAnimation(String, ILoopType)` | parameter type is the **interface** `ILoopType` | `loopType.getClass()` = `EDefaultLoopTypes` | the loop type was dropped; the clip's own `loop` decided |
| 2 | `SyncedDataKey<E extends Entity, T>#getValue(E)` | erases to `(Entity)Object` | `(Player)Object` | every SCG2 weapon state read as "off": no firing, reload or melee animation |
| 3 | `getCapability(Capability, Direction)` | belongs to the **provider** (`Entity` via `CapabilityProvider`) | called on the `Capability` token, as `(Object, Object)` | the capability was always `null`, so the model report never ran |

`Class#getMethod` matches parameter types **exactly**, so a value whose class implements the
declared parameter type - or whose generic argument is narrower than the erasure - misses.
Case 1 is the reported bug; cases 2 and 3 are the same mistake in the same file family, found
while proving case 1.

**Why only the RPG animation set showed it.** YSM passes
`ILoopType.EDefaultLoopTypes.LOOP` explicitly for the hold/aim/run/climb family
(`TacAnimHandler#playGunAnimation`), and the model packs' `tac:hold:rifle` / `tac:hold:pistol`
clips *also* declare `"loop": true`, so dropping the loop type changed nothing for them.
Every `tac:hold:rpg` and `tac:aim:rpg` in the packs checked (`builtin/wine_fox/*`,
`builtin/misc/*`, `builtin/default`) declares **no** `loop` field, and
`ILoopType.fromJson(null)` answers `PLAY_ONCE`. With `PLAY_ONCE`, YSM's
`AnimationControllerInstance#process` hits

```java
if (animationState == RUNNING && currentAnimationLoop == PLAY_ONCE && adjustedTick >= currentAnimation.animationLength) {
    startEndingTransition(tick);   // 3 ticks of blend back to rest
}
```

0.375 s (the clip's `animation_length`) after the weapon came up. The controller then goes
`IDLE` with `lastRequestedAnimation` still populated, and every later identical request
short-circuits on it (`setAnimation` returns early when the name *and* loop type match), so
the pose never returns. Raising and lowering the weapon once is exactly that sequence.

**Fix.** `MemberLookup` matches parameters by assignability (`isInstance`), preferring exact
types; `YsmMemberNames.resolveCallable` uses it before giving up, and reports a widened match
once, in the probe file. `YsmBridge.playAnimation` now resolves
`setAnimation(String, ILoopType)` correctly and **warns** if a requested loop type cannot be
delivered, so this can never degrade silently again. `Scg2GunAccess` resolves `getValue` by
assignability at first read, and `YsmBridge.getCapability` calls `getCapability` on the
player with assignability matching.

**Also corrected while proving the above** (not the reported symptom, but the same class of
error):

* `GunAnimationNames.Action#forGun` built `tac:hold:rp$scguns:terra_incognita` by appending
  the gun id to the *resolved* name. YSM's `ConditionTAC#doTest` is handed the action
  *prefix* and appends the id to that, and the model packs agree: the names they ship are
  `tac:hold$tacz:minigun`, `tac:aim:fire$tacz:minigun`, … - no type. The option
  `animation.use_per_gun_animation_override` was therefore inert. It now builds
  `tac:hold$scguns:<path>`.

---

## 3. Which YSM build matters, and why 1.0.1 did nothing

YSM is distributed in two forms that both declare `modId = "yes_steve_model"` and the same
version, so neither Forge's mod list nor a version range can tell them apart:

| | official release | community fork (this workspace) |
|---|---|---|
| class names | obfuscated: `com.elfmcys.yesstevemodel.OOO0O0O0oo0ooooo00oOOOO0` | readable: `client.compat.gun.tacz.TacCompat` |
| obfuscated `PlayState` / `AnimationEvent` | `O0oOo0OoO0O0o0000o0O00o0` / `OO00E0o0OooOOOo00OO00o00` | `geckolib3.core.enums.PlayState` / `...event.predicate.AnimationEvent` |
| native core | bundled (`META-INF/native/ysm-core.dll`, `libysm-core.so`) | **not bundled** - the `compileNative` task output is absent |
| on this machine | `%APPDATA%\.minecraft\...\mods\[是，史蒂夫模型] ysm-2.6.5-forge+mc1.20.1-release.jar` (63,269,843 bytes, sha256 `25B5E902…`) | `mods/OpenYSM/build/libs/ysm-2.6.5-forge+mc1.20.1.jar` (22,912,248 bytes) |

1.0.1 hardcoded the readable column. On the instance's official release every name missed,
the mixin's string target did not exist, and the reflection bridge went quiet - the exact
"mapping table rots silently" failure that `ysm_epicfight_compat` warns about. The instance
jar is byte-identical to the one that project compiles against, which is what settled the
question of which build to support.

### 1.1.0's answer: two source sets, one per namespace

A mixin cannot adapt to obfuscation by name alone, because a **handler's parameters must
match the target method's parameter types** - and those types live in whichever namespace the
class was compiled against. `Object` is rejected by Mixin's type check. So:

| source set | compiles against | mixin | targets |
|---|---|---|---|
| `main` | the readable fork | `TacCompatForkMixin` | `client.compat.gun.tacz.TacCompat#handleGunHoldAnimState` / `#handleGunActionAnimState` |
| `legacy` | the official release | `TacCompatLegacyMixin` | `OOO0O0O0oo0ooooo00oOOOO0#Oo0Oo0o00O00Oo0OOoOOoooo` / `#o0OOooo0o0OO00OoOOOo0o0O` |

`YsmForkMixinPlugin` identifies the build from **YSM's own class list**
(`ModFileScanData`, then the JPMS module reader) - keying on whether a readable
`capability.PlayerCapability` exists at all, never on a count of readable names, because the
obfuscated release still ships readable `mixin.*` classes - and then lists exactly one variant
in `getMixins()`.

The shared decision logic lives in `GunAnimationDecision` and takes the animation event as
`Object`, reaching its members through `YsmBridge`'s name-based reflection, which is
namespace-independent. One copy of the guard, two builds: two copies would drift, and a
drifted guard freezes models on whichever build was edited second.

### How the obfuscated targets were derived

Obfuscation renames members but does not rewrite descriptors, so the chain is followable:

1. Scan the official jar for classes whose constant pool mentions
   `com/tacz/guns/api/item/IGun` - the TACZ API only the bridge touches. Two match.
2. Of those, the `TacCompat` equivalent is the class carrying the whole wrapper API plus the
   `isGun(Player)` overload plus the nine molang lambdas:
   `com.elfmcys.yesstevemodel.OOO0O0O0oo0ooooo00oOOOO0`.
3. The two gun wrappers share one descriptor, so the descriptor cannot separate them. Each was
   matched to its handler by the `tac:*` constants of the handler method it calls:
   `tac:hold:`/`tac:aim:`/`tac:run:`/`tac:climb:` is the hold wrapper;
   `tac:reload:`/`tac:melee:`/`tac:*:fire:` is the action wrapper.

**The trap this avoids:** the obfuscator reuses `Oo0Oo0o00O00Oo0OOoOOoooo` for **eight**
members of that class, most of them unrelated. A bare method name would inject into the wrong
one, and with `require = 0` that failure is silent. Every injection therefore carries the full
descriptor, and `tools/verify-mixin-targets.ps1` checks that descriptor against the real jar -
reporting which of the eight overloads matched.

---

## 1.0.0 did not start

```
java.lang.NoClassDefFoundError: com/elfmcys/yesstevemodel/client/entity/LivingAnimatable
    at java.lang.Class.forName0(Native Method)
    at net.minecraftforge.fml.javafmlmod.AutomaticEventSubscriber.lambda$inject$6(AutomaticEventSubscriber.java:61)
    at net.minecraftforge.fml.javafmlmod.FMLModContainer.constructMod(FMLModContainer.java:92)
```

**Root cause (mine, not Yes Steve Model's).** Forge scans every `@Mod.EventBusSubscriber` class
in a mod during `FMLModContainer#constructMod` and loads it with `Class.forName`. The JVM
verifies a loaded class eagerly, so naming a type in a **member signature, field descriptor or
supertype** forces that type to resolve at load time. `Diagnostics` had `LivingAnimatable` and
`PlayerCapability` in its signatures, and `GunAnimationNames.Action` had `ILoopType` in a field
and an accessor. Both are reachable from the scan, so both blew up before any of this mod's own
checks ran. Note the stack frame: the error surfaces at `Class.forName0`, nowhere near the
declaration at fault.

Fixed by moving all YSM access behind `YsmBridge` (reflection only, no YSM type in any
signature), registering the subscribers by hand after the presence checks, and adding check 0
to the verifier - a build-time test for exactly this. The one class that legitimately names
target types in signatures is `GunAnimationDecision`, which sits in the mixin package so that
"is it inside the mixin surface?" is a package test rather than a judgement call.

---

## Verified (repeatable commands)

| Claim | How it was checked |
|---|---|
| Compiles against Forge 47.2.0 / MC 1.20.1 / official mappings, JDK 17 - **both** source sets | `gradlew build` → `BUILD SUCCESSFUL` |
| Shipped jar contains the mod class, config, both mixins, plugin, refmap | `jar tf build/libs/ysm_scg2_compat-1.1.0.jar` |
| `MixinConfigs:` survives reobfuscation | read `MANIFEST.MF` out of `build/reobfJar/output.jar` |
| No unresolved `${...}` placeholders in `mods.toml` / `pack.mcmeta` | read both out of the built jar |
| **No class outside the mixin surface names a YSM type in a load-time position** (the 1.0.0 crash, as a check) | `tools/verify-mixin-targets.ps1`, check 0 |
| **Each variant's compiled `@Mixin` target and every compiled `@Inject` descriptor exist in the jar for its build** | check 2c: reads the annotations out of the shipped `.class` files with `javap -v` and matches them against the target jar |
| The obfuscated hold/action names are one specific overload out of 8 / 5 respectively | same check; reports the overload count |
| No `@EventBusSubscriber` remains in the jar | `javap -p -v` over every class |
| The mixin config sets `defaultRequire: 0`, names a shipped plugin | the verifier |
| The gate refuses to apply when no mod list is observable | the verifier; loads the shipped plugin with a bare `URLClassLoader` and calls `shouldApplyMixin` |
| The instance's YSM jar is byte-identical to the one `ysm_epicfight_compat` compiles against | `Get-FileHash` on both (sha256 `25B5E902…`) |
| SCG2's gun hierarchy is `GunItem extends net.minecraft.world.item.Item` and not `IGun` | SCG2 source: `item/GunItem.java:42`; whole-tree grep for `com.tacz` / `IGun` → no hits |
| YSM's gun gate is `instanceof IGun`, and its animation command is a non-null `PlayState.STOP` that truncates the predicate chain | fork source: `TacAnimHandler.java:41,98-101`, `MainHandHoldPredicate.java:35-38,56-62` |
| A missing animation name clears and then freezes a controller | fork source: `AnimationControllerInstance.java:94-109`, `applyPendingAnimation()` |
| The mod reaches its own constructor and resolves both target mods | the 1.0.0 launch log line `[ysm_scg2_compat] path: active. Yes Steve Model + Scorched Guns 2 both present` |

### Added for 0.1.1

| Claim | How it was checked |
|---|---|
| An exact-type lookup **cannot** find `setAnimation(String, ILoopType)` from an `EDefaultLoopTypes` value, and the shipped resolver can - with YSM's real `ILoopType` loaded out of `openysm-forge-2.6.6.6.jar` | `powershell -File tools/verify-member-lookup.ps1` → 15/15 checks, run against both the fork jar and the installed ModernYSM jar |
| `SyncedDataKey#getValue` really is compiled as `(Lnet/minecraft/world/entity/Entity;)Ljava/lang/Object;`, i.e. not `(Player)` | `javap -p -s` on `framework-forge-1.20.1-0.8.0.jar`, printed by the same script |
| `net.minecraftforge.common.capabilities.Capability` declares **no** `getCapability` method, so calling it on the token could only ever return `null` | `javap -p` on `forge-1.20.1-47.4.10-universal.jar`; the provider-side method is `CapabilityProvider#getCapability(Capability, Direction)` |
| Every member the diagnostics chain uses exists on the installed build: `isModelActive()`, `getSelectedModelId()`, `getAnimation(String)`, `getModelAssembly()` → `getAnimationBundle()` → `getMainAnimations()` / `getArmAnimations()` | `javap` over `LivingAnimatable`, `CustomPlayerEntity`, `PlayerGeoEntity`, `GeoEntity`, `ModelAssembly`, `PlayerModelBundle` in the installed YSM jar |
| The `tac:hold:rpg` / `tac:aim:rpg` clips in every model pack on this machine declare no `loop`, while `tac:hold:rifle` / `tac:hold:pistol` declare `"loop": true` | raw read of `config/yes_steve_model/builtin/{wine_fox,misc,default}/**/animations/*.json` |
| The per-gun override names model packs actually ship are `tac:hold$tacz:minigun` and friends - action prefix plus `$<gun id>`, no type | same pack read; matches `ConditionTAC#doTest` in the fork source |

## In-game observations (1.1.0, obfuscated official release)

The first real launch of 1.1.0 produced this, reported by the player and confirmed in
`logs/latest.log`:

| | result |
|---|---|
| mod loads, no crash | **yes** - `path: active. Yes Steve Model + Scorched Guns 2 both present.` |
| obfuscated mixin target found and prepared | **yes** - `Preparing ysm_scg2_compat.mixins.json (2)`, no "target was not found" for it |
| first-person: gun hold + firing animation | **works** |
| third-person: gun hold animation | **missing** (shooting still works) |

And one line that should not have been there, two milliseconds after the first:

```
[ysm_scg2_compat] no YSM build found (mod 'yes_steve_model' is not loaded) - the animation bridge is inactive.
[ysm_scg2_compat] diagnostics unavailable on this YSM build
```

### That contradiction was the bug, and it explains both symptoms

Forge loads mods in parallel and fills `ModList` as it goes. During construction,
`ModList.get().isLoaded("yes_steve_model")` therefore answered `false` for a mod that was in
fact installed - while the equally valid check one line earlier had answered `true`. The
consequences were not cosmetic:

1. `YsmFork.info()` **cached** that false negative, so the build stayed `NONE` for the whole
   session.
2. `YsmBridge.checkAvailable()` therefore cached `false`, so `YsmBridge.isAvailable()` was
   permanently false.
3. `GunAnimationDecision.isEnabled()` is
   `ENABLE_GUN_ANIMATION && YsmScg2Compat.isYsmPresent() && YsmBridge.isAvailable()` - so the
   mixin's guard **declined on every frame**, and YSM's own answer for an SCG2 weapon ("no gun
   animation") stood.
4. The whole diagnostic layer was disabled with it, which is why the model report that would
   have shown this never appeared in the log.

The `mixin` was never at fault: **Mixin prepares configs at 18:21:50, before Forge starts
constructing mods**, when its view of the mod list is already complete. The mixin attached
correctly - which is why first person worked. Only the runtime path, which consults
`YsmBridge`, was dead.

One asymmetry is still unexplained and is the next thing to look at: why the first-person arm
renders the gun pose while the third-person body does not, if the guard declined in both.
Candidates, to be separated by the (now working) telemetry: the first-person arm may render
`tac:*` through `fp.arm` controllers rather than through the injected wrappers, or the arm and
main animatables may differ in whether they consult the guard. Do not guess further before the
model report has been read.

### Fixes applied in 1.1.0 (rebuilt)

| Fix | Where |
|---|---|
| A negative identification is no longer cached; it is re-tested until mod loading finishes | `YsmFork` (`loadComplete` gate) |
| Identification and bridge resolution are re-run from `FMLLoadCompleteEvent` | `LoadCompleteHandler` |
| YSM-facing client setup is deferred and each step isolated (`catch (Throwable)`) | `DeferredInit` |
| The selected mixin variant is named in the log, so "never matched" is distinguishable from "matched but declined" | `YsmForkMixinPlugin` |
| The diagnostic layer logs whether it came up, and why not | `DeferredInit` step + `YsmBridge` |

---

## NOT verified

1. **Whether 0.1.1 keeps the RPG pose up.** The 0.1.0 probe file proves the animation was
   requested and that the loop type was dropped; the chain from "PLAY_ONCE clip" to "pose
   released" is read from YSM's own source, not observed. The test is one launch with the
   weapon in hand: the pose must stay up, and the probe file must contain
   `[member] playAnimation carries the loop type: ... setAnimation -> (String, ILoopType)`.
   A `LOOP TYPE DROPPED` line instead means the remaining cause is elsewhere - do not guess
   past it.
2. **Whether the now-live firing, reloading and melee animations look right.** They were dead
   in 0.1.0 because every SCG2 state read as "off" (root cause table, case 2). They are
   enabled by the same fix, so they are new behaviour being seen for the first time: check
   that automatic fire, a magazine reload and a bayonet melee each play their `tac:*` clip and
   that nothing sticks afterwards.
3. **Whether the model report now prints.** The capability accessor was broken (case 3), so
   the report has never run on this instance. Expect the block starting
   `--- YSM model report ---` in `latest.log` within ~20 s of the world loading, with
   `tac:* in MAIN` listing the model's clips.
4. **The first/third-person asymmetry** recorded under 1.1.0 below. In the 0.1.0 session the
   guard was reached from both the render thread and `YSM Worker`, and the player reports the
   third-person pose appearing, so the asymmetry looks closed - but it was never measured
   against the model report, which was itself dead.
5. **Whether Mixin warns about the unselected variant's missing target.** `getMixins()` lists one
   variant per run and `shouldApplyMixin` refuses the other; if a "target was not found" line
   appears for the variant that does not match this build, it is cosmetic.
6. **Animation-state fidelity is approximate.** SCG2's `SHOOTING` flag is a monotonic sync flag
   rather than a per-shot pulse, so `tac:*:fire:*` is requested while it is set rather than once
   per bullet; a single-reload weapon's `reload_loop` phase is collapsed into one `tac:reload:*`
   request.
7. **Molang `tac_*` variables stay inert** for SCG2 weapons, by design (see
   `GunAnimationDecision`). A model that branches its whole controller tree on
   `query.tac_hold_gun` will not animate; one driven by `tac:*` animations will.
8. **Interaction with other mods that patch the same methods** is untested. `Mixin#priority` is
   at the default.
9. **The readable-fork path is untested in game**, because the fork jar in `libs/` has no native
   libraries. A fork build produced with `gradlew compileNative` is what that path expects.
10. **The legacy obfuscated release is contract-verified only.** 0.1.1's three reflection fixes
    are namespace-independent by construction (they match by assignability, not by name), but
    the obfuscated member table has not been exercised against a running client since the fix.
11. **SCG2 minigun-class weapons still share the rifle animation set.** The model packs ship
    dedicated `tac:hold$tacz:minigun` clips, and `scg2_maid_compat` reaches them for Touhou
    Little Maid by reporting the gun id `tacz:minigun`. This mod reports the real SCG2 id, so
    those clips are not selected. Deliberately out of scope here; recorded so the option is not
    mistaken for a bug.
12. **The report's reference-name count uses the arm bundle.** `Diagnostics` asks
    `AnimatableEntity#getAnimation`, which for a player resolves through `PlayerGeoEntity` to
    `getArmAnimations()`. Third-person poses come from the main bundle - which is why the report
    lists both bundles separately and says outright which one decides third person. The count is
    a lower bound; do not read a small number as "the model cannot do it".

Re-run the whole set:

```
gradlew build
powershell -File tools/verify-mixin-targets.ps1 -LegacyYsmJar "<path to the official release jar>"
powershell -File tools/verify-member-lookup.ps1
```

The last one picks up Yes Steve Model, Framework and gson from the installed instance when it
is not given paths, and runs the built jar's own resolver against Yes Steve Model's own
classes in a bare JVM - no Minecraft, no Forge.


---

