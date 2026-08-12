# uvcpad 开发旅程（journey）

> 简记 uvcpad 从需求到 M1 完成的开发历程。砍掉过程琐碎，留下决策与结论。
> 关联：`docs/PROPOSAL.md`（需求）、`docs/DESIGN.md`（设计）、`docs/todo.md`（M2 待办）。

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

## 关键 commit 对照

| commit | 内容 |
|---|---|
| 4ed1f58 | M1 骨架整合：hdmi2mp 显示链路 + KeysJoy 鼠标触控链路合体（工程骨架/显示链路/蓝牙链路/透明触控层，assembleDebug 通过） |
| 4abdc04 | M1 评审修复：onResume 双重 init 合并单路径；CrashHandler 品牌字样 hdmi2mp→uvcpad |
| 2cf23ba | P2 修复：首次启动（Android 12+）蓝牙 HID 注册不生效——权限串行链尾补触发 init（permissionDialogShown 标志） |
| 2bd498b | 触控区域 = 显示区域：触控层动态对齐 AspectRatioTextureView 显示矩形（方案 A） |
| 6d36a24 | 触控对齐评审修复：P1 修正 DESIGN §3.2.1 时序描述；P2 getChildAt(0) 加 TextureView 类型防御；P3 注释布局循环 |

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
