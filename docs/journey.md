# uvcpad 开发旅程（journey）

> 简记 uvcpad 从需求到 v0.2.x 迭代的开发历程。砍掉过程琐碎，留下决策与结论。
> 关联：`docs/PROPOSAL.md`（需求）、`docs/DESIGN.md`（设计）、`docs/todo.md`（M2/v0.2.x 待办）。

## 时间线总览

| 阶段 | 日期 | 产出 |
|---|---|---|
| M0 需求冻结 | 2026-08-11 | PROPOSAL.md 拍板（Q1–Q7 全部确认，需求边界冻结） |
| D3 设计评审 | 2026-08-11 | DESIGN.md 通过（P1/P2 已修，§3.6 三组输入→输出示例） |
| M1 编码 | 2026-08-11 | commit **4ed1f58**（骨架 + 显示 + 蓝牙 + 触控层） |
| 双验证 | 2026-08-11 → 12 | tester + reviewer 验证，出 P2 条目（构建/权限/代码质量） |
| 评审修复 | 2026-08-12 | commit **4abdc04**（onResume 双重 init 合并；CrashHandler 品牌字样） |
| 终审 | 2026-08-12 | auditor 终审，出 P3 条目 + 发现首次启动蓝牙 init 缺失 |
| P2 修复 | 2026-08-12 | commit **2cf23ba**（首次启动 Android 12+ 蓝牙 HID 注册不生效） |
| 触控对齐需求 | 2026-08-12 | iFeel 拍板：触控区域 = 显示区域（区域外不响应） |
| 触控对齐实现 | 2026-08-12 | commit **2bd498b** + **6d36a24**（评审修复） |
| M1 完成 | 2026-08-12 | main 决策：M1 ✅（待真机联调），M2 待启动，墨水屏适配列第一优先级 |
| M2 交互入口 | 2026-08-12 | commit **25225c6**（图标 + 下拉三角 + 自动隐藏按键栏） |
| v0.2.4→v0.2.9 迭代 | 2026-08-12 → 08-13 | 分辨率模式枚举 / 最近设备 / toast 单例 / 比例修复 / 图标兜底 / 按键栏打磨；质量修复批次完成（96e6b58 / 21e6640 / ff9abf4 / bf122c4） |

## 关键 commit 对照

| commit | 内容 |
|---|---|
| 4ed1f58 | M1 骨架整合：hdmi2mp 显示链路 + KeysJoy 鼠标触控链路合体（工程骨架/显示链路/蓝牙链路/透明触控层，assembleDebug 通过） |
| 4abdc04 | M1 评审修复：onResume 双重 init 合并单路径；CrashHandler 品牌字样 hdmi2mp→uvcpad |
| 2cf23ba | P2 修复：首次启动（Android 12+）蓝牙 HID 注册不生效——权限串行链尾补触发 init（permissionDialogShown 标志） |
| 2bd498b | 触控区域 = 显示区域：触控层动态对齐 AspectRatioTextureView 显示矩形（方案 A） |
| 6d36a24 | 触控对齐评审修复：P1 修正 DESIGN §3.2.1 时序描述；P2 getChildAt(0) 加 TextureView 类型防御；P3 注释布局循环 |
| 25225c6 | M2 交互入口：平板主题图标 + 下拉三角 + 自动隐藏按键栏（详见下文「M2 交互入口落地」） |

## M0 需求冻结（2026-08-11）

- **一句话需求**：采集卡画面显示在 pad + pad 触摸经蓝牙 HID 控制 PC + 触控层透明透出采集内容（整合 hdmi2mp 与 KeysJoy）。
- **关键拍板**（iFeel）：Q1 单 App 整合 / Q2 **纯触控板不含键盘** / Q3 横屏唯一形态 / Q4 保留截图 / Q5 minSdk 28 / Q6 显示流畅优先、触控延迟 ≤100ms / Q7 按键栏 4s 自动隐藏可配置。
- **产出**：docs/PROPOSAL.md，需求边界冻结，后续变更需重新评估。

## D3 设计评审（2026-08-11）

- DESIGN.md 全部复用点溯源至两项目真实源码：AUSBC 3.6.0 字节码反编译确认 `CameraActivity.initView()` 会 `removeAllViews()`、`AspectRatioTextureView` fit-inside 自缩放——这两条事实后来直接支撑了触控对齐方案 A。
- 评审通过，P1/P2 已修；坐标→HID 报告映射给出三组输入→输出示例（§3.6），供 reviewer 逐组验证。

