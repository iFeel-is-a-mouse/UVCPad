# uvcpad 技术设计（M1 骨架整合）

> 文档性质：M1 技术设计，基于两个存量项目真实源码（hdmi2mp / KeysJoy）的整合设计。
> 状态：✅ **M1 已完成**（含触控区域=显示区域需求落地），编码+构建通过，待真机联调；**M2 交互入口已实现（v0.2.x 迭代中）**。
> 关联文档：`docs/PROPOSAL.md`（需求理解，Q2/Q3 已确认：纯触控板、横屏唯一形态）；`docs/todo.md`（M2 待办清单）；`docs/journey.md`（开发历程）。

---

## 0. 设计目标与边界

**一句话：** 单 App 内同时承载「MS2130 采集卡画面显示」（复用 hdmi2mp 链路）与「全屏透明触控板 → 蓝牙 HID 鼠标」（复用 KeysJoy 链路），两条链路并行、互不阻塞，视觉上通过透明触控层合成。

**需求边界（来自 PROPOSAL 已确认决策）：**
- 纯触控板，**不含键盘**：BluetoothHidDevice 仅注册鼠标 HID 描述符，不注册/不使用键盘报告。
- 横屏唯一形态（`sensorLandscape` + `configChanges`，不做横竖切换）。
- 全屏透明触控板 + 顶部下拉三角（唯一常驻 UI）；三角点击唤出**自动隐藏按键栏**（4s 默认，可配置），按键栏合并 KeysJoy/hdmi2mp 菜单，**不含键盘设置项**。

**设计红线（来自 AGENTS.md）：**
- 触控板上不放置任何可点击按钮（点击会被识别为 tap→左键）；所有功能入口走下拉三角。
- 跨模块数据变换（触摸坐标 → HID 报告字节）必须给出输入→输出示例（见 §6.4）。
- 三角区域与按键栏区域的事件豁免（不穿透进触控手势层）是 M2 验收关键项。

---

## 1. 工程骨架

### 1.1 目录结构

```
projects/uvcpad/
├── docs/                          # PROPOSAL.md / DESIGN.md / todo.md / journey.md
├── android/                       # 新 Android 工程（参考 hdmi2mp/android 结构）
│   ├── settings.gradle            # 复制 hdmi2mp（阿里云镜像 + google + mavenCentral + jitpack）
│   ├── build.gradle               # AGP 8.10.1 + Kotlin 2.2.10（与 hdmi2mp 一致，已带 AUSBC 3.6.0 构建验证）
│   ├── gradle/wrapper/…           # Gradle 9.3.1（与 hdmi2mp 一致）
│   └── app/
│       ├── build.gradle
│       └── src/main/
│           ├── AndroidManifest.xml
│           ├── java/com/github/ifeel/uvcpad/
│           │   ├── UvcpadApplication.kt        # 复制 hdmi2mp Hdmi2mpApplication + CrashHandler 注册
│           │   ├── CrashHandler.kt             # 原样复制 hdmi2mp
│           │   ├── MainActivity.kt             # 改造自 hdmi2mp MainActivity（详见 §2/§3）
│           │   ├── touch/TransparentTouchLayer.kt   # 新建：全屏透明触控层
│           │   ├── ui/DropTriangleView.kt      # 新建：下拉三角（事件豁免区）
│           │   ├── ui/KeyBarPanel.kt           # 新建：按键栏（合并菜单）
│           │   ├── ui/KeyBarController.kt      # 新建：按键栏显隐/自动隐藏（改造自 hdmi2mp 工具栏计时逻辑）
│           │   ├── bt/BluetoothController.kt   # 复制 KeysJoy + 改造（描述符/设备名，见 §4.2）
│           │   ├── bt/DescriptorCollection.kt  # 复制 KeysJoy + 裁剪（仅保留鼠标描述符）
│           │   ├── bt/SpeedLevel.kt            # 原样复制 KeysJoy
│           │   ├── bt/reports/ScrollableTrackpadMouseReport.kt  # 原样复制
│           │   ├── bt/reports/FeatureReport.kt                  # 原样复制
│           │   ├── bt/senders/RelativeMouseSender.kt            # 原样复制
│           │   ├── bt/listeners/ViewListener.kt                 # 原样复制（手势引擎）
│           │   └── UvcpadPrefs.kt              # 新建：SharedPreferences 封装（speed_level/auto_pair/screen_on/auto_hide_ms）
│           └── res/
│               ├── layout/activity_main.xml    # 改造自 hdmi2mp（加触控层/三角/按键栏）
│               ├── xml/device_filter.xml       # 原样复制 hdmi2mp（UVC 类过滤 239/2, 14/1, 14/2）
│               ├── values/{strings,colors,themes}.xml  # 复制 hdmi2mp 并增补
│               └── drawable/…                 # 复制 hdmi2mp（bg_btn/ic_launcher）+ 三角/按键栏资源
```

### 1.2 Gradle 依赖清单（app/build.gradle 要点）

```groovy
plugins {
    id 'com.android.application'
    id 'org.jetbrains.kotlin.android'
}
android {
    namespace 'com.github.ifeel.uvcpad'
    compileSdk 36                     // 与 hdmi2mp 一致
    defaultConfig {
        applicationId "com.github.ifeel.uvcpad"
        minSdk 28                     // 决策 Q5：取 KeysJoy 的 minSdk 28（任务约束）
        targetSdk 36
    }
    compileOptions { sourceCompatibility JavaVersion.VERSION_17; targetCompatibility JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }   // KeysJoy 用 17；hdmi2mp 用 1.8，均可，选 17 对齐 KeysJoy 源码
    buildTypes {
        release { minifyEnabled true; proguardFiles … }  // 注意保留 BluetoothHidDevice 回调类
    }
    splits { abi { /* 照抄 hdmi2mp：release 仅 arm64 */ } }
}
dependencies {
    implementation 'androidx.core:core-ktx:1.18.0'
    implementation 'androidx.appcompat:appcompat:1.7.1'
    implementation 'com.google.android.material:material:1.13.0'
    implementation 'androidx.constraintlayout:constraintlayout:2.2.1'
    implementation 'com.github.ernestp.AndroidUSBCamera:libausbc:3.6.0'   // JitPack，与 hdmi2mp 同版本
}
```

