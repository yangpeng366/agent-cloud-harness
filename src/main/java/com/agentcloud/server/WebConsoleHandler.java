package com.agentcloud.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Map;

/**
 * 轻量静态控制台入口。
 * 当前仅分发内置 console 资源，不引入额外前端运行时。
 */
class WebConsoleHandler implements HttpHandler {
    private static final Logger log = LoggerFactory.getLogger(WebConsoleHandler.class);
    private static final Map<String, String> CONTENT_TYPES = Map.of(
        "html", "text/html; charset=UTF-8",
        "css", "text/css; charset=UTF-8",
        "js", "application/javascript; charset=UTF-8",
        "json", "application/json; charset=UTF-8",
        "svg", "image/svg+xml",
        "txt", "text/plain; charset=UTF-8"
    );
    private final String routePrefix;
    private final String resourceRoot;

    WebConsoleHandler() {
        this("/console", "web/console");
    }

    WebConsoleHandler(String routePrefix, String resourceRoot) {
        this.routePrefix = normalizePrefix(routePrefix);
        this.resourceRoot = resourceRoot;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            String method = exchange.getRequestMethod();
            boolean headOnly = "HEAD".equals(method);
            if (!"GET".equals(method) && !headOnly) {
                NioHttpServer.sendJson(exchange, 405, Map.of(
                    "success", false,
                    "code", "405",
                    "message", "method not allowed"
                ));
                return;
            }

            String resourcePath = resolveResourcePath(exchange.getRequestURI().getPath());
            if (resourcePath == null) {
                NioHttpServer.sendJson(exchange, 404, Map.of(
                    "success", false,
                    "code", "404",
                    "message", "not found"
                ));
                return;
            }

            byte[] body = readResource(resourceRoot + resourcePath);
            exchange.getResponseHeaders().set("Content-Type", contentTypeFor(resourcePath));
            exchange.sendResponseHeaders(200, headOnly ? -1 : body.length);
            if (!headOnly) {
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        } catch (Exception e) {
            log.error("WebConsoleHandler error", e);
            NioHttpServer.sendJson(exchange, 500, Map.of(
                "success", false,
                "code", "500",
                "message", "console render failed"
            ));
        }
    }

    private String resolveResourcePath(String requestPath) {
        if (requestPath == null || requestPath.isBlank() || routePrefix.equals(requestPath) || (routePrefix + "/").equals(requestPath)) {
            return "/index.html";
        }
        if (requestPath.startsWith(routePrefix + "/")) {
            String relative = requestPath.substring(routePrefix.length());
            if (isSafeStaticResource(relative)) {
                return relative;
            }
        }
        return null;
    }

    private String contentTypeFor(String resourcePath) {
        int extensionIndex = resourcePath.lastIndexOf('.');
        if (extensionIndex < 0 || extensionIndex >= resourcePath.length() - 1) {
            return "application/octet-stream";
        }
        String extension = resourcePath.substring(extensionIndex + 1).toLowerCase(Locale.ROOT);
        return CONTENT_TYPES.getOrDefault(extension, "application/octet-stream");
    }

    private boolean isSafeStaticResource(String relativePath) {
        if (relativePath == null || relativePath.isBlank() || !relativePath.startsWith("/")) {
            return false;
        }
        if (relativePath.contains("..") || relativePath.contains("\\") || relativePath.endsWith("/")) {
            return false;
        }
        int extensionIndex = relativePath.lastIndexOf('.');
        if (extensionIndex < 0 || extensionIndex >= relativePath.length() - 1) {
            return false;
        }
        String extension = relativePath.substring(extensionIndex + 1).toLowerCase(Locale.ROOT);
        return CONTENT_TYPES.containsKey(extension);
    }

    private byte[] readResource(String resourcePath) throws IOException {
        try (InputStream input = WebConsoleHandler.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (input == null) {
                throw new IOException("resource not found: " + resourcePath);
            }
            return input.readAllBytes();
        }
    }

    private static String normalizePrefix(String routePrefix) {
        if (routePrefix == null || routePrefix.isBlank() || "/".equals(routePrefix.trim())) {
            return "/console";
        }
        String normalized = routePrefix.trim();
        return normalized.endsWith("/") ? normalized.substring(0, normalized.length() - 1) : normalized;
    }
}
