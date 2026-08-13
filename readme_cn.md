<p align="center">
  <img src="docs/assets/icon.png" alt="uvcpad" width="128"/>
</p>

# uvcpad

**把 Android 平板变成 PC 的透明触控显示器。**

uvcpad 把两个成熟项目整合为一个 App：USB UVC 采集卡把 PC 画面镜像到平板（来自 hdmi2mp），一块透明的触控层把手指手势转为**蓝牙 HID 鼠标报告**，直接控制 PC 光标（来自 KeysJoy）。

看和触在同一块屏上——画面透出采集内容，触控层完全不挡画面。**纯触控、免键盘、免驱动。**

> 当前版本 v0.2.10（2026-08-13）。M1 骨架整合与 M2 交互入口（下拉三角 + 自动隐藏按键栏）已完成，真机联调进行中。

## 功能特性

- **UVC 采集显示**：MS2130 HDMI→USB 采集卡即插即用，OpenGL 渲染全屏显示 PC 画面
- **蓝牙 HID 触控板**：平板注册为鼠标 HID 设备，PC 端无需任何驱动
- **透明触控层**：只收事件、不画内容，画面零遮挡；触控区域 = 显示区域（黑边不响应）
- **完整手势**：单指移动、轻点左键、双指右键、长按/双击拖拽、双指滚动
- **纯触控板，无键盘**：仅注册鼠标报告，不含任何键盘功能
- **5 档灵敏度**：按键栏一键切换
- **自动配对 / 自动重连**：记住最近设备，断开自动回连
- **分辨率切换**：16:9（1920×1080）↔ 4:3（1872×1404），记忆所选档位；采集卡不支持时自动回退最近分辨率
- **一键截图**：保存到系统图库
- **墨水屏友好**：默认 4:3 档位，专为华为 MatePad Paper 等墨水屏平板调校

## 技术架构

```
┌───────────────────── pad（Android） ─────────────────────┐
│                                                          │
│  ┌─ 显示链路 ────────────────────────────────────────┐   │
│  │ PC HDMI → MS2130 采集卡 → USB → AUSBC(UVC) 采集    │   │
│  │ → OpenGL 渲染 → 全屏显示（底层画面）                │   │
│  └────────────────────────────────────────────────────┘   │
│                          ▲ 透明叠加                       │
│  ┌─ 触控链路 ────────────────────────────────────────┐   │
│  │ 触摸手势 → 手势识别（透明层）                        │   │
│  │ → ScrollableTrackpadMouseReport（ID=4，7 字节）     │   │
│  │ → BluetoothHidDevice → PC 光标移动/点击/滚动/拖拽    │   │
│  └────────────────────────────────────────────────────┘   │
│                                                          │
└──────────────────────────────────────────────────────────┘
```

两条链路互相独立、并行工作：显示链路只依赖 USB，触控链路只依赖蓝牙，无资源冲突。

- **显示链路**：AUSBC 3.6.0 采集 UVC 画面，`AspectRatioTextureView` + OpenGL 渲染（复用 hdmi2mp）
- **触控链路**：`ViewListener` 手势引擎 → 相对位移映射 → HID 报告发送（复用 KeysJoy，仅鼠标能力）
- **整合层**：`TransparentTouchLayer` 全屏透明层叠加在画面上；顶部下拉三角（事件豁免区）唤出自动隐藏按键栏

| 层 | 关键类 | 职责 |
|---|---|---|
| UI 层 | `DropTriangleView`、`KeyBarPanel`、`KeyBarController` | 下拉三角、按键栏、4s 自动隐藏 |
| 触控层 | `TransparentTouchLayer`、`ViewListener`、`RelativeMouseSender` | 手势识别 → 相对位移 → HID 报告 |
| 显示层 | `AspectRatioTextureView`（AUSBC） | UVC 采集 + OpenGL 渲染 |
| 基础层 | `BluetoothController`、`DescriptorCollection`、`SpeedLevel` | 蓝牙 HID 注册/重连/多设备、鼠标描述符、5 档速度 |

