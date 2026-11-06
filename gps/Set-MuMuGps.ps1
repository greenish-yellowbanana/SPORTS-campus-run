[CmdletBinding()]
param(
    [Parameter(Mandatory)] [ValidateRange(-90, 90)] [double]$BD09Latitude,
    [Parameter(Mandatory)] [ValidateRange(-180, 180)] [double]$BD09Longitude,
    [Parameter(Mandatory)] [string]$MuMuRoot,
    [ValidateRange(0, 2147483647)] [int]$Instance = 0
)

$ErrorActionPreference = 'Stop'
$mumuGpsCli = Join-Path $MuMuRoot 'nx_main/mumu-cli.exe'
$mumuGpsAdb = Join-Path $MuMuRoot 'nx_device/15.0/shell/adb.exe'

$mumuGpsInfo = & $mumuGpsCli info -v $Instance
if ($LASTEXITCODE -ne 0) { throw '读取 MuMu 实例失败。' }
$mumuGpsVm = ($mumuGpsInfo -join "`n") | ConvertFrom-Json
if (!$mumuGpsVm.is_android_started -or !$mumuGpsVm.adb_host_ip -or !$mumuGpsVm.adb_port) {
    throw '请先启动选定的 MuMu Android 15 实例并打开本地 ADB。'
}
$mumuGpsSerial = "$($mumuGpsVm.adb_host_ip):$($mumuGpsVm.adb_port)"

$mumuGpsState = & $mumuGpsAdb -s $mumuGpsSerial shell dumpsys location
if ($LASTEXITCODE -ne 0) { throw '读取 MuMu 的定位服务失败。' }
if ($mumuGpsState | Select-String '^\s+gps provider \[mock\]:' -Quiet) {
    throw '当前有 App 接管 GPS 定位源。请在任意门点「停止模拟」后再运行。'
}

# MuMu location command pattern follows pymumu Control.tool_location.
# The installed MuMu version was measured with BD-09 inputs.
$mumuGpsCulture = [Globalization.CultureInfo]::InvariantCulture
$mumuGpsLat = $BD09Latitude.ToString('R', $mumuGpsCulture)
$mumuGpsLon = $BD09Longitude.ToString('R', $mumuGpsCulture)
$mumuGpsReply = & $mumuGpsCli control -v $Instance tool location --longitude $mumuGpsLon --latitude $mumuGpsLat
if ($LASTEXITCODE -ne 0) { throw 'MuMu 原生定位命令执行失败。' }
$mumuGpsResult = ($mumuGpsReply -join "`n") | ConvertFrom-Json
if ($mumuGpsResult.errcode -ne 0) { throw "MuMu 定位失败 $($mumuGpsResult.errmsg)" }

Write-Output "已给 MuMu 实例 $Instance 设置原生定位，BD-09 $mumuGpsLat, $mumuGpsLon。"
