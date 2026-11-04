package com.society.updater;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public class Updater {
    public static final String VERSION = "1.0.0";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final List<String> DEFAULT_SKIP = List.of(".updater", "logs", "crash-reports", "saves", "backups", "screenshots", "manifest.json", "updater-manifest.json");

    private final Pixeldrain pixeldrain;

    public Updater() {
        this(null);
    }

    public Updater(String apiKey) {
        this.pixeldrain = new Pixeldrain(apiKey);
    }

    public Manifest fetchManifest(String url) throws IOException, InterruptedException {
        String text;
        if (url.startsWith("pixeldrain:")) {
            String id = url.substring("pixeldrain:".length()).trim();
            text = pixeldrain.downloadToString(Pixeldrain.API + "/file/" + id + "?download");
        } else if (url.startsWith("http://") || url.startsWith("https://")) {
            text = pixeldrain.downloadToString(url);
        } else {
            text = Files.readString(Path.of(url), StandardCharsets.UTF_8);
        }
        if (text != null && text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }
        Manifest manifest = GSON.fromJson(text, Manifest.class);
        if (manifest == null) {
            throw new IOException("清单内容无法解析");
        }
        if (manifest.files == null) {
            manifest.files = new ArrayList<>();
        }
        if (manifest.delete == null) {
            manifest.delete = new ArrayList<>();
        }
        if (manifest.ignore == null) {
            manifest.ignore = new ArrayList<>();
        }
        return manifest;
    }

    public void update(Manifest manifest, Path target, String side, boolean checkOnly, boolean backup, ProgressListener listener) throws Exception {
        Path root = target.toAbsolutePath().normalize();
        Files.createDirectories(root);
        Path stateDir = root.resolve(".updater");
        Files.createDirectories(stateDir);

        List<ManifestFile> files = new ArrayList<>();
        for (ManifestFile file : manifest.files) {
            if (file == null || file.path == null || file.path.isBlank()) {
                continue;
            }
            if (!sideMatches(file.side, side)) {
                continue;
            }
            if (isIgnored(manifest.ignore, file.path)) {
                continue;
            }
            files.add(file);
        }

        fillMissingInfo(files, listener);

        List<ManifestFile> pending = new ArrayList<>();
        for (ManifestFile file : files) {
            if (!isUpToDate(root, file)) {
                pending.add(file);
            }
        }

        listener.onLog("清单版本: " + safe(manifest.version) + "  文件总数: " + files.size() + "  需要更新: " + pending.size());
        if (manifest.changelog != null && !manifest.changelog.isBlank()) {
            listener.onLog("更新说明: " + manifest.changelog);
        }

        if (checkOnly) {
            for (ManifestFile file : pending) {
                listener.onLog("待更新: " + file.path);
            }
            listener.onFinished(true, "检查完成，需要更新 " + pending.size() + " 个文件");
            return;
        }

        Path tmpDir = stateDir.resolve("tmp");
        Files.createDirectories(tmpDir);
        int updated = 0;
        int index = 0;
        for (ManifestFile file : pending) {
            index++;
            Path dest = safeResolve(root, file.path);
            listener.onFile(file.path, index, pending.size());
            boolean hasId = file.id != null && !file.id.isBlank();
            boolean hasUrl = file.url != null && !file.url.isBlank();
            if (!hasId && !hasUrl) {
                listener.onLog("跳过（缺少下载地址）: " + file.path);
                continue;
            }
            Path tmp = tmpDir.resolve(Integer.toHexString(file.path.hashCode()) + "_" + dest.getFileName());
            downloadWithRetry(file, tmp, listener);
            if (file.sha256 != null && !file.sha256.isBlank()) {
                String actual = HashUtil.sha256(tmp);
                if (!file.sha256.equalsIgnoreCase(actual)) {
                    Files.deleteIfExists(tmp);
                    throw new IOException("SHA-256 校验失败: " + file.path);
                }
            }
            if (backup && Files.isRegularFile(dest)) {
                Path backupPath = stateDir.resolve("backup").resolve(file.path.replace('\\', '/'));
                Files.createDirectories(backupPath.getParent());
                Files.copy(dest, backupPath, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.createDirectories(dest.getParent());
            Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
            updated++;
            listener.onLog("已更新: " + file.path);
        }

        int deleted = 0;
        for (String path : manifest.delete) {
            if (path == null || path.isBlank() || isIgnored(manifest.ignore, path)) {
                continue;
            }
            Path dest = safeResolve(root, path);
            if (Files.deleteIfExists(dest)) {
                deleted++;
                listener.onLog("已删除: " + path);
            }
        }

        Files.writeString(stateDir.resolve("version"), safe(manifest.version), StandardCharsets.UTF_8);
        Files.writeString(stateDir.resolve("last-update.log"),
                "version=" + safe(manifest.version) + System.lineSeparator()
                        + "updated=" + updated + System.lineSeparator()
                        + "deleted=" + deleted + System.lineSeparator(), StandardCharsets.UTF_8);
        listener.onFinished(true, "更新完成，更新 " + updated + " 个文件，删除 " + deleted + " 个文件");
    }

    public Manifest generateManifest(Path source, String name, String version, String side) throws IOException {
        Path root = source.toAbsolutePath().normalize();
        long started = System.currentTimeMillis();
        Log.info("扫描清单目录: " + root);
        Manifest manifest = new Manifest();
        manifest.name = name == null || name.isBlank() ? root.getFileName().toString() : name;
        manifest.version = version == null ? "" : version;
        manifest.files = new ArrayList<>();
        int skippedEmpty = 0;
        try (var stream = Files.walk(root)) {
            var paths = stream.filter(Files::isRegularFile).sorted().toList();
            for (Path path : paths) {
                try {
                    String relative = root.relativize(path).toString();
                    if (isSkipped(relative)) continue;
                    long size = Files.size(path);
                    if (size <= 0) {
                        skippedEmpty++;
                        Log.info("跳过空文件: " + relative);
                        continue;
                    }
                    ManifestFile file = new ManifestFile();
                    file.path = relative.replace('\\', '/');
                    file.size = size;
                    file.sha256 = HashUtil.sha256(path);
                    file.side = side == null || side.isBlank() ? "both" : side;
                    manifest.files.add(file);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        }
        Log.info("清单扫描完成: " + manifest.files.size() + " 个文件, 跳过空文件 " + skippedEmpty
                + ", 耗时 " + (System.currentTimeMillis() - started) + " ms");
        return manifest;
    }

    public static boolean isSkippedPath(String relativePath) {
        return isSkipped(relativePath);
    }

    public Manifest uploadAndGenerate(Path source, String apiKey, String name, String version, String side, Manifest previous, ProgressListener listener) throws Exception {
        List<ManifestFile> files = generateManifest(source, name, version, side).files;
        return uploadAndGenerate(source, apiKey, name, version, side, previous, files, List.of(), true, listener);
    }

    public Manifest uploadAndGenerate(Path source, String apiKey, String name, String version, String side, Manifest previous, List<ManifestFile> desiredFiles, List<String> desiredDeletes, boolean autoDeleteMissing, ProgressListener listener) throws Exception {
        Pixeldrain uploader = new Pixeldrain(apiKey);
        Manifest manifest = new Manifest();
        manifest.version = version == null ? "" : version;
        manifest.name = name == null ? "" : name;
        manifest.files = new ArrayList<>();
        if (desiredFiles != null) {
            for (ManifestFile file : desiredFiles) {
                if (file != null && file.path != null && !file.path.isBlank()) {
                    manifest.files.add(file);
                }
            }
        }
        Map<String, ManifestFile> previousByPath = new LinkedHashMap<>();
        Map<String, String> deleteEntries = new LinkedHashMap<>();
        if (previous != null) {
            if (previous.files != null) {
                for (ManifestFile file : previous.files) {
                    if (file != null && file.path != null && !file.path.isBlank()) {
                        previousByPath.put(normalizeKey(file.path), file);
                    }
                }
            }
            if (previous.delete != null) {
                for (String path : previous.delete) {
                    if (path != null && !path.isBlank()) {
                        deleteEntries.put(normalizeKey(path), path);
                    }
                }
            }
        }
        if (desiredDeletes != null) {
            for (String path : desiredDeletes) {
                if (path != null && !path.isBlank()) {
                    deleteEntries.put(normalizeKey(path), path);
                }
            }
        }
        Set<String> currentPaths = new LinkedHashSet<>();
        int index = 0;
        int uploaded = 0;
        for (ManifestFile file : manifest.files) {
            index++;
            currentPaths.add(normalizeKey(file.path));
            ManifestFile old = previousByPath.get(normalizeKey(file.path));
            if (old != null && old.id != null && !old.id.isBlank() && file.sha256 != null && file.sha256.equalsIgnoreCase(old.sha256)) {
                file.id = old.id;
                listener.onLog("未变化: " + file.path);
                continue;
            }
            listener.onFile(file.path, index, manifest.files.size());
            Path local = source.toAbsolutePath().normalize().resolve(file.path.replace('/', java.io.File.separatorChar));
            file.id = uploader.upload(local);
            uploaded++;
            listener.onLog("已上传: " + file.path + " -> " + file.id);
        }
        deleteEntries.keySet().removeAll(currentPaths);
        if (autoDeleteMissing) {
            for (ManifestFile old : previousByPath.values()) {
                if (old.path != null && !currentPaths.contains(normalizeKey(old.path))) {
                    deleteEntries.put(normalizeKey(old.path), old.path);
                }
            }
        }
        manifest.delete = new ArrayList<>(deleteEntries.values());
        listener.onLog("本次上传 " + uploaded + " 个文件，复用 " + (manifest.files.size() - uploaded) + " 个，待删除 " + manifest.delete.size() + " 项");
        return manifest;
    }

    public Manifest uploadReleaseAndGenerate(Path source, String token, String repo, String tag, String releaseName, String changelog, String side, Manifest previous, ProgressListener listener) throws Exception {
        return uploadReleaseAndGenerate(source, token, repo, tag, releaseName, changelog, side, previous, listener, 0L);
    }

    public Manifest uploadReleaseAndGenerate(Path source, String token, String repo, String tag, String releaseName, String changelog, String side, Manifest previous, ProgressListener listener, long maxFileBytes) throws Exception {
        List<ManifestFile> files = generateManifest(source, releaseName, tag, side).files;
        return uploadReleaseAndGenerate(source, token, repo, tag, releaseName, changelog, side, previous, files, List.of(), true, listener, maxFileBytes);
    }

    public Manifest uploadReleaseAndGenerate(Path source, String token, String repo, String tag, String releaseName, String changelog, String side, Manifest previous, List<ManifestFile> desiredFiles, List<String> desiredDeletes, boolean autoDeleteMissing, ProgressListener listener) throws Exception {
        return uploadReleaseAndGenerate(source, token, repo, tag, releaseName, changelog, side, previous, desiredFiles, desiredDeletes, autoDeleteMissing, listener, 0L);
    }

    public Manifest uploadReleaseAndGenerate(Path source, String token, String repo, String tag, String releaseName, String changelog, String side, Manifest previous, List<ManifestFile> desiredFiles, List<String> desiredDeletes, boolean autoDeleteMissing, ProgressListener listener, long maxFileBytes) throws Exception {
        GitHub github = new GitHub();
        Manifest manifest = new Manifest();
        manifest.version = tag == null ? "" : tag;
        manifest.name = releaseName == null ? "" : releaseName;
        manifest.files = new ArrayList<>();
        if (desiredFiles != null) {
            for (ManifestFile file : desiredFiles) {
                if (file != null && file.path != null && !file.path.isBlank()) {
                    manifest.files.add(file);
                }
            }
        }
        listener.onLog("准备 Release: " + repo + " @" + tag);
        JsonObject release = github.getOrCreateRelease(token, repo, tag, releaseName, changelog);
        long releaseId = release.get("id").getAsLong();
        Map<String, JsonObject> assets = github.listAssets(token, repo, releaseId);
        listener.onLog("Release 现有附件: " + assets.size() + " 个");

        Map<String, String> assetNames = buildAssetNames(manifest);
        Map<String, String> previousAssetNames = previous == null ? Map.of() : buildAssetNames(previous);
        Map<String, ManifestFile> previousByPath = new LinkedHashMap<>();
        Map<String, String> deleteEntries = new LinkedHashMap<>();
        if (previous != null) {
            if (previous.files != null) {
                for (ManifestFile file : previous.files) {
                    if (file != null && file.path != null && !file.path.isBlank()) {
                        previousByPath.put(normalizeKey(file.path), file);
                    }
                }
            }
            if (previous.delete != null) {
                for (String path : previous.delete) {
                    if (path != null && !path.isBlank()) {
                        deleteEntries.put(normalizeKey(path), path);
                    }
                }
            }
        }
        if (desiredDeletes != null) {
            for (String path : desiredDeletes) {
                if (path != null && !path.isBlank()) {
                    deleteEntries.put(normalizeKey(path), path);
                }
            }
        }
        Set<String> currentPaths = new LinkedHashSet<>();
        int index = 0;
        int uploaded = 0;
        for (ManifestFile file : manifest.files) {
            index++;
            currentPaths.add(normalizeKey(file.path));
            String assetName = assetNames.get(file.path);
            ManifestFile old = previousByPath.get(normalizeKey(file.path));
            if (old != null && old.url != null && !old.url.isBlank() && old.sha256 != null && file.sha256 != null && old.sha256.equalsIgnoreCase(file.sha256)) {
                file.url = old.url;
                listener.onLog("未变化: " + file.path);
                continue;
            }
            if (maxFileBytes > 0 && file.size > maxFileBytes) {
                if (old != null && old.url != null && !old.url.isBlank() && assets.containsKey(assetName)) {
                    file.url = old.url;
                    file.sha256 = old.sha256;
                    file.size = old.size;
                    Log.info("跳过大文件，保留旧版本: " + file.path + " (" + file.size + " bytes)");
                } else {
                    Log.info("跳过大文件，暂无旧版本可复用: " + file.path + " (" + file.size + " bytes)");
                }
                continue;
            }
            if (file.size <= 0) {
                Log.info("跳过空文件(无法上传 GitHub): " + file.path);
                continue;
            }
            JsonObject existing = assets.get(assetName);
            if (existing != null && old == null && existing.has("size") && existing.get("size").getAsLong() == file.size) {
                file.url = existing.get("browser_download_url").getAsString();
                listener.onLog("已存在(同大小): " + file.path);
                continue;
            }
            listener.onFile(file.path, index, manifest.files.size());
            Log.info("[" + index + "/" + manifest.files.size() + "] 上传 " + file.path + " (" + file.size + " bytes)");
            long uploadStarted = System.currentTimeMillis();
            if (existing != null) {
                github.deleteAsset(token, repo, existing.get("id").getAsLong());
                listener.onLog("删除旧附件: " + assetName);
            }
            Path local = source.toAbsolutePath().normalize().resolve(file.path.replace('/', java.io.File.separatorChar));
            JsonObject asset = github.uploadAsset(token, repo, releaseId, assetName, local);
            file.url = asset.get("browser_download_url").getAsString();
            uploaded++;
            listener.onLog("已上传: " + file.path + " -> " + file.url);
            Log.info("上传完成 " + file.path + " 耗时 " + (System.currentTimeMillis() - uploadStarted) + " ms");
        }
        if (maxFileBytes > 0) {
            manifest.files.removeIf(file -> file.url == null || file.url.isBlank());
        }
        deleteEntries.keySet().removeAll(currentPaths);
        if (autoDeleteMissing) {
            for (ManifestFile old : previousByPath.values()) {
                if (old.path != null && !currentPaths.contains(normalizeKey(old.path))) {
                    deleteEntries.put(normalizeKey(old.path), old.path);
                }
            }
        }
        for (Map.Entry<String, String> entry : deleteEntries.entrySet()) {
            ManifestFile old = previousByPath.get(entry.getKey());
            if (old == null) {
                continue;
            }
            String oldAssetName = previousAssetNames.get(old.path);
            JsonObject asset = oldAssetName == null ? null : assets.get(oldAssetName);
            if (asset != null) {
                github.deleteAsset(token, repo, asset.get("id").getAsLong());
                listener.onLog("删除附件: " + oldAssetName);
            }
        }
        manifest.delete = new ArrayList<>(deleteEntries.values());
        listener.onLog("本次上传 " + uploaded + " 个文件，复用 " + (manifest.files.size() - uploaded) + " 个，待删除 " + manifest.delete.size() + " 项");
        return manifest;
    }

    private static Map<String, String> buildAssetNames(Manifest manifest) {
        Map<String, String> result = new LinkedHashMap<>();
        Map<String, Integer> used = new LinkedHashMap<>();
        if (manifest == null || manifest.files == null) {
            return result;
        }
        for (ManifestFile file : manifest.files) {
            if (file == null || file.path == null) {
                continue;
            }
            String base = file.path.replace('/', '_').replace('\\', '_').replace(' ', '.');
            base = base.replaceAll("[^A-Za-z0-9._-]", ".");
            if (base.length() > 180) {
                base = base.substring(0, 180) + "_" + (file.sha256 == null || file.sha256.length() < 8 ? "" : file.sha256.substring(0, 8));
            }
            int count = used.merge(base, 1, Integer::sum);
            result.put(file.path, count == 1 ? base : base + "_" + count);
        }
        return result;
    }

    public static String autoVersion() {
        return java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"));
    }

    public void writeManifest(Manifest manifest, Path out) throws IOException {
        Files.createDirectories(out.toAbsolutePath().getParent());
        Files.writeString(out, GSON.toJson(manifest), StandardCharsets.UTF_8);
    }

    private void fillMissingInfo(List<ManifestFile> files, ProgressListener listener) throws IOException, InterruptedException {
        List<String> missing = new ArrayList<>();
        for (ManifestFile file : files) {
            if ((file.sha256 == null || file.sha256.isBlank()) && file.id != null && !file.id.isBlank()) {
                missing.add(file.id);
            }
        }
        if (missing.isEmpty()) {
            return;
        }
        listener.onLog("查询 " + missing.size() + " 个文件的校验信息...");
        Map<String, JsonObject> info = pixeldrain.infoBatch(missing);
        for (ManifestFile file : files) {
            JsonObject object = info.get(file.id);
            if (object == null) {
                continue;
            }
            if ((file.sha256 == null || file.sha256.isBlank()) && object.has("hash_sha256")) {
                file.sha256 = object.get("hash_sha256").getAsString();
            }
            if (file.size <= 0 && object.has("size")) {
                file.size = object.get("size").getAsLong();
            }
        }
    }

    private boolean isUpToDate(Path root, ManifestFile file) {
        Path dest;
        try {
            dest = safeResolve(root, file.path);
        } catch (IOException e) {
            return false;
        }
        if (!Files.isRegularFile(dest)) {
            return false;
        }
        try {
            if (file.sha256 != null && !file.sha256.isBlank()) {
                return file.sha256.equalsIgnoreCase(HashUtil.sha256(dest));
            }
            if (file.size > 0) {
                return Files.size(dest) == file.size;
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private void downloadWithRetry(ManifestFile file, Path tmp, ProgressListener listener) throws Exception {
        Exception last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                if (file.url != null && !file.url.isBlank()) {
                    pixeldrain.downloadFromUrl(file.url, tmp, file.size, listener);
                } else {
                    pixeldrain.download(file.id, tmp, file.size, listener);
                }
                return;
            } catch (Exception e) {
                last = e;
                listener.onLog("下载失败(" + attempt + "/3): " + file.path + " - " + e.getMessage());
                Thread.sleep(2000L * attempt);
            }
        }
        throw new IOException("下载失败: " + file.path, last);
    }

    private static boolean sideMatches(String fileSide, String wanted) {
        if (fileSide == null || fileSide.isBlank() || "both".equalsIgnoreCase(fileSide)) {
            return true;
        }
        if (wanted == null || wanted.isBlank() || "both".equalsIgnoreCase(wanted)) {
            return true;
        }
        return fileSide.equalsIgnoreCase(wanted);
    }

    private static boolean isSkipped(String relativePath) {
        String normalized = relativePath.replace('\\', '/');
        for (String skip : DEFAULT_SKIP) {
            if (normalized.equals(skip) || normalized.startsWith(skip + "/")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isIgnored(List<String> patterns, String path) {
        if (patterns == null) {
            return false;
        }
        String normalized = path.replace('\\', '/');
        for (String pattern : patterns) {
            if (pattern != null && !pattern.isBlank() && globMatch(pattern, normalized)) {
                return true;
            }
        }
        return false;
    }

    private static boolean globMatch(String pattern, String path) {
        String normalizedPattern = pattern.replace('\\', '/');
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < normalizedPattern.length(); i++) {
            char c = normalizedPattern.charAt(i);
            if (c == '*') {
                if (i + 1 < normalizedPattern.length() && normalizedPattern.charAt(i + 1) == '*') {
                    regex.append(".*");
                    i++;
                } else {
                    regex.append("[^/]*");
                }
            } else if (c == '?') {
                regex.append("[^/]");
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return path.matches(regex.toString());
    }

    private static String normalizeKey(String path) {
        return path.replace('\\', '/').toLowerCase(Locale.ROOT);
    }

    private static Path safeResolve(Path root, String relative) throws IOException {
        Path resolved = root.resolve(relative.replace('\\', '/')).normalize();
        if (!resolved.startsWith(root)) {
            throw new IOException("非法路径: " + relative);
        }
        return resolved;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
