[CmdletBinding()]
param(
    [switch]$CheckOnly,
    [ValidateRange(2.0, 30.0)] [double]$PaceMinutesPerKm = 6.0,
    [Parameter(Mandatory)] [string]$MuMuRoot,
    [ValidateRange(0, 2147483647)] [int]$Instance = 0
)

$ErrorActionPreference = 'Stop'
$cli = Join-Path $MuMuRoot 'nx_main/mumu-cli.exe'
$adb = Join-Path $MuMuRoot 'nx_device/15.0/shell/adb.exe'
$vmInfo = & $cli info -v $Instance
if ($LASTEXITCODE -ne 0) { throw '读取 MuMu 实例失败。' }
$vm = ($vmInfo -join "`n") | ConvertFrom-Json
if (!$vm.is_android_started -or !$vm.adb_host_ip -or !$vm.adb_port) {
    throw '请先启动选定的 MuMu Android 15 实例并打开本地 ADB。'
}
$serial = "$($vm.adb_host_ip):$($vm.adb_port)"
$gpsArgs = @('-s', $serial, 'shell', 'dumpsys', 'location')
$invariant = [Globalization.CultureInfo]::InvariantCulture
$metersPerStep = 1000.0 / ($PaceMinutesPerKm * 60.0)
$radians = [Math]::PI / 180.0

<#
Coordinate formulas adapted from AnyDoor v1.4.4, GeoMath.java.
https://github.com/zhaoyuxiangyyds-lab/AnyDoor/blob/5260344a6ce5cf0917578e9dda607dca10d56cfb/app/src/main/java/io/github/zhaoyuxiangyyds_lab/anydoor/GeoMath.java

MIT License
Copyright (c) 2026 zyx

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
#>

function Confirm-Coordinate([double]$Latitude, [double]$Longitude) {
    if ([double]::IsNaN($Latitude) -or [double]::IsNaN($Longitude) -or
        [Math]::Abs($Latitude) -gt 90 -or [Math]::Abs($Longitude) -gt 180) {
        throw '坐标无效或越界。'
    }
    [pscustomobject]@{ Latitude = $Latitude; Longitude = $Longitude }
}

function ConvertTo-Bd09($Point) {
    $lat = $Point.Latitude
    $lon = $Point.Longitude
    if ($lat -ge 0.8293 -and $lat -le 55.8271 -and $lon -ge 72.004 -and $lon -le 137.8347) {
        $pi = [Math]::PI
        $x = $lon - 105.0
        $y = $lat - 35.0
        $sqrtX = [Math]::Sqrt([Math]::Abs($x))
        $common = (20 * [Math]::Sin(6 * $x * $pi) + 20 * [Math]::Sin(2 * $x * $pi)) * 2 / 3
        $dLat = -100 + 2 * $x + 3 * $y + 0.2 * $y * $y + 0.1 * $x * $y + 0.2 * $sqrtX + $common
        $dLat += (20 * [Math]::Sin($y * $pi) + 40 * [Math]::Sin($y * $pi / 3) +
            160 * [Math]::Sin($y * $pi / 12) + 320 * [Math]::Sin($y * $pi / 30)) * 2 / 3
        $dLon = 300 + $x + 2 * $y + 0.1 * $x * $x + 0.1 * $x * $y + 0.1 * $sqrtX + $common
        $dLon += (20 * [Math]::Sin($x * $pi) + 40 * [Math]::Sin($x * $pi / 3) +
            150 * [Math]::Sin($x * $pi / 12) + 300 * [Math]::Sin($x * $pi / 30)) * 2 / 3
        $a = 6378245.0
        $ee = 0.00669342162296594323
        $radLat = $lat * $radians
        $sinLat = [Math]::Sin($radLat)
        $magic = 1 - $ee * $sinLat * $sinLat
        $sqrtMagic = [Math]::Sqrt($magic)
        $lat += $dLat / (($a * (1 - $ee)) / ($magic * $sqrtMagic) * $radians)
        $lon += $dLon / ($a / $sqrtMagic * [Math]::Cos($radLat) * $radians)
    }
    $xPi = [Math]::PI * 3000 / 180
    $z = [Math]::Sqrt($lon * $lon + $lat * $lat) + 0.00002 * [Math]::Sin($lat * $xPi)
    $theta = [Math]::Atan2($lat, $lon) + 0.000003 * [Math]::Cos($lon * $xPi)
    Confirm-Coordinate ($z * [Math]::Sin($theta) + 0.006) ($z * [Math]::Cos($theta) + 0.0065)
}

