package com.moh.yehia.websocket.interceptor;

import com.moh.yehia.websocket.metrics.WebsocketMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ExtendWith(MockitoExtension.class)
class UserSessionTrackerTest {
    private UserSessionTracker userSessionTracker;

    @Mock
    private WebsocketMetrics websocketMetrics;
    private final String username = "moh";

    @BeforeEach
    void setUp() {
        userSessionTracker = new UserSessionTracker(websocketMetrics);
    }

    @Test
    void shouldAllowConnectionsWithinLimit() {
        assertDoesNotThrow(() -> userSessionTracker.registerSession(username));
        assertDoesNotThrow(() -> userSessionTracker.registerSession(username));
    }

    @Test
    void shouldRejectConnectionsExceedingLimit() {
        userSessionTracker.registerSession(username);
        userSessionTracker.registerSession(username);

        assertThrows(IllegalStateException.class, () -> userSessionTracker.registerSession(username));
    }

    @Test
    void shouldAllowAfterDisconnect() {
        userSessionTracker.registerSession(username);
        userSessionTracker.registerSession(username);
        userSessionTracker.removeSession(username);

        assertDoesNotThrow(() -> userSessionTracker.registerSession(username));
    }

    @Test
    void shouldNotThrowExceptionWhenNoSessionExists() {
        assertDoesNotThrow(() -> userSessionTracker.removeSession("unknown"));
    }

    @Test
    void shouldTrackDifferentUsersIndependently() {
        userSessionTracker.registerSession(username);
        userSessionTracker.registerSession(username);
        assertDoesNotThrow(() -> userSessionTracker.registerSession("another"));

    }
}