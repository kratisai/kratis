package com.kratisai.controlplane.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "kratis_installation")
public class KratisInstallation {

    public static final String SINGLETON_KEY = "singleton";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "singleton_key", nullable = false, unique = true, length = 32)
    private String singletonKey = SINGLETON_KEY;

    @Column(name = "install_id", nullable = false)
    private UUID installId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public KratisInstallation() {}

    public KratisInstallation(UUID installId) {
        this.installId = installId;
        this.createdAt = Instant.now();
    }

    public String getSingletonKey() {
        return singletonKey;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getInstallId() {
        return installId;
    }

    public void setInstallId(UUID installId) {
        this.installId = installId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
