# uvcpad 待办清单（todo）

> 状态图例：✅ 完成 / ⏳ 进行中 / ⬜ 未开始
> M1 已完成（2026-08-12，编码+构建通过）；本清单以 **M2 待办** 为主，来源：reviewer / tester / auditor 评审记录 + main 决策（2026-08-12）。
> 关联：`docs/PROPOSAL.md`（需求）、`docs/DESIGN.md`（技术设计）、`docs/journey.md`（开发历程）。

---

## M1 收尾遗留（进入 M2 处理）

- [ ] **push 上游**：github 仓库 `iFeel-is-a-mouse/uvcpad` 尚未创建，创建后 push 全部 5 个 commit（4ed1f58/4abdc04/2cf23ba/2bd498b/6d36a24）
- [ ] **真机联调（M1 验收，需用户提供 MS2130 采集卡 + PC 蓝牙配对环境）**：
  - 采集卡插入即全屏显示（1080p 默认）
  - PC 搜索 "uvcpad" 配对后单指移动光标跟手、tap 左键生效
  - 拔插采集卡 / 蓝牙断连不崩溃，错误提示可见
  - 触控层与渲染互不阻塞（渲染 60fps 时触摸无卡顿）

---

## M2 待办

### 🔴 第一优先级：墨水屏适配（main 决策 2026-08-12）

- [ ] **MatePad Paper 真机验证 UVC 兼容性 + 快速模式画面可接受度**（墨水屏为预期主要载体，先确认采集链路在墨水屏上可用、画面可读）
- [ ] **分辨率降到墨水屏舒适档**（当前硬编码 1080p 对墨水屏负担过重，结合"分辨率进菜单"一并落地）
- [ ] **必要时启用流畅模式 / 残影优化**（墨水屏残影控制，视真机观感决定）

### reviewer（P2）

- [ ] **分辨率硬编码 1080p 待 M2 进菜单**：`MODE_1080P_W/H = 1920×1080` 写死于 `getCameraRequest`（MainActivity），M2 按键栏提供分辨率切换入口（4:3 预设已预留，DESIGN §5.1）
- [ ] **非首次启动仅相机权限撤销时 getSender 双重注册边界**：`permissionDialogShown` 标志只覆盖"弹过对话框"路径；非首次启动仅撤销相机权限时，onStart/链尾 init 与 onResume 装配的 getSender 注册边界需在 M2 装配按键栏时核对（reviewer 记录）

### tester（P2）

- [ ] **AUSBC 合并引入的 RECORD_AUDIO 权限处理**：检查 AUSBC 库 manifest 合并带入的 RECORD_AUDIO 权限，uvcpad 不需要录音，如无必要则以 `tools:node="remove"` 剔除
- [ ] **setScanMode 反射记录**：`BluetoothController` 用反射调用隐藏 API `setScanMode`（compileSdk 36 android.jar 无此 API），补注释说明 Android 12+ 可发现性行为与失败降级
- [ ] **3 处 deprecation 告警清理**：`getDefaultAdapter`（MainActivity/BluetoothController）、`GestureDetectorCompat`（ViewListener）、`VIBRATOR_SERVICE`（ViewListener）——KeysJoy 原样代码带入，另有 inline class 警告一并处理
- [ ] **无测试目录，M2 补冒烟测试**：`app/src` 下仅有 `main`，M2 建立 `androidTest` 骨架 + 启动冒烟用例（应用可启动、权限链不崩）

### auditor（P3）

- [ ] **CrashHandler KDoc 旧引用**：`CrashHandler.kt` L63 仍写 "Registered from Hdmi2mpApplication.onCreate"，改为 UvcpadApplication
- [ ] **BluetoothController 单例监听未清空**：`object BluetoothController` 的 `deviceListener`/`disconnectListener` 无清理路径（KeysJoy 同款），评估 Activity 重建场景的影响后补清空
- [ ] **root build.gradle 注释残留 hdmi2mp**：首行 "Top-level build file for hdmi2mp" → uvcpad

### M2 功能主体（DESIGN §5.2，评审无异议项按原设计推进）

- [x] DropTriangleView（下拉三角，事件豁免区：点三角不产生鼠标报告）
- [x] KeyBarPanel + KeyBarController（4s 自动隐藏、时长可配置，读 auto_hide_ms）
- [x] 按键栏菜单：速度 5 档循环 / 蓝牙状态+设备切换 / 自动配对 / 分辨率 / 截图 / 退出（**无键盘设置项**）
- [x] 分辨率切换接线（按键栏按钮直接调用参数化 `switchMode`）
- [ ] 手势全量回归（两指滚动/双指右键/长按拖拽/双击拖拽 + 三角/按键栏边界交互）
- [ ] 真机标定 speed 系数与 ramp（DESIGN §6.3）
- [ ] 坐标映射调参 + 性能监控（主线程负载 / sendReport 迁移 HandlerThread 评估）
