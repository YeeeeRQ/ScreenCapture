Add-Type -AssemblyName System.Windows.Forms
$notify = New-Object System.Windows.Forms.NotifyIcon
$notify.Icon = [System.Drawing.SystemIcons]::Information
$notify.Visible = $true
$notify.ShowBalloonTip(3000, "Done", "Build 23 installed! Please test.", "Info")
Start-Sleep -Seconds 3
$notify.Dispose()
