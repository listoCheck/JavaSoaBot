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
    if ($left -or $right) { $armed = $true }
    # Either key arms a capture; wait for release to avoid affecting the snipping UI.
    if ($armed -and -not $left -and -not $right) {
        [Console]::WriteLine('SNIP')
        [Console]::Out.Flush()
        $armed = $false
    }
    Start-Sleep -Milliseconds 20
}
