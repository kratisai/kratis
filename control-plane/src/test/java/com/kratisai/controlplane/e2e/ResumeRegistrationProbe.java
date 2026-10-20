package com.kratisai.controlplane.e2e;

import com.kratisai.controlplane.model.event.EnvironmentRegisteredEvent;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.stereotype.Component;

/**
 * Counts {@link EnvironmentRegisteredEvent}s per environment for suspend/resume forensics: a
 * resume must fire exactly one. A duplicate initial registration means a second connector
 * process started on the resumed container.
 */
@Component
public class ResumeRegistrationProbe implements ApplicationListener<ApplicationEvent> {

    private final ConcurrentHashMap<UUID, AtomicInteger> counts = new ConcurrentHashMap<>();

    @Override
    public void onApplicationEvent(ApplicationEvent event) {
        Object payload = event instanceof PayloadApplicationEvent<?> wrapped ? wrapped.getPayload() : event;
        if (payload instanceof EnvironmentRegisteredEvent registered) {
            counts.computeIfAbsent(registered.environmentId(), id -> new AtomicInteger())
                    .incrementAndGet();
        }
    }

    public int countFor(UUID environmentId) {
        AtomicInteger count = counts.get(environmentId);
        return count == null ? 0 : count.get();
    }
}
