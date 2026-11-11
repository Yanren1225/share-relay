# 发版流程

本文档记录 ShareRelay 的发布流程，方便以后直接照做。

## 版本号规则

| 项目 | 规则 | 示例 |
| --- | --- | --- |
| Tag | `vMAJOR.MINOR.PATCH`（必须严格三段数字，否则 CI 直接失败） | `v0.2.0` |
| versionName | 去掉 `v` 的 tag | `0.2.0` |
| versionCode | `MAJOR*10000 + MINOR*100 + PATCH` | `0.2.0` → `200` |

本地默认版本在 `app/build.gradle.kts`（当前 `0.1.0` / `100`），CI 会用 tag 覆盖，本地构建不传参时保持默认值。

## 日常发版（三步）

```bash
# 1. 改代码，提交并推送
git add -A
git commit -m "feat: xxx"
git push origin main

# 2. 打 tag
git tag v0.2.0

# 3. 推 tag，触发自动发版
git push origin v0.2.0
```

推送 tag 后 GitHub Actions 会自动：

1. 解析 tag → `versionName` / `versionCode`；
2. 安装 JDK 17 和 Android SDK（`platforms;android-37.0` + `build-tools;36.0.0`）；
3. 从 Secrets 还原 keystore，生成 `keystore.properties`；
4. `./gradlew assembleRelease -PversionName=... -PversionCode=...`；
5. `apksigner verify` 验签（签名不对会直接失败）；
6. `gh release create ... --generate-notes`，把 `ShareRelay-x.y.z.apk` 挂到 Release 上。

大约 2–3 分钟，产物地址：`https://github.com/Yanren1225/share-relay/releases/tag/<tag>`。

## CI 配置（一次性）

Workflow 文件：`.github/workflows/release.yml`

需要在仓库 Settings → Secrets and variables → Actions 配置 4 个 secret：

| Secret | 值 |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | `keystore/release.jks` 的 base64 |
| `KEYSTORE_PASSWORD` | keystore 密码 |
| `KEY_ALIAS` | keystore alias |
| `KEY_PASSWORD` | key 密码 |

生成 base64（PowerShell）：

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("C:\Users\yanren\code\share-app\keystore\release.jks"))
```

keystore 与 `keystore.properties` 都在 `.gitignore` 里，不会进仓库；**keystore 必须自行备份，丢失后无法覆盖升级已发布版本**。

## 本地手动打包（CI 不可用时的备用方案）

```bash
# 1. 确认 keystore.properties 存在（storeFile 指向 keystore/release.jks）
# 2. 指定版本号构建
./gradlew assembleRelease -PversionName=0.2.0 -PversionCode=200

# 3. 验签
$ANDROID_HOME/build-tools/36.0.0/apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk

# 4. 手动发 Release（需要 gh 已登录）
gh release create v0.2.0 app/build/outputs/apk/release/app-release.apk \
  --title "ShareRelay 0.2.0" --generate-notes
```

## 常见问题

**1. CI 在 "Install SDK 37" 步骤失败**

曾经用过 `android-actions/setup-android@v3`，它会在新 runner 上执行 `sdkmanager tools`，报 `Failed to find package 'tools'`。现在的做法是直接用 runner 预装的 SDK：

```bash
sdkmanager --install "platforms;android-37.0" "build-tools;36.0.0"
ln -sfn "$ANDROID_HOME/platforms/android-37.0" "$ANDROID_HOME/platforms/android-37"
```

注意 SDK 37 的包名是 `android-37.0`，而 AGP 按 `platforms/android-37` 目录找，所以要建软链。

**2. 签名变更后用户无法覆盖安装**

`applicationId` 不变但签名变了会报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`，需要先卸载旧版再装（分享记录和设置会丢）。发版前尽量不要再换 keystore。

**3. tag 打错了想重发**

```bash
# 删远端 Release（可选，删了才会从列表消失）
gh release delete v0.2.0 --yes
# 删本地和远端 tag
git tag -d v0.2.0
git push origin :refs/tags/v0.2.0
# 修正后重新打 tag 推送
git tag v0.2.0 && git push origin v0.2.0
```

**4. Release notes 是空的/只有 Full Changelog**

首个 Release 没有上一个 tag 可对比，`--generate-notes` 生成不出内容。发布后可在 Release 页面点 **Edit** 手动补更新日志；后续版本会自动按 commit 生成。

**5. 想看某次构建日志**

```bash
gh run list --repo Yanren1225/share-relay
gh run view <run-id> --log-failed
```
