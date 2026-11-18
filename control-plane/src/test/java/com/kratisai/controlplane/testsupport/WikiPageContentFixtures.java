package com.kratisai.controlplane.testsupport;

public final class WikiPageContentFixtures {

    public static final String WIKI_PAGE_BODY = "The user management domain handles accounts, roles, and access for"
            + " the whole team. Users sign in with JSON Web Tokens, and the service validates each request before it"
            + " reaches a protected route. Administrators can create users, disable accounts, reset passwords, and"
            + " review audit records. The domain also stores session state, expires idle sessions, and publishes"
            + " account changes to downstream services. Every operation returns a consistent error model so the portal"
            + " can show clear messages to the operator. The API also exposes health and readiness endpoints for the"
            + " platform team. The portal uses this domain for sign-in, password changes, and account maintenance. It"
            + " also provides admin tools for support staff who must resolve access problems quickly. All changes are"
            + " written to an audit trail, and the metrics endpoint reports latency and error counts for each protected"
            + " operation. These controls keep the service predictable for users and operators.";

    private WikiPageContentFixtures() {}
}
