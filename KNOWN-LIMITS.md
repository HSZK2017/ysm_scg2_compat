# KNOWN-LIMITS.md

What is verified, what is not, and why. Required reading before treating any of this as
"done".

## STATUS: both YSM builds targeted, neither verified in game

1.1.0 supports the readable fork **and** the obfuscated official release - the build the
instance actually has. Both paths are offline-verified down to the descriptors compiled into
the shipped classes. Neither has been observed producing a gun pose in a running client.

The two problems that produced 1.0.0 and 1.0.1 are recorded below, because together they
explain why this mod now looks up class names, ships two mixin variants, and has a verifier
that reads its own compiled annotations.

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

1. **Whether the rebuilt 1.1.0 produces a third-person gun pose.** The fix above removed a
   proven blocker; it is not proof that the pose now plays. The model report is the test:
   `hold=tac:hold:rifle` (not `-`) means the guard passed, and the pose either plays or the
   remaining cause is elsewhere.
2. **The first/third-person asymmetry** described above. Unexplained until the report is read.
3. **Whether Mixin warns about the unselected variant's missing target.** `getMixins()` lists one
   variant per run and `shouldApplyMixin` refuses the other; if a "target was not found" line
   appears for the variant that does not match this build, it is cosmetic.
4. **Animation-state fidelity is approximate.** SCG2's `SHOOTING` flag is a monotonic sync flag
   rather than a per-shot pulse, so `tac:*:fire:*` is requested while it is set rather than once
   per bullet; a single-reload weapon's `reload_loop` phase is collapsed into one `tac:reload:*`
   request.
5. **Molang `tac_*` variables stay inert** for SCG2 weapons, by design (see
   `GunAnimationDecision`). A model that branches its whole controller tree on
   `query.tac_hold_gun` will not animate; one driven by `tac:*` animations will.
6. **Interaction with other mods that patch the same methods** is untested. `Mixin#priority` is
   at the default.
7. **The readable-fork path is untested in game**, because the fork jar in `libs/` has no native
   libraries. A fork build produced with `gradlew compileNative` is what that path expects.
8. **The report's `tac:*` count uses the arm bundle.** `Diagnostics` asks
   `AnimatableEntity#getAnimation`, which for a player resolves through `PlayerGeoEntity` to
   `getArmAnimations()`. Third-person poses come from the main bundle. The count is therefore a
   lower bound and is not the number that decides third person - do not read a small count as
   "the model cannot do it".

Re-run the whole set:

```
gradlew build
powershell -File tools/verify-mixin-targets.ps1 -LegacyYsmJar "<path to the official release jar>"
```


---

