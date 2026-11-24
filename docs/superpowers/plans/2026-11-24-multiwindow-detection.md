# 可见播放器主窗及失焦小窗识别修复

用户报告：抖音主窗上叠加高德小窗后不能自动跳广告；并明确要求抖音自身为小窗、焦点在其他应用时，也应继续识别。

现场元数据确认：高德 NewMapActivity 获得焦点，抖音 PlayletVideoPlayActivity 仍可见。旧代码的前台判断、广告证据、点击兜底均只读 rootInActiveWindow；无障碍 XML 也没有 FLAG_RETRIEVE_INTERACTIVE_WINDOWS，导致把地图焦点视为不在目标应用。

实现使用 getWindows() 获取当前交互窗口，最多24个；仅按根节点的包名验证启用目标，只有选中的目标才查询广告控件。计算窗口与屏幕的交集并减去上层窗口覆盖区域，从仍可见的已启用目标中选择唯一窗口；焦点只用于可见面积相同时的选择，不作为必要条件。完全被一或多个上层窗口遮住、移出屏幕、不可读的窗口不回退旧缓存。

检测、发送前复核及点击兜底使用同一窗口选择方式，广告 scope 绑定实际窗口 ID。媒体指令仍只作用于该包名的一个会话，发送前再次验证窗口 ID；点击兜底还要求按钮中心未被其他窗口遮挡。锁屏与关闭自动开关保留原来的保护。

- [x] 先写主窗受地图小窗抢焦点、目标自身小窗失焦等用例，复现旧实现失败。
- [x] 增加 VisibleWindowPolicy，启用无障碍多窗口标志；改用可见目标，保持去重、二次确认和媒体会话隔离。
- [x] 增加完全覆盖、多个遮挡窗口的并集、屏幕外、锁屏、禁用目标、遮挡点击、窗口替换和窗口尺寸改变的测试。
- [ ] 完整回归、Lint、同签名构建与两种方向的真机场景检查。

2026-11-24 真机阶段记录：修复验证包读到目标 window=6502，地图小窗边界为[-18,1562][634,2721]，抖音主窗[0,0][1240,2772]。地图仍显示时已记录 HIGH 广告证据、自动发送及1572ms后广告消失确认；这次成功记录时焦点在抖音，不能作为失焦目标真机验证的替代。目标自身小窗失焦现场正在等待用户准备。

官方接口依据：
- https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#getWindows()
- https://developer.android.com/reference/android/view/accessibility/AccessibilityWindowInfo

getWindows 返回默认显示器上可交互窗口，按层级排序；需 canRetrieveWindowContent 与 FLAG_RETRIEVE_INTERACTIVE_WINDOWS。窗口边界不是不透明度保证，本实现对上层窗口覆盖保守处理，保留真实设备窗口装饰差异的验证记录。