## M1 编码（2026-08-11，commit 4ed1f58）

- **工程骨架**：照抄 hdmi2mp/android gradle 配置（AGP 8.10.1 + Kotlin 2.2.10 + Gradle 9.3.1 + AUSBC 3.6.0），包名 `com.github.ifeel.uvcpad`，minSdk 28 / targetSdk 36。
- **显示链路**：getCameraRequest（1920×1080/MJPEG/OPENGL/captureRawImage）/getCameraView/onCameraState/串行权限/USB 提示 原样保留自 hdmi2mp。
- **蓝牙链路**：BluetoothController 三处改造（`MOUSE_RELATIVE_WITH_SCROLL` 描述符、`SUBCLASS1_MOUSE`、设备名 "uvcpad" + 提示串）；DescriptorCollection 仅留鼠标描述符（字节逐字提取，diff 验证）；reports/senders/listeners/SpeedLevel 原样复制。
- **透明触控层**：新建 TransparentTouchLayer（不绘制、onTouchEvent 转发 ViewListener）；连接挂载 / 断开先卸监听再置空。
- **适应性改动**：toolbar 删除（switchMode 去 button 参数，M2 按键栏直接调用）；statusText 移除改 toast；`setScanMode` 改反射（compileSdk 36 无隐藏 API）；CRLF 行尾修正（ScrollableTrackpadMouseReport.kt 包名替换漏网）。
- **构建结果**：`./gradlew :app:assembleDebug` BUILD SUCCESSFUL（APK 20MB；仅 KeysJoy 原样代码 deprecation 警告）。

## 双验证与评审修复（2026-08-11 → 08-12）

- **tester**：assembleDebug 验证；记录 setScanMode 反射、3 处 deprecation 告警、无测试目录、AUSBC merge RECORD_AUDIO 待处理。
- **reviewer**：分辨率硬编码 1080p 待进菜单；非首次启动仅相机权限撤销时 getSender 双重注册边界。
- **修复 commit 4abdc04**：onResume 双重 init 合并单路径；CrashHandler 品牌字样 hdmi2mp→uvcpad。

## 终审与 P2 修复（2026-08-12）

- **auditor 终审发现关键缺陷**：首次安装授予全部权限后无路径再触发蓝牙 init——PC 搜不到 "uvcpad"，需切后台回前台（onResume wasInBackground 路径）才恢复。
- **根因**：onStart 因权限未授予提前 return；onResume 的 init 在授权前执行，getProfileProxy 缺 BLUETOOTH_CONNECT 静默失败；权限链尾无动作。
- **修复 commit 2cf23ba（最小改动）**：onStart 守卫式 init 提取为 `initBluetooth()`；`permissionDialogShown` 标志（4 个 launch 点置位）；链尾两处终结点调 `maybeInitBluetoothAfterFirstLaunch()`，仅本次确实弹过对话框才补 init，非首次启动不重复触发。
- **auditor P3 记录**：CrashHandler KDoc 旧引用 / BluetoothController 单例监听未清空（KeysJoy 同款）/ root build.gradle 注释残留 hdmi2mp（→ todo.md）。

## 触控对齐需求与实现（2026-08-12，commit 2bd498b + 6d36a24）

- **需求（iFeel）**：透明触控层不再铺满全屏，只覆盖采集画面实际显示区域；显示区域外（黑边/留白）触摸不响应、不产生任何 HID 事件。
- **方案 A（布局 bounds 动态跟随）**，依据 AUSBC 3.6.0 源码事实：相机视图 bounds 即显示区域（fit-inside）；容器会被 `removeAllViews()` → 触控层保持 rootLayout 兄弟节点；`setAspectRatio → requestLayout` 作为分辨率切换的天然同步点。
- **实现**：MainActivity 注册 OnGlobalLayoutListener → `syncTouchLayerBounds()` → `TransparentTouchLayer.alignToDisplayRect(rect)`；值未变直接返回；onDestroy 移除监听防泄漏；相机视图未布局时触控层退化 0×0。
- **边界语义（方案 A 优势）**：区域外触摸落非 clickable 容器被框架丢弃；DOWN 定归属 → 滑出显示区域手势不丢失；M2 三角/按键栏在顶层与触控层无交集。
- **评审修复 6d36a24**：P1 修正 DESIGN §3.2.1 时序描述与代码一致；P2 `getChildAt(0)` 加 TextureView 类型防御；P3 注释 layoutParams 无害布局循环（最多 2 次触发）。

