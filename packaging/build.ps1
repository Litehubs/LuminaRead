# Run after mvn clean verify, on the target OS with Temurin JDK 25.
param(
    [ValidateSet('exe', 'deb')][Parameter(Mandatory)][string] $Type
)

$ErrorActionPreference = 'Stop'

function Add-LinuxLauncher($package, $destination, $icon, $stage) {
    $tree = Join-Path $stage 'deb'
    & dpkg-deb --raw-extract $package $tree
    if ($LASTEXITCODE -ne 0) { throw 'Could not extract the Debian package' }

    # Own the desktop entry in dpkg so installation also works on a minimal system.
    $launcher = @(Get-ChildItem (Join-Path $tree 'opt') -Recurse -File -Filter 'LuminaRead' |
        Where-Object { $_.FullName -cmatch '/bin/LuminaRead$' })
    if ($launcher.Count -ne 1) { throw 'Expected one launcher in the Debian package' }
    $exec = '/' + [IO.Path]::GetRelativePath($tree, $launcher[0].FullName)
    $menu = Join-Path $tree 'usr/share/applications'
    $icons = Join-Path $tree 'usr/share/icons/hicolor/1024x1024/apps'
    New-Item -ItemType Directory -Force $menu, $icons | Out-Null
    Copy-Item -LiteralPath $icon (Join-Path $icons 'luminaread.png')
    @"
[Desktop Entry]
Type=Application
Name=LuminaRead
Exec=$exec
Icon=luminaread
Categories=Office;Viewer;
Terminal=false
"@ | Set-Content -LiteralPath (Join-Path $menu 'luminaread.desktop')

    $size = (& du -sk --exclude=DEBIAN $tree) -split '\s+' | Select-Object -First 1
    if ($LASTEXITCODE -ne 0) { throw 'Could not measure the installed package size' }
    $control = Join-Path $tree 'DEBIAN/control'
    (Get-Content -LiteralPath $control) -replace '^Installed-Size: .*', "Installed-Size: $size" |
        Set-Content -LiteralPath $control
    & dpkg-deb --root-owner-group --build $tree $destination
    if ($LASTEXITCODE -ne 0) { throw 'Could not build the final Debian package' }
}

$root = Split-Path -Parent $PSScriptRoot
Push-Location $root
try {
    [xml] $pom = Get-Content pom.xml -Raw
    $version = [string] $pom.project.version
    # Windows Installer uses a numeric Major.Minor.Build version.
    if ($version -notmatch '^([0-9]+)\.([0-9]+)\.([0-9]+)(?:-SNAPSHOT)?$') {
        throw "Expected a three-part Maven version, with an optional -SNAPSHOT suffix: $version"
    }
    $nativeVersion = "$($Matches[1]).$($Matches[2]).$($Matches[3])"
    if ($env:GITHUB_REF_TYPE -eq 'tag' -and
        ($version -ne $nativeVersion -or $env:GITHUB_REF_NAME -ne "v$version")) {
        throw "Tag must be v$version and tagged releases must have a stable Maven version"
    }
    if ([Runtime.InteropServices.RuntimeInformation]::ProcessArchitecture -ne 'X64') {
        throw 'These installers target x64. Build on an x64 system.'
    }
    if (($Type -eq 'exe' -and !$IsWindows) -or ($Type -eq 'deb' -and !$IsLinux)) {
        throw "Build $Type on its target operating system"
    }

    $stage = Join-Path $root 'target/packaging'
    $inputDir = Join-Path $stage 'input'
    $dest = Join-Path $stage 'installers'
    $generatedDir = Join-Path $stage ([Guid]::NewGuid().ToString())
    New-Item -ItemType Directory -Force $inputDir, $dest, $generatedDir | Out-Null
    Copy-Item 'target/LuminaRead.jar' $inputDir

    $arguments = @('--type', $Type, '--name', 'LuminaRead', '--app-version', $nativeVersion,
        '--vendor', 'Litehubs', '--description', 'Desktop reader for books, manga and light novels',
        '--about-url', 'https://github.com/Litehubs/LuminaRead', '--license-file', "$root/LICENSE",
        '--input', $inputDir, '--main-jar', 'LuminaRead.jar', '--dest', $generatedDir,
        '--add-modules', 'java.se,jdk.httpserver,jdk.crypto.ec,jdk.charsets,jdk.localedata',
        '--jlink-options', '--strip-debug --no-man-pages --no-header-files',
        '--java-options', '-Dfile.encoding=UTF-8')

    $png = Join-Path $root 'src/main/resources/icons/app_icon.png'
    if ($Type -eq 'exe') {
        if ($env:WIX) {
            $env:PATH = "$(Join-Path $env:WIX 'bin');$env:PATH"
        }
        $icon = Join-Path $stage 'LuminaRead.ico'
        & magick $png -define icon:auto-resize=256,128,64,48,32,16 $icon
        if ($LASTEXITCODE -ne 0) { throw 'Could not create the Windows icon' }
        $arguments += @('--icon', $icon, '--win-per-user-install', '--win-menu', '--win-menu-group', 'LuminaRead',
            '--win-upgrade-uuid', '404d907b-5847-4a05-ae2f-bebc78526d12')
        $filename = "LuminaRead-$version-windows-x64.exe"
    } else {
        $arguments += @('--linux-package-name', 'luminaread',
            '--linux-deb-maintainer', 'Litehubs@users.noreply.github.com',
            '--linux-app-category', 'text', '--linux-package-deps', 'libfontconfig1')
        $filename = "LuminaRead-$version-linux-amd64.deb"
    }

    & jpackage @arguments
    if ($LASTEXITCODE -ne 0) { throw "jpackage failed with exit code $LASTEXITCODE" }
    $output = @(Get-ChildItem -LiteralPath $generatedDir -File -Filter "*.$Type")
    if ($output.Count -ne 1) { throw "Expected exactly one $Type from jpackage" }
    $destination = Join-Path $dest $filename
    if ($Type -eq 'deb') {
        Add-LinuxLauncher $output[0].FullName $destination $png $generatedDir
    } else {
        Move-Item -Force $output[0].FullName $destination
    }
    Remove-Item -LiteralPath $generatedDir -Recurse
} finally {
    Pop-Location
}
