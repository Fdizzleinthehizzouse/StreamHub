# StreamHub installer
#
# Downloads the official Electron runtime from GitHub, drops the StreamHub app
# into it, and leaves you with StreamHub.exe plus a desktop shortcut.
# Nothing else is installed and nothing is written outside your user folder.

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'   # makes Invoke-WebRequest far faster

# Some Windows builds still default to TLS 1.0, which GitHub refuses.
try { [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12 } catch {}

$ElectronVersion = '33.4.11'
$ExpectedSha     = 'f64c8a5a81d9b420b636fdba13e180f49d69f2198e1d86a8b01f858b17a9483c'
$ZipName         = "electron-v$ElectronVersion-win32-x64.zip"
$Url             = "https://github.com/electron/electron/releases/download/v$ElectronVersion/$ZipName"

$InstallDir = Join-Path $env:LOCALAPPDATA 'StreamHub'
$SourceDir  = Join-Path $PSScriptRoot 'app'
$TempZip    = Join-Path $env:TEMP $ZipName

function Say($msg, $color = 'Gray') { Write-Host "  $msg" -ForegroundColor $color }

Write-Host ''
Write-Host '  StreamHub' -ForegroundColor Cyan
Write-Host '  Netflix, Disney+, HBO Max and Prime Video in one window' -ForegroundColor DarkGray
Write-Host ''

if (-not (Test-Path $SourceDir)) {
    Say "Could not find the 'app' folder next to this installer." Red
    Say "Make sure you unzipped the whole download before running it." Red
    Read-Host "`n  Press Enter to close"
    exit 1
}

# --- 1. fetch the Electron runtime -----------------------------------------

if (Test-Path $TempZip) {
    Say "Found a previous download, checking it..."
    $hash = (Get-FileHash $TempZip -Algorithm SHA256).Hash
    if ($hash -ne $ExpectedSha) { Remove-Item $TempZip -Force }
}

if (-not (Test-Path $TempZip)) {
    Say "Downloading the Electron runtime (~110 MB, one time only)..." White
    Say "from github.com/electron/electron" DarkGray
    try {
        Invoke-WebRequest -Uri $Url -OutFile $TempZip -UseBasicParsing
    } catch {
        Say "Download failed: $($_.Exception.Message)" Red
        Say "Check your connection and run this again." Red
        Read-Host "`n  Press Enter to close"
        exit 1
    }
}

Say "Verifying the download..."
$hash = (Get-FileHash $TempZip -Algorithm SHA256).Hash
if ($hash -ne $ExpectedSha) {
    Say "Checksum mismatch - the download is corrupt or was tampered with." Red
    Say "expected $ExpectedSha" DarkGray
    Say "got      $hash" DarkGray
    Remove-Item $TempZip -Force
    Read-Host "`n  Press Enter to close"
    exit 1
}
Say "Checksum OK." Green

# --- 2. lay out the app -----------------------------------------------------

if (Test-Path $InstallDir) {
    Say "Removing the previous version..."
    # Do not kill a running copy silently - tell the user instead.
    $running = Get-Process -Name 'StreamHub' -ErrorAction SilentlyContinue
    if ($running) {
        Say "StreamHub is currently running. Close it and run this installer again." Red
        Read-Host "`n  Press Enter to close"
        exit 1
    }
    Remove-Item $InstallDir -Recurse -Force
}

Say "Unpacking to $InstallDir (this takes a minute)..."
New-Item -ItemType Directory -Path $InstallDir -Force | Out-Null
Expand-Archive -Path $TempZip -DestinationPath $InstallDir -Force

Say "Installing the StreamHub app files..."
$AppTarget = Join-Path $InstallDir 'resources\app'
New-Item -ItemType Directory -Path $AppTarget -Force | Out-Null
Copy-Item -Path (Join-Path $SourceDir '*') -Destination $AppTarget -Recurse -Force

$ElectronExe  = Join-Path $InstallDir 'electron.exe'
$StreamHubExe = Join-Path $InstallDir 'StreamHub.exe'
if (Test-Path $ElectronExe) { Rename-Item -Path $ElectronExe -NewName 'StreamHub.exe' -Force }

if (-not (Test-Path $StreamHubExe)) {
    Say "Something went wrong - StreamHub.exe was not created." Red
    Read-Host "`n  Press Enter to close"
    exit 1
}

# --- 3. shortcuts -----------------------------------------------------------

Say "Creating shortcuts..."
$shell = New-Object -ComObject WScript.Shell

foreach ($dir in @([Environment]::GetFolderPath('Desktop'),
                   (Join-Path $env:APPDATA 'Microsoft\Windows\Start Menu\Programs'))) {
    try {
        $lnk = $shell.CreateShortcut((Join-Path $dir 'StreamHub.lnk'))
        $lnk.TargetPath       = $StreamHubExe
        $lnk.WorkingDirectory = $InstallDir
        $lnk.Description      = 'Netflix, Disney+, HBO Max and Prime Video in one window'
        $lnk.Save()
    } catch {
        Say "Could not create a shortcut in $dir (not fatal)." DarkYellow
    }
}

# --- 4. done ----------------------------------------------------------------

Remove-Item $TempZip -Force -ErrorAction SilentlyContinue

Write-Host ''
Say "Done. StreamHub is installed." Green
Write-Host ''
Say "There is now a StreamHub shortcut on your desktop and in the Start menu."
Say "Installed at: $InstallDir" DarkGray
Write-Host ''
Say "Next: open it and paste a free TMDB key into Settings." White
Say "  https://www.themoviedb.org/signup  ->  Settings -> API -> API Key (v3 auth)" DarkGray
Write-Host ''
Say "To uninstall later, just delete that folder and the shortcuts." DarkGray
Write-Host ''

$answer = Read-Host "  Launch StreamHub now? [Y/n]"
if ($answer -eq '' -or $answer -match '^[Yy]') {
    Start-Process -FilePath $StreamHubExe -WorkingDirectory $InstallDir
}