> minSdk 28 下 multidex 原生支持，KeysJoy 的 `MultiDexApplication`/multidex 依赖**不需要**（KeysJoy 保留它是历史原因）。

### 1.3 Manifest 关键配置（合并 hdmi2mp + KeysJoy 权限面）

```xml
<!-- 显示链路：USB/UVC + 相机（hdmi2mp 原样） -->
<uses-permission android:name="android.permission.USB_PERMISSION" />
<uses-feature android:name="android.hardware.usb.host" android:required="true" />
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" android:maxSdkVersion="28" />

<!-- 触控链路：蓝牙 HID（KeysJoy 原样） -->
<uses-permission android:name="android.permission.BLUETOOTH" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />  <!-- API≤30 发现设备；Android 12/12L（API 31–32）若未声明 neverForLocation，则 BLUETOOTH_SCAN 也依赖定位权限；目标 S+ 且无定位需求时可声明 android:neverForLocation="true" 豁免 -->
<uses-feature android:name="android.hardware.bluetooth" android:required="true" />
<uses-permission android:name="android.permission.VIBRATE" />     <!-- 手势触觉反馈（ViewListener 使用） -->
<uses-permission android:name="android.permission.WAKE_LOCK" />   <!-- 常亮（FLAG_KEEP_SCREEN_ON） -->

<application android:name=".UvcpadApplication" android:largeHeap="true" …>
    <activity android:name=".MainActivity"
        android:configChanges="orientation|screenSize|keyboardHidden"
        android:launchMode="singleTask"
        android:screenOrientation="sensorLandscape"   <!-- 横屏唯一形态 -->
        android:exported="true">
        <intent-filter> MAIN / LAUNCHER </intent-filter>
        <intent-filter> android.hardware.usb.action.USB_DEVICE_ATTACHED </intent-filter>
        <meta-data android:name="android.hardware.usb.action.USB_DEVICE_ATTACHED"
            android:resource="@xml/device_filter" />
    </activity>
</application>
```

> **不引入** KeysJoy 的 `FOREGROUND_SERVICE`（两项目均未真正使用前台服务）、`sensor.gyroscope required`（SensorSender 传感器鼠标不复用）。

### 1.4 运行时权限（串行，避免弹窗叠加）

复制 hdmi2mp `MainActivity.requestPermissionsSequentially()` 的**串行模式**（相机结果回调里再请求下一个），权限序列扩展为：

```
CAMERA（UVC 必需）→ BLUETOOTH_CONNECT/BLUETOOTH_SCAN（S+，合并请求，模式见 KeysJoy SplashScreenActivity）
→ ACCESS_COARSE_LOCATION（API≤30 发现设备）→ WRITE_EXTERNAL_STORAGE（仅 23–28，截图导入图库）
```

---

## 2. 分层架构

### 2.1 层与类职责

```
┌─ UI 层 ─────────────────────────────────────────────────────────────┐
│ MainActivity (: CameraActivity)        生命周期宿主/装配/权限/状态    │
│ ├─ DropTriangleView（新建）             顶部下拉三角，事件豁免区       │
│ ├─ KeyBarPanel（新建）+ KeyBarController  按键栏 + 4s 自动隐藏        │
│ └─ errorText / statusText               错误与状态显示（复用 hdmi2mp）│
├─ 触控层 ─────────────────────────────────────────────────────────────┤
│ TransparentTouchLayer（新建，全屏透明 View，不绘制）                   │
│ └─ ViewListener（复制 KeysJoy）        手势识别引擎（onTouch 入口）    │
│    └─ RelativeMouseSender（复制）       手势 → 7 字节 HID 报告发送     │
│       └─ ScrollableTrackpadMouseReport（复制） ID=4 报告结构          │
├─ 显示层 ─────────────────────────────────────────────────────────────┤
│ cameraViewContainer（FrameLayout）                                    │
│ └─ AspectRatioTextureView（AUSBC 3.6.0）  OPENGL 渲染采集画面         │
│    └─ CameraActivity 基类（AUSBC）        UVC 采集会话管理            │
├─ 基础层 ─────────────────────────────────────────────────────────────┤
│ BluetoothController（复制+改造）        HID 注册/连接/自动重连/多设备  │
│ DescriptorCollection（复制+裁剪）        鼠标专用 HID 描述符          │
│ SpeedLevel / FeatureReport / UvcpadPrefs                              │
│ CrashHandler（复制）                    全局崩溃自捕获                │
└──────────────────────────────────────────────────────────────────────┘
```

### 2.2 类职责明细

