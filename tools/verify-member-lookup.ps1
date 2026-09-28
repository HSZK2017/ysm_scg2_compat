<#
.SYNOPSIS
    Offline verification of the reflective member lookups this mod performs at runtime.

.DESCRIPTION
    `verify-mixin-targets.ps1` verifies the mixin contract. This verifies the other half - the
    reflection that hands animations to Yes Steve Model - by running the built jar's own
    resolver against Yes Steve Model's own classes in a bare JVM:

      1. The lookup the mod used to perform (an exact-type `Class#getMethod` with the value's
         runtime class) cannot find `PredicateBasedController#setAnimation(String, ILoopType)`
         when the value held is an `ILoopType.EDefaultLoopTypes` constant. That silent miss is
         why the requested loop type was dropped, and why `tac:hold:rpg` - a clip that declares
         no `loop` of its own - played once and then released the pose.
      2. The shipped `YsmMemberNames.resolveCallable` does find it, and the loop type reaches
         the controller.
      3. The same rule repairs the generic-erasure miss that made every Scorched Guns 2 weapon
         state read as 'off' (`SyncedDataKey<E extends Entity, T>#getValue(E)`).

    The third check's real signature is also read out of the framework jar with `javap`, so the
    "erased to Entity" claim is not taken from this script's author.

    It does NOT verify in-game behaviour. See KNOWN-LIMITS.md.

.PARAMETER YsmJar
    Yes Steve Model jar to resolve against. Defaults to libs/ysm-*.jar, then to the newest
    openysm/ysm jar found in the Minecraft instance under %APPDATA%\.minecraft.

.PARAMETER MixinJar
    Built compat jar. Defaults to the newest build/libs/ysm_scg2_compat-*.jar.

.PARAMETER FrameworkJar
    MrCrayfish's Framework jar, used for the javap check on SyncedDataKey. Optional; the check
    is reported as skipped when absent.

.EXAMPLE
    powershell -NoProfile -File tools/verify-member-lookup.ps1
