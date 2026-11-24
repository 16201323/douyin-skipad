# 可见播放器主窗及失焦小窗识别修复

用户报告：抖音主窗上叠加高德小窗后不能自动跳广告；并明确要求抖音自身为小窗、焦点在其他应用时，也应继续识别。

现场元数据确认：高德 NewMapActivity 获得焦点，抖音 PlayletVideoPlayActivity 仍可见。旧代码的前台判断、广告证据、点击兜底均只读 rootInActiveWindow；无障碍 XML 也没有 FLAG_RETRIEVE_INTERACTIVE_WINDOWS，导致把地图焦点视为不在目标应用。

实现使用 getWindows() 获取当前交互窗口，最多24个；仅按根节点的包名验证启用目标，只有选中的目标才查询广告控件。计算窗口与屏幕的交集并减去上层窗口覆盖区域，从仍可见的已启用目标中选择唯一窗口；焦点只用于可见面积相同时的选择，不作为必要条件。完全被一或多个上层窗口遮住、移出屏幕、不可读的窗口不回退旧缓存。

检测、发送前复核及点击兜底使用同一窗口选择方式，广告 scope 绑定实际窗口 ID。媒体指令仍只作用于该包名的一个会话，发送前再次验证窗口 ID；点击兜底还要求按钮中心未被其他窗口遮挡。锁屏与关闭自动开关保留原来的保护。

- [x] 先写主窗受地图小窗抢焦点、目标自身小窗失焦等用例，复现旧实现失败。
- [x] 增加 VisibleWindowPolicy，启用无障碍多窗口标志；改用可见目标，保持去重、二次确认和媒体会话隔离。
- [x] 增加完全覆盖、多个遮挡窗口的并集、屏幕外、锁屏、禁用目标、遮挡点击、窗口替换和窗口尺寸改变的测试。
- [x] 完整回归、Lint、同签名构建与两种方向的真机场景检查；识别与完整跳过的实测证据分别记录如下。

2026-11-24 真机阶段记录：修复验证包读到目标 window=6502，地图小窗边界为[-18,1562][634,2721]，抖音主窗[0,0][1240,2772]。地图仍显示时已记录 HIGH 广告证据、自动发送及1572ms后广告消失确认；这次成功记录时焦点在抖音，不能作为失焦目标真机验证的替代。目标自身小窗失焦现场正在等待用户准备。

官方接口依据：
- https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#getWindows()
- https://developer.android.com/reference/android/view/accessibility/AccessibilityWindowInfo

getWindows 返回默认显示器上可交互窗口，按层级排序；需 canRetrieveWindowContent 与 FLAG_RETRIEVE_INTERACTIVE_WINDOWS。窗口边界不是不透明度保证，本实现对上层窗口覆盖保守处理，保留真实设备窗口装饰差异的验证记录。

2026-11-24 21:00 补充现场：展开抖音小窗并失焦后，系统报告抖音窗口6585与上层 ColorOS 系统装饰层6562具有相同外接矩形[739,138][1298,1132]，但装饰层的实际 region 只占标题栏、圆角与边框。1.5.1 的矩形差集把透明中心也当成遮挡，因而没有选中目标。这与此前缩成右上角预览（仅暴露 TYPE_SYSTEM、没有应用节点）是两种不同状态。

已先新增真实形状回归，确认旧实现失败；1.5.2 改为 Android 11/API30+ 调用 getRegionInScreen，使用 Region 差集计算可操作面积，按钮中心同样检查真实区域。旧系统仍采用矩形。13 项多窗口测试、115 项完整回归通过；Lint 0 错误、9 警告，同签名构建及真机覆盖安装成功。

1.5.2 真机日志21:00:39及21:00:44均选中抖音 window6632，bounds=[687,1210][1246,2204]、eligible=true、focus=false，其他主窗口持有焦点。确认透明装饰层下的失焦目标已被选中；当时正常短剧画面没有广告，尚不能将这条窗口选择记录当作完整广告跳过成功。测试没有改动其他应用内容或授予新权限。

区域接口依据：https://developer.android.com/reference/android/view/accessibility/AccessibilityWindowInfo#getRegionInScreen(android.graphics.Region) 。该接口提供可触摸区域，不是通用像素不透明度检测；对系统未暴露节点的缩略预览仍不猜测广告或发送盲跳。

后续完整跳过验证：21:02:07 同一抖音小窗6632识别 HIGH、automatic=true 发送请求，21:02:09 确认广告消失，request_to_confirm_ms=1239。21:02:46 再次在 scope=com.ss.android.ugc.aweme:6632 记录 MEDIUM→HIGH，自动请求后1357ms确认消失，随后恢复 NONE 正常播放器证据。期间21:02:21、21:02:25的窗口选择均为抖音 focus=false、eligible=true，主窗口焦点在本应用；随后系统窗口元数据也确认该失焦小窗仍为6632。因此失焦小窗已有完整识别、请求、画面消失确认的真机证据，非仅模拟或媒体请求发送成功。精简日志保存在本机 app/build/deployment/1.5.2-unfocused-recognition.log，不包含页面全文。

最终发布：提交10ba0ba触发 Actions run45399330135，全部步骤成功，GitHub Release v1.5.2和Pages均发布。重新下载公开APK，大小2836805字节，SHA256=224ed143fecbd01560f589e57dc6877c2bfb154711bd8aabdbab9f2477a1e8de；包名、versionCode14及既有签名核对成功。该公开文件已保存至项目根目录 douyin-skipad-release.apk 并覆盖安装到手机；安装后系统确认无障碍已绑定、前台服务正在运行。公开地址：https://douyin-skipad-updates.pages.dev/releases/v1.5.2/douyin-skipad-release.apk 。
