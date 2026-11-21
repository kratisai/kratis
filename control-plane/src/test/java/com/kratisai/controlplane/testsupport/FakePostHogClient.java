package com.kratisai.controlplane.testsupport;

import com.kratisai.controlplane.client.PostHogCaptureRequest;
import com.kratisai.controlplane.client.PostHogClient;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** In-memory {@link PostHogClient} double shared across Spring integration tests. */
public class FakePostHogClient implements PostHogClient {

    private final List<PostHogCaptureRequest> requests = new CopyOnWriteArrayList<>();
    private volatile boolean shouldThrow;

    public void setShouldThrow(boolean shouldThrow) {
        this.shouldThrow = shouldThrow;
    }

    public void reset() {
        requests.clear();
        shouldThrow = false;
    }

    public List<PostHogCaptureRequest> requests() {
        return requests;
    }

    @Override
    public void capture(PostHogCaptureRequest request) {
        if (shouldThrow) {
            throw new IllegalStateException("Simulated PostHog failure");
        }
        requests.add(request);
    }
}
