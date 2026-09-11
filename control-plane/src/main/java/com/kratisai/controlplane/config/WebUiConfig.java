package com.kratisai.controlplane.config;

import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Forwards client-side SPA routes to the embedded shell.
 *
 * <p>The production image copies the Vite build into {@code static/}, so a browser refresh
 * or deep link on a route such as {@code /settings} or {@code /repos/1} must return
 * {@code index.html} instead of 404. Keep this list in sync with the route tree in
 * {@code web/src/router.tsx}.
 */
@Configuration
public class WebUiConfig implements WebMvcConfigurer {

    static final List<String> SPA_ROUTES = List.of(
            "/ask",
            "/usage",
            "/repos",
            "/repos/*",
            "/chats/*",
            "/chats/*/canvas",
            "/chats/*/canvas/*",
            "/chats/*/executions/*",
            "/settings",
            "/wiki/*",
            "/wiki/*/*");

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        SPA_ROUTES.forEach(route -> registry.addViewController(route).setViewName("forward:/index.html"));
    }
}
