# Nuntra · 通知中继终端

把微信 / QQ / 短信的通知栏内容，汇聚到一个可拖动的护眼终端浮窗里。

> 本应用**只**基于系统通知栏内容工作：不读微信/QQ 数据库、不自动回复、不破解聊天记录、不申请短信权限。

---

## 0. 隐私声明（Privacy Statement）
## 1. 环境要求

| 项 | 要求 |
|---|---|
| JDK | **17**（AGP 8.13.2 要求） |
| Android Studio | 任意较新版本（自带 JBR 可直接用作 Gradle JDK） |
| Android SDK | **Platform 36**（compileSdk）+ Build-Tools |
| Gradle | 8.13（见 gradle/wrapper/gradle-wrapper.properties） |

AGP 8.13.2 要求 JDK 17：在 Android Studio 里确认
`File → Settings → Build, Execution, Deployment → Build Tools → Gradle → Gradle JDK` 指向 JDK 17。

---

## 1.5 关于 Gradle 与依赖版本（请不要随意升级）

| 组件 | 钉定版本 | 说明 |
|---|---|---|
| AGP | 8.13.2 | 你的选择 |
| Gradle | **8.13** | 必须落在 AGP 8.x 支持区间 |
| compileSdk | **36** | AGP 8.13.2 支持的上限 |
| targetSdk | 34 | 你的要求（Android 14） |
| minSdk | 29 | 你的要求（Android 10） |

**为什么不能升 Gradle**：CI 曾用运行器自带的 Gradle 9.8.0 重新生成 wrapper，导致构建失败：

```text
Plugin 'com.android.internal.application' relies on 'org.gradle.api.problems.internal.InternalProblems',
a Gradle internal API that was removed in Gradle 9.6.0. Update the plugin to a version that no longer
uses Gradle internal APIs, or use Gradle 9.5.
```

即：**AGP 8.x 无法在 Gradle 9.6+ 上运行**。若将来要升 Gradle 9.x，必须同时升 AGP 到 9.x，
那是另一次带 DSL 迁移的升级，不要只改 Gradle 版本。

### 依赖为何停在当前版本（不要「顺手升到最新」）

AndroidX 库的 AAR 里带有 `minCompileSdk` / `minAgpVersion` 元数据，构建时会由
`checkDebugAarMetadata` 校验。当前这套版本是逐条核对过 AAR 元数据后定下的：

| 依赖 | 版本 | 实测 minCompileSdk |
|---|---|---|
| compose-ui / foundation | 1.11.4（BOM 2026.06.01） | 35 |
| material3 | 1.4.0 | 35 |
| core-ktx | 1.18.0 | 36 |
| activity-compose | 1.13.0 | 36 |
| lifecycle | 2.10.0 | 34–35 |
| savedstate-ktx | 1.5.0 | 34 |
| datastore-preferences | 1.2.1 | 34 |

**已被排除的更高版本**（它们要求 AGP ≥ 9.1.0 与 compileSdk 37，会直接构建失败）：

| 依赖 | 版本 | 实测 minCompileSdk |
|---|---|---|
| compose-ui / animation / foundation 等 | 1.12.x | 37（且要求 AGP 9.1.0） |
| core-ktx / core | 1.19.x | 37（且要求 AGP 9.1.0） |
| lifecycle-* | 2.11.0 | 37（且要求 AGP 9.1.0） |

如果确实需要这些新版本，正确的做法是**整体升级到 AGP 9.x + compileSdk 37 + Gradle 9.x**，
而不是只改某一个依赖的版本号。

CI 中 Gradle 版本通过 `gradle/actions/setup-gradle` 的 `gradle-version: 8.13` 显式指定，
并在构建前 `./gradlew --version | grep 8.13` 自检，避免版本再次漂移。

---

## 1.8 隐私自检（CI 自动执行）

每次推送都会跑一个硬性闸门，任一条不过直接失败：

```bash
# 1) 不得声明 INTERNET 权限
grep -R "android.permission.INTERNET" app/src/main/AndroidManifest.xml   # 必须无输出
# 2) 不得引入网络/遥测依赖
grep -RniE "retrofit|okhttp|ktor|volley|firebase|crashlytics|umeng|bugly" \
  gradle/libs.versions.toml app/build.gradle.kts build.gradle.kts          # 必须无输出
```

