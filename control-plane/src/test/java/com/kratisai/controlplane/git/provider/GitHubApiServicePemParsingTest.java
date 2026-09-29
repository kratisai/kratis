package com.kratisai.controlplane.git.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.kratisai.controlplane.SpringIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.PrivateKey;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Tests for PEM key parsing and JWT generation in GitHubApiService.
 *
 * <p>Uses @SpringIntegrationTest (rather than a standalone @SpringBootTest + @TestPropertySource)
 * so this class shares the main test Spring context and avoids a redundant Liquibase cycle.
 * The required GitHub stub properties (app-id, app-name, private-key-path) are already present
 * in src/test/resources/application.properties.
 */
@SpringIntegrationTest
class GitHubApiServicePemParsingTest {

    @Autowired
    private GitHubApiService gitHubApiService;

    @Test
    void loadPrivateKey_pkcs8Format_shouldSucceed() throws Exception {
        Path keyPath = Path.of("src/test/resources/test-keys/pkcs8.pem");
        PrivateKey key = invokeLoadPrivateKey(keyPath);

        assertThat(key).isNotNull();
        assertThat(key.getAlgorithm()).isEqualTo("RSA");
        assertThat(key.getFormat()).isEqualTo("PKCS#8");
    }

    @Test
    void loadPrivateKey_pkcs1TraditionalFormat_shouldSucceed() throws Exception {
        Path keyPath = Path.of("src/test/resources/test-keys/pkcs1-traditional.pem");
        PrivateKey key = invokeLoadPrivateKey(keyPath);

        assertThat(key).isNotNull();
        assertThat(key.getAlgorithm()).isEqualTo("RSA");
    }

    @Test
    void generateJwt_withPkcs8Key_shouldSucceed() throws Exception {
        Path keyPath = Path.of("src/test/resources/test-keys/pkcs8.pem");
        String jwt = gitHubApiService.generateJwt("123456", keyPath);

        assertThat(jwt).isNotNull();
        assertThat(jwt.split("\\.")).hasSize(3);
        String header =
                new String(java.util.Base64.getUrlDecoder().decode(jwt.split("\\.")[0]), StandardCharsets.UTF_8);
        assertThat(header).contains("\"alg\":\"RS256\"");
    }

    @Test
    void generateJwt_withPkcs1Key_shouldSucceed() throws Exception {
        Path keyPath = Path.of("src/test/resources/test-keys/pkcs1-traditional.pem");
        String jwt = gitHubApiService.generateJwt("123456", keyPath);

        assertThat(jwt).isNotNull();
        assertThat(jwt.split("\\.")).hasSize(3);
        String header =
                new String(java.util.Base64.getUrlDecoder().decode(jwt.split("\\.")[0]), StandardCharsets.UTF_8);
        assertThat(header).contains("\"alg\":\"RS256\"");
    }

    /** Uses reflection to access the private loadPrivateKey method for testing. */
    private PrivateKey invokeLoadPrivateKey(Path keyPath) throws Exception {
        java.lang.reflect.Method method = GitHubApiService.class.getDeclaredMethod("loadPrivateKey", Path.class);
        method.setAccessible(true);
        return (PrivateKey) method.invoke(gitHubApiService, keyPath);
    }
}
