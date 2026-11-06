# SPORTS 解决方法（校园跑）

在 Windows 的 MuMu Android 15 上验证 Android 计步和 GPS 定位接口。包含虚拟计步模块、独立接收端、GPS 时间戳适配和 MuMu 原生定位测试脚本。

## 免责声明

本项目仅用于自有或获授权的 Android 测试环境。模拟步数、坐标和轨迹不代表真实运动。请勿将模拟结果当作真实记录提交，或用于课程成绩、考勤及其他考核。使用时请遵守学校和应用平台的规则。项目按现状提供，适用性取决于设备、系统版本和测试配置。

## 已验证的范围

| 项目 | MuMu Android 15 上的结果 |
| --- | --- |
| 计步接收 | 独立接收端收到累计步数和逐步事件。暂停后数值停止增长，重启后累计归零。 |
| 原生定位 | 原生 GPS 提供位置和卫星状态。这个 MuMu 版本的命令输入为 BD-09，Android GPS 样本为 WGS-84。 |
| GPS 时间戳 | 某些样本把开机时长写进日期字段。适配器只修正与开机时长吻合的异常值，GPSTest 读数恢复为当前日期。 |
| 点位识别 | 离线样例识别到五个标记，读出 4、5 两个编号。其余标记为勾，保留为未知。没有实现自动路线。 |

计步通过目标进程的 Java SensorManager 接口提供测试值。它覆盖的范围取决于模块作用域和应用使用的传感器接口。测试时以独立接收端和 GPSTest 的可观测结果为准。

## 构建和计步测试

需要 JDK 17、Android SDK Platform 35、Build-Tools 35 和 Xposed legacy API 82 的 JAR。Xposed API 仅用于编译。若只有 JRE 17，可额外传入 `-EcjJar`，使用 Eclipse ECJ 3.38.0。

```powershell
.\build.ps1 -JdkDir '<JDK 目录>' -BuildToolsDir '<Build-Tools 目录>' -AndroidJar '<android.jar>' -XposedApi '<api-82.jar>'
```

1. 将生成在 `dist` 下的 `VirtualSteps-1.1.apk` 和 `StepViewer-1.0.apk` 装到测试实例。
2. 在已配置的 Vector 或兼容 Xposed 框架启用「模拟计步测试」，勾选「计步接收测试」。重开接收端，授予身体活动权限。
3. 在控制端点击「开始模拟计步」，在接收端点击「开始监听」。完成后在控制端暂停。

构建脚本生成本地测试签名，后续覆盖安装需要使用同一密钥。两个 APK 的实现分别见 [计步模块](module) 和 [独立接收端](receiver)。

## GPS 测试

启动 MuMu Android 15，打开本地 ADB 并连接实例。将 `-MuMuRoot` 指向包含 `nx_main` 和 `nx_device` 的 MuMu 安装目录。以下坐标为演示输入。

```powershell
.\gps\Set-MuMuGps.ps1 -MuMuRoot '<MuMu 安装目录>' -Instance 0 -BD09Latitude 39.915 -BD09Longitude 116.404
.\gps\MuMu-GPS-Direction.ps1 -MuMuRoot '<MuMu 安装目录>' -Instance 0 -CheckOnly
```

`Set-MuMuGps.ps1` 设置一个测试位置。方向工具加 `-CheckOnly` 只读取和检查位置，移除此参数会打开方向控制窗口。测试时用 GPSTest 查看位置、卫星状态和日期。验证 GPS 时钟适配时，在模块作用域中勾选 GPSTest 的 osmdroid 版本并重开应用。

## 第三方参考

- GPS 样本检查使用 [GPSTest 3.10.6](https://github.com/barbeau/gpstest/releases/tag/v3.10.6)。
- MuMu 定位命令参考 [pymumu](https://github.com/rhuda21/pymumu)。方向脚本的坐标转换公式参考 [AnyDoor 1.4.4](https://github.com/zhaoyuxiangyyds-lab/AnyDoor/tree/v1.4.4)，脚本中保留了 MIT 许可和版权声明。
- 离线点位识别借鉴 [MaaCampusRun](https://github.com/KJH50/maa-campus-run/tree/7b10f7b32f24e45fa775b6f1d61725b97d3ad4dd) 的 OCR 工作流，路线预览调研参考 [FunSportWorld](https://github.com/Funrestan/FunSportWorld/tree/41359e518763e1cb5e5731249323d44378f8a124)。