本地也可以随时手工跑上面两条命令自证。

---

## 2. 第一次打开工程（重要：补 gradle-wrapper.jar）

本仓库是**在无 Android 工具链的机器上生成的**，因此：

- ✅ 已包含：`gradlew`、`gradlew.bat`、`gradle/wrapper/gradle-wrapper.properties`
- ❌ 缺少：`gradle/wrapper/gradle-wrapper.jar`（二进制文件，只能由 Gradle/Android Studio 生成）

**二选一补齐它：**

方式 A（Android Studio，推荐）

```text
File → Open → 选择 C:\dev\NotificationTerminal
IDE 会提示 Gradle wrapper 缺失，点允许自动生成 / 或直接 Sync，
Sync 成功后 gradle-wrapper.jar 会被写回本地（记得提交进仓库）。
```

方式 B（命令行，需本机已装 Gradle 8.x）

```powershell
cd C:\dev\NotificationTerminal
gradle wrapper --gradle-version 8.13
```

> CI 里已经做了兜底：`.github/workflows/build.yml` 检测到 jar 缺失时会先执行 `gradle wrapper` 再构建。

---

## 3. 首次构建与安装

```powershell
cd C:\dev\NotificationTerminal
.\gradlew.bat assembleDebug
# 产物：app\build\outputs\apk\debug\app-debug.apk
.\gradlew.bat installDebug   # 需已连接设备并开启 USB 调试
```

CI：推送到 GitHub 后自动跑 `./gradlew assembleDebug`，产物在 Actions 的 Artifacts 里（保留 14 天）。

---

## 4. 首次提交（仓库已建好：https://github.com/shihua66666/Nuntra）

```powershell
cd C:\dev\NotificationTerminal

git init
git branch -M main

# 用 SSH 的话把下一行 URL 换成 git@github.com:shihua66666/Nuntra.git
git remote add origin https://github.com/shihua66666/Nuntra.git

git add -A
git status                # 提交前确认没有 .jks / keystore.properties / local.properties 混进来
git commit -m "chore: 初始化 Nuntra 工程脚手架"
git push -u origin main
```

如果远程仓库初始化时带了 README，先拉再推：

```powershell
git pull --rebase origin main
git push -u origin main
```

### 建议在首次提交前跑一遍 `git status` 核对的两点

1. `gradle-wrapper.jar` 应该被提交（.gitignore 里用 `!gradle/wrapper/gradle-wrapper.jar` 显式放行了）。
2. `app/src/main/res/font/ibm_plex_mono_regular.ttf`（40 KB）与
   将来的 `app/src/main/res/raw/alert_priority.mp3` 是否入库由你决定：
   仓库里有合法分发权就入库；不入库也能编译（代码会回退系统字体/系统提示音）。

---

## 5. 字体与提醒音的缺失行为（刻意设计为不阻塞）

| 资源 | 约定文件名 | 缺失时行为 |
|---|---|---|
| IBM Plex Mono | `app/src/main/res/font/ibm_plex_mono_regular.ttf` | 回退系统等宽字体，**不崩溃、不阻塞构建** |
| 特殊关注提醒音 | `app/src/main/res/raw/alert_priority.mp3` | 回退系统通知音 |

实现方式是用**资源名探测**（`resources.getIdentifier`）而不是 `R.font.xxx` 直接引用：
后者在文件缺失时会让 aapt 阶段直接编译失败，整个工程无法 Sync。

---

## 6. 权限与首次使用

1. 打开 App → 首页会显示「就绪状态」清单。
2. 必开两项：**通知使用权**、**悬浮窗权限**；否则应用无法工作。
3. 建议开启：通知权限（Android 13+，决定前台服务常驻通知能否显示）、电池优化白名单。
4. 一加 / ColorOS 还需在系统设置里允许**自启动**与**后台活动**，否则浮窗会被清理。

---

## 7. 目录结构

