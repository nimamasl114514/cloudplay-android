# 参考 App 逆向笔记：夸克浏览器（com.quark.browser）

> 目的：为 CloudPlay Android 客户端提供「形态参考」，不是协议逆向（协议已在 Python 版 cloudplay 内实测）。
> 样本：`C:/Users/wwww/Downloads/110_58308686d13c7370638d041488691267.apk`（130,812,369 B）

## 1. 身份（D0 外壳识别）

| 项 | 值 | 证据 |
|---|---|---|
| 包名 | `com.quark.browser` | `pyaxmlparser` 解析 AndroidManifest |
| 版本 | 7.11.3.813（versionCode 813） | 同上 |
| 入口 | `com.ucpro.MainActivity` | 同上 |
| SDK | minSdk 21 / targetSdk 30 | 同上 |
| Activity 数 | 316 | 同上 |
| 权限数 | 61（含存储/定位/通知/悬浮窗） | 同上 |
| 技术栈 | Flutter（`assets/flutter_assets/`）+ KMP（`packages/clouddrive`）+ `ump_video_plugin` + `p3dengine` | APK 目录结构 |
| 私有格式 | `.qpdf` 文件关联（`.*\.qpdf` intent-filter） | Manifest |

## 2. 形态结论（对本客户端的指导）

夸克 App = **浏览器 + 网盘 + 云播** 三合一：

1. **浏览器**是入口（`com.ucpro.BrowserActivity`、`com.ucpro.feature.webwindow.external.ProxyActivity`），登录态（Cookie）天然沉淀在 WebView；
2. **网盘**是内容源（`packages/clouddrive`，Flutter 侧 UI + KMP 侧协议，见 JY-789）；
3. **云播**是播放内核（`ump_video_plugin`）：服务端转码 + 播放器直接拉转码流，等价「流畅播」。

CloudPlay Android 的映射：

| 夸克能力 | CloudPlay Android 实现 |
|---|---|
| 内置浏览器登录网盘 | `LoginActivity`（WebView + CookieManager） |
| 网盘文件浏览 | `MainActivity` + `FileAdapter`（目录栈） |
| 云播/流畅播 | `StreamProxy`（Range/HLS 改写）+ Media3 ExoPlayer |
| 服务端转码清晰度 | `CloudDiskAdapter.listResolutions()`（夸克/阿里已实现） |

## 3. 不做的事（边界）

- 不逆向 `libapp.so` / Dart AOT：本任务只需要形态参考，协议层已在 Python 项目内实测（JY-789）。
- 不复刻夸克的 UI/资源/商标；仅借鉴「浏览器 + 网盘 + 播放器」的产品结构。
- 不携带任何夸克私有密钥或证书。

## 4. 可复现命令

```bash
pip install pyaxmlparser
python -c "from pyaxmlparser import APK; a=APK('....apk'); print(a.package, a.version_name, a.get_main_activity())"
unzip -l 110_58308686d13c7370638d041488691267.apk | awk '{print $4}' | grep -E '^(lib/|assets/)' | head
```
