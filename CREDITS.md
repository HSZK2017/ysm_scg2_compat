# Credits and code provenance

Every non-original element in this repository, where it came from, and on what terms. Written to
be checkable rather than reassuring: each entry names the file in *this* project, the upstream it
relates to, and the license that applies.

## Summary

| Upstream | License | What this project takes from it |
|---|---|---|
| [Yes Steve Model / OpenYSM](https://github.com/OpenYSM) | **MIT** | Interface knowledge only. No source copied. |
| [ysm_epicfight_compat](https://github.com/HSZK2017/ysm_epicfight_compat) | **MIT** | One technique ported, credited in-file. |
| Scorched Guns 2 | **GNU GPLv3** | Reflection target only. No source copied. |
| [TACZ](https://github.com/MCModderAnchor/TACZ) | see upstream | Interface shape consulted. No source copied. |
| [Touhou Little Maid](https://github.com/TartaricAcid/TouhouLittleMaid) | **MIT** (code) / **CC BY-NC-SA 4.0** (assets) | Structural reference only. No source copied. |

**No source file in this project is a copy of a file from any of the above.** The audit that
establishes this is in "How the no-copy claim was checked" at the end.

---

## 1. Yes Steve Model (OpenYSM, ModernYSM) — MIT

The mod this project exists to bridge. Its classes are named, read and called, but never copied.

**Used as interface knowledge**, all derived from reading the upstream sources and from
`javap`/`javap -c` disassembly of the shipped jars:

| This project | What it learned from YSM |
|---|---|
| `client/mixin/TacCompatForkMixin.java` | that `TacCompat` has `handleGunHoldAnimState` / `handleGunActionAnimState` with `(ItemStack, AnimationEvent) -> PlayState`, and that these two entry points are where the gun animation decision is made |
| `client/mixin/TacCompatLegacyMixin.java` | the obfuscated equivalents of the same class and methods, and the `tac:*` handler constants used to tell the two same-descriptor methods apart |
| `client/GunAnimationNames.java` | the `tac:hold:` / `tac:aim:` / `tac:run:` / `tac:climb:` / `tac:reload:` / `tac:melee:` naming scheme, and the `$<id>` per-gun override form |
| `client/GunAnimationDecision.java` | the refusal semantics: a non-null `PlayState.STOP` truncates the predicate chain, and `AnimationControllerInstance#setAnimation` clears a controller before finding out the clip is missing. Both are quoted in that file's javadoc because they are the reason the guard exists. |
| `ysm/YsmClasses.java` | that `PlayerCapabilityProvider` declares a `Capability<PlayerCapability>` field — the structural anchor used to find it on any build |
| `ysm/YsmMemberNames.java` | the obfuscated member names, read off `javap` output |
| `SUPPORT-MATRIX.md` | the three-way build split and which marker belongs to which build |

YSM's own `LICENSE` is the MIT License, so this quoting and adaptation is permitted; it is also
credited here because it is the substance of the project.

## 2. ysm_epicfight_compat — MIT, one technique ported

Copyright (c) 2026 HSZK2017 · <https://github.com/HSZK2017/ysm_epicfight_compat>

This is the one place where code was genuinely adapted rather than merely consulted:

* **`src/main/java/com/ysm/scg2/compat/ysm/YsmClasses.java`** — the technique of resolving a
  target mod's classes from the mod loader's scan data (`ModFileScanData`, falling back to the
  JPMS module reader) instead of hardcoding names per build. The file's javadoc carries the
  attribution:

  > Technique ported from `ysm_epicfight_compat`'s `YsmClasses` (MIT), which uses it for the same
  > reason against the same three builds.

* **`src/main/java/com/ysm/scg2/compat/ysm/YsmFork.java`** — the three-branch model
  (`LEGACY_YSM` / `OPEN_YSM` / `MODERN_YSM`), the choice of discriminating markers, and two
  documented traps: that `config.GeneralConfig` is shared by both readable builds and therefore
  cannot mark ModernYSM, and that `ReplacePlayerRenderEvent` is shared so probe order matters.
  Those constraints are restated in this project's own wording, in the class javadoc and in
  `SUPPORT-MATRIX.md`.
* **`tools/verify-mixin-targets.ps1`** — the `-AMSG_MIXIN_SOFT_TARGET_NOT_FOUND=warning` build flag
  and the reasoning for it (a string mixin target cannot be validated by the annotation processor).

Not ported: anything to do with Epic Fight, skinning, shaders, or that project's renderer
replacement. This project has no Epic Fight dependency.

## 3. Scorched Guns 2 — GNU GPLv3

<https://github.com/ribs-scorched-guns> · referenced, not linked, not copied.

This project interacts with SCG2 **only through reflection**, from one file
(`compat/Scg2GunAccess.java`). There are no imports from `top.ribs.scguns.*` anywhere in this
source tree; the two class names it needs are string constants:

```java
private static final String GUN_ITEM_CLASS = "top.ribs.scguns.item.GunItem";
private static final String SYNCED_KEYS_CLASS = "top.ribs.scguns.init.ModSyncedDataKeys";
```

Written that way for a failure-handling reason (see that file's javadoc), and the consequence is a
clean licensing boundary: this project is not a derivative work of SCG2, so it is not placed under
the GPL by the interaction. SCG2 itself remains GPLv3 and is **not** redistributed here — it is a
required, separately installed mod.

What was learned from SCG2's sources, for the record:

* `GunItem extends net.minecraft.world.item.Item` and implements neither TACZ's `IGun` nor any
  TACZ type — the fact the entire project is built on.
* the serialised `GripType` ids (`one_handed`, `two_handed_shotgun`, `mini_gun*`, `bazooka`, …)
  used to pick an animation set, and the `gunItem.getModifiedGun(stack).getGeneral().getGripType(stack)`
  call chain, with the exact path in the doc comment.

## 4. Timeless and Classics Zero (TACZ) — see upstream

Consulted to confirm the interface shape YSM's gun pipeline expects: that "is this a gun" is
`instanceof IGun`, and that the animation set names correspond to `GunTabType` lower-cased. No TACZ
source is copied, and TACZ is not a dependency of this project at build or run time.

## 5. Touhou Little Maid — MIT (code) / CC BY-NC-SA 4.0 (assets)

Structural reference only: how a maid-side TACZ compatibility layer sits next to the mod it
bridges. No source, and no asset of any kind, is copied from TLM. This project ships exactly one
small JSON language file of its own text plus a `pack.mcmeta`; it contains no models, textures,
animations or sounds.

The sibling project `scg2_maid_compat` (GPLv3) was also read as a structural reference for the
"spoof the TACZ shape" idea. No code was copied from it, which is why its GPLv3 does not apply
here — the idea is not copyrightable, the implementation would have been.

## 6. Minecraft Forge — GNU LGPL 2.1

Compiled against (`net.minecraftforge:forge:1.20.1-47.2.0`) and used as the mod loader API. Not
redistributed. Minecraft itself is Mojang/Microsoft property; this project is not affiliated with
or endorsed by either.

---

## How the no-copy claim was checked

The audit is a two-command check, kept here so it can be re-run rather than believed:

```powershell
# 1. no imports from any bridged mod anywhere in src/
Select-String -Path (Get-ChildItem -Recurse -File src -Filter *.java).FullName `
    -Pattern '^\s*import\s+(top\.ribs|com\.tacz|com\.github\.tartaricacid|com\.scg2tlm)\.'
#    -> no output

# 2. every mention of those packages is a javadoc reference or a string literal
Select-String -Path (Get-ChildItem -Recurse -File src -Filter *.java).FullName `
    -Pattern 'top\.ribs|com\.tacz|tartaricacid|scg2tlm'
#    -> 2 string constants in Scg2GunAccess.java (reflection keys)
#    -> 1 string literal in CompatConfig.java (a quoted snippet in a config comment)
#    -> all remaining hits are inside /** ... */ javadoc
```

The single compile-time dependency on another mod is on YSM, and it is `compileOnly`: the readable
build's jar is used to type the `TacCompatForkMixin` handler parameters, and it is not bundled. The
`legacy` source set is likewise `compileOnly` against the official obfuscated release.
