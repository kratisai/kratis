package com.kratisai.controlplane.config;

import com.kratisai.controlplane.model.AgentHarness;
import org.springframework.stereotype.Component;

@Component
public class HarnessCatalogInitializer {

    public HarnessCatalogInitializer(HarnessesProperties properties) {
        AgentHarness.overlayDirectory(properties.getDirectory());
    }
}
