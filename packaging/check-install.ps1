# This script installs the package on a disposable CI runner.
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$profileDir = Join-Path ([IO.Path]::GetTempPath()) ("luminaread-install-" + [Guid]::NewGuid())
New-Item -ItemType Directory $profileDir | Out-Null
try {
    if ($IsWindows) {
        $package = Get-Item "$root/target/packaging/installers/*.exe"
        $process = Start-Process -FilePath $package.FullName -ArgumentList '/quiet', '/norestart' -PassThru
        if (!$process.WaitForExit(120000)) { throw 'Windows installer timed out' }
        if ($process.ExitCode -ne 0) { throw "Installer exited with code $($process.ExitCode)" }
        $installDir = Join-Path $env:LOCALAPPDATA 'LuminaRead'
        $launcher = Join-Path $installDir 'LuminaRead.exe'
        $java = Join-Path $installDir 'runtime/bin/java.exe'
        $jar = Join-Path $installDir 'app/LuminaRead.jar'
    } elseif ($IsLinux) {
        $package = Get-Item "$root/target/packaging/installers/*.deb"
        & sudo apt-get install -y $package.FullName
        if ($LASTEXITCODE -ne 0) { throw 'Debian package installation failed' }
        $installedFiles = dpkg -L luminaread
        $launcher = $installedFiles | Where-Object { $_ -cmatch '/bin/LuminaRead$' }
        $java = $installedFiles | Where-Object { $_ -cmatch '/runtime/bin/java$' }
        $jar = $installedFiles | Where-Object { $_ -cmatch '/app/LuminaRead\.jar$' }
        $desktopEntry = Get-ChildItem /usr/share/applications -Filter '*.desktop' |
            Where-Object { $_.Name -match 'luminaread' }
        if (!$desktopEntry) { throw 'The package did not register a desktop launcher' }
    } else {
        throw 'Unsupported installation test platform'
    }

    $oldJavaOptions = $env:JAVA_TOOL_OPTIONS
    try {
        $env:JAVA_TOOL_OPTIONS = "-Djava.awt.headless=true `"-Duser.home=$profileDir`""
        $application = Start-Process -FilePath $launcher -PassThru
        if (!$application.WaitForExit(30000)) {
            $application.Kill()
            throw 'Installed launcher did not exit'
        }
        if ($application.ExitCode -ne 0) { throw "Launcher exited with code $($application.ExitCode)" }
    } finally {
        $env:JAVA_TOOL_OPTIONS = $oldJavaOptions
    }
    if (!(Get-ChildItem (Join-Path $profileDir 'LuminaRead_Books') -Filter '*.fb2')) {
        throw 'The installed launcher did not initialize the library'
    }

    $classpath = "$root/target/test-classes$([IO.Path]::PathSeparator)$jar"
    # Exercise the installed Java runtime and PDF worker, without any system Java or Poppler.
    & $java "-Duser.home=$profileDir" '-Djava.awt.headless=true' '-Dluminaread.debugPdf=true' -cp $classpath com.luminaread.RegressionSmokeTest
    if ($LASTEXITCODE -ne 0) { throw 'The installed runtime failed the reading and PDF smoke test' }
} finally {
    Remove-Item -LiteralPath $profileDir -Recurse -Force
}