## M1 完成（2026-08-12）

- **main 决策**：M1 ✅ 完成（编码 + 构建通过，含触控区域=显示区域落地），**待真机联调**；M2 待启动。
- **新增第一优先级：墨水屏适配**——MatePad Paper 真机验证 UVC 兼容性 + 快速模式画面可接受度；分辨率降到墨水屏舒适档；必要时启用流畅模式/残影优化（→ todo.md）。
- **遗留**：push 上游（github 仓库未建）；真机联调（需采集卡 + PC 配对环境，验收 4 项见 todo.md）。

## M2 交互入口落地（2026-08-12，commit uvcpad-m2-entry）

- **任务 A：平板主题图标**：adaptive icon（`mipmap-anydpi-v26/ic_launcher.xml` + `ic_launcher_background` 深蓝 #0D47A1 + `ic_launcher_foreground` 平板轮廓：白色圆角边框 + 青色屏幕 + 底部 home 条 + 屏上触点）；manifest `android:icon` 改 `@mipmap/ic_launcher`；删除旧 hdmi2mp 显示器图标 `drawable/ic_launcher.xml`。minSdk 28 ≥ 26，全设备自适应图标，无需密度 PNG。
- **任务 B：下拉三角 + 完整按键栏（DESIGN §3.3/§3.4）**：
  - `ui/DropTriangleView`：顶部居中 48dp 热区，半透明白三角 + 阴影（采集画面可见）；DOWN 消费（事件豁免，点三角不产生鼠标报告）、MOVE 出热区标记取消、UP 热区内触发 onToggle。
  - `ui/KeyBarPanel` + `ui/KeyBarController`：横向顶栏（速度/蓝牙+设备切换/自动配对/分辨率/截图/退出，**无键盘项** Q2 ✅）；非按钮区域 onTouchEvent 消费（按钮 clickable 各自消费）→ 菜单区触摸不进触控手势层；4s 自动隐藏（`auto_hide_ms` 读 UvcpadPrefs，默认 4000）；show/hide 动画 + hideGeneration 防竞态（hdmi2mp 模式）；展开期间栏外仍是触控板。
  - `MainActivity`：三角 toggle → 控制器；按钮接线——速度循环写 `speed_level` + 更新 `viewListener.mouseSpeed/scrollSpeed`；蓝牙点击连接/断开 + 长按 `showDeviceSwitcher()`（KeysJoy 复制，含 Make Discoverable）；自动配对 🔗（autoPairFlag + startAutoReconnect/stopAutoReconnect）；分辨率 1080p↔4:3（switchMode 复用，失败回滚文案不变）；截图 📷；退出 ⏻。
  - 任意触摸（三角/按键栏/触控层）重置自动隐藏计时：`TransparentTouchLayer` 增 `onAnyTouch` 回调（最小改动）。
- **布局**：`activity_main.xml` 增 `topUiContainer`（keyBar 在前、dropTriangle 在后 → 三角在上），Z 序在触控层之上；初始状态三角可见、按键栏 GONE。
- **构建**：`./gradlew :app:assembleRelease :app:assembleDebug` 双 BUILD SUCCESSFUL——release arm64 精简版 4.0MB（4,232,690 B）、debug universal 19.5MB（20,474,453 B）；APK 内确认新图标资源（mipmap-anydpi-v26/ic_launcher + drawable 前后景）。

## 三项用户偏好持久化记忆核实（2026-08-12，[uvcpad-prefs-mem]）

- **需求（iFeel 2026-08-12 拍板）**：速度、自动配对、分辨率三项偏好必须全部持久化记忆，重启 App 后恢复。
- **核实方法**：逐项检查"写入点 → prefs 存储 → 启动读取 → 生效点"闭环，全部在 HEAD 源码确认：
  - **速度（speedLevel）**：✅ 完整。写入 btnSpeed 点击 `prefs.speedLevel = nextLevel`；onCreate `currentSpeedLevel = SpeedLevel.forLevel(prefs.speedLevel)`（先于 bindViews，btnSpeed.text 初始化用恢复值）；生效 setupTouchLayer `mouseSpeed/scrollSpeed = currentSpeedLevel.*`。
  - **自动配对（autoPair）**：✅ 完整。写入 btnAutoPair 点击 `prefs.autoPair = enabled` + `BluetoothController.autoPairFlag = enabled`；onCreate 同步 `autoPairFlag = prefs.autoPair` 且 `if (prefs.autoPair) startAutoReconnect()`（启动恢复重连循环，KeysJoy 行为）；生效 onAppStatusChanged 自动连接 + 断开时重连循环。
  - **分辨率（resolutionW/H）**：✅ 完整。写入 OPENED 回读 `saveResolution`（switchMode 预写 + OPENED 回读覆盖，回退尺寸如实记忆）；onCreate 恢复 `currentModeW/H = prefs.resolutionW/H`；生效 getCameraRequest 按记忆值请求。
