package ch.fmartin;

import com.sun.net.httpserver.HttpServer;
import com.zaxxer.hikari.HikariDataSource;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.WatchService;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Sends real HTTP requests from the two notification clients to a stub server and checks what
 * arrived and what was logged. A change in the JDK's HttpURLConnection or in org.json that alters
 * the request shape or the error handling fails here.
 */
class NotificationClientTest {

    private record RecordedRequest(String method, String path, Map<String, List<String>> headers, String body) {
    }

    private final Logger logger = mock(Logger.class);
    private final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private volatile int responseStatus = 200;
    private volatile String responseBody = "{}";

    @BeforeEach
    void startStubServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(new RecordedRequest(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders(), body));
            byte[] out = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(responseStatus, out.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
        });
        server.start();
    }

    @AfterEach
    void stopStubServer() {
        server.stop(0);
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private SiusDataToPostgresAdapter adapter(String pushbulletApiKey, String gotifyUrl, String gotifyToken, int gotifyPriority) {
        return new SiusDataToPostgresAdapter(logger, mock(ExecutorService.class), mock(HikariDataSource.class),
                mock(WatchService.class), "test_directory", "jdbc:postgresql://localhost/testdb", "user", "password",
                pushbulletApiKey, gotifyUrl, gotifyToken, gotifyPriority);
    }

    private RecordedRequest onlyRequest() {
        assertEquals(1, requests.size(), "exactly one request should reach the stub server");
        return requests.get(0);
    }

    @Test
    void pushbulletPostsANoteWithTheAccessToken() {
        SiusDataToPostgresAdapter adapter = adapter("secret-token", null, null, 5);
        adapter.setPushbulletEndpoint(baseUrl() + "/v2/pushes");

        adapter.sendPushbulletNotification("Title", "Body text");

        RecordedRequest request = onlyRequest();
        assertEquals("POST", request.method());
        assertEquals("/v2/pushes", request.path());
        assertEquals(List.of("secret-token"), request.headers().get("Access-token"));
        assertEquals(List.of("application/json"), request.headers().get("Content-type"));
        JSONObject json = new JSONObject(request.body());
        assertEquals("note", json.getString("type"));
        assertEquals("Title", json.getString("title"));
        assertEquals("Body text", json.getString("body"));
        verify(logger).debug("Pushbullet notification sent successfully.");
        verify(logger, never()).error(anyString(), any(Object.class));
    }

    @Test
    void pushbulletLogsStatusAndBodyWhenRejected() {
        responseStatus = 401;
        responseBody = "{\"error\":{\"message\":\"Access token is missing or invalid\"}}";
        SiusDataToPostgresAdapter adapter = adapter("wrong-token", null, null, 5);
        adapter.setPushbulletEndpoint(baseUrl() + "/v2/pushes");

        adapter.sendPushbulletNotification("Title", "Body text");

        onlyRequest();
        verify(logger).error("Failed to send Pushbullet notification. Response Code: {}", 401);
        verify(logger).error("Response body: {}", responseBody);
    }

    @Test
    void pushbulletLogsAConnectionFailureInsteadOfThrowing() {
        SiusDataToPostgresAdapter adapter = adapter("secret-token", null, null, 5);
        adapter.setPushbulletEndpoint(baseUrl() + "/v2/pushes");
        server.stop(0);

        adapter.sendPushbulletNotification("Title", "Body text");

        assertTrue(requests.isEmpty());
        verify(logger).error(eq("Error sending Pushbullet notification: {}"), anyString(), any(IOException.class));
    }

    @Test
    void gotifyPostsTheMessageWithKeyAndPriority() {
        SiusDataToPostgresAdapter adapter = adapter(null, baseUrl(), "app-token", 7);

        adapter.sendGotifyNotification("Title", "Body text");

        RecordedRequest request = onlyRequest();
        assertEquals("POST", request.method());
        assertEquals("/message", request.path());
        assertEquals(List.of("app-token"), request.headers().get("X-gotify-key"));
        assertEquals(List.of("application/json"), request.headers().get("Content-type"));
        JSONObject json = new JSONObject(request.body());
        assertEquals("Title", json.getString("title"));
        assertEquals("Body text", json.getString("message"));
        assertEquals(7, json.getInt("priority"));
        verify(logger).debug("Gotify notification sent successfully.");
        verify(logger, never()).error(anyString(), any(Object.class));
    }

    @Test
    void gotifyAppendsMessageToAUrlWithATrailingSlashAndAPath() {
        SiusDataToPostgresAdapter adapter = adapter(null, baseUrl() + "/gotify/", "app-token", 5);

        adapter.sendGotifyNotification("Title", "Body text");

        assertEquals("/gotify/message", onlyRequest().path());
    }

    @Test
    void gotifyLogsStatusAndBodyWhenRejected() {
        responseStatus = 403;
        responseBody = "{\"error\":\"Forbidden\",\"errorCode\":403}";
        SiusDataToPostgresAdapter adapter = adapter(null, baseUrl(), "wrong-token", 5);

        adapter.sendGotifyNotification("Title", "Body text");

        onlyRequest();
        verify(logger).error("Failed to send Gotify notification. Response Code: {}", 403);
        verify(logger).error("Response body: {}", responseBody);
    }

    @Test
    void gotifyLogsAConnectionFailureInsteadOfThrowing() {
        SiusDataToPostgresAdapter adapter = adapter(null, baseUrl(), "app-token", 5);
        server.stop(0);

        adapter.sendGotifyNotification("Title", "Body text");

        assertTrue(requests.isEmpty());
        verify(logger).error(eq("Error sending Gotify notification: {}"), anyString(), any(IOException.class));
    }
}