function Get-Destination($Point, [double]$Bearing) {
    $distance = $metersPerStep / 6371008.8
    $bearingRad = $Bearing * $radians
    $lat = $Point.Latitude * $radians
    $sinLat = [Math]::Sin($lat)
    $cosLat = [Math]::Cos($lat)
    $sinDistance = [Math]::Sin($distance)
    $cosDistance = [Math]::Cos($distance)
    $nextSinLat = $sinLat * $cosDistance + $cosLat * $sinDistance * [Math]::Cos($bearingRad)
    $nextLat = [Math]::Asin([Math]::Max(-1.0, [Math]::Min(1.0, $nextSinLat)))
    $lonChange = [Math]::Atan2([Math]::Sin($bearingRad) * $sinDistance * $cosLat,
        $cosDistance - $sinLat * [Math]::Sin($nextLat)) / $radians
    $nextLon = (($Point.Longitude + $lonChange + 540.0) % 360.0) - 180.0
    Confirm-Coordinate ($nextLat / $radians) $nextLon
}

function Read-GpsFix([string]$Dump) {
    if ($Dump -match '(?m)^[ \t]*gps provider[ \t]+\[mock\][ \t]*:') {
        throw '任意门或其他 App 正接管 GPS。请先在任意门点「停止模拟」。'
    }
    $indent = -1
    $fix = $null
    foreach ($line in ($Dump -split '\r?\n')) {
        if ($line -match '^([ \t]*)gps provider[ \t]*:[ \t]*$') {
            $indent = $Matches[1].Length
            continue
        }
        if ($indent -lt 0 -or [string]::IsNullOrWhiteSpace($line)) { continue }
        if (($line.Length - $line.TrimStart().Length) -le $indent) { break }
        if ($line -match '^[ \t]*enabled=false[ \t]*$') { throw '原生 GPS 提供者已关闭。' }
        if ($line -match '^[ \t]*last location=Location\[gps[ \t]+([+-]?[0-9]+(?:\.[0-9]+)?),([+-]?[0-9]+(?:\.[0-9]+)?)(?:[ \t]|\])') {
            $lat = [double]::Parse($Matches[1], $invariant)
            $lon = [double]::Parse($Matches[2], $invariant)
            if ($line -match '[ \t]mock(?:[ \t]|\])') { throw '当前 GPS 样本来自模拟提供者。' }
            $fix = Confirm-Coordinate $lat $lon
        }
    }
    if ($null -eq $fix) { throw '没有读到原生 GPS 位置。请打开 MuMu 中的 GPSTest 或地图等待定位。' }
    $fix
}

function Start-GpsCommand([string]$Program, [string[]]$Arguments) {
    $process = New-Object Diagnostics.Process
    $process.StartInfo.FileName = $Program
    $process.StartInfo.Arguments = $Arguments -join ' '
    $process.StartInfo.UseShellExecute = $false
    $process.StartInfo.CreateNoWindow = $true
    $process.StartInfo.RedirectStandardOutput = $true
    $process.StartInfo.RedirectStandardError = $true
    try {
        [void]$process.Start()
        [pscustomobject]@{
            Process = $process
            Output = $process.StandardOutput.ReadToEndAsync()
            ErrorOutput = $process.StandardError.ReadToEndAsync()
            Clock = [Diagnostics.Stopwatch]::StartNew()
        }
    } catch {
        $process.Dispose()
        throw
    }
}

function Stop-GpsCommand($Command) {
    if ($null -eq $Command) { return }
    try { if (!$Command.Process.HasExited) { $Command.Process.Kill() } } catch {}
    $Command.Process.Dispose()
}

function Receive-GpsCommand($Command) {
    if (!$Command.Process.HasExited -or !$Command.Output.IsCompleted -or !$Command.ErrorOutput.IsCompleted) {
        if ($Command.Clock.ElapsedMilliseconds -ge 5000) { throw 'MuMu 或 ADB 命令超时。' }
        return
    }
    $reply = $Command.Output.GetAwaiter().GetResult()
    if ($Command.Process.ExitCode -ne 0) {
        $reason = $Command.ErrorOutput.GetAwaiter().GetResult().Trim()
        if (!$reason) { $reason = "退出码 $($Command.Process.ExitCode)" }
        throw "MuMu 或 ADB 命令失败。$reason"
    }
    [pscustomobject]@{ Text = $reply }
}