| 类 | 来源 | 职责 | 关键入口 |
|---|---|---|---|
| `MainActivity` | 改造 hdmi2mp `MainActivity.kt` | 继承 `CameraActivity`；权限串行；USB 授权提示；蓝牙连接/断开装配；按键栏菜单动作；状态/错误显示 | `getCameraRequest()`、`getCameraView()`、`getRootView()`、`onCameraState()`、`switchMode()`、`captureJpg()` |
| `TransparentTouchLayer` | 新建 | 全屏透明 View；`onTouchEvent` 转发给 `ViewListener` 并返回 true；不绘制任何内容 | `setGestureListener(l: ViewListener)` |
| `DropTriangleView` | 新建 | 顶部小三角；触摸豁免（DOWN 消费，UP 判定 toggle）；视觉仅一个小三角图形 | `onToggle: (() -> Unit)?` |
| `KeyBarPanel` + `KeyBarController` | 新建（显隐逻辑改造自 hdmi2mp 工具栏） | 顶部滑出按键栏：速度/蓝牙/自动配对/分辨率/截图/退出；区域事件消费；4s 无操作自动隐藏 | `show()/hide()`、`resetAutoHideTimer()` |
| `BluetoothController` | 复制 KeysJoy + 改造 | HID 注册（仅鼠标）、连接/断开回调、5s 自动重连、多设备切换、FeatureReport 应答 | `init(ctx)`、`getSender{}`、`getDisconnector{}`、`switchTo()` |
| `ViewListener` | 原样复制 KeysJoy | 全手势引擎：单指移动/单击/双击拖拽/长按拖拽/双指滚动/双指右键/pinch | `onTouch(v, event)` |
| `RelativeMouseSender` | 原样复制 KeysJoy | 相对位移 ±2047 钳制、亚像素累积、按钮序列、滚轮 | `sendMouseMove/sendScroll/sendTestClick/...` |
| `ScrollableTrackpadMouseReport` | 原样复制 | ID=4 7 字节报告（按钮2bit+pad6 + dx16 + dy16 + vScroll8 + hScroll8） | `bytes` |
| `FeatureReport` | 原样复制 | ID=6，轮子分辨率倍率应答 | `wheelResolutionMultiplier` |
| `SpeedLevel` | 原样复制 | 5 档速度预设（0.4/0.6/0.8/1.0/1.2） | `LEVELS/DEFAULT/forLevel()` |
| `UvcpadApplication` | 改造 hdmi2mp | 注册 `CrashHandler` | `onCreate` |
| `CrashHandler` | 原样复制 hdmi2mp | 全局异常捕获 + 全屏崩溃对话框 | `install/setActiveActivity` |

### 2.3 线程模型（分离，互不阻塞）

```
┌─ 主线程（UI）─────────────────────────────────────────────────┐
│ 触摸事件（TouchLayer.onTouchEvent）→ ViewListener 手势处理     │
│ → RelativeMouseSender.sendReport（蓝牙 binder 异步，不阻塞）   │
│ Handler(Looper.getMainLooper())：                             │
│   · 单击/双击判定定时器（ViewListener 内 tapChecker/dragHandler）│
│   · onDown 4 连发 wake 报告（ViewListener 内 wakeHandler）     │
│   · 按键栏自动隐藏计时（KeyBarController，hdmi2mp 模式）        │
├─ AUSBC 内部线程（UVC 捕获 / OPENGL 渲染 / NV21 回调）──────────┤
│   CameraActivity 自带；状态/截图回调 → runOnUiThread 上抛       │
├─ 蓝牙 binder 线程─────────────────────────────────────────────┤
│   onConnectionStateChanged / onAppStatusChanged / onGetReport  │
│   → deviceListener 回调 → runOnUiThread 装配/拆卸              │
├─ 后台 Thread（截图 MediaStore 导入，hdmi2mp 模式）──────────────┤
└──────────────────────────────────────────────────────────────┘
```

**关键结论（源码依据）：**
- 触摸→HID 全链路在**主线程**（KeysJoy `ViewListener.onTouch` 直连 `sendReport`），`BluetoothHidDevice.sendReport()` 是异步 binder 调用，KeysJoy 已生产验证此模型可行。
- UVC 渲染在 **AUSBC 内部 GL 线程**，与主线程解耦 → 渲染卡顿不会阻塞触摸，触摸洪水不会阻塞渲染（§6.3 风险仍要监控主线程负载）。
- 两条链路**无共享可变状态**：显示链路只依赖 USB，触控链路只依赖蓝牙，无资源竞争（PROPOSAL §10.4 假设成立）。

---

## 3. 布局与关键机制设计

### 3.1 布局结构（activity_main.xml，改造自 hdmi2mp）

```xml
<FrameLayout id=rootLayout>                       <!-- 复制 hdmi2mp -->
    <FrameLayout id=cameraViewContainer/>         <!-- 底层：AUSBC 自动注入 AspectRatioTextureView -->
    <com…touch.TransparentTouchLayer id=touchLayer/>  <!-- 中层：触控层，运行时对齐到显示区域（uvcpad-touch-align），不绘制 -->
    <FrameLayout id=topUiContainer>               <!-- 顶层：三角 + 按键栏 -->
        <com…ui.DropTriangleView id=dropTriangle/>    <!-- 顶部居中，事件豁免区 -->
        <com…ui.KeyBarPanel id=keyBar/>               <!-- 顶部滑出，默认 GONE，区域事件消费 -->
    </FrameLayout>
    <TextView id=errorText/>                      <!-- 复用 hdmi2mp，marginTop 移到三角下方 -->
</FrameLayout>
```

**触控层边界（uvcpad-touch-align，2026-08-12）：** `touchLayer` 在 XML 中保持全屏占位，但运行时
被 MainActivity 收缩到**采集画面实际显示矩形**（= `AspectRatioTextureView` 的布局 bounds，见 §3.2 新节）；
触控层是 `cameraViewContainer` 的**兄弟节点**而非子节点——AUSBC `initView()` 会对容器执行
`removeAllViews()`，放进容器的 XML 子 View 会被清掉（AUSBC 3.6.0 源码确认）。

**Z 序与触摸分发依据（Android 事件模型）：**
- 触摸优先派发给**顶层可见 View**：三角/按键栏在 touchLayer 之上 → 落在其边界内的事件先到它们。
- 事件流**归属权由 ACTION_DOWN 的目标 View 决定**：手指从触控层滑入三角区域，事件流仍归触控层（三角不会中途截胡）；从三角滑出同理归三角。→ 豁免区实现无需 `onInterceptTouchEvent`，只需各 View 正确处理自己拿到的事件流。
- **显示区域外的触摸**（黑边/留白）：落在非 clickable 的 `cameraViewContainer` 上 → 框架直接丢弃，不产生任何 HID 事件（§3.2 新节）。

### 3.2 透明触控层（不挡画面、只收事件）

