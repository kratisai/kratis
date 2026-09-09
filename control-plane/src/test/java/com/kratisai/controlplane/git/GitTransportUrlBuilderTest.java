package com.kratisai.controlplane.git;

import static org.assertj.core.api.Assertions.*;

import com.kratisai.controlplane.git.credential.GitAuthMaterial;
import com.kratisai.controlplane.model.RepositoryType;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.junit.jupiter.api.Test;

class GitTransportUrlBuilderTest {

    private final GitTransportUrlBuilder builder = new GitTransportUrlBuilder();

    @Test
    void build_urlWithToken_shouldInjectCorrectUsername() {
        String url = builder.buildAuthenticatedCloneUrl(
                "https://github.com/user/repo.git", RepositoryType.GITHUB, GitAuthMaterial.ofToken("mytoken"));
        assertThat(url).isEqualTo("https://oauth2:mytoken@github.com/user/repo.git");
    }

    @Test
    void build_gitlabUrlWithToken_shouldInjectOauth2() {
        String url = builder.buildAuthenticatedCloneUrl(
                "https://gitlab.com/user/repo.git", RepositoryType.GITLAB, GitAuthMaterial.ofToken("mytoken"));
        assertThat(url).isEqualTo("https://oauth2:mytoken@gitlab.com/user/repo.git");
    }

    @Test
    void build_bitbucketUrlWithToken_shouldInjectXTokenAuth() {
        String url = builder.buildAuthenticatedCloneUrl(
                "https://bitbucket.org/user/repo.git", RepositoryType.BITBUCKET, GitAuthMaterial.ofToken("mytoken"));
        assertThat(url).isEqualTo("https://x-token-auth:mytoken@bitbucket.org/user/repo.git");
    }

    @Test
    void build_azureUrlWithToken_shouldUseTokenAsUsername() {
        String url = builder.buildAuthenticatedCloneUrl(
                "https://dev.azure.com/org/project/_git/repo",
                RepositoryType.AZURE,
                GitAuthMaterial.ofToken("mytoken"));
        assertThat(url).isEqualTo("https://mytoken@dev.azure.com/org/project/_git/repo");
    }

    @Test
    void build_sshUrl_shouldRemainUnchanged() {
        String url = builder.buildAuthenticatedCloneUrl(
                "git@github.com:user/repo.git", RepositoryType.GITHUB, GitAuthMaterial.ofSshKey("pem"));
        assertThat(url).isEqualTo("git@github.com:user/repo.git");
    }

    @Test
    void build_nullOrEmptyToken_shouldReturnRawUrl() {
        String url = builder.buildAuthenticatedCloneUrl(
                "https://github.com/user/repo.git", RepositoryType.GITHUB, GitAuthMaterial.none());
        assertThat(url).isEqualTo("https://github.com/user/repo.git");
    }

    @Test
    void build_nullAuth_shouldReturnRawUrl() {
        String url =
                builder.buildAuthenticatedCloneUrl("https://github.com/user/repo.git", RepositoryType.GITHUB, null);
        assertThat(url).isEqualTo("https://github.com/user/repo.git");
    }

    @Test
    void build_nullRawUrl_shouldThrow() {
        // Passing null deliberately to verify the builder rejects a null raw URL.
        assertThatThrownBy(this::buildWithNullUrl).isInstanceOf(NullPointerException.class);
    }

    @SuppressFBWarnings({"NP_NULL_PARAM_DEREF", "NP_NULL_PARAM_DEREF_ALL_TARGETS_DANGEROUS"})
    private void buildWithNullUrl() {
        builder.buildAuthenticatedCloneUrl(null, RepositoryType.GITHUB, GitAuthMaterial.ofToken("x"));
    }

    @Test
    void build_tokenWithSpecialChars_shouldEncode() {
        String url = builder.buildAuthenticatedCloneUrl(
                "https://github.com/user/repo.git", RepositoryType.GITHUB, GitAuthMaterial.ofToken("my/token+secret="));
        assertThat(url).contains("oauth2:my%2Ftoken%2Bsecret%3D@github.com/user/repo.git");
    }

    @Test
    void build_nonHttpUrl_shouldReturnRawUrl() {
        String url = builder.buildAuthenticatedCloneUrl(
                "git@github.com:user/repo.git", RepositoryType.GITHUB, GitAuthMaterial.ofToken("mytoken"));
        assertThat(url).isEqualTo("git@github.com:user/repo.git");
    }

    @Test
    void build_urlWithExistingUserInfo_shouldThrow() {
        assertThatThrownBy(() -> builder.buildAuthenticatedCloneUrl(
                        "https://user:pass@github.com/repo.git", RepositoryType.GITHUB, GitAuthMaterial.ofToken("x")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void build_httpUrlWithToken_shouldInjectUsername() {
        String url = builder.buildAuthenticatedCloneUrl(
                "http://github.com/user/repo.git", RepositoryType.GITHUB, GitAuthMaterial.ofToken("mytoken"));
        assertThat(url).isEqualTo("http://oauth2:mytoken@github.com/user/repo.git");
    }

    @Test
    void build_tokenWithWhitespace_shouldBeTrimmed() {
        String url = builder.buildAuthenticatedCloneUrl(
                "https://github.com/user/repo.git", RepositoryType.GITHUB, GitAuthMaterial.ofToken("  mytoken  "));
        assertThat(url).isEqualTo("https://oauth2:mytoken@github.com/user/repo.git");
    }

    @Test
    void build_urlWithPort_shouldPreservePort() {
        String url = builder.buildAuthenticatedCloneUrl(
                "https://github.com:8443/user/repo.git", RepositoryType.GITHUB, GitAuthMaterial.ofToken("mytoken"));
        assertThat(url).isEqualTo("https://oauth2:mytoken@github.com:8443/user/repo.git");
    }
}
