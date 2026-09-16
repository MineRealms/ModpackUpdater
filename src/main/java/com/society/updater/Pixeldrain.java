package com.society.updater;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class Pixeldrain {
    public static final String API = "https://pixeldrain.com/api";
    private final HttpClient client;
    private final Gson gson = new Gson();
    private final String apiKey;

    public Pixeldrain() {
        this(null);
    }

    public Pixeldrain(String apiKey) {
        this.apiKey = apiKey == null || apiKey.isBlank() ? null : apiKey.trim();
        this.client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .proxy(buildProxySelector())
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    private static String cachedProxyDescription;

    public static synchronized String proxyDescription() {
        if (cachedProxyDescription == null) {
            buildProxySelector();
        }
        return cachedProxyDescription == null ? "直连" : cachedProxyDescription;
    }

    public static ProxySelector proxySelector() {
        return buildProxySelector();
    }

    private static ProxySelector buildProxySelector() {
        String[] envNames = {"HTTPS_PROXY", "https_proxy", "HTTP_PROXY", "http_proxy", "ALL_PROXY", "all_proxy"};
        for (String name : envNames) {
            String value = System.getenv(name);
            ProxySelector selector = parseProxy(value);
            if (selector != null) {
                cachedProxyDescription = "环境变量 " + name + " = " + value;
                return selector;
            }
        }
        String fromProperties = proxyFromSystemProperties();
        ProxySelector propertySelector = parseProxy(fromProperties);
        if (propertySelector != null) {
            cachedProxyDescription = "系统属性 " + fromProperties;
            return propertySelector;
        }
        String fromRegistry = proxyFromWindowsRegistry();
        ProxySelector registrySelector = parseProxy(fromRegistry);
        if (registrySelector != null) {
            cachedProxyDescription = "系统代理 " + fromRegistry;
            return registrySelector;
        }
        cachedProxyDescription = "直连";
        return ProxySelector.getDefault();
    }

    private static ProxySelector parseProxy(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            String candidate = value.contains("://") ? value : "http://" + value;
            URI uri = URI.create(candidate);
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                return null;
            }
            int port = uri.getPort() > 0 ? uri.getPort() : 80;
            return ProxySelector.of(new InetSocketAddress(host, port));
        } catch (Exception e) {
            return null;
        }
    }

    private static String proxyFromSystemProperties() {
        String host = System.getProperty("https.proxyHost");
        String port = System.getProperty("https.proxyPort");
        if (host == null || host.isBlank()) {
            host = System.getProperty("http.proxyHost");
            port = System.getProperty("http.proxyPort");
        }
        if (host == null || host.isBlank()) {
            return null;
        }
        return host + (port == null || port.isBlank() ? "" : ":" + port);
    }

    private static String proxyFromWindowsRegistry() {
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
            return null;
        }
        try {
            Process process = new ProcessBuilder("reg", "query",
                    "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings")
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            process.waitFor(3, TimeUnit.SECONDS);
            boolean enabled = false;
            String server = null;
            for (String line : output.split("\\R")) {
                String trimmed = line.trim();
                if (trimmed.startsWith("ProxyEnable")) {
                    enabled = trimmed.endsWith("0x1");
                } else if (trimmed.startsWith("ProxyServer")) {
                    String[] parts = trimmed.split("\\s+", 3);
                    if (parts.length >= 3) {
                        server = parts[2].trim();
                    }
                }
            }
            if (!enabled || server == null || server.isBlank()) {
                return null;
            }
            if (server.contains("=")) {
                String httpEntry = null;
                for (String piece : server.split(";")) {
                    if (piece.startsWith("https=")) {
                        return "http://" + piece.substring("https=".length());
                    }
                    if (piece.startsWith("http=")) {
                        httpEntry = piece.substring("http=".length());
                    }
                }
                return httpEntry == null ? null : "http://" + httpEntry;
            }
            return "http://" + server;
        } catch (Exception e) {
            return null;
        }
    }

    private String authHeader() {
        if (apiKey == null) {
            return null;
        }
        return "Basic " + Base64.getEncoder().encodeToString((":" + apiKey).getBytes(StandardCharsets.UTF_8));
    }

    public JsonObject info(String id) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(API + "/file/" + id + "/info"))
                .header("User-Agent", "ModpackUpdater/" + Updater.VERSION)
                .timeout(Duration.ofSeconds(30))
                .GET();
        String auth = authHeader();
        if (auth != null) {
            builder.header("Authorization", auth);
        }
        HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() >= 400) {
            throw new IOException("pixeldrain info " + id + " HTTP " + response.statusCode() + ": " + response.body());
        }
        return gson.fromJson(response.body(), JsonObject.class);
    }

    public Map<String, JsonObject> infoBatch(List<String> ids) throws IOException, InterruptedException {
        Map<String, JsonObject> result = new LinkedHashMap<>();
        for (int i = 0; i < ids.size(); i += 100) {
            List<String> chunk = ids.subList(i, Math.min(ids.size(), i + 100));
            String joined = String.join(",", chunk);
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(API + "/file/" + joined + "/info"))
                    .header("User-Agent", "ModpackUpdater/" + Updater.VERSION)
                    .timeout(Duration.ofSeconds(60))
                    .GET();
            String auth = authHeader();
            if (auth != null) {
                builder.header("Authorization", auth);
            }
            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 400) {
                throw new IOException("pixeldrain info HTTP " + response.statusCode() + ": " + response.body());
            }
            JsonElement element = JsonParser.parseString(response.body());
            if (element.isJsonArray()) {
                for (JsonElement item : element.getAsJsonArray()) {
                    JsonObject object = item.getAsJsonObject();
                    if (object.has("id")) {
                        result.put(object.get("id").getAsString(), object);
                    }
                }
            } else if (element.isJsonObject()) {
                JsonObject object = element.getAsJsonObject();
                if (object.has("id")) {
                    result.put(object.get("id").getAsString(), object);
                }
            }
        }
        return result;
    }

    public String downloadToString(String url) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", "ModpackUpdater/" + Updater.VERSION)
                .timeout(Duration.ofSeconds(60))
                .GET();
        String auth = authHeader();
        if (auth != null) {
            builder.header("Authorization", auth);
        }
        HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() >= 400) {
            throw new IOException("HTTP " + response.statusCode() + " for " + url);
        }
        return response.body();
    }

    public void download(String id, Path dest, long expectedSize, ProgressListener listener) throws IOException, InterruptedException {
        downloadFromUrl(API + "/file/" + id + "?download", dest, expectedSize, listener);
    }

    public void downloadFromUrl(String url, Path dest, long expectedSize, ProgressListener listener) throws IOException, InterruptedException {
        long existing = Files.exists(dest) ? Files.size(dest) : 0L;
        if (expectedSize > 0 && existing == expectedSize) {
            return;
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", "ModpackUpdater/" + Updater.VERSION)
                .timeout(Duration.ofMinutes(30))
                .GET();
        String auth = authHeader();
        if (auth != null) {
            builder.header("Authorization", auth);
        }
        boolean resume = existing > 0 && (expectedSize <= 0 || existing < expectedSize);
        if (resume) {
            builder.header("Range", "bytes=" + existing + "-");
        } else {
            existing = 0L;
        }
        HttpResponse<InputStream> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        int code = response.statusCode();
        if (code >= 400) {
            try (InputStream in = response.body()) {
                String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                throw new IOException("pixeldrain download HTTP " + code + ": " + body);
            }
        }
        boolean append = code == 206 && existing > 0;
        long written = append ? existing : 0L;
        Files.createDirectories(dest.toAbsolutePath().getParent());
        if (!append) {
            Files.deleteIfExists(dest);
        }
        StandardOpenOption[] options = append
                ? new StandardOpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND}
                : new StandardOpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING};
        try (InputStream in = response.body(); OutputStream out = Files.newOutputStream(dest, options)) {
            byte[] buffer = new byte[256 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
                written += read;
                if (listener != null) {
                    listener.onProgress(written, expectedSize);
                }
            }
        }
    }

    public String upload(Path file) throws IOException, InterruptedException {
        if (apiKey == null) {
            throw new IOException("上传需要 API Key");
        }
        String name = file.getFileName().toString();
        String encoded = URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
        HttpRequest request = HttpRequest.newBuilder(URI.create(API + "/file/" + encoded))
                .header("Authorization", authHeader())
                .header("User-Agent", "ModpackUpdater/" + Updater.VERSION)
                .timeout(Duration.ofMinutes(120))
                .PUT(HttpRequest.BodyPublishers.ofFile(file))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() >= 400) {
            throw new IOException("pixeldrain upload " + name + " HTTP " + response.statusCode() + ": " + response.body());
        }
        JsonObject object = JsonParser.parseString(response.body()).getAsJsonObject();
        if (!object.has("id")) {
            throw new IOException("上传响应缺少 id: " + response.body());
        }
        return object.get("id").getAsString();
    }

    public void delete(String id) throws IOException, InterruptedException {
        if (apiKey == null) {
            throw new IOException("删除需要 API Key");
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(API + "/file/" + id))
                .header("Authorization", authHeader())
                .header("User-Agent", "ModpackUpdater/" + Updater.VERSION)
                .timeout(Duration.ofSeconds(60))
                .DELETE()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() >= 400) {
            throw new IOException("pixeldrain delete " + id + " HTTP " + response.statusCode() + ": " + response.body());
        }
    }
}
