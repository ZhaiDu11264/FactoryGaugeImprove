# Copies the freshly built jar into every instance whose mods folder has the
# *matching* Create version. Enumerates instances at runtime on purpose: the
# instance names may contain non-ASCII characters and hardcoding them in a script
# invites encoding corruption. Run through install.bat, which handles elevation.
#
# The game directory is discovered, never hard-coded: pass one with
#   powershell -File install.ps1 -MinecraftDir "D:\some\launcher\.minecraft"
# or let it fall back to the usual launcher locations.
#
# The version gate matters: the mod is compiled against one Create build and every
# injection uses require = 1, so loading it next to a different Create version fails
# during mod construction - which for the player looks like the game not starting.
# The expected Create version is read off the jar's own file name
# (factorygaugeimprove-<mod>-for-create-<version>-neoforge-<mc>.jar), so bumping the
# build dependency needs no edit here.

param(
    [string]$MinecraftDir = ''
)

$ErrorActionPreference = 'Stop'

$log = Join-Path $PSScriptRoot 'install.log'
function Say($message) {
    Write-Host $message
    Add-Content -Path $log -Value $message -Encoding UTF8
}

Set-Content -Path $log -Value ("factorygaugeimprove install - " + (Get-Date)) -Encoding UTF8

$jar = Get-ChildItem (Join-Path $PSScriptRoot 'build\libs\*.jar') |
    Where-Object { $_.Name -notlike '*-sources*' } |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1
if (-not $jar) {
    Say "FAIL: no jar in build\libs - run: gradlew.bat build -x test"
    exit 1
}
Say ("jar : " + $jar.FullName)

$expected = $null
if ($jar.Name -match 'for-create-([0-9][0-9.]*)-') {
    $expected = $Matches[1]
    Say ("built for Create " + $expected)
} else {
    Say "WARN: could not read a Create version from the jar name - every instance with Create will be offered"
}

$candidates = @()
if ($MinecraftDir) { $candidates += $MinecraftDir }
$candidates += @(
    (Join-Path $env:APPDATA '.minecraft'),
    (Join-Path ${env:ProgramFiles(x86)} 'PCL2\.minecraft'),
    (Join-Path $env:ProgramFiles 'PCL2\.minecraft')
)
$minecraft = $candidates |
    Where-Object { $_ -and (Test-Path (Join-Path $_ 'versions')) } |
    Select-Object -First 1
if (-not $minecraft) {
    Say "FAIL: no launcher found - pass one with -MinecraftDir <path to .minecraft>"
    exit 1
}
Say ("mc  : " + $minecraft)

# One row per instance that has a Create jar, carrying the Create version it runs.
$instances = @()
foreach ($versionDir in (Get-ChildItem (Join-Path $minecraft 'versions') -Directory -ErrorAction SilentlyContinue)) {
    $mods = Join-Path $versionDir.FullName 'mods'
    if (-not (Test-Path $mods)) {
        continue
    }
    foreach ($file in (Get-ChildItem $mods -Filter 'create-*.jar' -ErrorAction SilentlyContinue)) {
        # "create-<mc>-<version>.jar" -> <version>. Create addons start with a letter
        # where the MC version should be, so they cannot match. The version group is
        # \d+(?:\.\d+)* and not [0-9.]*, because the latter is greedy enough to
        # swallow the dot of ".jar" and yield "6.0.10." - which then matches no
        # instance at all and makes the gate below refuse everything.
        if ($file.Name -match '^create-\d+(?:\.\d+)+-(\d+(?:\.\d+)*)') {
            $instances += [pscustomobject]@{
                Name   = $versionDir.Name
                Mods   = $mods
                Create = $Matches[1]
            }
            break
        }
    }
}

if ($instances.Count -eq 0) {
    Say "FAIL: no instance with Create in its mods folder was found"
    exit 1
}

$targets = @($instances | Where-Object { -not $expected -or $_.Create -eq $expected })
$skipped = @($instances | Where-Object { $expected -and $_.Create -ne $expected })

foreach ($instance in $skipped) {
    Say ("SKIP       : " + $instance.Name + "  (Create " + $instance.Create + ", not " + $expected + ")")
}

if ($targets.Count -eq 0) {
    Say ("FAIL: no instance runs Create " + $expected + " - nothing was installed")
    exit 1
}

foreach ($instance in $targets) {
    $stale = @(Get-ChildItem $instance.Mods -Filter 'factorygaugeimprove-*.jar' -ErrorAction SilentlyContinue)
    foreach ($old in $stale) {
        Remove-Item $old.FullName -Force
        Say ("removed old: " + $old.Name)
    }
    Copy-Item $jar.FullName $instance.Mods -Force
    Say ("installed  : " + $instance.Name + "  (Create " + $instance.Create + ")  ->  " + $jar.Name)
}

Say 'DONE'
