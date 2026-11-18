$ErrorActionPreference = 'Stop'
try {
    $configPath = Join-Path $PSScriptRoot 'config.properties'
    $tokenLine = Get-Content -LiteralPath $configPath | Where-Object { $_ -match '^bot\.token=' } | Select-Object -First 1
    $botToken = ($tokenLine -replace '^bot\.token=', '').Trim()
    if ($botToken -notmatch '^\d+:[A-Za-z0-9_-]+$') { throw 'Set bot.token in config.properties first.' }
    $result = Invoke-RestMethod -Uri ('https://api.telegram.org/bot' + $botToken + '/getUpdates')
    $ids = @($result.result | ForEach-Object { if ($_.message.chat.type -eq 'private') { $_.message.chat.id } } | Select-Object -Unique)
    if ($ids.Count -eq 0) { Write-Host 'Send /start to your bot on the phone, then run this script again.' }
    elseif ($ids.Count -eq 1) {
        $configText = [IO.File]::ReadAllText($configPath)
        $configText = [regex]::Replace($configText, '(?m)^chat\.id=.*$', ('chat.id=' + $ids[0]))
        [IO.File]::WriteAllText($configPath, $configText, [Text.UTF8Encoding]::new($false))
        Write-Host 'One private chat found. chat.id saved. You can now run Start.cmd.'
    }
    else { Write-Host 'Private chat IDs (copy YOUR ID into chat.id in config.properties):'; $ids | ForEach-Object { Write-Host $_ } }
} catch { Write-Host 'Could not read chat IDs. Check token, Internet access, and that you sent /start to your bot.' }
Read-Host 'Press Enter to close'
