#Requires -Version 5.1
<#
 FlowPlay one-time PRIVATE signing setup for a Windows PC.
 Key material stays on this PC and in GitHub Actions encrypted secrets.
 NEVER commit private signing keys or passwords to a public repo.
#>
[CmdletBinding()]
param(
    [string]$Repository = "userdc-dev/FlowPlay",
    [string]$KeystorePath = ""
)
$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

if ($Repository -notmatch '^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$') {
    throw "Repository must be in owner/name format."
}
if (-not $KeystorePath) {
    $documents = [Environment]::GetFolderPath("MyDocuments")
    if ([string]::IsNullOrWhiteSpace($documents)) {
        $documents = Join-Path $env:USERPROFILE "Documents"
    }
    $KeystorePath = Join-Path $documents "FlowPlaySigning\flowplay-release.p12"
}
$gh = Get-Command gh -ErrorAction SilentlyContinue
if (-not $gh) {
    throw "Install GitHub CLI first: winget install --id GitHub.cli --exact. Reopen PowerShell."
}
$script:GhPath = $gh.Source
$keytoolCmd = Get-Command keytool -ErrorAction SilentlyContinue
$keytoolPath = if ($keytoolCmd) { $keytoolCmd.Source } else { $null }
if (-not $keytoolPath) {
    $candidates = @()
    if ($env:JAVA_HOME) { $candidates += Join-Path $env:JAVA_HOME "bin\keytool.exe" }
    $candidates += Join-Path $env:ProgramFiles "Android\Android Studio\jbr\bin\keytool.exe"
    $keytoolPath = $candidates | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
}
if (-not $keytoolPath) {
    throw "Java keytool not found. Install Android Studio or JDK 21 first."
}
& $script:GhPath auth status -h github.com *> $null
if ($LASTEXITCODE -ne 0) {
    Write-Host "Please sign in to your GitHub account."
    & $script:GhPath auth login -h github.com --web
    if ($LASTEXITCODE -ne 0) { throw "GitHub sign-in was unsuccessful." }
}
function Get-Plaintext([Security.SecureString]$Secure) {
    $ptr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($Secure)
    try { return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr) }
}
# Feed the secret directly to gh stdin. Do NOT expose it in process arguments.
function Set-Secret([string]$Name, [string]$Value, [string]$TargetRepo) {
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $script:GhPath
    $psi.Arguments = "secret set $Name -R $TargetRepo"
    $psi.UseShellExecute = $false
    $psi.RedirectStandardInput = $true
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.CreateNoWindow = $true
    $proc = New-Object System.Diagnostics.Process
    $proc.StartInfo = $psi
    try {
        [void]$proc.Start()
        $proc.StandardInput.Write($Value)
        $proc.StandardInput.Close()
        $stderr = $proc.StandardError.ReadToEnd()
        $proc.WaitForExit()
        if ($proc.ExitCode -ne 0) {
            throw "Could not configure $Name. Verify GitHub repository admin access. $stderr"
        }
        Write-Host "Configured $Name"
    } finally { $proc.Dispose() }
}
$existing = Test-Path -LiteralPath $KeystorePath
if ($existing) {
    Write-Host "Existing signing key found: it will be REUSED, not overwritten."
} else {
    $dir = Split-Path -Parent $KeystorePath
    [void](New-Item -ItemType Directory -Path $dir -Force)
    Write-Host "Creating a new signing key on this PC."
}
$secure = Read-Host "FlowPlay signing password (16+ chars, save in password manager)" -AsSecureString
$password = Get-Plaintext $secure
$secure.Dispose()
if ($password.Length -lt 16) { throw "Password must contain at least 16 characters." }
if (-not $existing) {
    $secure2 = Read-Host "Confirm signing password" -AsSecureString
    $confirm = Get-Plaintext $secure2
    $secure2.Dispose()
    if ($confirm -cne $password) { throw "Passwords did not match. Nothing was changed." }
    $confirm = $null
}
$env:FLOWPLAY_LOCAL_SIGNING_PASSWORD = $password
try {
    if (-not $existing) {
        $argsList = @("-genkeypair","-v","-keystore",$KeystorePath,"-alias","flowplay",
            "-keyalg","RSA","-keysize","3072","-validity","12000","-storetype","PKCS12",
            "-dname","CN=FlowPlay, OU=Private App, O=FlowPlay, C=AU",
            "-storepass:env","FLOWPLAY_LOCAL_SIGNING_PASSWORD",
            "-keypass:env","FLOWPLAY_LOCAL_SIGNING_PASSWORD","-noprompt")
        & $keytoolPath @argsList
        if ($LASTEXITCODE -ne 0) { throw "Keytool could not generate the private signing key." }
    }
    $listArgs = @("-list","-v","-keystore",$KeystorePath,"-alias","flowplay",
        "-storepass:env","FLOWPLAY_LOCAL_SIGNING_PASSWORD")
    $details = & $keytoolPath @listArgs 2>&1
    if ($LASTEXITCODE -ne 0) { throw "Cannot unlock signing key: check password/path." }
    $m = [regex]::Match(($details -join [Environment]::NewLine),
        '(?i)SHA256:\s*((?:[0-9A-F]{2}:){31}[0-9A-F]{2}|[0-9A-F]{64})')
    if (-not $m.Success) { throw "Keytool certificate SHA-256 fingerprint was not found." }
    $fingerprint = $m.Groups[1].Value.Replace(":","").ToLowerInvariant()
    $encoded = [Convert]::ToBase64String([IO.File]::ReadAllBytes($KeystorePath))
    Set-Secret "FLOWPLAY_SIGNING_KEYSTORE_BASE64" $encoded $Repository
    Set-Secret "FLOWPLAY_SIGNING_STORE_PASSWORD" $password $Repository
    Set-Secret "FLOWPLAY_SIGNING_KEY_ALIAS" "flowplay" $Repository
    Set-Secret "FLOWPLAY_SIGNING_KEY_PASSWORD" $password $Repository
    Set-Secret "FLOWPLAY_SIGNING_CERT_SHA256" $fingerprint $Repository
    Write-Host ""
    Write-Host "Permanent signing secrets configured. Certificate SHA256: $fingerprint"
    Write-Host "BACK UP THIS PRIVATE FILE OFFLINE: $KeystorePath"
    Write-Host "SAVE THE PASSWORD. Losing the key/password prevents future app updates."
    Write-Host "Next: GitHub > Actions > Build FlowPlay v2 Reliability APK > Run workflow."
    Write-Host "After this one-time signature change, export/restore FlowPlay data if reinstalling."
} finally {
    Remove-Item Env:\FLOWPLAY_LOCAL_SIGNING_PASSWORD -ErrorAction SilentlyContinue
    $password = $null
    $encoded = $null
}