#>
[CmdletBinding()]
param(
    [string] $YsmJar,
    [string] $MixinJar,
    [string] $FrameworkJar
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot

function Find-JavaHome {
    $fromGradle = Join-Path $projectRoot 'gradle.properties'
    if (Test-Path -LiteralPath $fromGradle) {
        $line = Select-String -LiteralPath $fromGradle -Pattern '^org\.gradle\.java\.home=' | Select-Object -First 1
        if ($line) {
            $value = $line.Line.Substring($line.Line.IndexOf('=') + 1).Replace('\\', '\').Replace('\:', ':')
            if (Test-Path -LiteralPath (Join-Path $value 'bin\javac.exe')) { return $value }
        }
    }
    if ($env:JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\javac.exe'))) {
        return $env:JAVA_HOME
    }
    $javac = Get-Command javac -ErrorAction SilentlyContinue
    if ($javac) { return (Split-Path -Parent (Split-Path -Parent $javac.Source)) }
    throw 'No JDK 17 found. Set JAVA_HOME or fix org.gradle.java.home in gradle.properties.'
}

function Find-InInstance([string] $filter) {
    $roots = @(
        (Join-Path $env:APPDATA '.minecraft\versions'),
        (Join-Path $env:APPDATA '.minecraft\mods'),
        # gson lives in the launcher's shared library store, not next to the mods.
        (Join-Path $env:APPDATA '.minecraft\libraries')
    )
    foreach ($root in $roots) {
        if (-not (Test-Path -LiteralPath $root)) { continue }
        $hit = Get-ChildItem -LiteralPath $root -Recurse -Filter $filter -File -ErrorAction SilentlyContinue |
            Sort-Object LastWriteTime -Descending | Select-Object -First 1
        if ($hit) { return $hit.FullName }
    }
    return $null
}

if (-not $YsmJar) {
    $inLibs = Get-ChildItem (Join-Path $projectRoot 'libs') -Filter 'ysm-*.jar' -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($inLibs) { $YsmJar = $inLibs.FullName } else { $YsmJar = Find-InInstance 'openysm-forge-*.jar' }
}
if (-not $MixinJar) {
    $candidate = Get-ChildItem (Join-Path $projectRoot 'build/libs') -Filter 'ysm_scg2_compat-*.jar' -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $candidate) { throw "No built jar found. Run 'gradlew build' first, or pass -MixinJar." }
    $MixinJar = $candidate.FullName
}
if (-not $FrameworkJar) { $FrameworkJar = Find-InInstance 'framework-forge-*.jar' }

foreach ($required in @($YsmJar, $MixinJar)) {
    if (-not $required -or -not (Test-Path -LiteralPath $required)) { throw "Not found: $required" }
}
$YsmJar = (Resolve-Path -LiteralPath $YsmJar).Path
$MixinJar = (Resolve-Path -LiteralPath $MixinJar).Path
if ($FrameworkJar -and (Test-Path -LiteralPath $FrameworkJar)) {
    $FrameworkJar = (Resolve-Path -LiteralPath $FrameworkJar).Path
}

$javaHome = Find-JavaHome
$javac = Join-Path $javaHome 'bin\javac.exe'
$java = Join-Path $javaHome 'bin\java.exe'
$javap = Join-Path $javaHome 'bin\javap.exe'

# ILoopType's own signature mentions gson, so the interface cannot be loaded without it.
$gson = Find-InInstance 'gson-2.1*.jar'
if (-not $gson) { $gson = Find-InInstance 'gson-*.jar' }

Write-Host "YSM jar    : $YsmJar"
Write-Host "Plugin jar : $MixinJar"
Write-Host "gson       : $(if ($gson) { $gson } else { '(not found - ILoopType cannot be loaded)' })"
Write-Host "java       : $javaHome"
Write-Host ''

$failures = 0

# ----------------------------------------------------------------------
# The framework jar's real descriptor, so the erasure claim is read, not asserted.
# ----------------------------------------------------------------------
if ($FrameworkJar) {
    Write-Host 'javap: com.mrcrayfish.framework.api.sync.SyncedDataKey#getValue'
    $descriptor = & $javap -cp $FrameworkJar -p -s com.mrcrayfish.framework.api.sync.SyncedDataKey 2>&1
    $line = $descriptor | Select-String -Pattern 'getValue' -Context 0,1 | Select-Object -First 1
    if ($line) {
        Write-Host ("  " + $line.Line.Trim())
        if ($line.Context.PostContext.Count -gt 0) { Write-Host ("  " + $line.Context.PostContext[0].Trim()) }
        if (($line.Context.PostContext -join ' ') -match 'Entity.*Object') {
            Write-Host '  [ok] the erased parameter is Entity, not Player - an exact getValue(Player) lookup cannot match'
        } else {
            Write-Host '  [FAIL] unexpected descriptor for getValue'
            $failures++
        }
    } else {
        Write-Host '  [FAIL] getValue not found in SyncedDataKey'
        $failures++
    }
} else {
    Write-Host 'javap: framework jar not found - SyncedDataKey descriptor check skipped'
}
Write-Host ''

# ----------------------------------------------------------------------
# Compile and run the harness.
# ----------------------------------------------------------------------
$scratch = Join-Path ([System.IO.Path]::GetTempPath()) ("ysm-scg2-member-lookup-" + [guid]::NewGuid().ToString('N'))
$classes = Join-Path $scratch 'classes'
$work = Join-Path $scratch 'run'
New-Item -ItemType Directory -Path $classes, $work -Force | Out-Null

$classpath = @($MixinJar, $YsmJar, $gson) | Where-Object { $_ }
$joined = ($classpath -join ';')
$source = Join-Path $PSScriptRoot 'harness/MemberLookupHarness.java'

try {
    & $javac -nowarn -Xlint:none -cp $joined -d $classes $source
    if ($LASTEXITCODE -ne 0) { throw "javac failed with exit code $LASTEXITCODE" }

    Push-Location $work
    try {
        & $java -cp "$classes;$joined" MemberLookupHarness
        if ($LASTEXITCODE -ne 0) { $failures++ }
    } finally {
        Pop-Location
    }

    $probe = Join-Path $work 'ysm_scg2_compat-probe.log'
    if (Test-Path -LiteralPath $probe) {
        Write-Host ''
        Write-Host 'probe lines written while resolving (the new "resolved ... as setAnimation" evidence):'
        Get-Content -LiteralPath $probe | Where-Object { $_ -match '\[member\]' } | ForEach-Object { "  $_" }
    }
} finally {
    Remove-Item -LiteralPath $scratch -Recurse -Force -ErrorAction SilentlyContinue
}

Write-Host ''
if ($failures -eq 0) {
    Write-Host 'Member lookup verification passed.'
    exit 0
}
Write-Host "Member lookup verification FAILED ($failures check group(s))."
exit 1