- `TransparentTouchLayer : View`，`setBackgroundColor(Color.TRANSPARENT)`（或 null background），**onDraw 不绘制** → 视觉上完全透出底层采集画面。
- 接收事件条件：`isEnabled=true` 且 `onTouchEvent` 返回 `true`（或设置 OnTouchListener 返回 true）；不做 `setClickable` 之外的额外状态，避免焦点/高亮。
- `MainActivity` 在蓝牙连接成功后 `touchLayer.setGestureListener(viewListener)`；断开时置 null 并清空内部状态（防止向 null sender 发报告，见 §3.6）。
- **触控板上不放任何可点击控件**（PROPOSAL §4.6）：全屏即手势区，点击 = tap→左键。

### 3.2.1 触控区域 = 显示区域（uvcpad-touch-align，2026-08-12）

**需求（iFeel 确认）：** 触控层不再铺满全屏，只覆盖采集画面实际显示的区域；显示区域外
（黑边/留白）的触摸不响应、不产生任何 HID 鼠标事件；显示区域内触摸 → 正常手势（ViewListener 链路不变）。

**方案：A（触控层布局 bounds 动态跟随显示区域）**，选它基于以下 AUSBC 3.6.0 源码事实：

| 事实 | 源码位置 | 结论 |
|---|---|---|
| `AspectRatioTextureView.onMeasure` 按 `mAspectRatio`（= 视频宽高比）**fit-inside 自缩放**：取容器尺寸，超出比例的维度被缩掉，视图自身 bounds 精确等于画面比例 | libausbc widget 字节码 | **显示区域 = 相机视图的布局 bounds**，无需再按宽高比手工推算 |
| `CameraActivity.initView()`：`container.removeAllViews()` + `addView(cameraView, LayoutParams(MATCH_PARENT, MATCH_PARENT, getGravity()))`；uvcpad `getGravity() = Gravity.CENTER` | CameraActivity 字节码 | 黑边落在容器上（视图居中）；**触控层不能放进容器**（会被 removeAllViews 清掉），只能做兄弟节点 |
| `CameraClient` 在预览尺寸确定/`updateResolution()` 后调 `setAspectRatio(实际W, 实际H)` → `post { requestLayout() }` | CameraClient 字节码 | 分辨率切换（switchMode）会重新测量视图 → 布局变化 → 触控层同步点天然存在 |
| `BaseActivity.onCreate`：`setContentView(getRootView())` → `initView()`（加入相机视图）早于 `MainActivity.onCreate` 的 `bindViews()` | BaseActivity 字节码 | 布局监听注册时相机视图已在容器中（`getChildAt(0)` 可用） |

**机制：**
- `touchLayer` 保持 rootLayout 直接子 View（Z 序在 cameraViewContainer 之上），XML 全屏占位。
- `MainActivity.bindViews()` 给 `cameraViewContainer` 注册 `OnGlobalLayoutListener`；每次布局变化调
  `syncTouchLayerBounds()`：取 `getChildAt(0)`（相机视图）的窗口坐标 − rootLayout 窗口坐标 →
  `touchLayer.alignToDisplayRect(rect)` 写入 LayoutParams（leftMargin/topMargin + 精确 width/height）；
  值未变化时直接返回（无谓重布局退化为空操作）。
- `alignToDisplayRect` 是 `TransparentTouchLayer` 的公开方法（本需求唯一新增 API）。

**同步触发点（显示区域变化的全部路径）：**
1. **首次对齐（相机打开后）**：`bindViews()` 在 `super.onCreate()`（含 setContentView → 首布局完成）之后才注册
   `OnGlobalLayoutListener`，因此监听注册后，首次对齐实际在**相机打开 → `setAspectRatio` → `requestLayout`** 的
   布局回调中完成；启动到相机打开之间的窗口期触控层保持 XML 全屏 match_parent（该窗口期通常无画面无交互，
   风险可忽略）；
2. **分辨率切换** `switchMode → updateResolution`：预览重启 → `setAspectRatio(新比例)` → `requestLayout`
   → 相机视图重新测量 → 布局监听触发；
3. **旋转/配置变更**：Activity 重建 → `bindViews` 重新注册监听 → 首布局即对齐；
4. 相机视图未布局/不存在（尺寸 0）→ 触控层退化为 0×0，任何触摸落不到本层（保守兜底）。

**边界处理：**
- **显示区域外触摸**（黑边/留白）：命中测试落在非 clickable 的 `cameraViewContainer` → 事件被框架丢弃，
  不产生任何 HID 事件（需求核心，方案 A 语义最干净——区域外根本不进触控层）；
- **滑出区域的手势连续性**：Android 事件归属由 ACTION_DOWN 决定——DOWN 落在显示区域内 → 整个事件流
  归触控层，手指滑出显示边界后 MOVE 仍持续派发给本层 → 拖拽/滚动不丢失（ViewListener 链路原样工作，
  无需坐标过滤与状态机，这是选 A 而非 B 的关键理由）；
- **DOWN 在区域外、滑入区域**：事件流归容器（DOWN 目标），持续被丢弃 → 不产生 HID 事件（合理：手势
  起点在区域外不应激活）；
- **M2 不受影响**：下拉三角/按键栏位于 rootLayout 顶层、与触控层无交集——本需求只约束触控手势层。

### 3.3 下拉三角事件豁免区

- `DropTriangleView` 顶部居中（横屏顶部边缘，建议热区 ≥ 48dp × 48dp，视觉三角更小，画半透明白色三角 + 轻微阴影保证在采集画面上可见）。
- 触摸处理：
  - `ACTION_DOWN`：**消费**（返回 true）→ 后续事件流归三角，不会穿透到触控层 → 点三角不会误触成 tap→左键。
  - `ACTION_UP`：若未滑出热区 → `onToggle()` 切换按键栏；否则忽略。
  - `ACTION_MOVE`：超出热区半径则标记取消（UP 不再触发 toggle）。
- 三角是唯一常驻 UI；点击效果：按键栏未展开→展开；已展开→收起（此时同时重置自动隐藏计时）。

### 3.4 按键栏展开期间事件优先消费