- **结论**：三项链路均闭环，无需代码改动（速度/自动配对恢复链路自 4ed1f58 即存在，分辨率 v0.2.4 落地，mem2 d202375 又补充"未插卡也可设置记忆"）。
- **构建**：`./gradlew :app:assembleRelease` BUILD SUCCESSFUL——`app/build/outputs/apk/release/app-arm64-v8a-release.apk`（sha256 bacfcb3d…095297，4,365,339 B）。

## 收尾三件套（2026-08-12，[uvcpad-toast-singleton] / [uvcpad-last-device-click] / [uvcpad-docs-sync]）

- **git 梳理**：B 任务（last-device）完整改动（UvcpadPrefs 字段 + BluetoothController resolveAutoConnectTarget/两入口 + MainActivity 接线）此前散落在工作树未提交，已核对完整性并入库（37ae486 [uvcpad-last-device]，功能同原 f3526c3 且补齐 MainActivity 接线）。
- **Toast 单例化（298f150）**：用户反馈提示堆积、新消息冲不掉旧的 → `toast()` 改全局单例 `sToast`，先 `cancel()` 旧 toast 再显示新消息；全项目仅 MainActivity 调 Toast，无其他散落点。
- **最近设备点击即记忆（6e8c7e8）**：用户澄清口径——showDeviceSwitcher 点击目标设备即写 `prefs.lastDeviceAddress` + 同步 `BluetoothController.lastDeviceAddress`（连接失败也记住意图）；保留连接成功回写为双保险。
- **docs 同步（本次 commit）**：恢复 stash 的 M1 完成状态更新（PROPOSAL/DESIGN），并将口径从"M2 待启动"小幅更新为"M1 完成 + M2 交互入口已实现（v0.2.x 迭代中）"；todo.md/journey.md 已是最新无需改动。

## v0.2.4 → v0.2.9 迭代（2026-08-12 → 08-13，质量修复批次收尾前）

> v0.2.x 为迭代系列口径（commit 前缀 [uvcpad-*]）；注：`app/build.gradle` versionName 原为 0.1.0，质量批次已改 **0.2.9（versionCode 2，21e6640 提交）**。

### 关键 commit 对照（本轮新增）

| commit | 内容 |
|---|---|
| d7de1b0 | fix(resolution)：分辨率切换按宽高比判断档位，修复 1600×1200 回退后永远回不到 16:9 [uvcpad-ratio-toggle] |
| 37ae486 | feat(bt)：自动连接优先最近成功连接的设备 + prefs 记忆 lastDeviceAddress [uvcpad-last-device] |
| 298f150 | refactor(ui)：Toast 全局单例——新消息先 cancel 旧消息再显示 [uvcpad-toast-singleton] |
| 6e8c7e8 | feat(bt)：点击设备即记忆最近设备并落盘——不等连接成功 [uvcpad-last-device-click] |
| 74d25a0 | feat(resolution)：分辨率记忆改为模式枚举（0=4:3/1=16:9），不再记忆硬件回退值 [uvcpad-resolution-mode] |
| ee62cb1 | fix(icon)：补 legacy mipmap 兜底 PNG（mdpi~xxxhdpi）[uvcpad-icon-fix] |
| 0b45e2e | fix(ui)：按键栏按钮统一 KeyBarButton style（固定 40dp 高）[uvcpad-keybar-style] |
| 21a958b | fix(ui)：按键栏按钮统一 14sp 字号 + wrap_content 高度自然等高；auto pair off 图标换 Unicode 15.1 断链 ⛓️💥 [uvcpad-keybar-font] |
| 96e6b58 | fix(bt) 状态机收口 [uvcpad-fix-p1-p2]：手动断开 vs 自动重连冲突（manualDisconnectFlag）+ onServiceDisconnected 状态残留 + 单例监听泄漏（clearListeners）+ 卡死点健壮性（initInProgress/重试/终止条件） + 蓝牙调用点统一权限守卫 |
| 21e6640 | fix(housekeeping) [uvcpad-fix-p3]：versionName 0.2.9（versionCode 2）+ 日志清理 + Toast applicationContext + uses-feature camera required=false + 死代码删除 + bondedDevices 兜底 + allowBackup=false；Manifest 显式剔除 RECORD_AUDIO（P2-7）+ sendReport 权限守卫（P2-8） |
| ff9abf4 | fix(bt) 3 处真实 SecurityException 风险 [uvcpad-fix-p2]：bondedDevices 兜底 / ACTION_REQUEST_DISCOVERABLE / 切换 toast 设备名安全读取 |
| bf122c4 | fix(bt) p2 重试收尾 [uvcpad-p2-retry-cleanup]：registerApp 3s 重试改字段持有 Runnable（可取消），onServiceDisconnected 复位标记 + 取消 pending 重试，防旧 proxy 误伤重连后的新 proxy |