```text
app/src/main/java/com/shihua66666/nuntra/
├── core/            日志、时间格式化、关键词匹配、依赖容器
├── model/           标签、关注人、消息、来源枚举（纯数据，不依赖 UI）
├── data/            DataStore 仓库：标签、设置、偏好、消息内存仓库
├── notify/          通知监听、字段抽取、过滤、标签解析（NotificationListenerService 链路）
├── overlay/         前台服务 + WindowManager + ComposeView 悬浮窗
├── perm/            权限检测与跳转引导
└── ui/
    ├── theme/       5 套配色预设、排版、形状、字体加载
    ├── components/  通用 UI 基元（全部从 CompositionLocal 取色，禁止硬编码颜色）
    ├── overlay/     折叠胶囊 / 面板 / 小窗 三态组件
    └── settings/    设置页
```

---

## 7.5 res 目录硬性约束（踩过的坑，务必遵守）

**Android 资源文件名只允许小写字母、数字、下划线**（`[a-z0-9_]`），且各目录有扩展名白名单。
本项目曾因在 `res/raw/` 与 `res/font/` 放 `README.txt` 而导致构建失败：

```text
error: 'README.txt' is not a valid file-based resource name character: File-based resource
names must contain only lowercase a-z, 0-9, or underscore
```

### 规则

| 目录 | 允许的扩展名 | 备注 |
|---|---|---|
| `res/font/` | `.ttf` `.otf` `.ttc` `.xml` | **只放字体**，不要放说明文件 |
| `res/raw/` | `.mp3` `.ogg` `.wav` 等媒体与 `.txt` `.json` | 文件名必须全小写；`README.txt` 这类大写名非法 |
| `res/values/` | `.xml` | 颜色、字符串、主题 |
| `res/drawable/` | `.xml` `.png` `.webp` | 矢量图用 `.xml` |
| `res/mipmap-anydpi-v26/` | `.xml` | 自适应图标 |

> 注意：**限定符目录**（`-anydpi`、`-v26`、`-zh`、`-xhdpi` 等）可以包含连字符与大写字母，
> 例如 `mipmap-anydpi-v26` 是合法的。限制针对的是**资源名本体**。

### 说明文字该放哪

- 工程级说明 → 根目录 `README.md` 或 `docs/`
- 随 APK 分发的只读文本 → `assets/`（不受资源命名限制，但注意：本项目为纯离线应用，不引入无关资源）
- **绝不要**为了写注释而在 `res/` 下新增占位文件

### 约定文件名（代码按名字引用，改名需同步改代码）

| 资源 | 约定路径 | 缺失时行为 |
|---|---|---|
| IBM Plex Mono | `res/font/ibm_plex_mono_regular.ttf` | 回退系统等宽字体（`MonoFont.load` 用资源名探测，不阻塞构建） |
| 特殊关注提醒音 | `res/raw/alert_priority.mp3` | 回退系统通知音 |

---

## 8. 已知未验证项（诚实标注）

本工程在**没有 Android 工具链的机器**上编写，因此以下内容尚未经过编译或真机验证：

1. 代码尚未在真机运行过（本仓库在无 Android 工具链的机器上编写）。
2. 依赖版本已逐条核对 AAR 元数据（见 §1.5），CI 已能通过 `checkDebugAarMetadata`；
   但 Kotlin 编译与真机行为仍需以 CI / 实机结果为准。
3. 微信/QQ 通知 extras 的真实字段（`EXTRA_MESSAGES` 键名）需在真机 Logcat 核对，代码已做多键名容错。
4. ColorOS 自启动/后台活动跳转无公开 API，只能「多候选尝试 + 失败降级到应用详情页」。

---

## 9. 设计约束（改动时请遵守）

- 不引入 XML 布局，全 Compose；`ui/theme/AppColors.kt` 定义全部语义色。
- **任何 UI 不得硬编码颜色**，一律走 `LocalAppColors`；标签色走 `TagPalette`。
- 圆角 4–8dp，无尖锐斜切；无扫描线、无闪烁动画；正文不小于 14sp、行高 1.4。
- 消息只存 `tagId`，名称与色值渲染时查表 —— 这样改名/改色对所有历史消息自动生效。
- `isDefault` 是兜底标签的身份标识，**永不按名字判断兜底**。