- `KeyBarPanel`（横向 LinearLayout，顶栏样式参考 hdmi2mp `topOverlay`）包含：
  | 按钮 | 行为（复用来源） |
  |---|---|
  | 速度（emoji 1️⃣–5️⃣ 循环） | `SpeedLevel.forLevel(next)`，写 SharedPreferences `speed_level`，更新 `viewListener.mouseSpeed/scrollSpeed`（复制 SelectDeviceActivity.setupToolbar 逻辑） |
  | 蓝牙状态/设备名 | 点击 → 连接/断开；长按或弹窗 → `showDeviceSwitcher()` 多设备切换（复制 SelectDeviceActivity） |
  | 自动配对 🔗 | 切换 `BluetoothController.autoPairFlag` + `startAutoReconnect/stopAutoReconnect` |
  | 分辨率（1080p ↔ 4:3） | `switchMode(MODE_1080P_W/H, …)` / `switchMode(1872, 1404, …)`（复制 hdmi2mp） |
  | 截图 📷 | `captureJpg()`（复制 hdmi2mp，含 MediaStore 导入） |
  | 退出 ⏻ | 清理 + finish |
  **不含任何键盘设置项**（Q2 ✅）。
- **速度口径说明**：按下速度按钮后 `mouseSpeed`/`scrollSpeed` 被覆盖为 `SpeedLevel.mouse/scroll`（level 4 均为 1.0f），`ViewListener` 构造时默认 0.33f 仅在覆盖装配之前生效——与 §3.6 例 2 口径一致。
- **区域消费**：panel 容器 `setOnTouchListener { _, _ -> true }` + 按钮各自 clickable → 落在按键栏边界内的所有触摸被消费，不进触控手势层（点菜单不会触发鼠标报告）。
- 按键栏展开期间，**栏外区域仍是触控板**（两指滚动/移动照常，符合需求"只有菜单区优先消费"）。
- 自动隐藏：`KeyBarController` 改造自 hdmi2mp `showToolbar/hideToolbar/hideGeneration`（动画 + 防竞态 generation 计数）；默认 `AUTO_HIDE_MS = 4000`（可配置，写 UvcpadPrefs）；**任意触摸（三角/按键栏/触控层）都重置计时**，与 hdmi2mp"点任意处重置 3s 计时"行为一致。

### 3.5 手势 → HID 报告映射（复用 ViewListener 全量）

- 接入点：`MainActivity` 在 BT 连接回调里执行与 KeysJoy `SelectDeviceActivity.setupSimpleModeTouch()` **逐行等价**的装配：

```kotlin
val sender = RelativeMouseSender(hidDevice, host)
val vListener = ViewListener(hidDevice, host, sender).also {
    it.mouseSpeed = currentSpeedLevel.mouse
    it.scrollSpeed = currentSpeedLevel.scroll
}
touchLayer.setGestureListener(vListener)
```

- 手势映射表（ViewListener 源码确认，无改动）：

| 手势 | 识别位置（KeysJoy 源码） | HID 输出 |
|---|---|---|
| 单指移动 | `GestureListener.onScroll`（`e1/e2.pointerCount < 2` 分支） | `sendMouseMove(dx, dy)`，亚像素累积 + speed + ramp |
| 单击 | `onSingleTapUp` + tapChecker（doubleTapTimeout 后确认） | `sendTestClick()` → 左键 down/up |
| 双指单击 | `onSingleTapUp`（`activePointerCount >= 2`） | `sendRightClick()` |
| 双指抬指右键 | `handleNormalTouch` ACTION_POINTER_UP（距离 > 30f 且未缩放/滚动） | `sendRightClick()` |
| 长按拖拽 | onDown 后 500ms 自定义 dragRunnable（`isLongPressed=true`） | `sendLeftClickOn()` + 移动时 `sendMouseMove` |
| 双击拖拽 | `onDoubleTapEvent`（ACTION_DOWN 即 `sendLeftClickOn`） | 左键按住 + 移动 |
| 双指滚动 | `GestureListener.onScroll`（ptrCount ≥ 2，未缩放时） | `sendScroll(sy, sx)`（-distanceY × scrollSpeed） |
| pinch 缩放 | `ScaleListener.onScale` | 当前实现发 `sendScroll(factorDelta, 0)`（保留原行为） |

### 3.6 坐标→相对位移映射与 5 档速度复用

- 映射公式（`ViewListener.onScroll` 单指分支，源码原样）：

```
accumMouseX += -distanceX * mouseSpeed * ramp
mx = round(accumMouseX); 若 mx≠0: sendMouseMove(mx, 0); accumMouseX -= mx
```

- `mouseSpeed`/`scrollSpeed` 来自 `SpeedLevel`（0.4/0.6/0.8/1.0/1.2），默认 `DEFAULT = LEVELS[3] = 1.0f`；`scrollSpeed` 初始化 0.33f（ViewListener 默认值，SelectDeviceActivity 会覆盖为 `SpeedLevel.scroll`，uvcpad 照抄覆盖逻辑）。
- 加速度 ramp（ViewListener 内置，保留）：按下后 400ms 内从 15% 线性升到 100%，避免起步突兀。
- 持久化：沿用 KeysJoy key `"speed_level"`（默认 4）。
- **输入→输出示例（跨模块数据变换，reviewer 可逐组验证）：**
  - 例 1：单指移动 `distanceX=45.6, distanceY=-12.3`，level 4（mouse=1.0），按下已 >400ms（ramp=1.0）→ `accumX=-45.6 → mx=-46`；`accumY=+12.3 → my=12`。报告字节：`dx = -46 = 0xFFD2`（dxLsb=0xD2, dxMsb=0xFF），`dy = 12 = 0x000C`（dyLsb=0x0C, dyMsb=0x00），按钮位不变，滚动为 0。
  - 例 2：双指滚动 `distanceY=25`，按下后经速度按钮（level 4）覆盖，scrollSpeed=1.0（ramp=1.0）→ `accumScrollY = -25×1.0×1.0 = -25 → sy=-25`；报告：`vScroll=0xE7`（-25 的 8 位补码：0x100−0x19=0xE7，vScroll 为单字节 bytes[5] 直接写入，与 ScrollableTrackpadMouseReport 的 put 逻辑一致），hScroll=0，X/Y 为 0。**若未走 SpeedLevel 覆盖、保持 ViewListener 默认 0.33**：`-25×0.33 = -8.25 → sy=-8 → vScroll=0xF8`——两种口径并列，编码时以装配代码实际覆盖值为准（uvcpad 照抄覆盖逻辑，故实际为 0xE7）。
  - 例 3：刚按下 100ms（ramp=0.15+0.85×100/400≈0.3625），单指移动 40px → `-40×1.0×0.3625 = -14.5 → mx=-14`（Math.round 四舍五入）。验证 ramp 起步阶段位移被削减。

