package com.kratisai.controlplane.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import com.kratisai.controlplane.service.SubscriptionRegistry;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SubscriptionRegistryTest {

    private SubscriptionRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new SubscriptionRegistry();
    }

    @Test
    void subscribe_shouldAddSubscription() {
        String sessionId = "session-1";
        UUID teamId = UUID.randomUUID();

        boolean added = registry.subscribe(sessionId, teamId);

        assertThat(added).isTrue();
        assertThat(registry.isSubscribed(sessionId, teamId)).isTrue();
    }

    @Test
    void subscribe_shouldReplacePreviousSubscription() {
        String sessionId = "session-1";
        UUID teamId1 = UUID.randomUUID();
        UUID teamId2 = UUID.randomUUID();

        registry.subscribe(sessionId, teamId1);

        boolean added = registry.subscribe(sessionId, teamId2);

        assertThat(added).isTrue();
        assertThat(registry.isSubscribed(sessionId, teamId1)).isFalse();
        assertThat(registry.isSubscribed(sessionId, teamId2)).isTrue();
    }

    @Test
    void subscribe_shouldReturnFalseIfAlreadySubscribedToSameTeam() {
        String sessionId = "session-1";
        UUID teamId = UUID.randomUUID();
        registry.subscribe(sessionId, teamId);

        boolean added = registry.subscribe(sessionId, teamId);

        assertThat(added).isFalse();
    }

    @Test
    void unsubscribe_shouldRemoveSubscription() {
        String sessionId = "session-1";
        UUID teamId = UUID.randomUUID();
        registry.subscribe(sessionId, teamId);

        boolean removed = registry.unsubscribe(sessionId, teamId);

        assertThat(removed).isTrue();
        assertThat(registry.isSubscribed(sessionId, teamId)).isFalse();
    }

    @Test
    void unsubscribe_shouldReturnFalseIfNotSubscribedToThatTeam() {
        String sessionId = "session-1";
        UUID teamId1 = UUID.randomUUID();
        UUID teamId2 = UUID.randomUUID();

        registry.subscribe(sessionId, teamId1);

        boolean removed = registry.unsubscribe(sessionId, teamId2);

        assertThat(removed).isFalse();
        assertThat(registry.isSubscribed(sessionId, teamId1)).isTrue();
    }

    @Test
    void unsubscribe_shouldReturnFalseIfSessionNotRegistered() {
        boolean removed = registry.unsubscribe("unknown-session", UUID.randomUUID());
        assertThat(removed).isFalse();
    }

    @Test
    void isSubscribed_shouldReturnFalseForUnknownSession() {
        assertThat(registry.isSubscribed("unknown-session", UUID.randomUUID())).isFalse();
    }

    @Test
    void getSubscribers_shouldReturnOnlySubscribedSessions() {
        UUID teamId = UUID.randomUUID();
        registry.subscribe("session-1", teamId);
        registry.subscribe("session-2", UUID.randomUUID()); // different team

        Set<String> subscribers = registry.getSubscribers(teamId);

        assertThat(subscribers).containsExactly("session-1");
    }

    @Test
    void getSubscribers_shouldReturnEmptySetIfNoSubscribers() {
        Set<String> subscribers = registry.getSubscribers(UUID.randomUUID());

        assertThat(subscribers).isEmpty();
    }

    @Test
    void getSubscription_shouldReturnSubscriptionForSession() {
        String sessionId = "session-1";
        UUID teamId = UUID.randomUUID();
        registry.subscribe(sessionId, teamId);

        UUID subscription = registry.getSubscription(sessionId);

        assertThat(subscription).isEqualTo(teamId);
    }

    @Test
    void getSubscription_shouldReturnNullForUnknownSession() {
        UUID subscription = registry.getSubscription("unknown-session");
        assertThat(subscription).isNull();
    }

    @Test
    void removeSession_shouldRemoveSubscription() {
        String sessionId = "session-1";
        UUID teamId = UUID.randomUUID();
        registry.subscribe(sessionId, teamId);

        registry.removeSession(sessionId);

        assertThat(registry.isSubscribed(sessionId, teamId)).isFalse();
        assertThat(registry.getSubscription(sessionId)).isNull();
    }

    @Test
    void removeSession_shouldRemoveFromSubscribers() {
        String sessionId = "session-1";
        UUID teamId = UUID.randomUUID();
        registry.subscribe(sessionId, teamId);

        registry.removeSession(sessionId);

        Set<String> subscribers = registry.getSubscribers(teamId);
        assertThat(subscribers).isEmpty();
    }
}
