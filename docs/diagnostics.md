# uvcpad T0 诊断手册（debug only）

> 目标：把「插卡 → 配对 → 手指试 → 眼看」换成「跑一条 adb 命令，看一段输出」。
> 所有入口只在 **debug** 构建存在：`DEBUG_HUD` / `DEBUG_SELFTEST` / `DEBUG_GESTURE`
> 广播接收器声明在 `src/debug/AndroidManifest.xml`，release 不合并、类也不编译；
> 代码内另有 `BuildConfig.DEBUG` 二次守卫。

## 1. 结构化日志（logcat）

五个频道，统一 `k=v` 格式：

| 频道 | 内容 |
|------|------|
| `UVCPAD_CAM` | 相机打开/关闭/错误、分辨率协商与回退、旋转切换、预览探针 |
| `UVCPAD_BT` | HID 服务连接/断开、registerApp 成败、设备连接状态 |
| `UVCPAD_HID` | 每秒一条报告速率汇总；HUD 开启时逐条报告 hex |
| `UVCPAD_TOUCH` | 手势注入（广播内容、解析结果、投递事件数） |
| `UVCPAD_UI` | HUD / 自检面板开关、广播入口 |

```bash
adb logcat -c
adb logcat -s UVCPAD_CAM UVCPAD_BT UVCPAD_HID UVCPAD_TOUCH UVCPAD_UI
```

只看分辨率协商与回退：

```bash
adb logcat -s UVCPAD_CAM | grep -E 'resolution_result|state=OPENED'
```

只看 HID 速率（每秒一行，不刷屏）：

```bash
adb logcat -s UVCPAD_HID | grep 'rate='
```

逐条 HID 报告（`hex` 只在 HUD 开启时输出）：

```
hud t=12345 id=4 dx=-3 dy=7 btn=1 scroll=0 hex=0100FD0007000000
```

## 2. debug HUD

- **打开/关闭**：长按顶部下拉三角（600ms）。或：

```bash
adb shell am broadcast -a com.github.ifeel.uvcpad.DEBUG_HUD
```

- **位置**：右上角、按键栏下方（topMargin 64dp），半透明黑底、10sp 等宽字体。
- **不吞触控**：HUD 不可点击/不可聚焦，`onTouchEvent` 恒为 false，落在它上面的手势会继续下发给触控层。
- **四行内容**：

```
req=1920x1080 act=1600x1200     # 请求分辨率 / 实际协商分辨率
rot=90 cam=OPENED fps=30        # 旋转角度 / 相机状态 / 预览 fps
hid=42/s bt=CONNECTED name=PC   # HID 报告速率 / 蓝牙状态与设备名
touch=0,0 1600x1200             # 触控层 bounds（相对 rootLayout）
```

fps 与 HID 速率都是 **1 秒滑动窗口**。

## 3. 启动自检面板

- **入口**：长按按键栏「🔗 自动配对」按钮；或：

```bash
adb shell am broadcast -a com.github.ifeel.uvcpad.DEBUG_SELFTEST
```

- **内容**：12 项，每项 `✅`/`❌`，失败项附一行修复建议；面板可见时每秒自动刷新。
- **关闭**：再次长按「自动配对」或再发一次广播。关闭/销毁时移除刷新回调和视图，不残留监听。
- **不吞触控**：面板同样不可点击，`onTouchEvent` 恒为 false。

| 自检项 | 失败时先查什么 |
|--------|----------------|
| USB host support | 设备/OTG 口是否支持 USB Host（部分平板仅充电口） |
| Capture card attached | 采集卡是否插在 OTG 口；换线/换口 |
| Camera permission | 系统设置里是否授予「相机」 |
| USB device authorized | USB 授权弹窗是否点了「允许/始终允许」；拔插重试 |
| Camera opened | 重插采集卡或重启 App；看 `UVCPAD_CAM` 的 ERROR |
| Bluetooth adapter enabled | 系统蓝牙开关 |
| BLUETOOTH_CONNECT | Android 12+「附近的设备」权限 |
| BLUETOOTH_SCAN | 同上（本 App 不扫描，但注册链路需要） |
| BLUETOOTH_ADVERTISE | 「允许被发现」需要；重新走权限链 |
| HID profile proxy | 点一下蓝牙按钮重新 `getProfileProxy` |
| HID app registered | 点蓝牙按钮重试 `registerApp`；看 `UVCPAD_BT` |
| Paired device present/connected | 先在系统蓝牙里配对 PC；或在按键栏切换设备 |

## 4. 手势注入（debug only）

action：`com.github.ifeel.uvcpad.DEBUG_GESTURE`，extra：`seq`。

语法（`;` 分隔，坐标 `x,y`）：

- `down:x,y` / `move:x,y` / `up:x,y` / `cancel:x,y`
- 第二指：`down2:x,y` / `move2:x,y` / `up2:x,y`
- `sleep:ms`（只推进合成 eventTime，不阻塞 UI）

示例：

```bash
# 单指：按下 (200,300) → 拖到 (600,500) → 抬起
adb shell am broadcast -a com.github.ifeel.uvcpad.DEBUG_GESTURE \
  --es seq 'down:200,300;move:600,500;up:600,500'

# 双指：第二指按下 → 双指移动 → 第二指抬起 → 第一指抬起
adb shell am broadcast -a com.github.ifeel.uvcpad.DEBUG_GESTURE \
  --es seq 'down:300,400;down2:500,400;move:350,450;move2:550,450;up2:550,450;up:350,450'

# 长按拖动（sleep 500ms 后再移动）
adb shell am broadcast -a com.github.ifeel.uvcpad.DEBUG_GESTURE \
  --es seq 'down:400,400;sleep:500;move:500,450;up:500,450'
```

注入器用 `MotionEvent.obtain` 构造事件（正确设置 `downTime/eventTime`、`pointerCount`、
`ACTION_POINTER_DOWN/UP` 的 `actionIndex` 与稳定 pointer id），直接投递给 `touchLayer.onTouchEvent`，
因此走的是真实手势引擎（点击/拖拽/双指滚动），不是旁路。

解析失败不会抛异常：`UVCPAD_TOUCH` 会打印 `inject invalid reason=...`，并弹出 debug toast。

## 5. 常见故障 → 对应自检项

| 现象 | 优先看 |
|------|--------|
| 黑屏、没有画面 | `Capture card attached` → `USB device authorized` → `Camera opened`；`UVCPAD_CAM` 的 `state=ERROR` |
| 有画面但按键无效 | `Bluetooth adapter enabled` → `BLUETOOTH_CONNECT` → `HID app registered`；`UVCPAD_HID` 是否 `rate=0/s` |
| PC 搜不到 uvcpad | `BLUETOOTH_ADVERTISE`；`UVCPAD_BT` 的 `hid_register` 与 discoverable 状态 |
| 能连但鼠标乱跳/方向反 | HUD 的 `rot=` 与点击落点；用 `DEBUG_GESTURE` 注入固定坐标复现 |
| 切换 16:9 无效 | `UVCPAD_CAM` 的 `resolution_result=FALLBACK` + HUD `req` / `act` 对比 |
| fps 很低 | HUD `fps=`；`UVCPAD_CAM` 的协商分辨率/格式 |

## 6. 验收命令

```bash
cd android
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug :app:assembleRelease

# release 不应包含 DEBUG 接收器（输出为空即通过）
$ANDROID_HOME/build-tools/<ver>/aapt2 dump xmltree \
  app/build/outputs/apk/release/app-arm64-v8a-release.apk --file AndroidManifest.xml \
  | grep -i 'DEBUG_\|DebugReceiver'
```
