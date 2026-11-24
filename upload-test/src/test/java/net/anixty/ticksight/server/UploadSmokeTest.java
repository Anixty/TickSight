package net.anixty.ticksight.server;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPInputStream;

public final class UploadSmokeTest {
    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<Throwable> receivedError = new AtomicReference<>();
        String payload = "{\"data\":{\"ticks\":200},\"diagnostics\":{\"test\":\"локально\"}}";
        String profileUrl = "https://ticksight.anixty.net/profile/local-test";
        server.createContext("/v1/add", exchange -> {
            try {
                require("POST".equals(exchange.getRequestMethod()), "HTTP method");
                require("gzip".equals(exchange.getRequestHeaders().getFirst("Content-Encoding")), "gzip header");
                require("application/json; charset=utf-8".equals(exchange.getRequestHeaders().getFirst("Content-Type")), "JSON content type");
                String body;
                try (GZIPInputStream input = new GZIPInputStream(exchange.getRequestBody())) {
                    body = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                }
                require(payload.equals(body), "UTF-8 JSON body");
                byte[] response = (profileUrl + "\n").getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(201, response.length);
                exchange.getResponseBody().write(response);
            } catch (Throwable error) {
                receivedError.set(error);
                exchange.sendResponseHeaders(500, -1);
            } finally {
                exchange.close();
            }
        });
        server.createContext("/fail", exchange -> { exchange.sendResponseHeaders(503, -1); exchange.close(); });
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().set("Location", "/v1/add");
            exchange.sendResponseHeaders(307, -1); exchange.close();
        });
        server.createContext("/invalid", exchange -> {
            byte[] response = "javascript:alert(1)".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response); exchange.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            require(profileUrl.equals(ProfileUpload.upload(base + "/v1/add", payload)), "profile URL");
            var file = Files.createTempFile("ticksight-upload-", ".json");
            try {
                Files.writeString(file, payload, StandardCharsets.UTF_8);
                require(profileUrl.equals(ProfileUpload.upload(base + "/v1/add", file)), "streamed file profile URL");
            } finally {
                Files.deleteIfExists(file);
            }
            if (receivedError.get() != null) throw new AssertionError(receivedError.get());
            expectFailure(base + "/fail", payload, "HTTP 503");
            expectFailure(base + "/redirect", payload, "HTTP 307");
            expectFailure(base + "/invalid", payload, "Invalid profile upload URL");
            expectFailure("file:///tmp/profile", payload, "Invalid profile upload URL");
            System.out.println("PASS: gzip POST, UTF-8 JSON, response URL, HTTP errors, no redirect, invalid URL rejection (loopback only)");
        } finally {
            server.stop(0);
        }
    }

    private static void expectFailure(String endpoint, String payload, String message) throws Exception {
        try {
            ProfileUpload.upload(endpoint, payload);
            throw new AssertionError("Expected failure: " + message);
        } catch (IOException error) {
            require(error.getMessage().contains(message), "expected " + message + ", got " + error);
        }
    }

    private static void require(boolean condition, String what) {
        if (!condition) throw new AssertionError(what);
    }
}
