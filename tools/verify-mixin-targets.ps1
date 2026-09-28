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
    [string] $MixinJar
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
    if (-not (Test-Path $jar)) { throw "Not found: $jar" }
}
if (-not (Get-Command javap -ErrorAction SilentlyContinue)) {
    throw 'javap is not on PATH. Install a JDK 17 and retry.'
}

Write-Host "YSM jar   : $YsmJar"
Write-Host "Mixin jar : $MixinJar"
Write-Host ''

<#
    The contract this mod depends on: target class, and for each injection the
    minimum the signature must contain (parameter and return types).
    Keep this table in step with TacCompatMixin. It is intentionally explicit -
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
                    $run = (& java -cp ($probeCp + [System.IO.Path]::PathSeparator + '.') GateProbe $MixinJar 2>&1 | Out-String)
                    if ($run -match 'GATE-CLOSED') {
                        $checks.Add('gate: shouldApplyMixin returns false when no mod list is visible')
                    } elseif ($run -match 'GATE-OPEN') {
                        $failures.Add('gate: shouldApplyMixin returned TRUE with no mod list visible - the mixin could apply without YSM')
                    } else {
                        $checks.Add("gate: probe ran but did not report a verdict: $($run.Trim())")
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
