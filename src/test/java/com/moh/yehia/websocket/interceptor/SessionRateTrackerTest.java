package com.moh.yehia.websocket.interceptor;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class SessionRateTrackerTest {
    @Test
    void shouldAllowMessagesWithinLimit() {
        var tracker = new SessionRateTracker(5);
        for (int i = 0; i < 5; i++) {
            Assertions.assertThat(tracker.allowMessage()).isTrue();
        }
    }

    @Test
    void shouldDenyMessagesExceedingLimit() {
        var tracker = new SessionRateTracker(2);
        Assertions.assertThat(tracker.allowMessage()).isTrue();
        Assertions.assertThat(tracker.allowMessage()).isTrue();
        Assertions.assertThat(tracker.allowMessage()).isFalse();
    }

    @Test
    void shouldResetAfterWindowExpires() throws InterruptedException {
        var tracker = new SessionRateTracker(2);
        Assertions.assertThat(tracker.allowMessage()).isTrue();
        Assertions.assertThat(tracker.allowMessage()).isTrue();
        Assertions.assertThat(tracker.allowMessage()).isFalse();
        Thread.sleep(1100);

        Assertions.assertThat(tracker.allowMessage()).isTrue();
    }
}