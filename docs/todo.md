# uvcpad 待办清单（todo）

> 状态图例：✅ 完成 / ⏳ 进行中 / ⬜ 未开始
> M1 已完成（2026-08-12，编码+构建通过）；M2 交互入口已实现，v0.2.4→v0.2.9 迭代中（**质量修复批次已完成**，2026-08-13）。本清单以 **M2 / v0.2.x 待办** 为主，来源：reviewer / tester / auditor 评审记录 + main 决策 + 状态机评审（2026-08-13）。
> 关联：`docs/PROPOSAL.md`（需求）、`docs/DESIGN.md`（技术设计）、`docs/journey.md`（开发历程）。
>
> **2026-11-09 真机实测：用户确认「已经实测可以用了」**——编码侧功能在真实设备上跑通（显示 + 触控两条链路）。细项待用户补充后逐项勾选：实际协商分辨率 / 手势全量 / 速度手感 / 墨水屏观感。

---

## M1 收尾遗留（进入 M2 处理）

- [ ] **push 上游**：remote 已配置 `git@github.com:iFeel-is-a-mouse/uvcpad.git`（收尾三件套时加入），但本地尚无 `origin/main` ref、未执行 push；确认 github 仓库创建后 push 全部 commit
- [ ] **真机联调（M1 验收，需用户提供 MS2130 采集卡 + PC 蓝牙配对环境）**：
  - 采集卡插入即全屏显示（默认 4:3 1872×1404；16:9 档真机协商失败回退 1600×1200 时如实显示并 toast 提示）
  - PC 搜索 "uvcpad" 配对后单指移动光标跟手、tap 左键生效
  - 拔插采集卡 / 蓝牙断连不崩溃，错误提示可见
  - 触控层与渲染互不阻塞（渲染 60fps 时触摸无卡顿）

---

## M2 待办

### 显式旋转按钮（✅ 编码完成 2026-11-09，真机待验）

- [x] **[uvcpad-rotate]** 按键栏新增独立 `btnRotate`（分辨率按钮右侧，不改自动横竖屏）：点击循环 0→90→180→270→0，写 `prefs.rotation_type`（0..3，默认 0），启动恢复 → `CameraRequest.setDefaultRotateType` + 相机视图 90/270 交换宽高比；旋转走「closeCamera + 1s 后 reopen」（AUSBC 3.6.0 无运行时 surface 尺寸更新路径，javap 已核实）
- [x] **[uvcpad-rotate]** 触摸映射：`TransparentTouchLayer` 用 `MotionEvent.transform(Matrix)` 把坐标反旋转回未旋转坐标系（90°→(-dy,dx) / 180°→取反 / 270°→(dy,-dx)），多指与历史采样自动覆盖，DOWN 事件归属不变
- [ ] 真机验证四个角度的**手势方向一致性**与**旋转后黑边位置**（构建已通过，APK 待安装实测）

### 🔴 墨水屏适配：调研已完成 ✅，真机验证待用户

- [x] **墨水屏适配调研（2026-08-12 → 08-13 完成）**，结论：
  - **采集卡不支持 1080p 是硬件限制**：真机 1920×1080 协商失败 → AUSBC 按最近宽度回退 **1600×1200**（4:3 比例、非预设值）——非软件可修，回退如实显示即可（d7de1b0 按比例归类后 16:9 档仍可请求）
  - **分辨率档位已进按键栏** + 记忆改**模式枚举**（74d25a0），默认 4:3（1872×1404）对墨水屏负担更轻
  - **定位门禁已放宽**：BLUETOOTH_SCAN 声明 `neverForLocation` → S+ 豁免定位权限（4ed1f58 已含，DESIGN §1.3 已同步）
  - **RECORD_AUDIO 来源确认**：AUSBC 库 manifest 合并带入（merged manifest 中仍存在），uvcpad 自身不声明；剔除与否见 v0.2.9 质量批次
- [ ] **MatePad Paper 真机验证 UVC 兼容性 + 快速模式画面可接受度**（墨水屏为预期主要载体；**需用户真机**）
- [ ] **必要时启用流畅模式 / 残影优化**（墨水屏残影控制，视真机观感决定，**需用户真机**）

### v0.2.9 质量修复批次（✅ 已完成 2026-08-13）

> 状态机评审（2026-08-13 main 侧提出）P1/P2 项已全部修复并提交：**96e6b58**（状态机收口 P1/P2）/ **21e6640**（housekeeping + RECORD_AUDIO 剔除）/ **ff9abf4**（3 处 SecurityException 兜底）/ **bf122c4**（registerApp 重试收尾）。

- [x] **P1 手动断开重连冲突**：`manualDisconnectFlag` 区分"用户主动断开"与"意外断开"，抑制 DISCONNECTED 自动重连 / startAutoReconnect 循环 / onAppStatusChanged 自动连接三处（96e6b58 P1-1）
- [x] **P1 onServiceDisconnected 残留**：清理 hostDevice/mpluggedDevice、stopAutoReconnect、invoke disconnectListener（96e6b58 P1-2）
- [x] **P2 单例泄漏**：`clearListeners()` 在 MainActivity.onDestroy 置空 deviceListener/disconnectListener/statusListener（96e6b58 P1-3，auditor P3 同源一并解决）
- [x] **P2 卡死点**：initInProgress 短路双 getProfileProxy、S1 注册失败重试、S7 重连 btHid==null 终止、S8 connect 重试、USB 授权超时重置（96e6b58 P2 系列）
- [x] **RECORD_AUDIO 剔除**：Manifest `tools:node="remove"` 剔除 AUSBC 合并带入的录音权限（AudioSource.NONE 不需要）——已提交于 **21e6640**（P2-7）；同批另有 `allowBackup=false`、camera `required=false`（P3-4/P3-7）

