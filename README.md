# ModpackUpdater（Society 整合包更新器）

基于 `manifest.json` 的 Minecraft 整合包增量更新器：**玩家端**启动时自动增量更新，**发布端**一键把「镜像目录」发布到 GitHub Releases（或 Pixeldrain）并生成清单。

- **增量更新**：按 SHA-256 比对，只下载缺失/变化的文件
- **删除同步**：清单 `delete` 列表里的文件在客户端删除（历史删除项自动保留）
- **自动备份**：覆盖/删除前备份到 `游戏目录/.updater/backup/`
- **多端支持**：Windows（`ModpackUpdater.exe` / jar）、macOS / Linux（jar + `cli-mac.sh`）
- **启动器集成**：HMCL 启动前命令（`precalledCommand`），启动即更新
- **后端可切换**：GitHub Releases / Pixeldrain
- **代理支持**：环境变量 / Windows 注册表 / JVM 参数
- **日志**：`~/.modpackupdater/logs/updater-时间.log`（Windows 为 `%USERPROFILE%\.modpackupdater\logs\`）

## 快速开始（玩家 / 客户端模式）

Windows：

```bat
ModpackUpdater.exe --client --manifest <清单URL> --target "<游戏目录>"
```

macOS / Linux（jar 与 `cli-mac.sh` 放同一目录）：

```bash
chmod +x cli-mac.sh
./cli-mac.sh --client --manifest <清单URL> --target "<游戏目录>"
```

- `--manifest` 可省略：读本机配置（`updater.properties`）
- `--target` 可省略：自动从更新器所在目录向上找 `.minecraft`（或 `versions`），再退回当前工作目录
- 成功自动退出（退出码 0）；失败弹窗提示并给出日志路径（退出码 1）
- `--side client|server|both` 可按文件类别过滤（默认 `client`）

### HMCL 启动前命令

Windows（`hmclversion.cfg` → `precalledCommand`）：

```
"$INST_JAVA" -jar "$INST_DIR\ModpackUpdater-all.jar" --client --manifest <清单URL> --side client
```

macOS（路径用正斜杠）：

```
"$INST_JAVA" -jar "$INST_DIR/ModpackUpdater-all.jar" --client --manifest <清单URL> --side client
```

> `$INST_JAVA` / `$INST_DIR` 是 HMCL 自动替换的变量（Java 路径 / 实例目录）。
> 把 `ModpackUpdater-all.jar` 放进实例目录即可（`$INST_DIR`）。

## 发布（发布者）

1. 把改动放进**镜像目录**（结构与客户端 `versions/...` 同构）
2. 一键发布：

```bat
cli.cmd --publish-all "<镜像目录>"
```

或直接用 jar：

```bash
java -jar build/libs/ModpackUpdater-all.jar --publish-all "<镜像目录>"
```

3. 清单地址固定不变，玩家端启动时自动更新

发布逻辑：

1. 新增 / 变化的文件上传到 Release（同名旧附件自动替换）
2. 未变化的文件复用已有附件地址（不重复上传）
3. 镜像里被删掉的文件自动进入 `delete` 列表；历史删除项持续保留
4. 生成新版本 `manifest.json` 并提交到清单仓库（默认 GitHub Contents API）

## 命令行参考

```
--client --manifest <URL> --target <目录> [--side client|server|both]
--publish-all <镜像目录>
--gen-manifest <目录> <输出.json>
--upload <目录> --out manifest.json --api-key KEY
--publish-github <清单.json>
--cli --manifest <URL> --target <目录> [--check] [--no-backup]
--help
```

完整说明见 [`更新器使用文档.md`](更新器使用文档.md)。

## 构建

需要 JDK 17+、Gradle 8：

```bat
gradle build        :: 生成 build/libs/ModpackUpdater-all.jar（含依赖，跨平台）
build-exe.cmd       :: 打包 Windows exe（jpackage；JAVA_HOME / Gradle 路径按本机调整）
```

如 Gradle 自动检测不到 JDK 17，可在 `gradle.properties` 里取消 `org.gradle.java.home` 的注释并填写路径。

## 配置

`~/.modpackupdater/updater.properties`（Windows：`%USERPROFILE%\.modpackupdater\updater.properties`）：

| 字段 | 说明 |
|---|---|
| `manifest` | 默认清单地址（客户端模式可省 `--manifest`） |
| `target` | 默认目标目录（空 = 自动识别） |
| `side` | `client` / `server` / `both` |
| `backend` | `github` / `pixeldrain` |
| `githubRepo` / `githubBranch` / `githubPath` | 清单所在仓库 / 分支 / 路径 |
| `githubToken` | 发布用的 GitHub Token（仅发布端需要） |
| `apiKey` | Pixeldrain API Key（可选） |
| `releaseTag` | Release 标签（默认 `latest`，附件就地替换） |
| `localManifest` | 本地发布状态清单路径（发布增量比对用） |
| `backup` | 覆盖前是否备份（默认开） |

## 目录结构

```
src/main/java/com/society/updater/
  Main.java                入口 / 命令行解析 / 目标目录识别
  Updater.java             更新同步 / 发布逻辑（文件比对、delete 列表维护）
  ClientUpdaterWindow.java 客户端模式进度窗口
  UpdaterFrame.java        发布者 GUI
  GitHub.java              GitHub Releases / Contents API
  Pixeldrain.java          Pixeldrain 后端 + 代理探测
  Manifest.java            清单模型（files / delete）
  ManifestFile.java        文件条目（path / sha256 / size / side / url）
  UpdaterConfig.java       本机配置读写
  HashUtil.java            SHA-256
  Log.java                 日志
  ProgressListener.java    进度回调
  PublishTableModel.java   发布列表模型
cli.cmd / cli-mac.sh       Windows / macOS 命令行包装
build-exe.cmd              Windows exe 打包脚本
```

## 日志与排查

- 所有模式（GUI / 客户端 / 命令行）都会写日志到 `~/.modpackupdater/logs/`
- 客户端模式失败时弹窗会直接显示日志文件路径
- 目标目录识别顺序：`--target` → 配置 → 更新器所在目录向上找 `.minecraft` → 当前工作目录
