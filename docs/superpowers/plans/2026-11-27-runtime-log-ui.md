# 运行日志与紧凑界面实施计划

**Goal:** 用户在独立日志页看懂识别与跳过异常，首页保持简洁。

**Architecture:** 扩展RecognitionHistory的可选诊断字段与分类，独立DiagnosticReport负责展示和导出。RecognitionHistoryActivity只在可见且数据变化时刷新。保留现有检测器和跳过门槛，在退出分支补充有界日志。

**Tech Stack:** Kotlin、SharedPreferences、原生Android UI、Robolectric。

- [x] 新增日志兼容、分类、上限、导出和独立页测试，确认旧实现失败。
- [x] 扩展RecognitionHistory、实现DiagnosticReport和分段日志页，补充检测退出记录。
- [x] 使用AppUi统一导航行和返回按钮，压缩MainActivity首页、设置及权限子页。
- [x] 完整回归、Lint、浅深界面渲染检查；不改变用户开关、主题、规则或隐私权限。
- [x] 按后续要求补查抖音/极速版跨版本兼容，复现并修复介绍栏导致旧锚点漏查询，补充节点回归及验证边界说明。
- [ ] 同签名构建与自动发布，重新下载公开APK核对版本、签名和摘要。
