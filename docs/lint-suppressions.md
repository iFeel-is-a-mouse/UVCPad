# lint 抑制记录（uvcpad）

本文件记录仓库中所有长期保留的 lint 抑制项与理由。原则：**能真修就真修；抑制必须带一行理由注释**，并且只能用于“产品/品牌/平台契约决定”的场景。

## 设计类（文件/资源级）

| lint id | 位置 | 理由 |
|---|---|---|
| `IconLauncherShape` | `app/lint.xml` | 启动图标是用户提供的满幅方形品牌图，铺满整块方区是有意设计；不改图。自适应图标另由 `mipmap-anydpi/ic_launcher.xml` 提供圆角遮罩版本。 |
| `IconXmlAndPng` | `app/lint.xml` | `mipmap-anydpi` 的自适应 XML 在 minSdk 28 全量生效；`mipmap-*dpi` PNG 作为品牌/旧版兜底资源原样保留。 |
| `ChromeOsAbiSupport` | `android/app/build.gradle` (`lint { disable }`) | release 变体有意仅打包 arm64-v8a（目标平板瘦包），不发布 x86_64 ChromeOS 版本。 |
| `DiscouragedApi`（固定横屏） | `AndroidManifest.xml` Activity `tools:ignore` | 固定横屏采集监看应用，键栏布局与触摸映射均按横屏设计，自由旋转会破坏交互。 |

## 代码级（必须带理由注释）

| lint id | 位置 | 理由 |
|---|---|---|
| `MissingPermission` ×5 | `BluetoothController.kt` | `replyReport`/`connectedDevices`/`setName`/`registerApp` 需要 `BLUETOOTH_CONNECT`：能加 `hasConnectPermission()` 守卫的已加；其余由既有 try/catch 降级（撤销授权时不崩溃），lint 无法穿透 helper 才抑制。 |
| `MissingPermission`（发现） | `MainActivity.requestDiscoverable()` | 调用点紧邻 `hasAdvertisePermission()` 守卫；抑制只因 lint 无法穿透 helper。 |
| `InflateParams` | `MainActivity.getRootView()` | AUSBC 的 `CameraActivity` 契约要求返回 detach 的根视图，由库自行挂载；此阶段不存在可用 parent。 |
| `ClickableViewAccessibility` | `TransparentTouchLayer.onTouchEvent`、`ViewListener.onTouch` | 两者是纯触控转发面，输入被翻译为远端 HID 鼠标事件；调用 `performClick()` 会向 TalkBack 播报虚假的本地点击。 |
| `DEPRECATION`（局部） | `ViewListener.vibratorOf()` | API 28–30 必须走 `VIBRATOR_SERVICE` 旧路径，31+ 走 `VibratorManager`，为保持兼容的局部抑制。 |

## 已真修、无需抑制（供对照）

- `MissingPermission`（`ACTION_REQUEST_DISCOVERABLE`）：补 `BLUETOOTH_ADVERTISE` 声明 + 运行时串行权限链。
- `HardcodedText` ×7、`ObsoleteSdkInt` ×2、`Overdraw`、`MonochromeLauncherIcon`、`DataExtractionRules`、`UseKtx` ×15、`UnnecessaryArrayInit` ×5、`ClickableViewAccessibility` ×2。

## 维护约定

1. 新增抑制必须在同一行/同一块写明 `[uvcpad-lint]` 理由；无理由的抑制在 review 中一律打回。
2. 升级 minSdk / 更换图标 / 调整 release ABI 策略时，先回来删除对应条目并重跑 `./gradlew :app:lintDebug`。
