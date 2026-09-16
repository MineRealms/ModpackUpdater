package com.society.updater;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

public class GitHub {
    private final HttpClient client;
    private final Gson gson = new Gson();

    public GitHub() {
        this.client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .proxy(Pixeldrain.proxySelector())
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public String publish(Path manifestFile, String token, String repo, String branch, String path) throws IOException, InterruptedException {
        if (token == null || token.isBlank()) {
            throw new IOException("缺少 GitHub Token");
        }
        if (repo == null || !repo.contains("/")) {
            throw new IOException("仓库格式应为 owner/repo，例如 MineRealms/UpdateRepo");
        }
        String normalizedRepo = repo.trim();
        String normalizedBranch = branch == null || branch.isBlank() ? "main" : branch.trim();
        String normalizedPath = path == null || path.isBlank() ? "manifest.json" : path.trim().replace('\\', '/');
        while (normalizedPath.startsWith("/")) {
            normalizedPath = normalizedPath.substring(1);
        }
        if (!Files.isRegularFile(manifestFile)) {
            throw new IOException("清单文件不存在: " + manifestFile);
        }
        byte[] bytes = Files.readAllBytes(manifestFile);
        String content = Base64.getEncoder().encodeToString(bytes);

        String sha = null;
        HttpRequest getRequest = HttpRequest.newBuilder(URI.create(
                        "https://api.github.com/repos/" + normalizedRepo + "/contents/" + normalizedPath + "?ref=" + normalizedBranch))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "ModpackUpdater/" + Updater.VERSION)
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        HttpResponse<String> getResponse = client.send(getRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (getResponse.statusCode() == 200) {
            JsonObject object = JsonParser.parseString(getResponse.body()).getAsJsonObject();
            if (object.has("sha")) {
                sha = object.get("sha").getAsString();
            }
        } else if (getResponse.statusCode() != 404) {
            throw new IOException("GitHub 读取文件失败 HTTP " + getResponse.statusCode() + ": " + summarize(getResponse.body()));
        }

        JsonObject body = new JsonObject();
        body.addProperty("message", "更新整合包清单 " + LocalDateTime.now());
        body.addProperty("content", content);
        body.addProperty("branch", normalizedBranch);
        if (sha != null) {
            body.addProperty("sha", sha);
        }
        HttpRequest putRequest = HttpRequest.newBuilder(URI.create(
                        "https://api.github.com/repos/" + normalizedRepo + "/contents/" + normalizedPath))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "ModpackUpdater/" + Updater.VERSION)
                .timeout(Duration.ofSeconds(60))
                .PUT(HttpRequest.BodyPublishers.ofString(gson.toJson(body), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> putResponse = client.send(putRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (putResponse.statusCode() >= 400) {
            throw new IOException("GitHub 发布失败 HTTP " + putResponse.statusCode() + ": " + summarize(putResponse.body()));
        }
        return "https://raw.githubusercontent.com/" + normalizedRepo + "/" + normalizedBranch + "/" + normalizedPath;
    }

    public JsonObject getOrCreateRelease(String token, String repo, String tag, String name, String body) throws IOException, InterruptedException {
        JsonObject payload = new JsonObject();
        payload.addProperty("tag_name", tag);
        payload.addProperty("name", name == null || name.isBlank() ? tag : name);
        payload.addProperty("body", body == null ? "" : body);
        payload.addProperty("draft", false);
        payload.addProperty("prerelease", false);
        HttpResponse<String> response = sendJson("POST", "https://api.github.com/repos/" + repo + "/releases", token, gson.toJson(payload), Duration.ofSeconds(60));
        if (response.statusCode() == 201) {
            return JsonParser.parseString(response.body()).getAsJsonObject();
        }
        if (response.statusCode() == 422) {
            HttpResponse<String> existing = sendJson("GET", "https://api.github.com/repos/" + repo + "/releases/tags/" + tag, token, null, Duration.ofSeconds(30));
            if (existing.statusCode() == 200) {
                return JsonParser.parseString(existing.body()).getAsJsonObject();
            }
        }
        throw new IOException("GitHub 创建 Release 失败 HTTP " + response.statusCode() + ": " + summarize(response.body()));
    }

    public Map<String, JsonObject> listAssets(String token, String repo, long releaseId) throws IOException, InterruptedException {
        Map<String, JsonObject> result = new LinkedHashMap<>();
        int page = 1;
        while (true) {
            HttpResponse<String> response = sendJson("GET",
                    "https://api.github.com/repos/" + repo + "/releases/" + releaseId + "/assets?per_page=100&page=" + page,
                    token, null, Duration.ofSeconds(60));
            if (response.statusCode() >= 400) {
                throw new IOException("GitHub 读取 Release 附件失败 HTTP " + response.statusCode() + ": " + summarize(response.body()));
            }
            JsonArray array = JsonParser.parseString(response.body()).getAsJsonArray();
            if (array.isEmpty()) {
                break;
            }
            for (JsonElement element : array) {
                JsonObject object = element.getAsJsonObject();
                result.put(object.get("name").getAsString(), object);
            }
            if (array.size() < 100) {
                break;
            }
            page++;
        }
        return result;
    }

    public JsonObject uploadAsset(String token, String repo, long releaseId, String assetName, Path file) throws IOException, InterruptedException {
        String encodedName = URLEncoder.encode(assetName, StandardCharsets.UTF_8).replace("+", "%20");
        HttpRequest request = HttpRequest.newBuilder(URI.create(
                        "https://uploads.github.com/repos/" + repo + "/releases/" + releaseId + "/assets?name=" + encodedName))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("Content-Type", "application/octet-stream")
                .header("User-Agent", "ModpackUpdater/" + Updater.VERSION)
                .timeout(Duration.ofMinutes(120))
                .POST(HttpRequest.BodyPublishers.ofFile(file))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() == 422 && response.body().contains("already_exists")) {
            JsonObject conflicting = findAsset(token, repo, releaseId, assetName);
            if (conflicting != null) {
                deleteAsset(token, repo, conflicting.get("id").getAsLong());
                response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            }
        }
        if (response.statusCode() >= 400) {
            throw new IOException("GitHub 上传附件失败 HTTP " + response.statusCode() + ": " + summarize(response.body()));
        }
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    private JsonObject findAsset(String token, String repo, long releaseId, String assetName) throws IOException, InterruptedException {
        Map<String, JsonObject> assets = listAssets(token, repo, releaseId);
        JsonObject existing = assets.get(assetName);
        if (existing != null) {
            return existing;
        }
        return assets.get(assetName.replace(' ', '.'));
    }

    public void deleteAsset(String token, String repo, long assetId) throws IOException, InterruptedException {
        HttpResponse<String> response = sendJson("DELETE",
                "https://api.github.com/repos/" + repo + "/releases/assets/" + assetId, token, null, Duration.ofSeconds(30));
        if (response.statusCode() >= 400 && response.statusCode() != 404) {
            throw new IOException("GitHub 删除附件失败 HTTP " + response.statusCode() + ": " + summarize(response.body()));
        }
    }

    private HttpResponse<String> sendJson(String method, String url, String token, String body, Duration timeout) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "ModpackUpdater/" + Updater.VERSION)
                .timeout(timeout);
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json");
            builder.method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static String summarize(String body) {
        if (body == null) {
            return "";
        }
        String text = body.replace('\n', ' ').trim();
        return text.length() > 300 ? text.substring(0, 300) + "..." : text;
    }
}
