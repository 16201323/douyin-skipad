# 悬浮窗拖动卡顿修复

用户授权修复并延续手机部署。已有证据：广告节点读取由 main Handler 执行，真机 85ms～258ms；拖动每个 MOVE 同步 updateViewLayout，DOWN 返回 false。当前 gfxinfo 23 帧中 4 帧卡顿，90/95 分位 97/117ms（非严格受控基准）。

- [x] 先写并运行失败测试：触摸 DOWN 消费、多个 MOVE 合并一帧、末次坐标准确、广告节点读取不在主线程。
- [x] 将检测、确认、媒体控制和节点读取放到单一 HandlerThread；UI/窗口操作保留主线程。停止服务后禁止旧任务继续执行。
- [x] 按下到松手/取消期间暂停自动检测，并丢弃已有候选；重查询后校验拖动与启停状态，防止过期识别发出指令。
- [x] MOVE 只更新目标坐标，postOnAnimation 合并窗口移动；UP/CANCEL 刷新最后坐标，退出拖动并恢复检测。
- [x] 缓存绘图对象；图片缓存命中不每帧访问文件，配置刷新时检查图片变化。
- [x] 运行全部回归、Lint、Release 构建。覆盖部署 1.0.3 / versionCode 4，确认线程、服务与权限；不把非受控帧统计当作绝对流畅保证。


## 验证结果

- 原实现新增三项测试全部失败：DOWN 未消费、MOVE 未合并、节点读取位于 UI 主线程；另验证了图片文件每次重绘被重新检查以及 UP 坐标与末次 MOVE 不同时的位置偏差。
- 最终 45 项测试通过：包含真正后台线程的慢读屏阻塞试验，验证查询未返回时 UI 仍可移动窗口；拖动期间事件和轮询不发起额外节点读取；普通时序测试使用可控虚拟 Looper，线程隔离测试使用真实 HandlerThread。
- `:app:testDebugUnitTest :app:lintDebug :app:assembleRelease` 成功；Lint 0 错误、9 条警告。
- 2026-11-24 02:11:42（北京时间）最终覆盖安装到 f610d31c，versionName=1.0.3、versionCode=4。前台悬浮服务、通知监听与无障碍绑定正常，进程中存在 SkipAdDetection 独立线程；本次本进程启动日志未见 AndroidRuntime 崩溃。
- 真机进行了两次短距离按钮拖动操作。gfxinfo 重置后没有记录窗口位置移动的绘制帧（0 frames），因此该结果不能计算改善百分比或作流畅度保证；不将其 0 janky frames 当作证据。
- 已保留原有样式、自动跳过开关与 495ms 补充检测间隔。根目录 APK 与构建输出一致，1.0.2 安装包备份在 app/build/deployment/previous-1.0.2-release.apk。
