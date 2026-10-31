# CloudPlay Android

多网盘云播客户端（Android）—— [cloudplay](../) Python 内核的 Kotlin 原生移植。

**网盘只当存储，播放由内核统一负责**：适配器取直链 + 本地流代理（Range 透传 / HLS 分片改写）→ Media3 ExoPlayer 播放。
内置 WebView 用于网盘网页登录并抓取 Cookie（只做登录，不做通用浏览器）。

## 架构

```
┌──────────────┐   listFiles / getLink   ┌────────────────────┐
│  任意网盘     │◄───────────────────────│ CloudDiskAdapter   │  ← 唯一扩展点
│ 夸克/UC/阿里  │                         │  （适配层）         │
│ 123/115/189   │                         └────────┬───────────┘
│ 本地目录      │                                  │ PlaySource{url, headers}
└──────────────┘                                  ▼
                                        ┌────────────────────────┐
                                        │ StreamProxy (127.0.0.1)│  ← 云播解耦关键件
                                        │ Range 透传 + 头注入     │
                                        │ + m3u8 分片改写         │
                                        └────────┬───────────────┘
                                                 │ http://127.0.0.1:port/s/<token>[.ts]
                                                 ▼
                                        ┌────────────────────────┐
                                        │ Media3 ExoPlayer       │
                                        └────────────────────────┘
```

模块：

| 包 | 职责 |
|---|---|
| `core/` | CloudFile / PlaySource / CloudDiskAdapter / 注册表 |
| `adapters/` | local、quark、uc、aliyun、123、115、189 |
| `net/` | OkHttp 底座（query 拼装 / JSON / 可关重定向） |
| `stream/` | StreamProxy（GET/HEAD/Range/206/m3u8 改写）、M3u8Rewriter |
| `player/` | Media3 ExoPlayer 播放页 |
| `browser/` | 登录 WebView（CookieManager 取 Cookie → 凭据存储） |
| `store/` | 凭据存储（App 私有 SharedPreferences，不上传） |
| `ui/` | 网盘选择 / 文件列表 / 播放 / 登录 |

## 构建

### GitHub Actions（推荐）

推送到 `main` 自动触发 `.github/workflows/android.yml`：

1. JDK 17 + Gradle 8.7
2. `gradle testDebugUnitTest`（StreamProxy / m3u8 单测）
3. `gradle assembleDebug` → `app/build/outputs/apk/debug/*.apk`
4. `aapt dump badging` + `apksigner verify` 校验
5. 上传 artifact `cloudplay-debug-apk`（Actions 页面下载）

### 本地

需要 Android SDK（platform 34 / build-tools 34）与 JDK 17：

```bash
cd android
gradle assembleDebug
# 或 ./gradlew assembleDebug（如已生成 wrapper）
```

## 使用

1. 打开 App，顶部下拉选择网盘 → 「连接」
2. 首次使用：
   - **夸克 / UC / 115 / 123 / 189**：点「网页登录」，在 WebView 里登录后点「已登录，保存」→ 自动保存 Cookie
   - **阿里云盘**：点「凭据」，填 `refresh_token=...`（网页登录拿不到 refresh_token）
   - **本地目录**：凭据填 `path=/sdcard/Movies`
3. 目录点击进入，视频点击播放（Range 生效可拖动进度；HLS 自动改写分片）

## 支持网盘

| 网盘 | 列目录 | 取直链 | 备注 |
|---|---|---|---|
| 本地目录 | ✅ | ✅ | 离线自证用 |
| 夸克 | ✅ | ✅ | Cookie（`__pus` / `__puus`）；含清晰度接口 |
| UC | ✅ | ⚠️ | 协议同夸克；`ut` 下载令牌待移植（可凭据手填 `ut=`） |
| 阿里云盘 | ✅ | ✅ | `refresh_token`；personal 模式 Referer 必须逐字匹配 |
| 123 云盘 | ✅ | ✅ | Bearer token + `timeSign` 签名 |
| 115 | ✅ | ✅ | Cookie（UID/CID/SEID/KID） |
| 天翼云盘 | ✅ | ✅ | Cookie；302 手动取签名直链 |

> 协议依据：本项目 Python 版已实测的端点（见知识库 JY-789 / JY-790）。客户端只做协议复用，不含任何服务端绕过逻辑。

## 逆向参考

`docs/quark-apk-notes.md`：参考 App（`com.quark.browser` 7.11.3.813）的形态分析——浏览器 + 网盘 + 云播三合一，以及本客户端的功能映射。

## 安全与边界

- 凭据只存 App 私有目录（SharedPreferences），不上传、不写日志、不进仓库
- 不申请存储/定位/通讯录权限；仅 INTERNET
- 本客户端是个人云播工具，不提供任何网盘内容或破解能力

## License

MIT（代码）；网盘协议与商标归各自厂商所有。
