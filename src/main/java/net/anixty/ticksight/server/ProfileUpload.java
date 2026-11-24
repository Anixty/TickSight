package net.anixty.ticksight.server;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;

/** The viewer API accepts gzip JSON and returns the public profile URL as text. */
public final class ProfileUpload {
    private static final int MAX_RESPONSE_BYTES = 8192;

    private ProfileUpload() {}

    public static String upload(String endpoint, String json) throws IOException {
        return upload(endpoint, output -> output.write(json.getBytes(StandardCharsets.UTF_8)));
    }

    public static String upload(String endpoint, Path json) throws IOException {
        return upload(endpoint, output -> Files.copy(json, output));
    }

    @FunctionalInterface
    private interface Body { void write(OutputStream output) throws IOException; }

    private static String upload(String endpoint, Body json) throws IOException {
        URI uri = httpUri(endpoint);
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(30_000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setRequestProperty("Content-Encoding", "gzip");
        connection.setRequestProperty("Accept", "text/plain");
        connection.setRequestProperty("User-Agent", "TickSight");
        connection.setDoOutput(true);
        connection.setChunkedStreamingMode(64 * 1024);
        try {
            try (GZIPOutputStream body = new GZIPOutputStream(connection.getOutputStream())) {
                json.write(body);
            }
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IOException("Profile upload returned HTTP " + status);
            }
            byte[] response;
            try (var input = connection.getInputStream()) {
                response = input.readNBytes(MAX_RESPONSE_BYTES + 1);
            }
            if (response.length > MAX_RESPONSE_BYTES) {
                throw new IOException("Profile upload returned an oversized response");
            }
            String url = new String(response, StandardCharsets.UTF_8).trim();
            httpUri(url);
            return url;
        } finally {
            connection.disconnect();
        }
    }

    private static URI httpUri(String value) throws IOException {
        try {
            URI uri = URI.create(value);
            if ((!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null) {
                throw new IllegalArgumentException("Expected an absolute HTTP(S) URL");
            }
            return uri;
        } catch (IllegalArgumentException error) {
            throw new IOException("Invalid profile upload URL", error);
        }
    }
}
