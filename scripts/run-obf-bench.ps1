[CmdletBinding()]
param(
    [string]$WorkspaceRoot,
    [string]$BenchJarPath,
    [switch]$SkipObfuscatorBuild
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Write-Section {
    param([string]$Title)
    Write-Host ""
    Write-Host "== $Title =="
}

function Invoke-External {
    param(
        [string]$FilePath,
        [string[]]$Arguments,
        [string]$WorkingDirectory
    )

    if ($WorkingDirectory) {
        Push-Location $WorkingDirectory
    }
    try {
        $output = & $FilePath @Arguments 2>&1
    } finally {
        if ($WorkingDirectory) {
            Pop-Location
        }
    }
    $exitCode = $LASTEXITCODE

    return [pscustomobject]@{
        ExitCode = $exitCode
        Output   = @($output)
    }
}

function Require-Success {
    param(
        [string]$StepName,
        $Result
    )

    if ($Result.ExitCode -ne 0) {
        throw "$StepName failed with exit code $($Result.ExitCode)`n$($Result.Output -join [Environment]::NewLine)"
    }
}

function Get-HostTargetConfig {
    $os = [System.Runtime.InteropServices.RuntimeInformation]::OSDescription.ToLowerInvariant()
    $arch = [System.Runtime.InteropServices.RuntimeInformation]::OSArchitecture.ToString().ToLowerInvariant()

    $target = @{
        windowsX64  = $false
        windowsArm64 = $false
        linuxX64    = $false
        linuxArm64  = $false
        macosX64    = $false
        macosArm64  = $false
    }

    if ($os.Contains("windows")) {
        if ($arch.Contains("arm64")) {
            $target.windowsArm64 = $true
        } else {
            $target.windowsX64 = $true
        }
        return $target
    }

    if ($os.Contains("linux")) {
        if ($arch.Contains("arm64")) {
            $target.linuxArm64 = $true
        } else {
            $target.linuxX64 = $true
        }
        return $target
    }

    if ($os.Contains("mac") -or $os.Contains("darwin")) {
        if ($arch.Contains("arm64")) {
            $target.macosArm64 = $true
        } else {
            $target.macosX64 = $true
        }
        return $target
    }

    throw "Unsupported host for bench script: os=$os arch=$arch"
}

function Resolve-BuildWorkspacePath {
    param(
        [string[]]$Lines,
        [string]$OutputRoot
    )

    foreach ($line in $Lines) {
        if ($line -like "Build workspace:*") {
            return $line.Substring("Build workspace:".Length).Trim()
        }
    }

    $candidates = @(
        Get-ChildItem -Path $OutputRoot -Directory -Filter "build_*" -ErrorAction SilentlyContinue |
            Sort-Object LastWriteTimeUtc -Descending
    )
    if ($candidates.Count -gt 0) {
        return $candidates[0].FullName
    }

    throw "Unable to locate build workspace from obfuscator output or output directory."
}

function Resolve-RepackedJarPath {
    param(
        [string[]]$Lines,
        [string]$BuildWorkspace,
        [string]$InputJarPath
    )

    foreach ($line in $Lines) {
        if ($line -like "IR repacked jar:*") {
            return $line.Substring("IR repacked jar:".Length).Trim()
        }
    }

    $fallbackJar = Join-Path $BuildWorkspace ([System.IO.Path]::GetFileName($InputJarPath))
    if (Test-Path $fallbackJar) {
        return (Resolve-Path $fallbackJar).Path
    }

    throw "Unable to locate the repacked jar from obfuscator output or build workspace."
}

function Resolve-FrontendSkipsPath {
    param(
        [string[]]$Lines,
        [string]$BuildWorkspace
    )

    foreach ($line in $Lines) {
        if ($line -like "Frontend skips:*") {
            return $line.Substring("Frontend skips:".Length).Trim()
        }
    }

    $fallbackSkips = Join-Path $BuildWorkspace "frontend-skips.txt"
    if (Test-Path $fallbackSkips) {
        return (Resolve-Path $fallbackSkips).Path
    }

    return $null
}

function Get-ClassNamesFromJar {
    param([string]$JarPath)

    $jarListResult = Invoke-External -FilePath "jar" -Arguments @("--list", "--file", $JarPath) -WorkingDirectory $repoRoot
    Require-Success -StepName "jar --list" -Result $jarListResult

    return @(
        $jarListResult.Output |
            Where-Object { $_ -like "*.class" } |
            Where-Object { $_ -notlike "native0/*" } |
            Where-Object { $_ -ne "module-info.class" } |
            ForEach-Object { ([string]$_).Replace("/", ".") -replace '\.class$', '' }
    )
}

function Get-NativeMethods {
    param([string]$JarPath)

    $nativeMethods = New-Object System.Collections.Generic.List[object]
    foreach ($className in @(Get-ClassNamesFromJar -JarPath $JarPath)) {
        $javapResult = Invoke-External -FilePath "javap" -Arguments @("-classpath", $JarPath, "-p", "-s", $className) -WorkingDirectory $repoRoot
        if ($javapResult.ExitCode -ne 0) {
            continue
        }

        for ($index = 0; $index -lt $javapResult.Output.Count; $index++) {
            $line = [string]$javapResult.Output[$index]
            if ($line -notmatch '\(' -or $line -notmatch '\)\s*;') {
                continue
            }
            if ($line -notmatch '\bnative\b') {
                continue
            }

            $descriptor = ""
            if ($index + 1 -lt $javapResult.Output.Count) {
                $nextLine = ([string]$javapResult.Output[$index + 1]).Trim()
                if ($nextLine.StartsWith("descriptor:")) {
                    $descriptor = $nextLine.Substring("descriptor:".Length).Trim()
                }
            }

            $nativeMethods.Add([pscustomobject]@{
                Class       = $className
                Declaration = $line.Trim()
                Descriptor  = $descriptor
            })
        }
    }

    return @($nativeMethods | ForEach-Object { $_ })
}

function Get-FrontendSkipEntries {
    param([string]$SkipFile)

    if (-not $SkipFile -or -not (Test-Path $SkipFile)) {
        return @()
    }

    $entries = New-Object System.Collections.Generic.List[object]
    $currentClass = $null
    foreach ($line in Get-Content -Path $SkipFile) {
        if ([string]::IsNullOrWhiteSpace($line)) {
            continue
        }
        if ($line -notmatch '^\s+-\s+') {
            $currentClass = $line.Trim()
            continue
        }

        $payload = $line.Trim().Substring(2)
        $separatorIndex = $payload.IndexOf(" :: ")
        if ($separatorIndex -ge 0) {
            $methodText = $payload.Substring(0, $separatorIndex)
            $reason = $payload.Substring($separatorIndex + 4)
        } else {
            $methodText = $payload
            $reason = ""
        }
        $entries.Add([pscustomobject]@{
            Class  = $currentClass
            Method = $methodText
            Reason = $reason
        })
    }

    return @($entries | ForEach-Object { $_ })
}

function Get-SkipCategory {
    param([string]$Reason)

    if ($Reason -like "*invokedynamic lowering is not implemented yet*") {
        return "invokedynamic"
    }
    if ($Reason -like "*Only int static field types are supported*") {
        return "object/static field types"
    }
    if ($Reason -like "*Only int invokestatic parameter types are supported*") {
        return "object/static invoke parameters"
    }
    if ($Reason -like "*Only int/void static return types are supported*") {
        return "object/static invoke returns"
    }
    if ($Reason -like "*Only int/void virtual return types are supported*") {
        return "object/virtual invoke returns"
    }
    if ($Reason -like "*only integer LDC constants are supported*") {
        return "non-int ldc constants"
    }
    if ($Reason -like "*opcode#188*") {
        return "primitive arrays"
    }
    if ($Reason -like "*opcode#189*") {
        return "object arrays"
    }
    if ($Reason -like "*opcode#176*") {
        return "object return opcodes"
    }
    if ($Reason -like "*method is abstract or native*") {
        return "abstract/native methods"
    }
    return "other"
}

function Add-ReportSection {
    param(
        $ReportLines,
        [string]$Title,
        [string[]]$Lines
    )

    $ReportLines.Add("== $Title ==")
    if (-not $Lines -or $Lines.Count -eq 0) {
        $ReportLines.Add("(none)")
    } else {
        foreach ($line in $Lines) {
            $ReportLines.Add([string]$line)
        }
    }
    $ReportLines.Add("")
}

$scriptRoot = Split-Path -Parent $PSCommandPath
$repoRoot = (Resolve-Path (Join-Path $scriptRoot "..")).Path
$WorkspaceRoot = if ($WorkspaceRoot) { $WorkspaceRoot } else { Join-Path $repoRoot "build\obf-bench" }
$workspace = (Resolve-Path (New-Item -ItemType Directory -Force -Path $WorkspaceRoot)).Path
$outDir = Join-Path $workspace "out"
$benchJar = if ($BenchJarPath) { (Resolve-Path $BenchJarPath).Path } else { Join-Path $workspace "obf-bench.jar" }
$configPath = Join-Path $workspace "obf-bench-config.json"
$reportPath = Join-Path $workspace "obf-bench-report.txt"

if (Test-Path $outDir) {
    Remove-Item -Recurse -Force $outDir
}
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

if (-not (Test-Path $benchJar)) {
    throw "Unable to locate obf-bench.jar at $benchJar"
}

Write-Section "Use obf-bench.jar"
Write-Host $benchJar

Write-Section "Run original obf-bench.jar"
$originalResult = Invoke-External -FilePath "java" -Arguments @("-jar", $benchJar) -WorkingDirectory $repoRoot
$originalResult.Output | ForEach-Object { Write-Host $_ }
if ($originalResult.ExitCode -ne 0) {
    throw "Original obf-bench.jar failed."
}

if (-not $SkipObfuscatorBuild) {
    Write-Section "Build j2ll shadow jar"
    $gradleResult = Invoke-External -FilePath (Join-Path $repoRoot "gradlew.bat") -Arguments @("shadowJar") -WorkingDirectory $repoRoot
    $gradleResult.Output | ForEach-Object { Write-Host $_ }
    Require-Success -StepName "shadowJar" -Result $gradleResult
}

$obfuscatorJar = Get-ChildItem -Path (Join-Path $repoRoot "build\libs") -Filter *.jar |
        Sort-Object LastWriteTimeUtc -Descending |
        Select-Object -First 1 -ExpandProperty FullName
if (-not $obfuscatorJar) {
    throw "Unable to find obfuscator jar under build\libs"
}

$config = @{
    jarFile = $benchJar
    outputDirectory = $outDir
    librariesDirectory = $null
    blackList = @()
    whiteList = $null
    target = Get-HostTargetConfig
    libraryName = $null
    embeddedLibraryDirectory = "native0"
    stringObfuscation = @{
        enabled = $true
    }
}
$config | ConvertTo-Json -Depth 6 | Set-Content -Path $configPath -Encoding UTF8

Write-Section "Obfuscate obf-bench.jar"
$obfuscateResult = Invoke-External -FilePath "java" -Arguments @("-jar", $obfuscatorJar, "--config", $configPath) -WorkingDirectory $repoRoot
$obfuscateResult.Output | ForEach-Object { Write-Host $_ }
Require-Success -StepName "obfuscator" -Result $obfuscateResult

$buildWorkspace = Resolve-BuildWorkspacePath -Lines $obfuscateResult.Output -OutputRoot $outDir
$repackedJar = Resolve-RepackedJarPath -Lines $obfuscateResult.Output -BuildWorkspace $buildWorkspace -InputJarPath $benchJar
$frontendSkips = Resolve-FrontendSkipsPath -Lines $obfuscateResult.Output -BuildWorkspace $buildWorkspace

Write-Section "Run obfuscated obf-bench.jar"
$obfuscatedResult = Invoke-External -FilePath "java" -Arguments @("--enable-native-access=ALL-UNNAMED", "-jar", $repackedJar) -WorkingDirectory $repoRoot
$obfuscatedResult.Output | ForEach-Object { Write-Host $_ }
$obfuscatedFailed = $obfuscatedResult.ExitCode -ne 0

Write-Section "Frontend skips"
$frontendSkipLines = @()
if ($frontendSkips -and (Test-Path $frontendSkips)) {
    $content = @(Get-Content -Path $frontendSkips)
    $frontendSkipLines = $content
    if ($content.Count -eq 0) {
        Write-Host "(none)"
    } else {
        $content | ForEach-Object { Write-Host $_ }
    }
} else {
    Write-Host "(none)"
}

$skipEntries = @()
$skipSummaryLines = @()
$nativeMethodLines = @()
$obfuscatedGateFailures = New-Object System.Collections.Generic.List[string]

try {
    $nativeMethods = @(Get-NativeMethods -JarPath $repackedJar)
    foreach ($nativeMethod in $nativeMethods | Sort-Object Class, Declaration) {
        $descriptorSuffix = if ($nativeMethod.Descriptor) { " :: $($nativeMethod.Descriptor)" } else { "" }
        $nativeMethodLines += "$($nativeMethod.Class) -> $($nativeMethod.Declaration)$descriptorSuffix"
    }

    Write-Section "Native rewritten methods"
    if ($nativeMethodLines.Count -eq 0) {
        Write-Host "(none)"
    } else {
        $nativeMethodLines | ForEach-Object { Write-Host $_ }
    }

    $skipEntries = @(Get-FrontendSkipEntries -SkipFile $frontendSkips)
    foreach ($group in $skipEntries | Group-Object { Get-SkipCategory $_.Reason } | Sort-Object -Property Count, Name -Descending) {
        $skipSummaryLines += "{0} -> {1}" -f $group.Name, $group.Count
    }

    Write-Section "Skip summary"
    if ($skipSummaryLines.Count -eq 0) {
        Write-Host "(none)"
    } else {
        $skipSummaryLines | ForEach-Object { Write-Host $_ }
    }

    if ($skipEntries.Count -gt 0) {
        $obfuscatedGateFailures.Add("frontend skips remain: $($skipEntries.Count) method(s) were not native-lowered")
    }
    if ($nativeMethodLines.Count -eq 0) {
        $obfuscatedGateFailures.Add("no methods were rewritten to native")
    }

    $benchStatusLines = @()
    if ($obfuscatedFailed) {
        $benchStatusLines += "FAIL"
        $benchStatusLines += "reason -> obfuscated obf-bench.jar exited with a non-zero code"
    } elseif ($obfuscatedGateFailures.Count -gt 0) {
        $benchStatusLines += "FAIL"
        foreach ($failure in $obfuscatedGateFailures) {
            $benchStatusLines += "reason -> $failure"
        }
    } else {
        $benchStatusLines += "PASS"
        $benchStatusLines += "reason -> obfuscated run passed and every benchmark method was native-lowered"
    }

    $reportLines = New-Object System.Collections.Generic.List[string]
    Add-ReportSection -ReportLines $reportLines -Title "Bench status" -Lines $benchStatusLines
    Add-ReportSection -ReportLines $reportLines -Title "Original run" -Lines @($originalResult.Output | ForEach-Object { [string]$_ })
    Add-ReportSection -ReportLines $reportLines -Title "Obfuscated run" -Lines @($obfuscatedResult.Output | ForEach-Object { [string]$_ })
    Add-ReportSection -ReportLines $reportLines -Title "Native rewritten methods" -Lines $nativeMethodLines
    Add-ReportSection -ReportLines $reportLines -Title "Frontend skips" -Lines @($frontendSkipLines | ForEach-Object { [string]$_ })
    Add-ReportSection -ReportLines $reportLines -Title "Skip summary" -Lines $skipSummaryLines
    $reportLines.Add("obf-bench.jar -> $benchJar")
    $reportLines.Add("repacked jar -> $repackedJar")
    Set-Content -Path $reportPath -Value $reportLines -Encoding UTF8
} catch {
    throw ("Report generation failed at line {0}: {1}" -f $_.InvocationInfo.ScriptLineNumber, $_.Exception.Message)
}

Write-Section "Done"
Write-Host "obf-bench.jar -> $benchJar"
Write-Host "repacked jar -> $repackedJar"
Write-Host "report -> $reportPath"

if ($obfuscatedFailed) {
    throw "Obfuscated obf-bench.jar failed."
}
if ($obfuscatedGateFailures.Count -gt 0) {
    throw ("Obfuscated obf-bench.jar failed strict native gate:`n" + ($obfuscatedGateFailures -join [Environment]::NewLine))
}