### 分辨率迭代（d7de1b0 + 74d25a0）

- **真机反馈（1600×1200 回退）**：1920×1080 协商失败 → AUSBC 按最近宽度回退 1600×1200（4:3 比例、非预设值）——**采集卡不支持 1080p 是硬件限制**，非软件可修。旧代码"精确相等"判断失效 → 16:9 分支永远进不去；d7de1b0 改为按宽高比判断档位（`W*9 >= H*16` 整数交叉相乘），1600×1200/1872×1404/1024×768 归 4:3 档，点击即可重新请求 16:9 预设；OPENED 回读实际协商尺寸（含回退值）如实显示并 toast 提示。
- **记忆改模式枚举（74d25a0）**：prefs 从 `resolution_w/h` 宽高对 → `resolution_mode` 枚举（0=4:3/1=16:9，默认 4:3）；只记忆**用户选择的模式**（按钮点击时写入），硬件回退值不回写记忆——换硬件后下次启动仍按用户模式请求预设；旧数据首次读时按宽高比一次性迁移（`migrateResolutionMode`，幂等）。
- **墨水屏适配调研结论**（main/analyze）：分辨率档位已进按键栏 + 默认 4:3 对墨水屏负担更轻；定位门禁已放宽（BLUETOOTH_SCAN 声明 `neverForLocation`，S+ 豁免定位权限，4ed1f58 已含）；RECORD_AUDIO 来源确认为 AUSBC manifest 合并带入。

### 最近设备记忆（37ae486 + 6e8c7e8）

- **37ae486**：多设备场景自动连接优先"最近成功连接的设备"（`lastDeviceAddress`），而非系统返回的"最早配对"；连接成功回调写 prefs 落盘。
- **6e8c7e8（口径澄清）**：showDeviceSwitcher 点击目标设备即写 prefs + 同步 `BluetoothController.lastDeviceAddress`（**不等连接成功**——连接失败也记住意图，下次仍优先尝试）；连接成功回写保留为双保险。

### Toast 单例（298f150）

- **用户反馈**：提示堆积、新消息冲不掉旧的。改全局单例 `sToast`：新消息先 `cancel()` 旧 toast 再显示——最新优先、不排队。全项目仅 MainActivity 调 Toast，无其他散落点。

### 图标 legacy 兜底（ee62cb1）

- **问题**：鸿蒙等 launcher 对 `mipmap-anydpi-v26` 支持不佳 → 图标显示异常/高度异常。补 legacy mipmap 兜底 PNG（mdpi~xxxhdpi 五档），自适应图标 + 密度 PNG 双保险。

### 按键栏按钮统一（0b45e2e + 21a958b）

- **0b45e2e**：按键栏按钮统一 `KeyBarButton` style（固定 40dp 高），根治高度不齐。
- **21a958b（替代方案）**：固定 40dp 改回 `wrap_content` + 全部按钮统一 14sp 字号 → 行高一致 → 按钮自然等高（避免固定高度在不同字体缩放下的裁切/不齐）；auto pair off 图标换 Unicode 15.1 断链 ⛓️💥（旧 glyph 在部分字体缺失显示豆腐块）。

### v0.2.9 质量修复批次（✅ 已完成 2026-08-13）

