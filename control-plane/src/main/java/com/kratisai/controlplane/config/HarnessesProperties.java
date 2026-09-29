package com.kratisai.controlplane.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "kratis.harnesses")
public class HarnessesProperties {

    /** Filesystem directory of extra or override {@code FILENAME.json} harnesses. Empty uses classpath only. */
    private String directory = "";

    public String getDirectory() {
        return directory;
    }

    public void setDirectory(String directory) {
        this.directory = directory;
    }
}