技术栈：Kotlin · AUSBC 3.6.0 · BluetoothHidDevice（API 28+）· OpenGL · minSdk 28 / targetSdk 36。

## 快速开始

### 构建

方式一：Android Studio

1. 用 Android Studio 打开 `android/` 目录
2. 等待 Gradle 同步（Gradle 9.3.1，仓库已配阿里云镜像）
3. Run ▶

方式二：命令行

```bash
cd android
./gradlew assembleDebug
# APK 输出：android/app/build/outputs/apk/debug/app-debug.apk
```

### 安装

```bash
adb install android/app/build/outputs/apk/debug/app-debug.apk
```

或把 APK 复制到平板直接安装。首次启动按提示授予相机与蓝牙权限。

## 使用说明

### 1. 连接采集卡

1. 通过 USB-C（OTG）把 MS2130 采集卡接入平板
2. 授权 USB 设备访问，允许相机权限
3. 画面即出。若采集卡不支持所选分辨率，App 自动回退到最接近的分辨率并提示

### 2. 蓝牙配对（PC 端）

1. 在 PC 蓝牙设置中搜索设备 **`uvcpad`**（App 注册的 HID 设备名）
2. 配对即可，**无需安装任何驱动**
3. 连接成功后，平板的透明触控板即开始工作

### 3. 手势

| 手势 | 效果 |
|---|---|
| 单指滑动 | 移动光标 |
| 轻点 | 左键单击 |
| 双指轻点 / 双指抬起 | 右键 |
| 长按拖动 | 左键拖拽 |
| 双击后拖动 | 左键拖拽 |
| 双指滚动 | 滚动页面 |

### 4. 按键栏

顶部中央的下拉三角是唯一常驻 UI。点击唤出按键栏，**4 秒无操作自动隐藏**（时长可配置）。点三角不会误触左键。

| 按钮 | 功能 |
|---|---|
| 速度 | 5 档灵敏度循环切换 |
| 蓝牙 | 连接/断开、切换设备（自动记住最近设备） |
| 自动配对 | 开/关自动重连 |
| 分辨率 | 16:9 ↔ 4:3 切换（记忆所选档位） |
| 截图 | 保存到系统图库 |
| 退出 | 清理并退出 |

> 按键栏不含任何键盘设置项——uvcpad 是纯触控板。

## 目录结构

```
uvcpad/
├── readme.md / readme_cn.md        # 本文档（英文 / 中文）
├── docs/
│   ├── PROPOSAL.md                 # 需求理解（已冻结）
│   ├── DESIGN.md                   # 技术设计
│   ├── todo.md                     # 待办清单
│   ├── journey.md                  # 开发历程
│   └── assets/icon.png             # App 图标
└── android/                        # Android 工程
    └── app/src/main/
        ├── AndroidManifest.xml
        ├── java/com/github/ifeel/uvcpad/
        │   ├── MainActivity.kt     # 生命周期/装配/权限
        │   ├── touch/              # 透明触控层
        │   ├── ui/                 # 下拉三角 + 按键栏
        │   ├── bt/                 # 蓝牙 HID（含 reports/senders/listeners）
        │   └── UvcpadPrefs.kt      # 偏好设置
        └── res/                    # 布局/资源/设备过滤/三方许可证
```

## 致谢与许可证

- 显示链路整合自 [hdmi2mp](https://github.com/iFeel-is-a-mouse/)（MS2130/UVC 采集显示）
- 触控链路整合自 [KeysJoy](https://github.com/iFeel-is-a-mouse/KeysJoy)（蓝牙 HID 触控板）
- UVC 采集基于 [AndroidUSBCamera (AUSBC) 3.6.0](https://github.com/ernestp/AndroidUSBCamera)

libuvc、libusb、libjpeg-turbo 的许可证文本随 APK 内置（`app/src/main/res/raw/`）。
