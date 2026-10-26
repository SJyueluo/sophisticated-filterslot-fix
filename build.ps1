param(
    [string]$OutDir = (Join-Path $PSScriptRoot 'built'),
    [string]$ClientClasspath = ''
)
$ErrorActionPreference = 'Stop'

$repo = $PSScriptRoot
$cpFile = Join-Path $repo 'compile-classpath.txt'
if (!(Test-Path -LiteralPath $cpFile)) { throw "missing $cpFile - copy compile-classpath.example.txt to compile-classpath.txt and fill in your own jar paths" }

# One jar per line. Joined with ';' so Windows paths stay untouched. We deliberately do not use
# javac @argfiles here: they are read with the system charset and mangle non-ASCII paths.
$cp = ((Get-Content -LiteralPath $cpFile) | Where-Object { $_ -and $_.Trim() -and -not $_.TrimStart().StartsWith('#') } | ForEach-Object { $_.Trim() }) -join ';'

$javac = Join-Path $env:JAVA_HOME 'bin\javac.exe'
$jar = Join-Path $env:JAVA_HOME 'bin\jar.exe'
if (!(Test-Path -LiteralPath $javac)) { throw 'JAVA_HOME is not set to a JDK 21 install' }

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

function Build-Mod {
    param([string]$Dir, [string]$JarName, [string]$ExtraClasspath)

    $srcDir = Join-Path $repo $Dir
    $clsDir = Join-Path $OutDir ($Dir + '-classes')
    Remove-Item -LiteralPath $clsDir -Recurse -Force -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force -Path $clsDir | Out-Null

    $sources = @(Get-ChildItem -LiteralPath $srcDir -Recurse -Filter *.java |
        Where-Object { $_.FullName -notmatch '\\stubs\\' } | ForEach-Object { $_.FullName })
    $effectiveClasspath = if ($ExtraClasspath) { $cp + ';' + $ExtraClasspath } else { $cp }

    Write-Host ("compiling {0} ({1} file(s)) ..." -f $Dir, $sources.Count)
    & $javac -encoding UTF-8 -proc:none -nowarn -d $clsDir -cp $effectiveClasspath @sources
    if ($LASTEXITCODE -ne 0) { throw "compile failed: $Dir" }

    New-Item -ItemType Directory -Force -Path (Join-Path $clsDir 'META-INF') | Out-Null
    Copy-Item -LiteralPath (Join-Path $srcDir 'META-INF\neoforge.mods.toml') `
        -Destination (Join-Path $clsDir 'META-INF\neoforge.mods.toml') -Force
    Get-ChildItem -LiteralPath $srcDir -Filter *.mixins.json -ErrorAction SilentlyContinue |
        ForEach-Object { Copy-Item -LiteralPath $_.FullName -Destination $clsDir -Force }

    $jarPath = Join-Path $OutDir $JarName
    Remove-Item -LiteralPath $jarPath -Force -ErrorAction SilentlyContinue
    & $jar --create --file $jarPath -C $clsDir .
    Write-Host ("built {0}  {1} B" -f $jarPath, (Get-Item -LiteralPath $jarPath).Length)
}

Build-Mod -Dir 'backpack-check' -JarName 'backpack-check-1.0.0.jar'
Build-Mod -Dir 'backpack-transfer-fix' -JarName 'backpack-transfer-fix-1.0.0.jar'

# The client probe needs client-side Minecraft classes, which are not part of a server library set.
# Pass a classpath that contains them (a patched client jar) to build it; otherwise it is skipped.
if ($ClientClasspath) {
    $stubOut = Join-Path $OutDir 'client-stubs'
    Remove-Item -LiteralPath $stubOut -Recurse -Force -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force -Path $stubOut | Out-Null

    $stubSource = Join-Path $repo 'backpack-client-demo\stubs'
    if (Test-Path -LiteralPath $stubSource) {
        $stubFiles = @(Get-ChildItem -LiteralPath $stubSource -Recurse -Filter *.java | ForEach-Object { $_.FullName })
        if ($stubFiles.Count -gt 0) {
            & $javac -encoding UTF-8 -proc:none -nowarn -d $stubOut @stubFiles
            if ($LASTEXITCODE -ne 0) { throw 'compile failed: client stubs' }
        }
    }

    Build-Mod -Dir 'backpack-client-demo' -JarName 'backpack-client-demo-1.0.0.jar' `
        -ExtraClasspath ($ClientClasspath + ';' + $stubOut)
} else {
    Write-Host 'skipped backpack-client-demo (pass -ClientClasspath <client jars> to build it)'
}

Write-Host 'done.'
