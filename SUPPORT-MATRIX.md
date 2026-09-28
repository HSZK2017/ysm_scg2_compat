# YSM branch support matrix

Yes Steve Model ships in three distributions that all declare `modId = "yes_steve_model"` and
similar versions, but name their internals completely differently. This mod identifies which one
is installed and adapts. The fingerprints below follow the reference project
`ysm_epicfight_compat`'s system, and every row is asserted by
`tools/verify-mixin-targets.ps1` against the real jars.

## The three branches

| | LEGACY (official release) | OPEN (community fork) | MODERN (OpenYSM successor) |
|---|---|---|---|
| tested jar | `libs/ysm-2.6.5-official-release.jar` = `[是，史蒂夫模型] ysm-2.6.5-forge+mc1.20.1-release.jar` (63,269,843 B, sha256 `25B5E902…`) | `libs/ysm-2.6.5-forge+mc1.20.1.jar` (22,912,248 B) | `openysm-forge-2.6.6.6.jar` (25,744,108 B) |
| class names | obfuscated | readable | readable |
| member names | **obfuscated** | readable | readable |
| TACZ bridge class | `com.elfmcys.yesstevemodel.OOO0O0O0oo0ooooo00oOOOO0` | `client.compat.gun.tacz.TacCompat` | `client.compat.gun.tacz.TacCompat` |
| capability provider | `com.elfmcys.yesstevemodel.O0OooOo0oOOoOoOoOooO000o` | `capability.PlayerCapabilityProvider` | `forge.capability.PlayerCapabilityProvider` |
| capability field | `Oo0Oo0o00O00Oo0OOoOOoooo` | `PLAYER_CAP` | `PLAYER_CAP` |
| bundled natives | yes | no | yes |
| **identified as** | `LEGACY_YSM` | `OPEN_YSM` | `MODERN_YSM` |
| **mixin variant used** | `TacCompatLegacyMixin` | `TacCompatForkMixin` | `TacCompatForkMixin` |
| **member names resolved** | obfuscated column | readable column | readable column |
| **status** | **works in game** (confirmed on 2.6.5) | verified offline | **works in game** (confirmed on 2.6.6.6) |

## Fingerprints, and why these markers

| Marker | LEGACY | OPEN | MODERN |
|---|---|---|---|
| `forge.capability.PlayerCapabilityProvider` | – | – | **YES** |
| `forge.event.ReplacePlayerRenderForgeHook` | – | – | **YES** |
| `capability.PlayerCapabilityProvider` | – | **YES** | – |
| `client.event.ReplacePlayerRenderEvent` | – | YES | YES |
| every readable marker | – | ✓ | ✓ |

Detection reads **YSM's own class list** (`ModFileScanData`, then the JPMS module reader) and keys
on whether a readable player-capability class exists at all, plus which package holds its provider.
Name probes are only the fallback for a build whose scan data is unavailable.

Two traps, both recorded by the reference project and both avoided here:

1. **A class that both readable builds ship cannot be a ModernYSM marker.** `config.GeneralConfig`
   exists in OpenYSM too, so using it would classify OpenYSM as ModernYSM and hand it a capability
   provider path it does not have.
2. **`ReplacePlayerRenderEvent` is shared** by OpenYSM and ModernYSM, so it can only serve as an
   OpenYSM probe *after* the ModernYSM markers have been ruled out. Probe order matters.

## What is resolved per branch

| Binding | LEGACY | OPEN / MODERN |
|---|---|---|
| `AnimationEvent#getAnimatable()` | `o0OOooo0o0OO00OoOOOo0o0O` | `getAnimatable` |
| `AnimationEvent#getController()` | `o0OOO0o0o0OOo000oO00o00O` | `getController` |
| `AnimationEvent#getLimbSwingAmount()` | `oOOOo0OOO0ooooo0O00OO0o0` | `getLimbSwingAmount` |
| `AnimatableEntity#getAnimation(String)` | `OOOOo0O0oO0OOo0O0O0Oo0O0` | `getAnimation` |
| `AnimatableEntity#getEntity()` | `OO00OOOOo0Ooo0oo0o0Oo0OO` | `getEntity` |
| `PredicateBasedController#setAnimation(String, ILoopType)` | `Oo0Oo0o00O00Oo0OOoOOoooo` | `setAnimation` |
| capability provider | `O0OooOo0oOOoOoOoOooO000o` | per branch, above |
| capability field | `Oo0Oo0o00O00Oo0OOoOOoooo` | `PLAYER_CAP` |

The obfuscated TACZ wrapper carries **two** methods with the same descriptor
(`(ItemStack, AnimationEvent) -> PlayState`), so descriptor alone cannot separate them. They were
mapped by the `tac:*` constants of the handler each one calls - `tac:hold:`/`tac:aim:`/`tac:run:`/
`tac:climb:` versus `tac:reload:`/`tac:melee:`/`tac:*:fire:` - and the same method name is reused
by eight members of that class, which is why every injection carries the full descriptor.

## Fail-closed behaviour on an unknown build

If a future distribution matches none of the above:

* the mixin config's plugin lists **no** variant, so nothing is injected and no Mixin warning is
  emitted for a target that does not exist;
* `YsmBridge` cannot resolve a capability token, logs one warning, and the diagnostics stay off;
* `YsmMemberNames` logs one line naming the member and both candidate names it tried;
* the game starts, and SCG2 weapons keep YSM's generic item-holding pose.

Nothing in that path throws, and `tools/verify-mixin-targets.ps1` turns a re-obfuscation into a red
build rather than an in-game mystery.

## Re-verifying a branch

```
# official release only
powershell -File tools/verify-mixin-targets.ps1 -YsmJar "<release jar>"

# a readable build (the release jar is still checked as the legacy side)
powershell -File tools/verify-mixin-targets.ps1 `
    -YsmJar "<openysm or modern jar>" `
    -LegacyYsmJar "<release jar>"
```

The script classifies whichever jar it is handed, then runs only the checks that apply to it.

## 2.6.2 is a different legacy build

`ysm-2.6.2-forge+mc1.20.1-release.jar` is also obfuscated, but it shares **none** of 2.6.5's
obfuscated names: not the TACZ wrapper class, not the event class, not a single member this mod
resolves. It is therefore not covered, and the failure mode is the intended one - the plugin lists
no variant, the bridge logs what it could not resolve, and the game runs with SCG2 weapons on YSM's
generic pose.

The verifier reports that case as an explicit classification (`fork fingerprint … -> LEGACY_YSM`)
followed by the TACZ wrapper being absent, rather than as a silent pass. If 2.6.2 support is ever
wanted, its own name table has to be derived the same way 2.6.5's was - from the class list and the
`tac:*` handler constants - not assumed.