function Invoke-GpsRead([string]$Program, [string[]]$Arguments) {
    $command = Start-GpsCommand $Program $Arguments
    try {
        do {
            $result = Receive-GpsCommand $command
            if ($null -eq $result) { [Threading.Thread]::Sleep(25) }
        } while ($null -eq $result)
        $result.Text
    } finally { Stop-GpsCommand $command }
}

function Get-CurrentFix {
    Read-GpsFix (Invoke-GpsRead $adb $gpsArgs)
}

if ($CheckOnly) {
    $current = Get-CurrentFix
    $next = Get-Destination $current 0
    [pscustomobject]@{
        Serial = $serial
        Provider = 'gps'
        CurrentWgs84 = $current
        NextNorthWgs84 = $next
        NextNorthBd09 = (ConvertTo-Bd09 $next)
        StepMeters = $metersPerStep
    } | ConvertTo-Json -Depth 3
    return
}

Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
Add-Type -TypeDefinition @'
using System.Runtime.InteropServices;
public static class MuMuGpsKeyState {
    [DllImport("user32.dll")]
    public static extern short GetAsyncKeyState(int key);
}
'@

[Windows.Forms.Application]::EnableVisualStyles()
try { $current = Get-CurrentFix } catch {
    [void][Windows.Forms.MessageBox]::Show($_.Exception.Message, 'MuMu 原生 GPS 方向控制')
    return
}

$form = New-Object Windows.Forms.Form
$form.Text = 'MuMu 原生 GPS 方向控制'
$form.ClientSize = [Drawing.Size]::new(394, 273)
$form.StartPosition = 'Manual'
$workArea = [Windows.Forms.Screen]::PrimaryScreen.WorkingArea
$form.Location = [Drawing.Point]::new($workArea.Left + 24, $workArea.Top + 50)
$form.TopMost = $true
$form.FormBorderStyle = 'FixedDialog'
$form.MaximizeBox = $false
$form.KeyPreview = $true
$form.Font = [Drawing.Font]::new('Microsoft YaHei UI', 9)

function Add-WindowLabel([string]$Text, [int]$Top, [int]$Height) {
    $label = New-Object Windows.Forms.Label
    $label.SetBounds(15, $Top, 364, $Height)
    $label.Text = $Text
    $form.Controls.Add($label)
    $label
}

$helpText = '按住方向键或用鼠标按住按钮，松手即停。' + [Environment]::NewLine +
    "测试配速 $PaceMinutesPerKm 分钟/公里"
[void](Add-WindowLabel $helpText 12 42)
$coordinate = Add-WindowLabel ('当前位置 WGS-84  {0:F6}, {1:F6}' -f $current.Latitude, $current.Longitude) 57 25
$status = Add-WindowLabel '已就绪。' 218 47
$keys = @{ Up = 0; Right = 90; Down = 180; Left = 270 }
$heldKeys = [Collections.Generic.HashSet[int]]::new()
$clock = [Diagnostics.Stopwatch]::StartNew()
$script:move = $null
$script:pending = $null
$script:stage = ''

function Start-Direction([double]$Bearing, [int]$InputCode) {
    if ($null -ne $script:move) { return }
    $script:move = @{ Bearing = $Bearing; InputCode = $InputCode; Point = $null; DueMs = 0 }
    $status.Text = '正在读取 GPS 位置…'
}

function Stop-Direction([string]$Message = '已停止。') {
    if ($null -eq $script:move) { return }
    $script:move = $null
    Stop-GpsCommand $script:pending
    $script:pending = $null
    $status.Text = $Message
}

function Test-Held([int]$Key) { [MuMuGpsKeyState]::GetAsyncKeyState($Key) -lt 0 }

function Set-ArrowInputKey($eventArgs) {
    if ($keys.ContainsKey($eventArgs.KeyCode.ToString())) { $eventArgs.IsInputKey = $true }
}

