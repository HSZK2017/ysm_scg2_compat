<#
.SYNOPSIS
    Offline verification of the YSM x Scorched Guns 2 compat mixin contract.

.DESCRIPTION
    Answers the questions a compile cannot, by inspecting the shipped jars with javap:

      1. Does the mixin config point at classes that exist in the built jar, and is it
         gated (plugin present, defaultRequire 0)?
      2. Do the methods TacCompatMixin injects into still exist, with the exact
         descriptors the mixin declares, in the Yes Steve Model jar that will be loaded
         at runtime?

    (2) is the one that matters. Mixin matches an @Inject target by name + descriptor,
    and `remap = false` on a third-party class means the literal name in the mixin
    source is what gets looked up at runtime. With `defaultRequire: 0` a rename in YSM
    turns the injection into a silent no-op - the guns simply stop animating, with
    nothing in the log to say why. This script converts that into a loud, repeatable
    check.

    It does NOT verify runtime behaviour. See KNOWN-LIMITS.md.

.PARAMETER YsmJar
    Yes Steve Model jar to verify against. Defaults to libs/ysm-2.6.5-forge+mc1.20.1.jar.

.PARAMETER MixinJar
    Built compat jar. Defaults to the newest build/libs/ysm_scg2_compat-*.jar.

.EXAMPLE
    pwsh -File tools/verify-mixin-targets.ps1
#>
[CmdletBinding()]
param(
    [string] $YsmJar,
    [string] $MixinJar,
    [string] $LegacyYsmJar
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot

if (-not $YsmJar) {
    $YsmJar = Join-Path $projectRoot 'libs/ysm-2.6.5-forge+mc1.20.1.jar'
}
if (-not $MixinJar) {
    $candidate = Get-ChildItem (Join-Path $projectRoot 'build/libs') -Filter 'ysm_scg2_compat-*.jar' -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $candidate) {
        throw "No built jar found. Run 'gradlew build' first, or pass -MixinJar."
    }
    $MixinJar = $candidate.FullName
}

foreach ($jar in @($YsmJar, $MixinJar)) {
    # -LiteralPath: mod jar names legitimately contain [ ] (e.g. "[是，史蒂夫模型] ysm-..."),
    # which plain Test-Path reads as a wildcard class and fails to match.
    if (-not (Test-Path -LiteralPath $jar)) { throw "Not found: $jar" }
}
if (-not (Get-Command javap -ErrorAction SilentlyContinue)) {
    throw 'javap is not on PATH. Install a JDK 17 and retry.'
}

Write-Host "YSM jar   : $YsmJar"
Write-Host "Plugin jar: $MixinJar"
Write-Host ''

<#
    The contract this mod depends on: target class, and for each injection the
    minimum the signature must contain (parameter and return types).
    Keep this table in step with TacCompatForkMixin. It is intentionally explicit -
    a copied-out list is what makes a rename loud.
#>
$targetClass = 'com.elfmcys.yesstevemodel.client.compat.gun.tacz.TacCompat'
$expectedInjections = @(
    @{
        Method  = 'handleGunHoldAnimState'
        Params  = @('net.minecraft.world.item.ItemStack',
                    'com.elfmcys.yesstevemodel.geckolib3.core.event.predicate.AnimationEvent')
        Returns = 'com.elfmcys.yesstevemodel.geckolib3.core.enums.PlayState'
    },
    @{
        Method  = 'handleGunActionAnimState'
        Params  = @('net.minecraft.world.item.ItemStack',
                    'com.elfmcys.yesstevemodel.geckolib3.core.event.predicate.AnimationEvent')
        Returns = 'com.elfmcys.yesstevemodel.geckolib3.core.enums.PlayState'
    }
)

<#
    The same contract on the OBFUSCATED official release. This mod cannot attach there yet
    (see KNOWN-LIMITS.md section 4), but the names are recorded and CHECKED so that:

      * the derivation recorded in YsmFork stays honest - if a future release re-obfuscates,
        this check goes red instead of the notes going quietly stale, and
      * when the second source set is built, the strings it will use are already verified.

    The pair is only unambiguous together: both methods carry the SAME descriptor, so the
    method names are the only thing that separates the hold wrapper from the action wrapper.
    They were mapped by attributing each handler's tac:* string constants (tac:hold:/tac:aim:/
    tac:run:/tac:climb: vs tac:reload:/tac:melee:/tac:*:fire:).