### 3.7 生命周期协调

```
App 启动（USB attach 或 Launcher）
  ├─ UvcpadApplication.onCreate → CrashHandler.install
  ├─ MainActivity.onCreate
  │    ├─ 串行权限请求（相机→蓝牙→定位→存储）
  │    ├─ 恢复偏好（分辨率模式 / speed_level / auto_pair / auto_hide_ms）
  │    ├─ FLAG_KEEP_SCREEN_ON（常亮，hdmi2mp + KeysJoy 默认行为）
  │    └─ 状态恢复 savedInstanceState（KEY_MODE_W/H，hdmi2mp 模式）
  ├─ onStart → BluetoothController.init + getSender/getDisconnector 注册（KeysJoy onStart 模式）
  ├─ AUSBC（USB 授权后自动）→ OPENED → 状态更新；ERROR → errorText（hdmi2mp onCameraState 模式）
  ├─ BT CONNECTED → 创建 sender + ViewListener → touchLayer 挂载手势
  ├─ BT DISCONNECTED → 卸载手势、置空 sender、提示（KeysJoy getDisconnector 模式）
  ├─ 5s 自动重连循环（autoPair 开启时，BluetoothController.startAutoReconnect 原样）
  ├─ onStop → wasInBackground=true（KeysJoy 模式）；onResume 回来强制重初始化 BT（btHid 可能陈旧）
  ├─ 按键栏：三角点击 → show；4s 无操作 → hide（KeyBarController）
  └─ onDestroy → mainHandler 清空、BluetoothController.stopAutoReconnect、CrashHandler.setActiveActivity(null)、super.onDestroy()（AUSBC clear() 释放 UVC）
```

**异常处理要点：**
- `onCameraState ERROR`（UVC 打开失败/权限拒绝）→ 状态栏 + errorText，不崩溃（hdmi2mp 已实现，原样保留）。
- 蓝牙断开瞬间触摸层必须**先卸载监听再置空 sender**，避免 `sendReport` 到失效连接（KeysJoy 在 getDisconnector 回调里 `setOnTouchListener(null)` + 置空，照抄）。
- `BluetoothController.onAppStatusChanged` 未注册自动重注册逻辑保留（KeysJoy 原样）。
- 截图 `captureImage` 可能抛异常：hdmi2mp 已全 try-catch + 失败提示，原样保留。

---

## 4. 复用清单（源码级）

### 4.1 从 hdmi2mp 复用（显示链路）

| 源文件（路径相对 projects/hdmi2mp/android） | 处理 | 改造点 |
|---|---|---|
| `app/src/main/java/com/hdmi2mp/MainActivity.kt` | 复制后改造 | ① 删除 toolbar 按钮逻辑（btnMode1080p/btnMode4by3/btnCapture/btnExit 迁入按键栏）② `bindViews` 增加 touchLayer/三角/按键栏绑定 ③ `setupListeners` 增蓝牙装配 ④ 新增 `setupTouchLayer()` ⑤ `getRootView` 换新布局；其余（`getCameraRequest`/`getCameraView`/`onCameraState`/`switchMode`/`captureJpg`/`hideSystemUi`/状态保存/串行权限）**原样保留** |
| `app/src/main/java/com/hdmi2mp/CrashHandler.kt` | 原样复制 | 无（包名改） |
| `app/src/main/java/com/hdmi2mp/Hdmi2mpApplication.kt` | 复制改名 `UvcpadApplication` | 类名/包名 |
| `app/src/main/AndroidManifest.xml` | 复制后合并 | 增蓝牙权限/`touchLayer` 无需声明；activity 名改 |
| `app/src/main/res/xml/device_filter.xml` | 原样复制 | 无 |
| `app/src/main/res/layout/activity_main.xml` | 复制后改造 | 加 touchLayer/topUiContainer（三角+按键栏）；errorText marginTop 下移 |
| `app/build.gradle` / `build.gradle` / `settings.gradle` | 复制改包名 | minSdk 23→28；applicationId；namespace |
| `app/src/main/res/values/{strings,colors,themes}.xml`、`drawable/bg_btn.xml` 等 | 复制 | 增补按键栏文案 |
| `app/src/main/res/raw/*license*` | 复制 | AUSBC/libuvc/libusb/libjpeg 许可证文本随库分发 |

> hdmi2mp 无"不搬"项——其全部代码属于显示链路，uvcpad 全量保留。

### 4.2 从 KeysJoy 复用（仅鼠标/触控板能力）