$form.Add_PreviewKeyDown({ param($sender, $eventArgs) Set-ArrowInputKey $eventArgs })
foreach ($buttonInfo in @(
    @{ Text = '↑ 上'; Left = 165; Top = 84; Bearing = 0 },
    @{ Text = '← 左'; Left = 96; Top = 126; Bearing = 270 },
    @{ Text = '→ 右'; Left = 234; Top = 126; Bearing = 90 },
    @{ Text = '↓ 下'; Left = 165; Top = 168; Bearing = 180 }
)) {
    $button = New-Object Windows.Forms.Button
    $button.SetBounds($buttonInfo.Left, $buttonInfo.Top, 64, 39)
    $button.Text = $buttonInfo.Text
    $button.Tag = $buttonInfo.Bearing
    $button.Add_PreviewKeyDown({ param($sender, $eventArgs) Set-ArrowInputKey $eventArgs })
    $button.Add_MouseDown({
        param($sender, $eventArgs)
        if ($eventArgs.Button -eq [Windows.Forms.MouseButtons]::Left) {
            Start-Direction ([double]$sender.Tag) 1
        }
    })
    $button.Add_MouseUp({
        param($sender, $eventArgs)
        if ($script:move.InputCode -eq 1 -and $eventArgs.Button -eq [Windows.Forms.MouseButtons]::Left) { Stop-Direction }
    })
    $button.Add_MouseCaptureChanged({
        param($sender, $eventArgs)
        if (!$sender.Capture -and $script:move.InputCode -eq 1) { Stop-Direction }
    })
    $form.Controls.Add($button)
}

$form.Add_KeyDown({
    param($sender, $eventArgs)
    $direction = $eventArgs.KeyCode.ToString()
    if (!$keys.ContainsKey($direction) -or $eventArgs.Alt -or $eventArgs.Control) { return }
    $eventArgs.Handled = $true
    if ($heldKeys.Add($eventArgs.KeyValue)) { Start-Direction $keys[$direction] $eventArgs.KeyValue }
})
$form.Add_KeyUp({
    param($sender, $eventArgs)
    [void]$heldKeys.Remove($eventArgs.KeyValue)
    if ($script:move.InputCode -eq $eventArgs.KeyValue) { Stop-Direction }
})
$form.Add_Deactivate({ Stop-Direction })

$timer = New-Object Windows.Forms.Timer
$timer.Interval = 50
$timer.Add_Tick({
    foreach ($key in @($heldKeys)) {
        if (!(Test-Held $key)) { [void]$heldKeys.Remove($key) }
    }
    if ($null -eq $script:move) { return }
    if (!$form.ContainsFocus -or !(Test-Held $script:move.InputCode)) { Stop-Direction; return }
    try {
        if ($null -eq $script:pending) {
            if ($clock.ElapsedMilliseconds -lt $script:move.DueMs) { return }
            $script:move.DueMs = $clock.ElapsedMilliseconds + 1000
            $script:pending = Start-GpsCommand $adb $gpsArgs
            $script:stage = 'read'
            return
        }

        $reply = Receive-GpsCommand $script:pending
        if ($null -eq $reply) { return }
        Stop-GpsCommand $script:pending
        $script:pending = $null
        if ($script:stage -eq 'read') {
            $fix = Read-GpsFix $reply.Text
            if ($null -eq $script:move.Point) { $script:move.Point = $fix }
            $target = Get-Destination $script:move.Point $script:move.Bearing
            $bd = ConvertTo-Bd09 $target
            if (!$form.ContainsFocus -or !(Test-Held $script:move.InputCode)) { Stop-Direction; return }
            $lat = $bd.Latitude.ToString('R', $invariant)
            $lon = $bd.Longitude.ToString('R', $invariant)
            $script:move.Target = $target
            $script:pending = Start-GpsCommand $cli @('control', '-v', [string]$Instance, 'tool', 'location', '--longitude', $lon, '--latitude', $lat)
            $script:stage = 'write'
        } else {
            $result = $reply.Text | ConvertFrom-Json
            if ($null -eq $result.errcode -or $result.errcode -ne 0) { throw "MuMu 定位失败。$($result.errmsg)" }
            $script:move.Point = $script:move.Target
            $coordinate.Text = '已下发 WGS-84  {0:F6}, {1:F6}' -f $script:move.Point.Latitude, $script:move.Point.Longitude
            $status.Text = '正在移动，松手或切换窗口即停。'
        }
    } catch { Stop-Direction $_.Exception.Message }
})
$form.Add_FormClosing({ $timer.Stop(); Stop-Direction })

try {
    $timer.Start()
    [void]$form.ShowDialog()
} finally {
    Stop-Direction
    $timer.Dispose()
    $form.Dispose()
}
