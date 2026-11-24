# 静态更新发布

正式更新源：<https://douyin-skipad-updates.pages.dev>。此地址属于本项目的 Cloudflare Pages，不要求安装用户注册、登录或提供 API Key。GitHub 仍保存历史版本并作为网络故障备用源。

## 自动发布（默认）

用户已选择 GitHub Actions 自动构建并上传。一次性私密配置完成后，日常发布只需要增加版本号、写对应的 docs/releases/v版本号.md，再推送 main。

工作流 `.github/workflows/android-release.yml` 自动执行测试、Lint、使用既有密钥签名构建、生成静态清单、发布 GitHub Release、部署 Cloudflare Pages，以及从公网重新下载核对 SHA256。失败不会伪装为发布成功。没有增加版本号时，会复用已经发布的同版本 APK，不覆盖旧附件；拒绝把站点回退到更低版本。

需要的 GitHub Actions repository secrets（只保存到 16201323/douyin-skipad）：

- `ANDROID_SIGNING_KEYSTORE_BASE64`：当前安装包对应的签名文件。文件不可提交到源码或日志；CI 仅在临时目录解码，结束时删除。当前密钥沿用本机原有 Android debug 密钥及其默认密码和别名。
- `CLOUDFLARE_API_TOKEN`：仅当前 Cloudflare 账户的 Cloudflare Pages Edit 权限，不授予 DNS、域名、账单或其他产品权限。
- `CLOUDFLARE_ACCOUNT_ID`：站点所属账户标识。

`.github/release-signing-sha256.txt` 是公开签名证书摘要，发布脚本将实际 APK 证书与其比较，避免 CI 换密钥导致手机无法覆盖安装。密钥、发布令牌不会嵌入 APK、静态站点或公开仓库。

## 网页手动发布（备用）

1. 保持现有签名密钥，增加 `app/build.gradle.kts` 的 versionCode 和 versionName，编写对应发布说明。
2. 使用 JDK 17、SDK 34 和 Gradle 8.7 执行 `gradle :app:testDebugUnitTest :app:lintDebug :app:assembleRelease`。
3. 在项目根目录运行：

```powershell
./scripts/Prepare-UpdateSite.ps1 -NotesPath 'docs/releases/v1.5.0.md'
```

脚本从实际 APK 读取包名、版本名称、版本编号，并验证签名、计算大小及 SHA256，不手工填写摘要。输出 `build/update-site` 及可直接上传的 `build/update-site.zip`。APK 超过 Pages 25 MiB 限制时停止生成。

4. 登录 Cloudflare 网页后台，进入 Workers & Pages → douyin-skipad-updates → Create deployment，选择 Production，上传整个 ZIP 并部署。只上传脚本生成的目录，不上传源码、local.properties 或签名密钥。
5. 打开站点的 `/update.json`，核对版本和 versionCode。下载清单 `apk` 指向的文件，核对其 SHA256 与本地 APK 一致。
6. 同时把同一份 APK 上传 GitHub 对应版本 Release，使备用地址真实有效；不得在相同版本路径覆盖不同内容的 APK。

生成站点将 update.json 标为不缓存，版本 APK 标为长期缓存。新部署必须同时包含最新清单和最新 APK。若需要在站点保留以前的 APK，可复用生成目录中的旧版本目录；GitHub 历史版本入口一直保留。

## 构建来源配置

站点根地址写在 gradle.properties 的 staticUpdateBaseUrl，也可在构建时用 `-PstaticUpdateBaseUrl=https://实际站点` 覆盖。只接受不带路径、参数和账号信息的 HTTPS origin。换地址后必须重新构建并安装客户端，不能仅在网页中任意指定第三方下载站。

默认先请求 `/update.json`，失败后尝试固定 GitHub 仓库的最新正式版。静态 APK 下载网络错误时最多切换一次到同版本 GitHub 直链；存储错误或包校验失败不会触发此切换。Wi-Fi、计费网络及漫游限制在两种下载来源中保持一致。

首次从 1.4.1 升级需要手动安装一次 1.5.0：旧客户端只认识 GitHub 来源，无法自动读取新站点。安装后后续新版才会使用新来源自动检查和下载；安装仍由用户按系统提示确认。