#>
$legacyTargetClass = 'com.elfmcys.yesstevemodel.OOO0O0O0oo0ooooo00oOOOO0'
$legacyExpectedInjections = @(
    @{
        Method  = 'Oo0Oo0o00O00Oo0OOoOOoooo'
        Params  = @('net.minecraft.world.item.ItemStack',
                    'com.elfmcys.yesstevemodel.OO00O0o0OooOOOo00OO00o00')
        Returns = 'com.elfmcys.yesstevemodel.O0oOo0OoO0O0o0000o0O00o0'
    },
    @{
        Method  = 'o0OOooo0o0OO00OoOOOo0o0O'
        Params  = @('net.minecraft.world.item.ItemStack',
                    'com.elfmcys.yesstevemodel.OO00O0o0OooOOOo00OO00o00')
        Returns = 'com.elfmcys.yesstevemodel.O0oOo0OoO0O0o0000o0O00o0'
    }
)

$work = Join-Path ([System.IO.Path]::GetTempPath()) ("ysm-verify-" + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $work -Force | Out-Null

$failures = New-Object System.Collections.Generic.List[string]
$checks = New-Object System.Collections.Generic.List[string]

function Expand-JarEntry {
    param([string] $Jar, [string] $Entry, [string] $Destination)
    Push-Location $Destination
    try { & jar xf $Jar $Entry 2>&1 | Out-Null } finally { Pop-Location }
    $path = Join-Path $Destination $Entry
    if (-not (Test-Path $path)) { throw "Entry '$Entry' missing from $Jar" }
    return $path
}

try {
    # ------------------------------------------------------------------
    # 0. No target-mod type may appear where the JVM resolves it at LOAD time.
    #
    #    This is the check that would have caught the 1.0.0 startup crash:
    #
    #        java.lang.NoClassDefFoundError:
    #            com/elfmcys/yesstevemodel/client/entity/LivingAnimatable
    #          at java.lang.Class.forName0(Native Method)
    #          at net.minecraftforge.fml.javafmlmod.AutomaticEventSubscriber...
    #
    #    Forge loads every @Mod.EventBusSubscriber class during mod construction with
    #    Class.forName, and the JVM verifies the loaded class eagerly. A target-mod type
    #    in a member signature, a field descriptor or a supertype is therefore resolved
    #    during startup - before any of this mod's presence checks can run - and an
    #    unresolvable one aborts the game.
    #
    #    Method BODIES are only resolved when they execute, so they are allowed (that is
    #    how YsmBridge works). This check enforces exactly that distinction.
    # ------------------------------------------------------------------
    $forbiddenPackages = @('com.elfmcys.yesstevemodel')
    $mixinClassNames = @()
    $mixinClassNamesFqn = @()
    $mixinConfigEntry = 'ysm_scg2_compat.mixins.json'
    [void](Expand-JarEntry -Jar $MixinJar -Entry $mixinConfigEntry -Destination $work)
    $mixinConfig = Get-Content (Join-Path $work $mixinConfigEntry) -Raw | ConvertFrom-Json
    if ($mixinConfig.client) { $mixinClassNames += $mixinConfig.client }
    if ($mixinConfig.mixins) { $mixinClassNames += $mixinConfig.mixins }
    foreach ($m in $mixinClassNames) { $mixinClassNamesFqn += ,($mixinConfig.package + '.' + $m) }

    # Extract every class in the jar.
    Push-Location $work
    try { & jar xf $MixinJar 2>&1 | Out-Null } finally { Pop-Location }

    $classFiles = Get-ChildItem -Recurse -File $work -Filter '*.class' |
        Where-Object { $_.FullName -notmatch 'gateprobe' }

    $scanned = 0
    $exemptNames = New-Object System.Collections.Generic.List[string]
    foreach ($classFile in $classFiles) {
        $relative = $classFile.FullName.Substring($work.Length + 1)
        $fqn = $relative -replace '\\', '.' -replace '\.class$', ''

        # The MIXIN SURFACE is exempt, and it has to be: a mixin handler's parameters must
        # match the target method's parameter types, so "no target type in a signature"
        # cannot hold there. What must hold instead is that the surface is never loaded
        # without the target present - which is exactly what the config plugin guarantees,
        # and what check 3 below measures by running the plugin.
        #
        # The rule is package-wide on purpose, so a helper added next to a mixin is covered
        # automatically and "is this class in the surface?" never becomes a judgement call.
        # The 1.0.0 crash was a class naming YSM types (Diagnostics) sitting OUTSIDE this
        # surface while still being loaded by Forge's subscriber scan.
        # Exempt ONLY the classes the config actually declares. An earlier version exempted the
        # whole mixin package, which silently tolerated a shared helper living there - and Mixin
        # then refused to let outside code reference it:
        #   IllegalClassLoadError: ... is in a defined mixin package ... and cannot be referenced
        #   directly
        # That error only appears at runtime, in whichever caller touches the helper first. Naming
        # the classes here makes the same mistake a build-time failure.
        if ($mixinClassNamesFqn -contains $fqn) {
            $exemptNames.Add($fqn)
            continue
        }
        if ($fqn.StartsWith($mixinConfig.package + '.')) {
            $failures.Add("$fqn lives in the mixin package ($($mixinConfig.package)) but is not a declared mixin. Mixin forbids referencing it from outside that package (IllegalClassLoadError). Move it elsewhere.")
            continue
        }

        $scanned++
        Push-Location $work
        try {
            $signatures = (& javap -p -s -classpath $work $fqn 2>&1) -join "`n"
        } finally { Pop-Location }

        # javap -p -s prints the declaration, then a "descriptor:" line. Restrict the
        # search to descriptor lines plus method/field declaration lines, so comments in
        # the constant pool cannot produce a false positive.
        $loadTimeLines = @()
        foreach ($line in ($signatures -split "`r?`n")) {
            if ($line -match '^\s*descriptor:\s' -or
                $line -match '^\s{2,}(public|private|protected|static|final|abstract|class|interface|extends|implements)\b') {
                $loadTimeLines += $line
            }
        }
        $signatureText = $loadTimeLines -join "`n"

        foreach ($package in $forbiddenPackages) {
            if ($signatureText -match [regex]::Escape($package)) {
                $offending = ($loadTimeLines | Where-Object { $_ -match [regex]::Escape($package) }) -join ' | '
                $failures.Add("$fqn references $package in a LOAD-TIME position (signature/field/supertype): $offending")
            }
        }
    }
    if ($classFiles.Count -gt 0) {
        $checks.Add("load-time reference check: $scanned class(es) scanned outside the mixin surface; $($exemptNames.Count) exempt as part of it (target types belong there)")
    }

    # ------------------------------------------------------------------
    # 1. Mixin config: gate present, soft injections, classes shipped.
    # ------------------------------------------------------------------
    $configEntry = 'ysm_scg2_compat.mixins.json'
    [void](Expand-JarEntry -Jar $MixinJar -Entry $configEntry -Destination $work)
    $config = Get-Content (Join-Path $work $configEntry) -Raw | ConvertFrom-Json

    if (-not $config.plugin) {
        $failures.Add('mixin config has no "plugin" - nothing gates it on YSM being installed')
    } else {
        $pluginEntry = $config.plugin.Replace('.', '/') + '.class'
        try {
            [void](Expand-JarEntry -Jar $MixinJar -Entry $pluginEntry -Destination $work)
            $checks.Add("config plugin shipped: $($config.plugin)")
        } catch {
            $failures.Add("config plugin class missing from the jar: $pluginEntry")
        }
    }

    if ($config.injectors.defaultRequire -ne 0) {
        $failures.Add("injectors.defaultRequire is '$($config.injectors.defaultRequire)'; expected 0 so a future YSM rename degrades instead of crashing")
    } else {
        $checks.Add('injectors.defaultRequire: 0 (soft injection)')
    }

    $configuredMixins = @()
    if ($config.client) { $configuredMixins += $config.client }
    if ($config.mixins) { $configuredMixins += $config.mixins }
    if (-not $configuredMixins) {
        $failures.Add('mixin config lists no mixins')
    }
    foreach ($mixinClass in $configuredMixins) {
        $entry = ($config.package + '.' + $mixinClass).Replace('.', '/') + '.class'
        try {
            [void](Expand-JarEntry -Jar $MixinJar -Entry $entry -Destination $work)
            $checks.Add("mixin class shipped: $entry")
        } catch {
            $failures.Add("mixin class missing from the jar: $entry")
        }
    }

    # When -YsmJar IS the obfuscated release there is nothing readable in it to check, so
    # this whole section is skipped for that configuration. Section 2d covers it instead.
    # Classify the main jar BEFORE anything checks it: when the official release is
    # passed as -YsmJar (a valid thing to do - it is YSM), the readable-only checks would
    # otherwise report a wall of false failures.
    $mainNames = (& jar tf $YsmJar 2>$null)
    $hasReadableBridge = $mainNames -contains 'com/elfmcys/yesstevemodel/client/compat/gun/tacz/TacCompat.class'
    $hasObfuscatedBridge = $mainNames -contains 'com/elfmcys/yesstevemodel/OOO0O0O0oo0ooooo00oOOOO0.class'
    $mainIsLegacy = (-not $hasReadableBridge) -and $hasObfuscatedBridge
    $checks.Add("main jar classified as $(if ($mainIsLegacy) { 'LEGACY_YSM (obfuscated)' } else { 'a readable build' })")

    if (-not $mainIsLegacy) {
        # ------------------------------------------------------------------
        # 2. Target class and every injected method, against the YSM jar.
        # ------------------------------------------------------------------
        $targetEntry = $targetClass.Replace('.', '/') + '.class'
        try {
            [void](Expand-JarEntry -Jar $YsmJar -Entry $targetEntry -Destination $work)
        } catch {
            $failures.Add("mixin target class is missing from $([System.IO.Path]::GetFileName($YsmJar)): $targetEntry")
        }
    
        $javapText = ''
        if (Test-Path (Join-Path $work $targetEntry)) {
            Push-Location $work
            try {
                $javapText = (& javap -p -s -classpath $work $targetClass 2>&1) -join "`n"
            } finally { Pop-Location }
        }
    
        foreach ($injection in $expectedInjections) {
            $name = $injection.Method
            # Locate the method block: the declaration line, its descriptor line, and the
            # generic signature javap prints underneath it.
            $pattern = '(?s)\b' + [regex]::Escape($name) + '\s*\((.*?)\)\s*;\s*\r?\n\s*descriptor:\s*(\S+)'
            $match = [regex]::Match($javapText, $pattern)
            if (-not $match.Success) {
                $failures.Add("$targetClass#$name : NOT FOUND - the injection would silently not apply")
                continue
            }
    
            $actualParams = $match.Groups[1].Value
            $descriptor = $match.Groups[2].Value
    
            foreach ($param in $injection.Params) {
                if ($actualParams -notlike "*$param*") {
                    $failures.Add("$targetClass#$name : parameter '$param' not present (actual: $actualParams)")
                }
            }
    
            # Return type from the descriptor tail, e.g. (...)...Lcom/.../PlayState;
            $returnDescriptor = ($descriptor -split '\)')[-1]
            $expectedReturn = 'L' + $injection.Returns.Replace('.', '/') + ';'
            if ($returnDescriptor -ne $expectedReturn) {
                $failures.Add("$targetClass#$name : returns $returnDescriptor, expected $expectedReturn")
            } else {
                $checks.Add("$targetClass#$name : signature verified")
            }
        }
    
        # ------------------------------------------------------------------
    }
    # 2b. The same contract on the obfuscated official release, when that jar is available.
    #
    #     This mod does not attach to that build yet (KNOWN-LIMITS.md section 4), so a
    #     missing jar is not a failure - but when the jar IS present the recorded names are
    #     checked, which is what stops the derivation notes from going quietly stale and
    #     what pre-verifies the strings the future second source set will use.
    # ------------------------------------------------------------------
    if (-not $LegacyYsmJar) {
        $candidate = Get-ChildItem (Join-Path $env:APPDATA '.minecraft\versions') -Recurse `
            -Filter '*ysm*release*.jar' -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($candidate) { $LegacyYsmJar = $candidate.FullName }
    }
    if ($LegacyYsmJar -and (Test-Path -LiteralPath $LegacyYsmJar)) {
        Write-Host "Legacy jar: $LegacyYsmJar"
        $legacyEntry = $legacyTargetClass.Replace('.', '/') + '.class'
        try {
            [void](Expand-JarEntry -Jar $LegacyYsmJar -Entry $legacyEntry -Destination $work)
            Push-Location $work
            try {
                $legacyText = (& javap -p -s -classpath $work $legacyTargetClass 2>&1) -join "`n"
            } finally { Pop-Location }

            foreach ($injection in $legacyExpectedInjections) {
                $name = $injection.Method
                $expectedReturn = 'L' + $injection.Returns.Replace('.', '/') + ';'

                # Several overloads share this obfuscated name (the class carries nine of
                # them, most for unrelated members). Test EVERY overload and require one to
                # satisfy the whole contract - parameters and return type. Matching only the
                # first was a false failure against the real jar, which is how this
                # distinction was found: the verifier caught a flaw in itself.
                $candidates = [regex]::Matches($legacyText,
                        '(?s)\b' + [regex]::Escape($name) + '\s*\((.*?)\)\s*;\s*\r?\n\s*descriptor:\s*(\S+)')

                $satisfied = $false
                foreach ($candidate in $candidates) {
                    $actualParams = $candidate.Groups[1].Value
                    $returnDescriptor = ($candidate.Groups[2].Value -split '\)')[-1]
                    if ($returnDescriptor -ne $expectedReturn) { continue }
                    $allParams = $true
                    foreach ($param in $injection.Params) {
                        if ($actualParams -notlike "*$param*") { $allParams = $false; break }
                    }
                    if ($allParams) { $satisfied = $true; break }
                }

                if ($candidates.Count -eq 0) {
                    $failures.Add("$legacyTargetClass#$name : NOT FOUND - the recorded obfuscated name is stale")
                } elseif ($satisfied) {
                    $checks.Add("$legacyTargetClass#$name : one of $($candidates.Count) overload(s) matches the recorded descriptor")
                } else {
                    $failures.Add("$legacyTargetClass#$name : none of the $($candidates.Count) overload(s) match the recorded descriptor")
                }
            }
        } catch {
            $checks.Add('legacy jar present but the recorded obfuscated target is not in it (re-obfuscated release?)')
        }
    } else {
        $checks.Add('legacy jar not found - obfuscated-target check skipped (pass -LegacyYsmJar to enable)')
    }

    # ------------------------------------------------------------------
    # 2c. THE COMPILED DESCRIPTORS, against the jar each variant targets.
    #
    #     Stronger than a hand-maintained table: this reads the @Mixin target and every
    #     @Inject method string out of the SHIPPED mixin class files (javap -v prints
    #     annotations verbatim) and checks each one against the target jar. So it verifies
    #     what was actually compiled, and it cannot drift from the source the way a copied
    #     list can.
    #
    #     This matters most on the obfuscated build, where the whole method string is a
    #     fingerprint: a bare name would match eight different members of that class, and
    #     only the full descriptor picks the right one.
    # ------------------------------------------------------------------
    # Classify the main jar first. When the official release is passed as -YsmJar (a valid thing to
    # do - it is YSM), the readable-only checks would otherwise report a pile of false failures.

    # Each variant is checked against the jar for the fork it targets.
    $variantJars = @{ 'TacCompatForkMixin' = $(if ($mainIsLegacy) { $null } else { $YsmJar })
                      'TacCompatLegacyMixin' = $(if ($mainIsLegacy) { $YsmJar } else { $LegacyYsmJar }) }
    foreach ($variant in $variantJars.Keys) {
        $variantFqn = $mixinConfig.package + '.' + $variant
        $variantJar = $variantJars[$variant]
        if (-not $variantJar -or -not (Test-Path -LiteralPath $variantJar)) {
            $checks.Add("$variant : compiled-descriptor check skipped (no jar given for its build)")
            continue
        }

        Push-Location $work
        try { $annotations = (& javap -p -v -classpath $work $variantFqn 2>&1) -join "`n" } finally { Pop-Location }

        # The @Mixin target. javap -v renders the annotation as:
        #   org.spongepowered.asm.mixin.Mixin(
        #       targets=["com.example.Target"]        <- a string target (array form)
        #       remap=false
        #   )
        # or, for a Class literal, as `value=com.example.Target.class,`. Both shapes are
        # matched here; a naive optional-group regex instead captures the literal word
        # "targets", which is how this was found.
        $declaredTarget = $null
        $m = [regex]::Match($annotations, 'Mixin\(\s*targets\s*=\s*\[?"([A-Za-z0-9_.$]+)"')
        if ($m.Success) {
            $declaredTarget = $m.Groups[1].Value
        } else {
            $m = [regex]::Match($annotations, 'Mixin\(\s*value\s*=\s*([A-Za-z0-9_.$]+)\.class')
            if ($m.Success) { $declaredTarget = $m.Groups[1].Value }
        }
        if (-not $declaredTarget) {
            $failures.Add("$variant : could not read its @Mixin target out of the shipped class")
            continue
        }

        $targetEntry = $declaredTarget.Replace('.', '/') + '.class'
        try {
            [void](Expand-JarEntry -Jar $variantJar -Entry $targetEntry -Destination $work)
        } catch {
            $failures.Add("$variant : its @Mixin target $declaredTarget is not in $([System.IO.Path]::GetFileName($variantJar))")
            continue
        }

        Push-Location $work
        try { $targetText = (& javap -p -s -classpath $work $declaredTarget 2>&1) -join "`n" } finally { Pop-Location }

        # Every @Inject method string, as written into the class file.
        $methodStrings = [regex]::Matches($annotations, 'method=\["([^"]+)"\]') |
            ForEach-Object { $_.Groups[1].Value } | Select-Object -Unique
        if ($methodStrings.Count -eq 0) {
            $failures.Add("$variant : no @Inject method string found in the shipped class")
            continue
        }

        foreach ($methodString in $methodStrings) {
            # "name(descriptor)" - the descriptor is everything from the first '('.
            $paren = $methodString.IndexOf('(')
            if ($paren -lt 1) {
                $failures.Add("$variant : malformed @Inject method string '$methodString'")
                continue
            }
            $name = $methodString.Substring(0, $paren)
            $signature = $methodString.Substring($paren)

            # Find the target method whose descriptor matches, tolerating generics which
            # javap renders on the declaration line rather than in the descriptor.
            $pattern = '(?s)\b' + [regex]::Escape($name) + '\s*\(.*?\)\s*;\s*\r?\n\s*descriptor:\s*(\S+)'
            $overloads = [regex]::Matches($targetText, $pattern)
            $matched = $false
            foreach ($overload in $overloads) {
                if ($overload.Groups[1].Value -eq $signature) { $matched = $true; break }
            }

            if ($matched) {
                $checks.Add("$variant -> $declaredTarget#$name : compiled descriptor matches ($($overloads.Count) overload(s) in target)")
            } else {
                $failures.Add("$variant -> $declaredTarget#$name : NO overload matches the compiled descriptor $signature")
            }
        }
    }

    # ------------------------------------------------------------------
    # 2d. The obfuscated MEMBER names this mod calls, against the official release.
    #
    #     Obfuscation renames members too, and that is what broke the mod: the wrapper class and
    #     method were resolved correctly, then `event.getAnimatable()` was called by name - which
    #     does not exist in the release - so every guard decision became "no such animation" and
    #     the third-person gun pose never played. The failure was a silent null.
    #
    #     Each entry below is a name YsmMemberNames will call. Checking them here means a future
    #     re-obfuscation is a red build rather than a mystery in game.
    # ------------------------------------------------------------------
    if ($LegacyYsmJar -and (Test-Path -LiteralPath $LegacyYsmJar)) {
        $memberExpectations = @(
            @{ Class = 'com.elfmcys.yesstevemodel.OO00O0o0OooOOOo00OO00o00'
               Name  = 'o0OOooo0o0OO00OoOOOo0o0O'
               Note  = 'AnimationEvent#getAnimatable()' },
            @{ Class = 'com.elfmcys.yesstevemodel.OO00O0o0OooOOOo00OO00o00'
               Name  = 'o0OOO0o0o0OOo000oO00o00O'
               Note  = 'AnimationEvent#getController()' },
            @{ Class = 'com.elfmcys.yesstevemodel.OO00O0o0OooOOOo00OO00o00'
               Name  = 'oOOOo0OOO0ooooo0O00OO0o0'
               Note  = 'AnimationEvent#getLimbSwingAmount()' },
            @{ Class = 'com.elfmcys.yesstevemodel.o0000OoOooO0oo0o0oooo0Oo'
               Name  = 'OOOOo0O0oO0OOo0O0O0Oo0O0'
               Note  = 'AnimatableEntity#getAnimation(String)' },
            @{ Class = 'com.elfmcys.yesstevemodel.o0000OoOooO0oo0o0oooo0Oo'
               Name  = 'OO00OOOOo0Ooo0oo0o0Oo0OO'
               Note  = 'AnimatableEntity#getEntity()' },
            @{ Class = 'com.elfmcys.yesstevemodel.oo000oooo0OOoo00O0o0OOOO'
               Name  = 'Oo0Oo0o00O00Oo0OOoOOoooo'
               Note  = 'PredicateBasedController#setAnimation(String, ILoopType)' }
        )

        foreach ($expectation in $memberExpectations) {
            $entry = $expectation.Class.Replace('.', '/') + '.class'
            try {
                [void](Expand-JarEntry -Jar $LegacyYsmJar -Entry $entry -Destination $work)
            } catch {
                $failures.Add("member check: class $($expectation.Class) is not in the release jar")
                continue
            }
            Push-Location $work
            try {
                $classText = (& javap -p -classpath $work $expectation.Class 2>&1) -join "`n"
            } finally { Pop-Location }

            if ($classText -match ('\b' + [regex]::Escape($expectation.Name) + '\s*\(')) {
                $checks.Add("member: $($expectation.Note) -> $($expectation.Name) present")
            } else {
                $failures.Add("member: $($expectation.Note) -> '$($expectation.Name)' NOT FOUND in $($expectation.Class); a re-obfuscation has changed it")
            }
        }
    }
    # ------------------------------------------------------------------
    # 2d-bis. The READABLE member names, against the readable-build jar.
    #
    #     The obfuscated names are checked in 2d; the readable ones matter just as much, because
    #     the mod resolves members per build and a readable build that renamed one would break
    #     exactly the same way - silently, as a null animation.
    # ------------------------------------------------------------------
    if ((Test-Path -LiteralPath $YsmJar) -and -not $mainIsLegacy) {
        $readableMembers = @(
            @{ Class = 'com.elfmcys.yesstevemodel.geckolib3.core.event.predicate.AnimationEvent'
               Name  = 'getAnimatable'; Note = 'AnimationEvent#getAnimatable()' },
            @{ Class = 'com.elfmcys.yesstevemodel.geckolib3.core.event.predicate.AnimationEvent'
               Name  = 'getController'; Note = 'AnimationEvent#getController()' },
            @{ Class = 'com.elfmcys.yesstevemodel.geckolib3.core.event.predicate.AnimationEvent'
               Name  = 'getLimbSwingAmount'; Note = 'AnimationEvent#getLimbSwingAmount()' },
            @{ Class = 'com.elfmcys.yesstevemodel.geckolib3.core.AnimatableEntity'
               Name  = 'getAnimation'; Note = 'AnimatableEntity#getAnimation(String)' },
            @{ Class = 'com.elfmcys.yesstevemodel.geckolib3.core.AnimatableEntity'
               Name  = 'getEntity'; Note = 'AnimatableEntity#getEntity()' },
            @{ Class = 'com.elfmcys.yesstevemodel.geckolib3.core.controller.PredicateBasedController'
               Name  = 'setAnimation'; Note = 'PredicateBasedController#setAnimation(String, ILoopType)' }
        )
        foreach ($expectation in $readableMembers) {
            $entry = $expectation.Class.Replace('.', '/') + '.class'
            try {
                [void](Expand-JarEntry -Jar $YsmJar -Entry $entry -Destination $work)
            } catch {
                $failures.Add("readable member check: class $($expectation.Class) is not in $([System.IO.Path]::GetFileName($YsmJar))")
                continue
            }
            Push-Location $work
            try { $classText = (& javap -p -classpath $work $expectation.Class 2>&1) -join "`n" } finally { Pop-Location }
            if ($classText -match ('\b' + [regex]::Escape($expectation.Name) + '\s*\(')) {
                $checks.Add("readable member: $($expectation.Note) present")
            } else {
                $failures.Add("readable member: $($expectation.Note) -> '$($expectation.Name)' NOT FOUND in $($expectation.Class)")
            }
        }
    }
    # ------------------------------------------------------------------
    # 2e. The fork fingerprint: which YSM build is this jar, and does the mod's per-build
    #     capability provider path exist in it?
    #
    #     YsmFork keys on the same markers the reference project `ysm_epicfight_compat` uses,
    #     and the markers are asserted here rather than trusted:
    #       ModernYSM  - forge subpackage provider, or the forge-only render hook
    #       OpenYSM    - capability provider under `capability`
    #       LEGACY     - neither readable marker exists (everything is obfuscated)
    #
    #     The trap this guards against is the one the reference documents: a class BOTH readable
    #     builds ship (e.g. config.GeneralConfig) cannot be a ModernYSM marker, or OpenYSM gets
    #     classified as ModernYSM and handed a provider path it does not have.
    # ------------------------------------------------------------------
    $forkMarkers = @{
        ModernProvider = 'com/elfmcys/yesstevemodel/forge/capability/PlayerCapabilityProvider.class'
        ModernRenderHook = 'com/elfmcys/yesstevemodel/forge/event/ReplacePlayerRenderForgeHook.class'
        OpenProvider   = 'com/elfmcys/yesstevemodel/capability/PlayerCapabilityProvider.class'
    }
    $forkJars = @(
        @{ Label = 'fork jar'; Jar = $YsmJar },
        @{ Label = 'legacy jar'; Jar = $LegacyYsmJar }
    )
    foreach ($entry in $forkJars) {
        $jar = $entry.Jar
        if (-not $jar -or -not (Test-Path -LiteralPath $jar)) { continue }

        $names = (& jar tf $jar 2>$null)
        $isModern = ($names -contains $forkMarkers.ModernProvider) -or ($names -contains $forkMarkers.ModernRenderHook)
        $isOpen = $names -contains $forkMarkers.OpenProvider
        $detected = if ($isModern) { 'MODERN_YSM' } elseif ($isOpen) { 'OPEN_YSM' } else { 'LEGACY_YSM' }

        $checks.Add("fork fingerprint ($($entry.Label) $([System.IO.Path]::GetFileName($jar))) -> $detected")

        # The provider the mod will try for that build must actually be in the jar.
        $expectedProvider = switch ($detected) {
            'MODERN_YSM' { $forkMarkers.ModernProvider }
            'OPEN_YSM'   { $forkMarkers.OpenProvider }
            default      { $null }
        }
        if ($expectedProvider -and -not ($names -contains $expectedProvider)) {
            $failures.Add("fork fingerprint: $detected was detected but its provider $expectedProvider is missing from the jar")
        }

        # The TACZ bridge class the paired mixin variant targets must exist too.
        $expectedBridge = if ($detected -eq 'LEGACY_YSM') {
            'com/elfmcys/yesstevemodel/OOO0O0O0oo0ooooo00oOOOO0.class'
        } else {
            'com/elfmcys/yesstevemodel/client/compat/gun/tacz/TacCompat.class'
        }
        if ($names -contains $expectedBridge) {
            $checks.Add("fork fingerprint: $detected ships its TACZ bridge $expectedBridge")
        } else {
            $failures.Add("fork fingerprint: $detected does not ship $expectedBridge - the paired mixin would not attach")
        }
    }
    # ------------------------------------------------------------------
    # 3. The gate must actually close when YSM is absent.
    #     Tested by running shouldApplyMixin from the artefact itself.
    # ------------------------------------------------------------------
    $pluginFqn = $config.plugin
    if ($pluginFqn -and (Test-Path (Join-Path $work ($pluginFqn.Replace('.', '/') + '.class')))) {
        $script = @"
import java.net.URLClassLoader;
public class GateProbe {
    public static void main(String[] args) throws Exception {
        try (URLClassLoader cl = new URLClassLoader(new java.net.URL[]{new java.io.File(args[0]).toURI().toURL()},
                GateProbe.class.getClassLoader())) {
            Class<?> c = Class.forName("$pluginFqn", true, cl);
            Object plugin = c.getDeclaredConstructor().newInstance();
            c.getMethod("onLoad", String.class).invoke(plugin, "com.ysm.scg2.compat.client.mixin");
            boolean apply = (Boolean) c.getMethod("shouldApplyMixin", String.class, String.class)
                    .invoke(plugin, "$targetClass", "TacCompatMixin");
            System.out.println("shouldApplyMixin(YSM absent) = " + apply);
            if (apply) {
                System.out.println("GATE-OPEN");
            } else {
                System.out.println("GATE-CLOSED");
            }
        }
    }
}
"@
        $probeDir = Join-Path $work 'gateprobe'
        New-Item -ItemType Directory -Path $probeDir -Force | Out-Null
        # ASCII (not UTF8) so no byte-order mark is written: javac rejects a BOM.
        [System.IO.File]::WriteAllText((Join-Path $probeDir 'GateProbe.java'), $script, [System.Text.Encoding]::ASCII)

        # The plugin implements IMixinConfigPlugin, so the probe needs Mixin (and its ASM
        # dependencies) on the classpath. Find them in the Gradle cache rather than
        # requiring the caller to set anything up; if they are absent the probe is skipped
        # rather than failed, since it is a bonus check on top of the signature checks.
        $mixinJars = @()
        $gradleCache = Join-Path $env:USERPROFILE '.gradle/caches/modules-2/files-2.1'
        foreach ($pattern in @('org.spongepowered/mixin/*/*/*.jar', 'org.ow2.asm/asm/*/*/*.jar', 'org.ow2.asm/asm-tree/*/*/*.jar', 'org.ow2.asm/asm-commons/*/*/*.jar', 'org.ow2.asm/asm-analysis/*/*/*.jar', 'org.ow2.asm/asm-util/*/*/*.jar')) {
            $found = Get-ChildItem (Join-Path $gradleCache $pattern) -ErrorAction SilentlyContinue |
                Where-Object { $_.Name -notmatch 'sources|javadoc' } |
                Sort-Object LastWriteTime -Descending
            if ($found) { $mixinJars += $found[0].FullName }
        }

        if ($mixinJars.Count -eq 0) {
            $checks.Add('gate: skipped - Mixin not found in the Gradle cache (signature checks still stand)')
        } else {
            $probeCp = ($mixinJars -join [System.IO.Path]::PathSeparator)

            Push-Location $probeDir
            # Native tools write progress/errors to stderr; with ErrorActionPreference Stop
            # PowerShell turns the first stderr line into a terminating error before the exit
            # code can be examined.
            $previousPreference = $ErrorActionPreference
            $ErrorActionPreference = 'Continue'
            try {
                $compile = (& javac -cp $probeCp -d . GateProbe.java 2>&1 | Out-String)
                if ($LASTEXITCODE -ne 0) {
                    $failures.Add("gate probe did not compile: $($compile.Trim())")
                } else {
                    # No Forge on the classpath on purpose: this simulates the phase in which
                    # ModPresence cannot see a mod list at all, which is when the gate must
                    # still answer "do not apply".
                    #
                    # It also enforces the rule that a mixin config plugin must be SELF-CONTAINED.
                    # Mixin calls shouldApplyMixin before Forge has wired its services, so an
                    # exception there does not degrade gracefully - it takes the injection with
                    # it, silently. A version of this plugin logged through the mod's main class,
                    # which dragged in Forge's config classes and threw
                    # NoClassDefFoundError: net/minecraftforge/fml/config/IConfigSpec from inside
                    # the apply decision. That is why the verdict must be a clean GATE-CLOSED
                    # here, and why any exception is a hard failure rather than a skip.
                    $run = (& java -cp ($probeCp + [System.IO.Path]::PathSeparator + '.') GateProbe $MixinJar 2>&1 | Out-String)
                    if ($run -match 'GATE-CLOSED') {
                        $checks.Add('gate: shouldApplyMixin returns false when no mod list is visible, without throwing')
                    } elseif ($run -match 'GATE-OPEN') {
                        $failures.Add('gate: shouldApplyMixin returned TRUE with no mod list visible - the mixin could apply without YSM')
                    } else {
                        $failures.Add("gate: shouldApplyMixin did not answer cleanly (this is what silently kills the injection): $($run.Trim())")
                    }
                }
            } finally {
                $ErrorActionPreference = $previousPreference
                Pop-Location
            }
        }
    }
} finally {
    Remove-Item -Recurse -Force $work -ErrorAction SilentlyContinue
}

Write-Host 'Checks:'
foreach ($check in $checks) { Write-Host "  [ok] $check" }

if ($failures.Count -gt 0) {
    Write-Host ''
    Write-Host 'FAILURES:'
    foreach ($failure in $failures) { Write-Host "  [!!] $failure" }
    exit 1
}

Write-Host ''
Write-Host 'All mixin contract checks passed.'
exit 0
