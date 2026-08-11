# uvcpad 开发旅程（journey）

## M1 骨架整合（2026-08-11）

**阶段 6 编码（coder）完成。** 严格按 DESIGN.md 实施，复用两项目真实源码。

### 关键决策与实施记录

1. **工程骨架**：照抄 hdmi2mp/android 的 gradle 配置（settings/build/gradle.properties/wrapper/local.properties/.gitignore），
   app/build.gradle 改 namespace/applicationId=com.github.ifeel.uvcpad、minSdk 23→28、jvmTarget 17（对齐 KeysJoy 源码，DESIGN §1.2）。
2. **显示链路**：getCameraRequest/getCameraView/getCameraViewContainer/getGravity/onCameraState/串行权限/USB 提示/hideSystemUi 从 hdmi2mp 原样保留。
3. **蓝牙链路**：BluetoothController 复制 + DESIGN §4.2 三处改造（描述符 MOUSE_RELATIVE_WITH_SCROLL、SUBCLASS1_MOUSE、设备名 "uvcpad" + 提示串 "Search 'uvcpad'…"）；
   DescriptorCollection 仅保留 MOUSE_RELATIVE_WITH_SCROLL + MOUSE_RELATIVE_WITH_SCROLL_NOTSMOOTH（字节逐字提取，diff 验证）；
   reports/senders/listeners/SpeedLevel 原样复制（仅包名/import 改）。
4. **透明触控层**：新建 TransparentTouchLayer（不绘制、onTouchEvent 转发 ViewListener）；setupTouchLayer/teardownTouchLayer 按 DESIGN §3.5/§3.7 装配。
5. **联调自测**：`:app:assembleDebug` 成功（APK 20MB，minSdk 28/targetSdk 36 校验通过）；无真机，做静态自检（§3.5 装配逐行核对 / 红线扫描无键盘代码 / §4.2 改造点核对）。

### M1 适应性改动（均已在 MainActivity 注释与报告说明）

- **改造点① toolbar 删除**：btnMode1080p/btnMode4by3/btnCapture/btnExit/topOverlay 及其自动隐藏计时器整体删除（M1 触控板不放可点击按钮）。
  `switchMode` 因 toolbar 删除被迫去掉 `button: TextView` 参数与 `highlightMode()`（M2 按键栏按钮将直接调用 switchMode）。
- **statusText 移除**：DESIGN §3.1 最终布局无独立 statusText（状态并入 M2 按键栏），M1 布局仅 cameraViewContainer + touchLayer + errorText；
  `onCameraState OPENED` 改为 toast 呈现连接状态，ERROR 走 errorText。
- **setupListeners 留白**：M1 无按键栏，蓝牙装配放在 onStart 回调（KeysJoy 模式，DESIGN §3.7），M2 再引入 setupListeners 接按键栏。
- **setScanMode 编译适配**：compileSdk 36 的 android.jar 不含隐藏 API `BluetoothAdapter.setScanMode`，改用反射（与 KeysJoy SelectDeviceActivity "Make Discoverable" 同一模式），行为不变。
- **CRLF 修正**：KeysJoy 部分源码文件为 CRLF 行尾，初次 sed 包名替换漏掉 ScrollableTrackpadMouseReport.kt，构建报 Unresolved reference 后已用 perl 修正。

### 构建结果

- `./gradlew :app:assembleDebug` → **BUILD SUCCESSFUL**（35 tasks；仅 KeysJoy 原样代码的 deprecation 警告：getDefaultAdapter/GestureDetectorCompat/VIBRATOR_SERVICE/inline class）。
- APK：app/build/outputs/apk/debug/app-debug.apk（20,434,126 B；classes×9 + libUVCCamera/libuvc/libusb/libjpeg-turbo 原生库）。

### M1 遗留问题

- **push 未执行**：github 仓库 iFeel-is-a-mouse/uvcpad 尚不存在（hdmi2mp/KeysJoy 均为独立仓库模式），remote 创建后补 push。
- **真机联调未做**：需 MS2130 采集卡 + PC 蓝牙配对环境（todo.md 已列验收项）。
