package com.kratisai.controlplane.git.credential;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class GitAuthMaterialTest {

    @Test
    void ofToken_shouldCreateTokenOnly() {
        GitAuthMaterial material = GitAuthMaterial.ofToken("secret");
        assertThat(material.maybeToken()).hasValue("secret");
        assertThat(material.maybeSshKey()).isEmpty();
    }

    @Test
    void ofSshKey_shouldCreateSshKeyOnly() {
        GitAuthMaterial material = GitAuthMaterial.ofSshKey("pem");
        assertThat(material.maybeSshKey()).hasValue("pem");
        assertThat(material.maybeToken()).isEmpty();
    }

    @Test
    void none_shouldHaveNeither() {
        GitAuthMaterial material = GitAuthMaterial.none();
        assertThat(material.maybeToken()).isEmpty();
        assertThat(material.maybeSshKey()).isEmpty();
    }

    @Test
    void bothTokenAndSshKey_shouldThrow() {
        assertThatThrownBy(() -> new GitAuthMaterial("token", "key")).isInstanceOf(IllegalArgumentException.class);
    }
}