- **状态机评审（2026-08-13 main 侧）**提出 P1/P2：① 手动断开后 auto-reconnect 仍按旧 target 重连（需区分主动断开与意外断开）；② `onServiceDisconnected` 后 proxy/状态清理残留；③ `object BluetoothController` 单例监听（deviceListener/disconnectListener）无清理路径（auditor P3 同源）；④ 重连 Handler/回调链卡死点排查。
- **96e6b58（状态机收口）**：① `manualDisconnectFlag` 抑制 DISCONNECTED 自动重连/startAutoReconnect/onAppStatusChanged 三处自动连接；② 断连时清理 hostDevice/mpluggedDevice + stopAutoReconnect + 通知 UI；③ `clearListeners()` onDestroy 置空（单例泄漏，auditor P3 同源一并解决）；④ 卡死点：initInProgress 短路双 getProfileProxy、S1 注册失败 3s 重试、S7 重连 btHid==null 终止、S8 connect 失败单次重试、USB 授权 30s 超时重置；蓝牙调用点统一 hasConnectPermission 守卫（SecurityException 兜底）。
- **21e6640（housekeeping）**：versionName 0.2.9（versionCode 2）、日志清理、Toast 改 applicationContext、camera `required=false`、删死代码、bondedDevices 兜底、`allowBackup=false`（prefs 含蓝牙地址，隐私考虑）；**RECORD_AUDIO 剔除**（AUSBC 合并带入，AudioSource.NONE 不需要，`tools:node="remove"`）+ sendReport 路径权限守卫。
- **ff9abf4（SecurityException 兜底）**：3 处真实风险——bondedDevices 兜底 / ACTION_REQUEST_DISCOVERABLE / 切换 toast 设备名安全读取。
- **bf122c4（p2 重试收尾）**：registerApp 3s 重试 Runnable 存字段可取消；onServiceDisconnected 复位 registerAppRetried + 取消 pending 重试——防旧 proxy 的残留重试误伤重连后的新 proxy。
- **状态**：全部已提交，回填完成（journey/todo 同步）。


### v0.2.10 三维复核修复批次（✅ 已完成 2026-08-13）

- **三维复核（auditor：一致性/完整性/健壮性）**结论"需修补"：3 个 P2（同源系统性缺陷）+ 9 个 P3。v0.2.9 引入的 manualDisconnectFlag/initInProgress/registerRetryRunnable/tryConnectWithRetry 等机制残留缺陷本轮修复。
- **P2-2 死设备空转**：换设备/重新配对后 prefs 记忆的旧地址仍被 5s 无限重连、永不回退默认设备。`resolveAutoConnectTarget()` 增加 bondedDevices 校验：记忆地址不在已配对列表 → 清空记忆（内存 + 经 `lastDeviceAddressRemovedListener` 清 prefs）回退 mpluggedDevice；无权限读取时保守返回记忆地址。
- **P2-1 重试提示不可执行**：registerApp 失败提示 "tap BT to retry" 但 btnBt 点击只做连接/断开。btnBt 点击分支补 `btHid==null` → 清 manualDisconnectFlag + `initBluetooth()` 重新走 init 链。
- **P2-3 切换重试覆盖**：switchTo 后 3s 内再切另一设备 → 旧 Runnable 3s 后连回旧设备。`tryConnectWithRetry` 的 Runnable/Handler 存字段，`switchTo` 开头 `removeCallbacksAndMessages` 作废旧重试。
- **P3**：① btHid!! 竞态改安全解包（唯一一处 line 371）；② 高频 Log.i → Log.d（updateStatus/连接状态/自动重连循环/onAppStatusChanged 等 10 处），TAG 核对一致；③ 拔卡提示：onCameraState CLOSED 且 pending==0 → errorText "Capture card disconnected"（AUSBC 无 DISCONNECTED 枚举，CLOSED 即挂断信号；分辨率切换中间态不误报）；④ showDeviceSwitcher 改 bondedDevices 优先（pairedDevices 可能含已解绑设备，解绑后永不清理）；⑤ onStop 清 targetSwitchDevice（防旋转/重建残留）；⑥ initInProgress 3s 超时复位（getProfileProxy 理论挂起兜底）；⑦ manualDisconnectFlag 清除入口核对——btnBt 连接/switchTo/autoPair 重开/onAppStatusChanged/连接成功均已清，P2-1 补齐 btHid==null 点击入口；⑧ btnBt 断开后立即 updateBtButton（核对：v0.2.9 已实现，无改动）；⑨ DESIGN.md §3.7.1 补 4 卡死点修复说明。
- **版本**：versionCode 2→3，versionName 0.2.9→0.2.10。
- **状态**：构建 assembleRelease 通过，已提交（commit shas 见 git log）。
