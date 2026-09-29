package dev.mcai.assistant;

import java.net.URI;
import java.util.List;
import java.util.Objects;

final class SourceUrls {
    private SourceUrls() {
    }

    static URI parse(String value) {
        if (value == null) {
            return null;
        }
        try {
            URI uri = URI.create(value);
            return ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null && uri.getUserInfo() == null ? uri : null;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    static List<URI> validated(List<String> values) {
        return values.stream().map(SourceUrls::parse).filter(Objects::nonNull).distinct().toList();
    }
}
