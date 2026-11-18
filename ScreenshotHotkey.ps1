param([int]$ParentProcessId, [switch]$SelfTest)
$ErrorActionPreference = 'Stop'
Add-Type -TypeDefinition @'
using System.Runtime.InteropServices;
public static class ClipboardScreenshotKeys {
    [DllImport("user32.dll")]
    public static extern short GetAsyncKeyState(int key);
}
'@
if ($SelfTest) {
    $null = [ClipboardScreenshotKeys]::GetAsyncKeyState(0x25)
    $null = [ClipboardScreenshotKeys]::GetAsyncKeyState(0x27)
    Write-Output 'PASS: Windows keyboard API loaded; Left/Right readable'
    exit 0
}
$owner = Get-Process -Id $ParentProcessId
$armed = $false
while (-not $owner.HasExited) {
    $left = ([ClipboardScreenshotKeys]::GetAsyncKeyState(0x25) -band 0x8000) -ne 0
    $right = ([ClipboardScreenshotKeys]::GetAsyncKeyState(0x27) -band 0x8000) -ne 0
    # Start at key-down, once per press. No synthetic mouse button is held globally.
    if (($left -or $right) -and -not $armed) {
        [Console]::WriteLine('SNIP')
        [Console]::Out.Flush()
        $armed = $true
    }
    if (-not $left -and -not $right) { $armed = $false }
    Start-Sleep -Milliseconds 20
}
