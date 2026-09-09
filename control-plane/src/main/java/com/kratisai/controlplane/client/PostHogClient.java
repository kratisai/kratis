package com.kratisai.controlplane.client;

public interface PostHogClient {

    void capture(PostHogCaptureRequest request);
}
