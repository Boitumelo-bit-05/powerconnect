package powerconnect;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

public class ClaudeClient {

    private static final String URL = "https://api.anthropic.com/v1/messages";
    // If you get a "model not found" error, replace this with a current model name
    // from the Anthropic Console.
    private static final String MODEL = "claude-haiku-5-5";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private static String lastError = "";

    public static boolean isConfigured() {
        return apiKey() != null;
    }

    public static String lastError() {
        return lastError;
    }

    public static String ask(String system, String user, int maxTokens) {
             return complete(system, java.util.Collections.singletonList(new String[] {"user", user}), maxTokens);
    }
    // turns: each entry is {role, text}, starting with "user". Returns null on failure.
    public static String complete(String system, List<String[]> turns, int maxTokens) {
        String key = apiKey();
        if (key == null) {
            lastError = "no API key";
            return null;
        }

        StringBuilder body = new StringBuilder();
        body.append("{\"model\":\"").append(MODEL).append("\",\"max_tokens\":").append(maxTokens);
        if (system != null && !system.isEmpty()) {
            body.append(",\"system\":\"").append(escape(system)).append("\"");
        }
        body.append(",\"messages\":[");
        for (int i = 0; i < turns.size(); i++) {
            if (i > 0) {
                body.append(",");
            }
            body.append("{\"role\":\"").append(turns.get(i)[0])
                    .append("\",\"content\":\"").append(escape(turns.get(i)[1])).append("\"}");
        }
        body.append("]}");

        HttpRequest request = HttpRequest.newBuilder(URI.create(URL))
                .timeout(Duration.ofSeconds(30))
                .header("x-api-key", key)
                .header("anthropic-version", "2023-06-01")
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        try {
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                String message = jsonString(response.body(), "message");
                lastError = "HTTP " + response.statusCode() + (message == null ? "" : ": " + message);
                return null;
            }
            String text = jsonString(response.body(), "text");
            if (text == null) {
                lastError = "empty response";
            }
            return text;
        } catch (IOException e) {
            lastError = "could not reach the API (" + e.getMessage() + ")";
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            lastError = "request interrupted";
            return null;
        }
    }

    // The key comes from claude-api-key.txt in the project folder, or the
    // ANTHROPIC_API_KEY environment variable.
    private static String apiKey() {
        try {
            Path file = Path.of("claude-api-key.txt");
            if (Files.exists(file)) {
                String key = Files.readString(file).trim();
                if (!key.isEmpty()) {
                    return key;
                }
            }
        } catch (IOException e) {
            // fall through to the environment variable
        }
        String env = System.getenv("ANTHROPIC_API_KEY");
        return (env == null || env.isBlank()) ? null : env.trim();
    }

    private static String escape(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    // Reads the first string value for "key" from a JSON text.
    public static String jsonString(String json, String key) {
        if (json == null) {
            return null;
        }
        int from = 0;
        while (true) {
            int k = json.indexOf("\"" + key + "\"", from);
            if (k < 0) {
                return null;
            }
            int i = k + key.length() + 2;
            while (i < json.length() && Character.isWhitespace(json.charAt(i))) {
                i++;
            }
            if (i < json.length() && json.charAt(i) == ':') {
                i++;
                while (i < json.length() && Character.isWhitespace(json.charAt(i))) {
                    i++;
                }
                if (i < json.length() && json.charAt(i) == '"') {
                    return readString(json, i + 1);
                }
            }
            from = k + 1;
        }
    }

    private static String readString(String json, int start) {
        StringBuilder sb = new StringBuilder();
        int i = start;
        while (i < json.length()) {
            char c = json.charAt(i++);
            if (c == '"') {
                return sb.toString();
            }
            if (c == '\\' && i < json.length()) {
                char n = json.charAt(i++);
                switch (n) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'u' -> {
                        if (i + 4 <= json.length()) {
                            sb.append((char) Integer.parseInt(json.substring(i, i + 4), 16));
                            i += 4;
                        }
                    }
                    default -> sb.append(n);
                }
            } else {
                sb.append(c);
            }
        }
        return null;
    }
}