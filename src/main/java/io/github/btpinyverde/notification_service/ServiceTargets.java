package io.github.btpinyverde.notification_service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

@Component
class ServiceTargets {
    private final Set<String> allowed;

    ServiceTargets(@Value("${notifications.service-targets}") String targets) {
        allowed = Arrays.stream(targets.split(",")).map(String::strip).filter(url -> !url.isEmpty())
                .peek(ServiceTargets::validateConfiguredUrl).collect(Collectors.toUnmodifiableSet());
    }

    boolean allows(String url) {
        return url != null && allowed.contains(url);
    }

    private static void validateConfiguredUrl(String url) {
        URI uri = URI.create(url);
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("Cada destino SERVICE debe ser una URL HTTP(S) válida");
        }
    }
}