| 源文件（路径相对 projects/KeysJoy/app/src/main/java/com/github/ifeel/keysjoy） | 处理 | 改造点 |
|---|---|---|
| `BluetoothController.kt` | 复制后改造 | ① `sdpRecord`：`DescriptorCollection.MOUSE_KEYBOARD_COMBO` → `MOUSE_RELATIVE_WITH_SCROLL`；`SUBCLASS1_COMBO` → `SUBCLASS1_MOUSE`；设备名 `"Pixel HID1"` → `"uvcpad"`（改名强制 PC 重新配对）② `onServiceConnected` 硬编码提示串 `"Search 'Pixel HID1' on target device"` → `"Search 'uvcpad' on target device"`（已对照源码确认原文）③ 其余（init/自动重连/switchTo/getSender/getDisconnector/onGetReport 应答 FeatureReport）**原样**。`sdpRecord` 的 description `"Mobile BController"` / provider `"bla"` **保持原样**：二者仅为 SDP 元信息，PC 端配对显示名与 HID 枚举均以 name（"uvcpad"）为准，不改可最小化与 KeysJoy 的源码差异 |
| `DescriptorCollection.kt` | 复制后裁剪 | 仅保留 `MOUSE_RELATIVE_WITH_SCROLL`（主）与 `MOUSE_RELATIVE_WITH_SCROLL_NOTSMOOTH`（回退）；删除 MOUSE_KEYBOARD_COMBO/KEYBOARD*/MOUSE_ABSOLUTE/MOUSE_RELATIVE*/featurerr 等未用描述符 |
| `reports/ScrollableTrackpadMouseReport.kt` | 原样复制 | 无（ID=4，7 字节布局与 `MOUSE_RELATIVE_WITH_SCROLL` 严格对应：按钮2bit+pad6 / dx16 / dy16 / vScroll8 / hScroll8） |
| `reports/FeatureReport.kt` | 原样复制 | 无（ID=6，onGetReport 应答用） |
| `senders/RelativeMouseSender.kt` | 原样复制 | 无（±2047 钳制/亚像素/按钮序列/滚动） |
| `listeners/ViewListener.kt` | 原样复制 | 无（全手势引擎；`wheelMode/buttonHeld` 路径保留但 uvcpad 不触发——无鼠标三键栏，无害） |
| `SpeedLevel.kt` | 原样复制 | 无 |
| `SelectDeviceActivity.kt` | **不整体复制**，仅提取接线片段 | ① `setupSimpleModeTouch()`（§3.5 装配）② `onStart` 的 BT init/getSender/getDisconnector 模式 ③ `setupToolbar` 中速度循环、auto-pair、设备切换弹窗 `showDeviceSwitcher()` ④ `onResume` 的 wasInBackground 重初始化 |
| `SplashScreenActivity.kt` | 不整体复制 | 仅提取权限请求集合（§1.4）合并进 MainActivity 串行流程 |
| `AndroidManifest.xml` | 不整体复制 | 仅合并蓝牙权限段（§1.3） |

### 4.3 不搬（键盘/不需要）

| 文件 | 原因 |
|---|---|
| `senders/KeyboardSender.kt`、`reports/KeyboardReport.kt`、`reports/Sender.kt`（发送器基类） | 键盘能力，Q2 决策排除 |
| `ui/KeyboardPanelHelper.kt`、`ui/KeyLayouts.kt`、`ui/DividerView.kt`、`ui/ModifierState.kt` | 键盘面板/分隔条/修饰键 UI |
| `listeners/GestureDetectListener.kt`、`extraLibraries/CustomGestureDetector.kt` | 遗留手势路径，`SelectDeviceActivity` 未使用（实际手势引擎是 ViewListener） |
| `senders/SensorSender.kt`、`Unhide.kt` | 传感器鼠标/遗留功能 |
| `reports/{MouseReport,TrackpadMouseReport,TestTrackpadMouseReport,AbsMouseReport}.kt` | 备选/测试报告类型，活动路径未使用 |
| `res/layout/activity_select_device_{landscape,portrait}.xml`、`res/menu/*` | 键盘布局与旧菜单 |
| 鼠标三键栏（`btn_mouse_left/middle/right` UI） | 全屏触控板不可放可点击按钮（PROPOSAL §4.6）；左右键/滚动已由手势覆盖 |

---

## 5. 里程碑 M1 / M2

### 5.1 M1 骨架整合（验收：画面显示 + 光标可动）— ✅ 编码完成 2026-08-12

实施顺序（状态截至 2026-08-12）：
1. ✅ **工程骨架**：gradle/settings/manifest/res 复制整理；`UvcpadApplication`+`CrashHandler`；空 `MainActivity` 可编译运行（assembleDebug 通过）。
2. ✅ **显示链路**：布局（cameraViewContainer + touchLayer + errorText）；`getCameraRequest`（1920×1080/MJPEG/OPENGL/captureRawImage）/`getCameraView`（AspectRatioTextureView）/`onCameraState`/USB 授权提示/串行权限；插卡即出画面（编码就绪）。
3. ✅ **蓝牙链路**：复制 `BluetoothController`（改鼠标专用描述符 + "uvcpad" 设备名）+ reports + senders + `ViewListener` + `SpeedLevel`；BT 权限；`onStart` init；连接回调装配（含 P2 修复：首次启动授权后链尾补 init）。
4. ✅ **透明触控层**：`TransparentTouchLayer` 叠加 + 连接后挂载 ViewListener；断开卸载；另已落地"触控区域=显示区域"（§3.2.1）。
5. ⬜ **联调（真机，待用户提供采集卡+PC 配对环境）**：PC 配对 → 单指移动/单击验证光标；临时验证 5 档速度（可先硬编码 level，正式入口在 M2 按键栏）。
6. ✅ git init/commit（5 个 commit：4ed1f58/4abdc04/2cf23ba/2bd498b/6d36a24）；⬜ push（github 仓库 iFeel-is-a-mouse/uvcpad 未建）。

验收要点（编码侧状态；**真机验证留待用户，见 docs/todo.md M1 收尾遗留**）：
- [x] 采集卡插入即全屏显示（1080p 默认；4:3 预留，M2 进菜单）— 编码就绪，真机待验
- [x] 画面铺满、透明层不可见（视觉零遮挡）— 编码就绪，真机待验
- [x] 蓝牙配对后单指移动光标跟手、tap 左键生效 — 编码就绪（ViewListener 链路原样），真机待验
- [x] 拔插采集卡/蓝牙断连不崩溃，错误提示可见 — 编码就绪（ERROR→errorText / 断开先卸监听），真机待验
- [x] 触控层与渲染互不阻塞（渲染 60fps 时触摸无卡顿）— 线程模型已分离（§2.3），真机待验

