libs/
=====

Jars here are consumed by `build.gradle` (through `flatDir` or `files(...)`). They are
compile-time only; nothing here is bundled into the mod jar.

Two YSM builds, because this mod targets both
---------------------------------------------

Yes Steve Model ships in two forms that declare the same `modId` and the same version but
name their internals completely differently, so the mod needs one jar per source set.

1. `ysm-2.6.5-forge+mc1.20.1.jar`  ->  the `main` source set
   The readable community fork (OpenYSM). 22.9 MB, plain class names.

   Used by `src/main/java/.../client/mixin/TacCompatForkMixin.java`, which targets
   `com.elfmcys.yesstevemodel.client.compat.gun.tacz.TacCompat`.

   Where to get it: the OpenYSM checkout next to this project,
       ../OpenYSM/build/libs/ysm-2.6.5-forge+mc1.20.1.jar
   built with `gradlew build` there. Note that build does NOT bundle its native libraries
   (they come from the `compileNative` task), so a fork build used in a real instance needs
   that task to have run.

2. `ysm-2.6.5-official-release.jar`  ->  the `legacy` source set
   The official obfuscated release. 63.3 MB, every class in `com.elfmcys.yesstevemodel`
   named like `OOO0O0O0oo0ooooo00oOOOO0`.

   Used by `src/legacy/java/.../client/mixin/TacCompatLegacyMixin.java`. That source set
   exists precisely because a mixin handler's parameters must name the target method's
   parameter types, and the obfuscated `AnimationEvent`/`PlayState` exist only here.

   Where to get it: any instance that runs YSM, e.g.
       %APPDATA%\.minecraft\versions\<instance>\mods\[是，史蒂夫模型] ysm-<version>-release.jar
   or the upstream release download. It bundles its own natives
   (`META-INF/native/ysm-core.dll`, `libysm-core.so`, `libysm-core-android.so`).

   Keep the file name stable: `build.gradle` refers to it by name.

Not here
--------

Scorched Guns 2, Framework and GeckoLib are resolved from Cursemaven by `build.gradle`. To
build offline, drop those jars in and switch those dependency lines from `curse.maven:...`
to `name:version:` coordinates in this directory's `flatDir`.

Verifying the two variants
--------------------------

`tools/verify-mixin-targets.ps1` reads the `@Mixin` target and every `@Inject` method string
out of the SHIPPED mixin classes and checks each against the jar for its build. Pass the
official release explicitly to also check the obfuscated variant:

    powershell -File tools/verify-mixin-targets.ps1 -LegacyYsmJar "C:\...\mods\[是，史蒂夫模型] ysm-2.6.5-forge+mc1.20.1-release.jar"

That check matters more than it looks on the obfuscated build: the method name
`Oo0Oo0o00O00Oo0OOoOOoooo` is reused by EIGHT different members of the target class, so only
the full descriptor selects the right one - and a soft injection that picks the wrong
overload fails silently by design.
