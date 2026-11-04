package com.society.updater;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class Main {
    public static void main(String[] args) {
        Log.init(Path.of(System.getProperty("user.home"), ".modpackupdater", "logs"));
        try {
            Log.info("启动参数: " + redact(args));
            if (args.length == 0) {
                launchGui();
                return;
            }
            List<String> arguments = new ArrayList<>(Arrays.asList(args));
            if (arguments.contains("--help") || arguments.contains("-h")) {
                printHelp();
                return;
            }
            if (arguments.contains("--gui")) {
                launchGui();
                return;
            }
            if (arguments.contains("--client")) {
                launchClient(arguments);
                return;
            }
            runCli(arguments);
        } catch (Throwable t) {
            Log.error("未处理异常", t);
            boolean cli = args.length > 0
                    && !Arrays.asList(args).contains("--gui")
                    && !Arrays.asList(args).contains("--client");
            if (cli) {
                // A CLI run must never block on a Swing error dialog; the caller needs the stack trace.
                t.printStackTrace(System.err);
                System.out.flush();
                System.exit(1);
            }
            showFatal(t);
        }
    }

    private static void launchGui() {
        Log.info("打开发布者界面");
        SwingUtilities.invokeLater(() -> new UpdaterFrame().setVisible(true));
    }

    private static void launchClient(List<String> arguments) {
        UpdaterConfig config = UpdaterConfig.load(UpdaterConfig.defaultFile());
        String manifestUrl = value(arguments, "--manifest");
        if (manifestUrl == null || manifestUrl.isBlank()) {
            manifestUrl = config.manifestUrl;
        }
        String target = value(arguments, "--target");
        if (target == null || target.isBlank()) {
            target = config.targetDir;
        }
        if (target == null || target.isBlank()) {
            target = detectTargetDir().toString();
            Log.info("自动识别游戏目录: " + target);
        }
        String side = value(arguments, "--side");
        if (side == null || side.isBlank()) {
            side = config.side;
        }
        String apiKey = value(arguments, "--api-key");
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = System.getenv("PIXELDRAIN_API_KEY");
        }
        if (manifestUrl == null || manifestUrl.isBlank()) {
            Log.error("客户端模式缺少清单地址");
            showFatal(new IllegalArgumentException("缺少清单地址，请通过 --manifest 或 updater.properties 配置"));
            return;
        }
        String finalManifestUrl = manifestUrl;
        String finalTarget = target;
        String finalSide = side;
        String finalApiKey = apiKey;
        Log.info("客户端模式启动");
        SwingUtilities.invokeLater(() -> new ClientUpdaterWindow(finalManifestUrl, finalTarget, finalSide, finalApiKey).setVisible(true));
    }

    private static void showFatal(Throwable t) {
        if (GraphicsEnvironment.isHeadless()) {
            System.err.println("错误: " + t.getMessage());
            System.exit(1);
        }
        try {
            SwingUtilities.invokeAndWait(() -> JOptionPane.showMessageDialog(null,
                    "发生错误: " + t.getMessage() + "\n\n日志文件:\n" + Log.file(),
                    "ModpackUpdater", JOptionPane.ERROR_MESSAGE));
        } catch (Exception ignored) {
        }
        System.exit(1);
    }

    private static String redact(String[] args) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < args.length; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            if (i > 0 && ("--api-key".equals(args[i - 1]) || "--gh-token".equals(args[i - 1]))) {
                sb.append("***");
            } else {
                sb.append(args[i]);
            }
        }
        return sb.toString();
    }

    private static void runCli(List<String> arguments) throws Exception {
        int genIndex = arguments.indexOf("--gen-manifest");
        if (genIndex >= 0) {
            if (genIndex + 2 >= arguments.size()) {
                throw new IllegalArgumentException("--gen-manifest 需要 <目录> <输出文件>");
            }
            Path source = Path.of(arguments.get(genIndex + 1));
            Path out = Path.of(arguments.get(genIndex + 2));
            Updater updater = new Updater(value(arguments, "--api-key"));
            Manifest manifest = updater.generateManifest(source, value(arguments, "--name"), value(arguments, "--version"), value(arguments, "--side"));
            updater.writeManifest(manifest, out);
            System.out.println("已生成清单: " + out + " (" + manifest.files.size() + " 个文件)");
            return;
        }

        int uploadIndex = arguments.indexOf("--upload");
        if (uploadIndex >= 0) {
            if (uploadIndex + 1 >= arguments.size()) {
                throw new IllegalArgumentException("--upload 需要 <目录>");
            }
            Path source = Path.of(arguments.get(uploadIndex + 1));
            String outValue = value(arguments, "--out");
            if (outValue == null) {
                throw new IllegalArgumentException("--upload 需要 --out <输出文件>");
            }
            String apiKey = value(arguments, "--api-key");
            UpdaterConfig config = UpdaterConfig.load(UpdaterConfig.defaultFile());
            if (apiKey == null || apiKey.isBlank()) {
                apiKey = config.apiKey;
            }
            if (apiKey == null || apiKey.isBlank()) {
                throw new IllegalArgumentException("--upload 需要 --api-key (或先在本机配置里填好 API Key)");
            }
            Updater updater = new Updater(apiKey);
            Path outPath = Path.of(outValue);
            Manifest previous = null;
            String previousValue = value(arguments, "--previous");
            Path previousPath = previousValue != null ? Path.of(previousValue) : (Files.isRegularFile(outPath) ? outPath : null);
            if (previousPath != null) {
                previous = updater.fetchManifest(previousPath.toString());
                System.out.println("已加载上次清单作为对比: " + previousPath);
            }
            Manifest manifest = updater.uploadAndGenerate(source, apiKey, value(arguments, "--name"), value(arguments, "--version"), value(arguments, "--side"), previous, cliListener());
            updater.writeManifest(manifest, outPath);
            String ghRepo = firstNonBlank(value(arguments, "--gh-repo"), config.githubRepo);
            String ghToken = firstNonBlank(value(arguments, "--gh-token"), System.getenv("GITHUB_TOKEN"), config.githubToken);
            String ghBranch = firstNonBlank(value(arguments, "--gh-branch"), config.githubBranch);
            String ghPath = firstNonBlank(value(arguments, "--gh-path"), config.githubPath);
            if (ghRepo != null && ghToken != null) {
                String raw = new GitHub().publish(outPath, ghToken, ghRepo, ghBranch, ghPath);
                System.out.println("已发布到 GitHub: " + raw);
            }
            System.out.println("已上传并生成清单: " + outValue + " (" + manifest.files.size() + " 个文件, 待删除 " + manifest.delete.size() + " 项)");
            return;
        }

        int publishIndex = arguments.indexOf("--publish-github");
        if (publishIndex >= 0) {
            if (publishIndex + 1 >= arguments.size()) {
                throw new IllegalArgumentException("--publish-github 需要 <清单文件>");
            }
            Path manifestFile = Path.of(arguments.get(publishIndex + 1));
            UpdaterConfig config = UpdaterConfig.load(UpdaterConfig.defaultFile());
            String repo = firstNonBlank(value(arguments, "--gh-repo"), config.githubRepo);
            String token = firstNonBlank(value(arguments, "--gh-token"), System.getenv("GITHUB_TOKEN"), config.githubToken);
            String branch = firstNonBlank(value(arguments, "--gh-branch"), config.githubBranch);
            String path = firstNonBlank(value(arguments, "--gh-path"), config.githubPath);
            if (repo == null) {
                throw new IllegalArgumentException("--publish-github 需要 --gh-repo owner/repo");
            }
            if (token == null) {
                throw new IllegalArgumentException("--publish-github 需要 --gh-token (或环境变量 GITHUB_TOKEN)");
            }
            String raw = new GitHub().publish(manifestFile, token, repo, branch, path);
            System.out.println("已发布到 GitHub: " + raw);
            return;
        }

        int publishAllIndex = arguments.indexOf("--publish-all");
        if (publishAllIndex >= 0) {
            if (publishAllIndex + 1 >= arguments.size()) {
                throw new IllegalArgumentException("--publish-all 需要 <目录>");
            }
            Path source = Path.of(arguments.get(publishAllIndex + 1));
            UpdaterConfig config = UpdaterConfig.load(UpdaterConfig.defaultFile());
            String backend = firstNonBlank(value(arguments, "--backend"), config.backend, "github");
            String repo = firstNonBlank(value(arguments, "--gh-repo"), config.githubRepo);
            String token = firstNonBlank(value(arguments, "--gh-token"), System.getenv("GITHUB_TOKEN"), config.githubToken);
            String branch = firstNonBlank(value(arguments, "--gh-branch"), config.githubBranch, "main");
            String ghPath = firstNonBlank(value(arguments, "--gh-path"), config.githubPath, "manifest.json");
            String tag = firstNonBlank(value(arguments, "--release-tag"), config.releaseTag, "latest");
            String apiKey = firstNonBlank(value(arguments, "--api-key"), config.apiKey);
            String localManifest = firstNonBlank(value(arguments, "--manifest-out"), config.localManifest);
            if (repo == null || token == null) {
                throw new IllegalArgumentException("--publish-all 需要 GitHub 仓库和 Token");
            }
            if (localManifest == null) {
                throw new IllegalArgumentException("--publish-all 需要 --manifest-out <本地清单路径>");
            }
            if ("pixeldrain".equalsIgnoreCase(backend) && apiKey == null) {
                throw new IllegalArgumentException("pixeldrain 模式需要 API Key");
            }
            String version = firstNonBlank(value(arguments, "--version"), Updater.autoVersion());
            String side = value(arguments, "--side");
            int releaseAssetLimit = 900;
            String releaseAssetLimitValue = value(arguments, "--release-asset-limit");
            if (releaseAssetLimitValue != null && !releaseAssetLimitValue.isBlank()) {
                releaseAssetLimit = Math.max(100, Math.min(990, Integer.parseInt(releaseAssetLimitValue.trim())));
            }
            long maxFileBytes = 0L;
            String maxFileMb = value(arguments, "--max-file-mb");
            if (maxFileMb != null && !maxFileMb.isBlank()) {
                try {
                    maxFileBytes = Math.max(0L, Long.parseLong(maxFileMb.trim())) * 1024L * 1024L;
                } catch (NumberFormatException ignored) {
                    throw new IllegalArgumentException("--max-file-mb 需要整数 MB");
                }
            }
            Path manifestPath = Path.of(localManifest);
            Updater updater = new Updater(apiKey);
            Manifest previous = null;
            if (Files.isRegularFile(manifestPath)) {
                previous = updater.fetchManifest(manifestPath.toString());
                System.out.println("已加载上次清单: " + manifestPath);
            }
            Manifest manifest;
            if ("pixeldrain".equalsIgnoreCase(backend)) {
                manifest = updater.uploadAndGenerate(source, apiKey, value(arguments, "--name"), version, side, previous, cliListener());
            } else {
                manifest = updater.uploadReleaseAndGenerate(source, token, repo, tag, value(arguments, "--name"), "", side, previous, cliListener(), maxFileBytes, releaseAssetLimit);
            }
            manifest.version = version;
            updater.writeManifest(manifest, manifestPath);
            System.out.println("清单已生成: " + manifestPath);
            String raw = new GitHub().publish(manifestPath, token, repo, branch, ghPath);
            System.out.println("一键发布完成 (版本 " + version + ")，客户端清单: " + raw);
            return;
        }

        String manifestUrl = value(arguments, "--manifest");
        if (manifestUrl == null || manifestUrl.isBlank()) {
            manifestUrl = UpdaterConfig.load(UpdaterConfig.defaultFile()).manifestUrl;
        }
        String target = value(arguments, "--target");
        if (target == null || target.isBlank()) {
            target = detectTargetDir().toString();
            System.out.println("自动识别游戏目录: " + target);
        }
        if (manifestUrl == null || manifestUrl.isBlank()) {
            printHelp();
            return;
        }
        boolean checkOnly = arguments.contains("--check");
        boolean backup = !arguments.contains("--no-backup");
        Updater updater = new Updater(value(arguments, "--api-key"));
        Manifest manifest = updater.fetchManifest(manifestUrl);
        updater.update(manifest, Path.of(target), value(arguments, "--side"), checkOnly, backup, cliListener());
    }

    private static Path detectTargetDir() {
        List<Path> starts = new ArrayList<>();
        String appPath = System.getProperty("jpackage.app-path");
        if (appPath != null && !appPath.isBlank()) {
            Path dir = Path.of(appPath).toAbsolutePath().normalize().getParent();
            if (dir != null) {
                starts.add(dir);
            }
        }
        try {
            Path codeSource = Path.of(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path jarDir = Files.isDirectory(codeSource) ? codeSource : codeSource.getParent();
            if (jarDir != null) {
                starts.add(jarDir);
            }
        } catch (Exception ignored) {
        }
        Path working = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        starts.add(working);
        for (Path start : starts) {
            Path candidate = start;
            for (int level = 0; level < 5 && candidate != null; level++) {
                if (Files.isDirectory(candidate.resolve("versions"))) {
                    return candidate;
                }
                Path nested = candidate.resolve(".minecraft");
                if (Files.isDirectory(nested.resolve("versions"))) {
                    return nested;
                }
                candidate = candidate.getParent();
            }
        }
        return working;
    }

    private static String value(List<String> arguments, String key) {
        int index = arguments.indexOf(key);
        if (index < 0 || index + 1 >= arguments.size()) {
            return null;
        }
        return arguments.get(index + 1);
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static ProgressListener cliListener() {
        return new ProgressListener() {
            @Override
            public void onLog(String message) {
                Log.info(message);
                System.out.println(message);
            }

            @Override
            public void onFile(String path, int index, int total) {
                Log.info("[" + index + "/" + total + "] " + path);
                System.out.println("[" + index + "/" + total + "] " + path);
            }

            @Override
            public void onProgress(long done, long total) {
                if (total > 0 && done >= total) {
                    System.out.println("    完成 " + (total / 1024 / 1024) + " MB");
                }
            }

            @Override
            public void onFinished(boolean success, String message) {
                if (success) {
                    Log.info(message);
                } else {
                    Log.error(message);
                }
                System.out.println(message);
                if (!success) {
                    System.exit(1);
                }
            }
        };
    }

    private static void printHelp() {
        System.out.println("Society 整合包更新器 v" + Updater.VERSION);
        System.out.println();
        System.out.println("用法:");
        System.out.println("  ModpackUpdater.exe                    打开图形界面(发布者 + 客户端按钮)");
        System.out.println("  --client [--manifest <地址>] [--target <目录>] [--side client|server|both] [--api-key KEY]");
        System.out.println("                                        客户端静默更新(弹窗进度, 完成后自动退出, 适合放启动器)");
        System.out.println("  --cli --manifest <地址> --target <目录> [--side client|server|both] [--check] [--no-backup] [--api-key KEY]");
        System.out.println("  --gen-manifest <目录> <输出.json> [--side client|server|both] [--version 版本] [--name 名称]");
        System.out.println("  --upload <目录> --out <输出.json> --api-key KEY [--previous 上次清单.json] [--side client|server|both] [--version 版本] [--name 名称] [--gh-repo owner/repo --gh-token TOKEN [--gh-branch main] [--gh-path manifest.json]]");
        System.out.println("  --publish-all <目录> [--backend github|pixeldrain] [--release-tag latest] [--manifest-out 本地清单.json] [--version 版本] [--name 名称] [--side client|server|both]");
        System.out.println("                                        一键发布: 扫描目录 -> 只传变动文件 -> 生成清单 -> 提交到 GitHub");
        System.out.println("  --publish-github <清单.json> --gh-repo owner/repo --gh-token TOKEN [--gh-branch main] [--gh-path manifest.json]");
        System.out.println();
        System.out.println("同步规则:");
        System.out.println("  清单里的文件按 SHA-256 强制同步: 缺失或哈希不一致(过时/被改)就下载覆盖");
        System.out.println("  作者从镜像里删掉的文件进入 delete 列表, 客户端会删除对应文件");
        System.out.println("  清单之外的文件(玩家自加的 mod、自己改的配置)不会被删除或覆盖");
        System.out.println();
        System.out.println("清单地址支持:");
        System.out.println("  https://...            任意 HTTP 地址");
        System.out.println("  pixeldrain:<文件ID>    pixeldrain 文件");
        System.out.println("  <本地路径>             本地 JSON 文件");
    }
}
