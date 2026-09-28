libs/
=====

Jars in this directory are consumed through the `flatDir` repository declared in
`build.gradle`. They are compile-time only; nothing here is bundled into the mod jar.

Required
--------

  ysm-2.6.5-forge+mc1.20.1.jar
      Yes Steve Model (OpenYSM) 2.6.5 for Minecraft 1.20.1 / Forge.

      Needed at COMPILE time because the client mixins target
      `com.elfmcys.yesstevemodel.client.compat.gun.tacz.TacCompat`.
      OPTIONAL at runtime: `mods.toml` declares `yes_steve_model` with
      `mandatory=false`, and `YsmScg2MixinPlugin` refuses to apply anything when the
      mod is absent, so a game without YSM never resolves a YSM class.

      Where to get it: the OpenYSM project checkout next to this one
          ../OpenYSM/build/libs/ysm-2.6.5-forge+mc1.20.1.jar
      (built with `gradlew build` in that project), or the upstream release jar.

Not required here
-----------------

  Scorched Guns 2, Framework and GeckoLib are resolved from Cursemaven by
  `build.gradle`. If you need to build offline, drop the jars in this directory under
  the names used in the `flatDir(...)` dependency lines and switch those lines from
  `curse.maven:...` to `name:version:` coordinates.