### 5.2 M2 透明层全量 + 交互入口（验收：全手势 + 三角 + 自动隐藏按键栏）

实施顺序：
1. **透明层打磨**：确认 onDraw 零绘制、全屏事件、无障碍无焦点闪烁。
2. **下拉三角**：`DropTriangleView` + 事件豁免验证（点三角不产生鼠标报告）。
3. **按键栏**：`KeyBarPanel` + `KeyBarController`（4s 自动隐藏、可配置）+ 合并菜单（速度/蓝牙/自动配对/分辨率/截图/退出）+ 区域事件消费验证。
4. **手势全量回归**：两指滚动/双指右键/长按拖拽/双击拖拽（ViewListener 原样，主要验证与三角/按键栏的边界交互）。
5. **坐标映射调参**：真机标定 speed 系数与 ramp（§7.2）。
6. git commit + push。

验收要点：
- [ ] 横屏下全部手势可用（移动/单击/右键/滚动/拖拽），画面透出正常
- [ ] 点三角唤出按键栏；4s 无操作自动隐藏（时长可配）
- [ ] 点三角/按键栏按钮**不产生**鼠标报告（豁免区 + 消费区）
- [ ] 按键栏展开时栏外仍可正常触控
- [ ] 按键栏无任何键盘设置项
- [ ] 分辨率切换/截图/多设备切换/自动配对功能正常

---

## 6. 风险与调参点

### 6.1 关键风险（3–5 条核心）

1. **HID 描述符切换风险（高）**：从 `MOUSE_KEYBOARD_COMBO` 切到 `MOUSE_RELATIVE_WITH_SCROLL` 改变了 PC 看到的 HID 枚举。旧配对 `"Pixel HID1"` 会残留 → 设备名改为 `"uvcpad"` 强制重新配对；若 PC 端对 7 字节报告（含 AC Pan）识别异常，回退 `MOUSE_RELATIVE_WITH_SCROLL_NOTSMOOTH`（同一 7 字节布局、更简单的描述符结构）。`SUBCLASS1_MOUSE` 与描述符必须一致。
2. **蓝牙延迟与 tap 判定延迟（中）**：移动/滚动报告是即时的（BR/EDR 通常 10–30ms，异步 binder）；但**单击左键有 ~300ms 双击判定延迟**（`ViewConfiguration.getDoubleTapTimeout`，KeysJoy 同款，属设计内取舍）。≤100ms 目标针对移动/滚动跟手性，需真机验证；2.4GHz WiFi/BT 共存干扰是环境变量。
3. **事件路由边界（中）**：三角起始触摸滑出/滑入、按键栏展开时栏外两指手势、栏收起动画进行中点击——依赖 Android ACTION_DOWN 归属模型 + hdmi2mp 的 `hideGeneration` 竞态防护，需真机手势回归。
4. **主线程负载（中）**：120Hz 触摸采样 + sendReport + 各类定时器集中在主线程；`RelativeMouseSender.sendMouseMove` 每次 new ByteBuffer（微分配）。弱平板（渲染满负载）可能 jank。缓解：sendReport 本身异步、GL 渲染独立线程；M2 后按实测决定是否将报告发送迁移到专用 HandlerThread / 复用 ByteBuffer。
5. **AUSBC 帧率与截图成本（中低）**：AUSBC 3.6.0 `CameraRequest.Builder` 无显式 60fps API（MainActivity 源码注释确认），依赖 UVC 协商；`captureRawImage(true)`（截图必需）在 OPENGL 模式持续投递 NV21 帧，有内存/带宽开销——保留（hdmi2mp 已验证），但作为性能监控点。

### 6.2 其他注意

- **BT 连接状态竞态**：`onConnectionStateChanged`/`getSender` 回调在 binder 线程 → 必须在主线程装配 sender/touchLayer；断开时先卸监听再置空（§3.7）。
- **分辨率切换期间触控不受影响**：`updateResolution` 内部 stopPreview+startPreview，与蓝牙链路无耦合；切换失败 UI 回滚逻辑（hdmi2mp P3-L1）保留。
- **minSdk 28 的蓝牙**：`BluetoothHidDevice` API 自 API 28 引入（P），S+ 需运行时 BLUETOOTH_CONNECT——已纳入权限串行。

### 6.3 调参点清单（真机标定，M2）

| 参数 | 位置（源码） | 默认 | 说明 |
|---|---|---|---|
| `mouseSpeed` 五档 | `SpeedLevel.kt` LEVELS | 0.4–1.2 | 触控灵敏度核心 |
| `scrollSpeed` | `ViewListener.scrollSpeed` + `SpeedLevel.scroll` | 0.33f | 双指滚动手感 |
| ramp 起步 | `ViewListener.RAMP_START_FRACTION / RAMP_DURATION_MS` | 0.15 / 400ms | 起步加速度 |
| 按键栏自动隐藏 | `KeyBarController.AUTO_HIDE_MS`（可配置） | 4000ms | PROPOSAL Q7 建议 |
| 三角热区 | `DropTriangleView` | ≥48dp | 易点按 |
| 双指右键距离阈值 | `ViewListener` ACTION_POINTER_UP `30f` | 30px | 防误触 |
| HID 报告钳制 | `RelativeMouseSender` ±2047 | 2047 | 已实现，无需调 |

---

## 7. 待办（编码前需 main/analyze 确认 — ✅ 已全部完成 2026-08-12）

- [x] DESIGN.md 评审通过（含 §3.6 三组输入→输出示例验证）
- [x] 确认 M1 验收口径 = "画面显示 + 光标可动"（PROPOSAL §9）
- [x] 确认按键栏菜单集合（§3.4 表格）与 PROPOSAL Q4（截图保留）一致
- [x] 确认设备名 `"uvcpad"`（PC 端显示名）

> M1 后的待办已迁移至 `docs/todo.md`（M2 清单，来源：reviewer/tester/auditor 评审记录 + main 决策 2026-08-12）。

---

*本文档所有复用点均可溯源至两项目真实源码；未读源码前不得改动复用范围。*
