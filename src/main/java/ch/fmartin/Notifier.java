package ch.fmartin;

import com.google.common.base.Throwables;
import org.json.JSONObject;
import org.slf4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Logs errors and sends them through Pushbullet and Gotify, each only when it is configured.
 */
class Notifier {

    // Pushbullet has one fixed endpoint. Tests point this at a local stub server.
    static final String PUSHBULLET_ENDPOINT = "https://api.pushbullet.com/v2/pushes";
    private String pushbulletEndpoint = PUSHBULLET_ENDPOINT;

    private final Logger logger;
    private final String pushbulletApiKey;
    private final String gotifyUrl;
    private final String gotifyToken;
    private final int gotifyPriority;

    Notifier(Logger logger, String pushbulletApiKey, String gotifyUrl, String gotifyToken, int gotifyPriority) {
        this.logger = logger;
        this.pushbulletApiKey = pushbulletApiKey;
        this.gotifyUrl = gotifyUrl;
        this.gotifyToken = gotifyToken;
        this.gotifyPriority = gotifyPriority;
    }

    /**
     * Sends a notification through Pushbullet if the API key is set.
     *
     * @param title   The title of the notification.
     * @param message The message body of the notification.
     */
    void sendPushbulletNotification(String title, String message) {
        if (pushbulletApiKey == null || pushbulletApiKey.isEmpty()) {
            logger.debug("Pushbullet API key not set. Skipping notification.");
            return;
        }

        HttpURLConnection conn = null;
        try {
            URL url = URI.create(pushbulletEndpoint).toURL();
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Access-Token", pushbulletApiKey);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);

            // Create the JSON payload
            JSONObject json = new JSONObject();
            json.put("type", "note");
            json.put("title", title);
            json.put("body", message);

            // Using try-with-resources for OutputStream
            try (OutputStream os = conn.getOutputStream()) {
                os.write(json.toString().getBytes(StandardCharsets.UTF_8));
            }

            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                logger.error("Failed to send Pushbullet notification. Response Code: {}", responseCode);

                InputStream errorStream = conn.getErrorStream();
                if (errorStream != null) {
                    // Using try-with-resources for BufferedReader to read the error response body
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(errorStream, StandardCharsets.UTF_8))) {
                        StringBuilder responseBody = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) {
                            responseBody.append(line);
                        }
                        logger.error("Response body: {}", responseBody.toString());
                    } catch (IOException e) {
                        logger.error("Error reading response body: {}", e.getMessage(), e);
                    }
                }
            } else {
                logger.debug("Pushbullet notification sent successfully.");
            }

        } catch (IOException e) {
            logger.error("Error sending Pushbullet notification: {}", e.getMessage(), e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    void sendGotifyNotification(String title, String message) {
        if (gotifyUrl == null || gotifyUrl.isBlank()) {
            logger.debug("Gotify URL not set. Skipping notification.");
            return;
        }
        if (gotifyToken == null || gotifyToken.isBlank()) {
            logger.debug("Gotify token not set. Skipping notification.");
            return;
        }

        HttpURLConnection conn = null;
        try {
            String endpoint = gotifyUrl.endsWith("/") ? gotifyUrl + "message" : gotifyUrl + "/message";
            URL url = URI.create(endpoint).toURL();
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("X-Gotify-Key", gotifyToken);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);

            JSONObject json = new JSONObject();
            json.put("title", title);
            json.put("message", message);
            json.put("priority", gotifyPriority);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(json.toString().getBytes(StandardCharsets.UTF_8));
            }

            int responseCode = conn.getResponseCode();
            if (responseCode < 200 || responseCode >= 300) {
                logger.error("Failed to send Gotify notification. Response Code: {}", responseCode);

                InputStream errorStream = conn.getErrorStream();
                if (errorStream != null) {
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(errorStream, StandardCharsets.UTF_8))) {
                        StringBuilder responseBody = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) {
                            responseBody.append(line);
                        }
                        logger.error("Response body: {}", responseBody.toString());
                    } catch (IOException e) {
                        logger.error("Error reading Gotify response body: {}", e.getMessage(), e);
                    }
                }
            } else {
                logger.debug("Gotify notification sent successfully.");
            }

        } catch (IOException e) {
            logger.error("Error sending Gotify notification: {}", e.getMessage(), e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    void setPushbulletEndpoint(String pushbulletEndpoint) {
        this.pushbulletEndpoint = pushbulletEndpoint;
    }

    void sendNotifications(String title, String message) {
        sendPushbulletNotification(title, message);
        sendGotifyNotification(title, message);
    }

    /**
     * Centralized method to log errors and send notifications.
     *
     * @param message   The error message to log and send.
     * @param throwable The throwable associated with the error (can be null).
     */
    void logError(String message, Throwable throwable) {
        if (throwable != null) {
            logger.error(message, throwable);
            sendNotifications("SiusData Adapter Error", message + "\n" + Throwables.getStackTraceAsString(throwable));
        } else {
            logError(message); // Delegate to the overloaded method
        }
    }

    /**
     * Overloaded method to log errors without a Throwable.
     *
     * @param message The error message to log and send.
     */
    void logError(String message) {
        logger.error(message);
        sendNotifications("SiusData Adapter Error", message);
    }
}
