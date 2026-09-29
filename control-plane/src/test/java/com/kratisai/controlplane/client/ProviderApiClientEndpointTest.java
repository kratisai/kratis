package com.kratisai.controlplane.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;
import org.springframework.web.util.DefaultUriBuilderFactory;

/**
 * Verifies the declarative endpoint templates of the provider HTTP interfaces, including the path
 * encoding relied on for GitLab project paths and Azure project/repository names.
 */
class ProviderApiClientEndpointTest {

    private <T> T createClient(Class<T> type, RestClient.Builder builder) {
        RestClient restClient = builder.build();
        RestClientAdapter adapter = RestClientAdapter.create(restClient);
        return HttpServiceProxyFactory.builderFor(adapter).build().createClient(type);
    }

    @Test
    void gitHubEndpointsUseDeclarativePathsAndQueryParams() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.github.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitHubApiClient client = createClient(GitHubApiClient.class, builder);

        server.expect(requestTo("https://api.github.com/repos/kratis-ai/kratis/branches?per_page=1"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer tok"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.github.com/user/repos"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThat(client.getBranches("kratis-ai", "kratis", 1, "Bearer tok")
                        .getStatusCode()
                        .is2xxSuccessful())
                .isTrue();
        client.createUserRepository(Map.of("name", "repo"), "Bearer tok");
        server.verify();
    }

    @Test
    void gitLabEndpointsEncodeProjectAndFilePathAndUseDynamicBase() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitLabApiClient client = createClient(GitLabApiClient.class, builder);

        server.expect(
                        requestTo(
                                "https://gitlab.com/api/v4/projects/group%2Fsub%2Frepo/repository/files/src%2FMy%20File.java/raw?ref=HEAD"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("PRIVATE-TOKEN", "tok"))
                .andRespond(withSuccess("content", MediaType.TEXT_PLAIN));

        client.getRawFile(
                new DefaultUriBuilderFactory("https://gitlab.com"),
                "group/sub/repo",
                "src/My File.java",
                "HEAD",
                "tok");
        server.verify();
    }

    @Test
    void azureEndpointsEncodeProjectAndPreserveCollectionBasePath() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AzureDevOpsApiClient client = createClient(AzureDevOpsApiClient.class, builder);

        server.expect(
                        requestTo(
                                "https://azure.mycompany.com/tfs/DefaultCollection/my%20project/_apis/git/repositories/my%20repo?api-version=7.1"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        client.getRepository(
                new DefaultUriBuilderFactory("https://azure.mycompany.com/tfs/DefaultCollection"),
                "my project",
                "my repo",
                "7.1",
                "Basic tok");
        server.verify();
    }

    @Test
    void bitbucketEndpointsUseDeclarativePathsAndQueryParams() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.bitbucket.org/2.0");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        BitbucketApiClient client = createClient(BitbucketApiClient.class, builder);

        server.expect(requestTo("https://api.bitbucket.org/2.0/repositories/myteam/my%20repo/refs/branches?pagelen=1"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"values\": []}", MediaType.APPLICATION_JSON));

        client.getBranches("myteam", "my repo", 1, "Bearer tok");
        server.verify();
    }
}
