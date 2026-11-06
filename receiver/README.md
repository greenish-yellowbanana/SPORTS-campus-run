# 计步接收测试

Package: `io.codex.stepviewer`.

Pure Android Java Activity using standard `SensorManager` APIs. No network, root, Xposed code, fabricated values, provider access, or connection to any third-party app. It displays sensor names/vendors, registration results, raw cumulative count, counter callback count, detector callback count, and latest monotonic sensor timestamp. The missing-hardware baseline reads “未发现计步传感器”. It requests `ACTIVITY_RECOGNITION` on Android 10+.

Compile `src/io/codex/stepviewer/MainActivity.java` against Android API 35; package with the included manifest. App uses only platform widgets and a platform theme, so no resource compilation beyond manifest linking is required. Min SDK 27, target SDK 35.

For cross-app validation, capture a baseline before enabling the synthetic-sensor module for this package, then explicitly add `io.codex.stepviewer` to the test module scope and relaunch. Starting listener should return real SensorManager registration results. Keep in foreground while testing; listener unregisters when backgrounded and resumes on return if requested.