### reviewer（P2）

- [x] **分辨率硬编码 1080p 进菜单**：✅ 按键栏分辨率按钮（11024e4 拆分 1080p/4:3 独立点选）+ 按宽高比判断档位（d7de1b0）+ 记忆改模式枚举（74d25a0）
- [x] **非首次启动仅相机权限撤销时 getSender 双重注册边界**：`permissionDialogShown` 标志只覆盖"弹过对话框"路径；非首次启动仅撤销相机权限时，onStart/链尾 init 与 onResume 装配的 getSender 注册边界需核对——**已并入 v0.2.9 状态机评审核对**：initInProgress 标志短路 onStart+onResume 双 getProfileProxy（96e6b58 P2-1）

### tester（P2）

- [x] **setScanMode 反射记录**：✅ 代码已补注释（compileSdk 36 android.jar 无此 API → 反射，KeysJoy 同款）+ 失败降级提示（"Warning: not discoverable, tap to enable"，Android 12+ 可发现性行为已说明）
- [ ] **3 处 deprecation 告警清理**（仍存在）：`getDefaultAdapter`（MainActivity ×3 / BluetoothController）、`GestureDetectorCompat`（ViewListener）、`VIBRATOR_SERVICE`（ViewListener）——KeysJoy 原样代码带入，另有 inline class 警告一并处理
- [x] **无测试目录，补冒烟测试**：✅ [uvcpad-rotate-test]（2026-11-09）建立 `app/src/test` JVM 单测基建（Robolectric SDK 28 + Mockito inline），56 个用例覆盖旋转功能的模拟输入/模拟输出；`:app:testDebugUnitTest` 一条命令跑完，CI 已加 `.github/workflows/android.yml`。原计划的 `androidTest` 真机冒烟仍可后续按需补。

### auditor（P3）

- [ ] **CrashHandler KDoc 旧引用**（仍存在）：`CrashHandler.kt` 仍写 "Registered from Hdmi2mpApplication.onCreate" → 改 UvcpadApplication
- [x] **BluetoothController 单例监听未清空**：**已并入 v0.2.9 质量批次"单例泄漏"项**一并修复——`clearListeners()` 置空监听（96e6b58 P1-3）
- [ ] **root build.gradle 注释残留 hdmi2mp**（仍存在）：首行 "Top-level build file for hdmi2mp" → uvcpad

### M2 功能主体（DESIGN §5.2，评审无异议项按原设计推进）

- [x] DropTriangleView（下拉三角，事件豁免区：点三角不产生鼠标报告）
- [x] KeyBarPanel + KeyBarController（4s 自动隐藏、时长可配置，读 auto_hide_ms）
- [x] 按键栏菜单：速度 5 档循环 / 蓝牙状态+设备切换 / 自动配对 / 分辨率 / 截图 / 退出（**无键盘设置项**）
- [x] 分辨率切换接线：按键栏按钮调用参数化 `switchMode` + 点击写 `prefs.resolutionMode` 模式枚举（74d25a0）
- [x] 按键栏 UI 打磨：按钮统一 `KeyBarButton` style + 14sp 字号 → 自然等高（0b45e2e/21a958b）；App 图标补 legacy mipmap 兜底 PNG（ee62cb1）
- [ ] 手势全量回归（两指滚动/双指右键/长按拖拽/双击拖拽 + 三角/按键栏边界交互，**需用户真机**）
- [ ] 真机标定 speed 系数与 ramp（DESIGN §6.3，**需用户真机**）
- [ ] 坐标映射调参 + 性能监控（主线程负载 / sendReport 迁移 HandlerThread 评估）

### 旋转测试未覆盖缺口（[uvcpad-rotate-test2] 登记 2026-11-09）

> tester 独立验证 `06f96e0`（56 用例全绿、6 个变异全部杀死）后确认的剩余盲区。本轮已补：RotationSpec 纯逻辑（循环/角度/RotateType 映射）、竖屏 1200×1600 的 90°/270° 旋转中心与坐标变换、`RelativeMouseSender.emitReport` 生产路径（SecurityException / sendReport=false / 权限守卫），并清理 3 处测试卫生问题。以下仍未受测试保护，按优先级登记。

- [ ] **P2 `alignToDisplayRect`（TransparentTouchLayer）**：layoutParams 写入分支与「值不变提前返回」分支零覆盖。补法：Robolectric 中挂到 FrameLayout 父容器，断言 margin/width/height 生效，二次调用同 rect 不触发重布局（可用 shadow layout 次数或对象同一性）。
- [ ] **P2 `onAnyTouch`（TransparentTouchLayer）**：任意触摸都应回调（按键栏自动隐藏计时重置）——当前只测了 gesture listener 收发，未断言 onAnyTouch 的触发（含无 listener 的 pass-through 分支）。
- [ ] **P2 `migrateResolutionMode`（UvcpadPrefs）**：旧 `resolution_w/h` → 模式枚举的一次性迁移零覆盖：16:9 / 4:3 分类（含 1600×1200 归 4:3）、旧键删除、二次构造幂等。
- [ ] **P3 `applyRotation` 竞态（MainActivity）**：`resolutionReopenInFlight` / `rotationPendingAfterResolution` / `rotationReopenInFlight` 三条交错路径（旋转中切换分辨率、reopen 中双击旋转、无相机时只持久化）无状态机级测试；需抽出可测状态机或 ActivityScenario + 可控 Handler。
- [ ] **P3 CI 未在真实 runner 跑过**：`.github/workflows/android.yml` 已提交但从未在 GitHub Actions 执行；首次 push 后需确认 JDK 21 + Gradle 9.3.1 + Robolectric/Mockito 依赖下载与 `assembleDebug` 全绿。
