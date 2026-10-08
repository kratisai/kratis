package com.kratisai.controlplane.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * External parser binaries used during ingestion. Paths point inside the control-plane runtime
 * (the deploy image stages the binaries on the PATH); they have no sensible defaults because a
 * missing binary surfaces as a failed parse rather than a recoverable condition.
 */
@Component
@ConfigurationProperties(prefix = "kratis.parser")
public class ParserProperties {

    private String binaryPath;

    /** Directory the parser writes its per-repository SQLite database to. */
    private String tempDir;

    /** scc (command-line code counter) binary used by wiki generation. */
    private String sccBinaryPath;

    public String getBinaryPath() {
        return binaryPath;
    }

    public void setBinaryPath(String binaryPath) {
        this.binaryPath = binaryPath;
    }

    public String getTempDir() {
        return tempDir;
    }

    public void setTempDir(String tempDir) {
        this.tempDir = tempDir;
    }

    public String getSccBinaryPath() {
        return sccBinaryPath;
    }

    public void setSccBinaryPath(String sccBinaryPath) {
        this.sccBinaryPath = sccBinaryPath;
    }
}
