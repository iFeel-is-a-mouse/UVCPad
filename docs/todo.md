# uvcpad M1 待办（骨架整合）

> 依据：docs/DESIGN.md（已过 D3 评审）。状态图例：✅ 完成 / ⏳ 进行中 / ⬜ 未开始。

## M1 骨架整合（验收：画面显示 + 光标可动）

- [x] **工程骨架**：projects/uvcpad/android/ 新建；gradle/settings/manifest/res 复制整理；`UvcpadApplication` + `CrashHandler`；MainActivity 可编译运行（assembleDebug 通过）
  - 包名 com.github.ifeel.uvcpad；minSdk 28；compileSdk/targetSdk 36；AGP 8.10.1 + Kotlin 2.2.10 + Gradle 9.3.1
  - 依赖：core-ktx 1.18.0 / appcompat 1.7.1 / material 1.13.0 / constraintlayout 2.2.1 / AUSBC 3.6.0（JitPack）
- [x] **显示链路**：activity_main.xml（cameraViewContainer + touchLayer + errorText）；getCameraRequest（1920×1080/MJPEG/OPENGL/captureRawImage）/ getCameraView（AspectRatioTextureView）/ onCameraState / USB 授权提示 / 串行权限（相机→蓝牙→定位→存储，DESIGN §1.4）
- [x] **蓝牙链路**：BluetoothController（鼠标描述符 + "uvcpad" 设备名 + SUBCLASS1_MOUSE + 提示串）+ DescriptorCollection（仅鼠标描述符）+ reports/senders/listeners/SpeedLevel；BT 权限；onStart init；连接回调装配
- [x] **透明触控层**：TransparentTouchLayer（全屏透明不绘制）+ 连接挂载手势 / 断开先卸监听再置空（DESIGN §3.7）
- [x] **联调自测**：`:app:assembleDebug` 构建成功；静态自检对照 DESIGN §3.5/§3.6/§3.7 逐行核对通过
- [x] **git**：projects/uvcpad/ git init + commit
- [ ] **push**：remote 尚不存在（需先创建 github 仓库 iFeel-is-a-mouse/uvcpad），创建后 push
- [ ] **真机联调（验收前必需，待 main 安排真机/外设环境）**：
  - 采集卡插入即全屏显示（1080p 默认）
  - 蓝牙配对（PC 搜索 "uvcpad"）后单指移动光标跟手、tap 左键生效
  - 拔插采集卡 / 蓝牙断连不崩溃，错误提示可见
  - 触控层与渲染互不阻塞

## M2 待办（透明层全量 + 交互入口）

- [ ] DropTriangleView（下拉三角，事件豁免区）
- [ ] KeyBarPanel + KeyBarController（4s 自动隐藏、可配置、改造自 hdmi2mp 工具栏计时）
- [ ] 按键栏菜单：速度 5 档循环 / 蓝牙状态+设备切换 / 自动配对 / 分辨率 / 截图 / 退出（无键盘项）
- [ ] 分辨率切换接线（switchMode 恢复 button 参数或按键栏直接调用）
- [ ] 手势全量回归（两指滚动/双指右键/长按拖拽/双击拖拽 + 三角/按键栏边界）
- [ ] 真机标定 speed 系数与 ramp（DESIGN §6.3）
- [ ] 坐标映射调参 + 性能监控（主线程负载 / sendReport 迁移评估）
