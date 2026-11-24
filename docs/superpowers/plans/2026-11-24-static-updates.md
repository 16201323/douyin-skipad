# Static APK Updates Implementation Plan

**Goal:** 通过 Cloudflare Pages 的公开版本 JSON 和 APK 直链自动下载，不在客户端使用账号、API Key 或服务端接口；保留 GitHub 备用。

**Architecture:** 静态来源和 GitHub 来源实现相同的 UpdateSource；独立 HTTP 读取器限制响应体、超时和重定向。静态清单只允许构建时配置的 HTTPS 站点及指定版本路径。现有 DownloadManager、网络策略和安装前校验继续使用。发布脚本从实际 APK 提取版本与摘要并生成网页上传目录。

**Tech Stack:** Kotlin、Android DownloadManager、Robolectric、PowerShell、Cloudflare Pages Direct Upload。

### 1. 版本与来源（先测试）

- [x] 新增 StaticUpdateTest，验证静态清单数字版本、包名、路径、大小、SHA256、版本号、序列化恢复及拒绝跨站/相对路径穿越。
- [x] 把 HTTP 来源移到 UpdateSources.kt，增加静态来源、失败时 GitHub 备用和有界读取；来源测试注入 HTTP 文本读取器，不访问真实网络。
- [x] UpdateRelease 保留旧 GitHub 清单解析，新增 schemaVersion=1 的静态清单，安装前额外核对清单 versionCode。

### 2. 下载与界面

- [x] 测试默认 Wi-Fi 和任意网络在静态来源下均生效；重新打开恢复来源和任务；网络下载失败只自动切换一次到该版本的固定 GitHub 地址，不对文件校验失败做自动放行。
- [x] 更新页改用通用版本描述、显示下载来源；保留原有历史版本入口和网络偏好。
- [x] 下载暂停区分网络等待、Wi-Fi 等待和连接失败重试；关闭自动检查与手动取消保持既有行为。

### 3. 部署与验证

- [x] Gradle 用 staticUpdateBaseUrl 构建参数配置真实 HTTPS 站点；没有真实地址时不填入虚构地址，明确显示未配置。
- [x] scripts/Prepare-UpdateSite.ps1 读取实际 APK 包名、versionName、versionCode、签名和 SHA256；生成 update.json、版本目录 APK、下载页、404 页和缓存响应头，限制 APK 不超过 Pages 25 MiB。
- [ ] 完整测试、Lint、构建、校验生成清单及部署包；通过用户登录的 Cloudflare 免费账号网页发布。
- [ ] 获取真实站点地址后构建接入包，核对公开版本信息与下载文件摘要；手机在线时验证下载与安装。未完成账号或手机步骤时如实记录，不以模拟测试声称端到端完成。

用户已批准本方案并要求开始实施。当前 Cloudflare 浏览器停留登录页，已请用户自行登录；不在聊天中收集账号密码。

用户随后选择自动发布：推送 main 后自动测试、构建、签名和上传。已准备 .github/workflows/android-release.yml，以及可在 Windows/Linux 运行的站点生成脚本。一次性创建 Cloudflare Pages Edit 发布令牌，并将其与现有签名密钥保存到指定 GitHub 仓库 Secrets，需要明确的凭据创建及密钥传输确认；确认前不执行这两项操作。

2026-11-24：用户已自行创建 Cloudflare 令牌及 CLOUDFLARE_API_TOKEN / CLOUDFLARE_ACCOUNT_ID。用户单独明确授权上传现有签名文件；已在本机加密后保存 ANDROID_SIGNING_KEYSTORE_BASE64，只核对 Secret 名称，不输出其内容。97 项本地回归通过，Lint 0 错误、9 警告，同签名构建检查通过。下一步执行首次真实 CI 发布。
