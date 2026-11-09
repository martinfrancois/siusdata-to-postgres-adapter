package ch.fmartin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

class NotifierTest {

    private final Logger logger = mock(Logger.class);

    private Notifier notifier;

    @BeforeEach
    void setUp() {
        notifier = spy(new Notifier(logger, "pushbulletApiKey", null, null, AdapterConfiguration.DEFAULT_GOTIFY_PRIORITY));
        // ensure we don't call the real pushbullet endpoint
        lenient().doNothing().when(notifier).sendPushbulletNotification(anyString(), anyString());
    }

    @Test
    void testSendPushbulletNotification_ApiKeyNotSet() {
        // given
        notifier = new Notifier(logger, null, null, null, AdapterConfiguration.DEFAULT_GOTIFY_PRIORITY);

        // when
        notifier.sendPushbulletNotification("title", "message");

        // then
        verify(logger).debug("Pushbullet API key not set. Skipping notification.");
    }

    @Test
    void testSendGotifyNotification_UrlNotSet() {
        // when
        new Notifier(logger, "pushbulletApiKey", null, null, AdapterConfiguration.DEFAULT_GOTIFY_PRIORITY)
                .sendGotifyNotification("title", "message");

        // then
        verify(logger).debug("Gotify URL not set. Skipping notification.");
    }

    @Test
    void testSendGotifyNotification_TokenNotSet() {
        // when
        new Notifier(logger, null, "https://gotify.example.com", null, 5).sendGotifyNotification("title", "message");

        // then
        verify(logger).debug("Gotify token not set. Skipping notification.");
    }

    @Test
    void testSendNotifications() {
        // given
        doNothing().when(notifier).sendGotifyNotification(anyString(), anyString());

        // when
        notifier.sendNotifications("title", "message");

        // then
        verify(notifier).sendPushbulletNotification("title", "message");
        verify(notifier).sendGotifyNotification("title", "message");
    }

    @Test
    void testLogError_WithThrowable() {
        // given
        Throwable throwable = new RuntimeException("Test exception");

        // when
        notifier.logError("Error message", throwable);

        // then
        verify(logger).error("Error message", throwable);
        verify(notifier).sendPushbulletNotification(anyString(), anyString());
        verify(notifier).sendGotifyNotification(anyString(), anyString());
    }

    @Test
    void testLogError_WithoutThrowable() {
        // when
        notifier.logError("Error message");

        // then
        verify(logger).error("Error message");
        verify(notifier).sendPushbulletNotification(anyString(), anyString());
        verify(notifier).sendGotifyNotification(anyString(), anyString());
    }
}
