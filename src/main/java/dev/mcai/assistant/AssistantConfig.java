package dev.mcai.assistant;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.ZoneId;

final class AssistantConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    String protocol = "openai_responses";
    String baseUrl = "https://openrouter.ai/api/v1";
    String primaryModel = "deepseek/deepseek-v4-flash-0731:floor";
    String fallbackModel = "inclusionai/ling-3.0-flash-fin:free";
    String secretFile = "";
    String timezone = "Asia/Shanghai";
    boolean enabled = true;
    boolean fullConversationLog = false;
    int playerCooldownSeconds = 8;
    int maxConcurrentRequests = 2;
    int inputMaxCharacters = 300;
    int outputMaxTokens = 1200;
    int requestTimeoutSeconds = 60;
    int dailyRequestLimit = 100;
    int historyTurns = 6;
    int historyIdleMinutes = 30;
    String searchEngine = "firecrawl";
    int searchMaxResults = 3;
    String firecrawlBaseUrl = "https://api.firecrawl.dev/v2";
    String firecrawlSecretFile = "";
    String wikiApiUrl = "https://minecraft.wiki/api.php";
    boolean wikiFirecrawlFallback = false;
    int logRetentionDays = 30;

    static AssistantConfig load(Path path) throws IOException {
        if (Files.notExists(path)) {
            AssistantConfig defaults = new AssistantConfig();
            defaults.save(path, true);
            return defaults;
        }

        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            AssistantConfig config = GSON.fromJson(reader, AssistantConfig.class);
            if (config == null) {
                throw new IOException("Configuration is empty");
            }
            config.validate();
            return config;
        } catch (RuntimeException exception) {
            throw new IOException("Invalid configuration: " + exception.getMessage(), exception);
        }
    }

    void save(Path path) throws IOException {
        save(path, false);
    }

    private void save(Path path, boolean createNew) throws IOException {
        validate();
        Files.createDirectories(path.getParent());
        if (createNew) {
            Files.writeString(path, GSON.toJson(this) + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } else {
            Files.writeString(path, GSON.toJson(this) + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        }
    }

    private void validate() {
        if (!protocol.equals("openai_responses") && !protocol.equals("anthropic_messages")) {
            throw new IllegalArgumentException("protocol must be openai_responses or anthropic_messages");
        }
        URI uri = URI.create(baseUrl);
        boolean secure = "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null;
        boolean localMock = "http".equalsIgnoreCase(uri.getScheme()) && isLoopbackName(uri.getHost());
        if (!secure && !localMock) {
            throw new IllegalArgumentException("baseUrl must use HTTPS (HTTP is allowed only for a local mock server)");
        }
        baseUrl = baseUrl.replaceAll("/+$", "");
        requireText(primaryModel, "primaryModel");
        requireText(fallbackModel, "fallbackModel");
        ZoneId.of(timezone);
        requireRange(playerCooldownSeconds, 0, 3600, "playerCooldownSeconds");
        requireRange(maxConcurrentRequests, 1, 64, "maxConcurrentRequests");
        requireRange(inputMaxCharacters, 1, 10_000, "inputMaxCharacters");
        requireRange(outputMaxTokens, 1, 16_384, "outputMaxTokens");
        requireRange(requestTimeoutSeconds, 1, 300, "requestTimeoutSeconds");
        requireRange(dailyRequestLimit, 1, 1_000_000, "dailyRequestLimit");
        requireRange(historyTurns, 0, 100, "historyTurns");
        requireRange(historyIdleMinutes, 1, 10_080, "historyIdleMinutes");
        requireText(searchEngine, "searchEngine");
        if (!searchEngine.equals("firecrawl")) {
            throw new IllegalArgumentException("searchEngine must be firecrawl for direct search");
        }
        requireRange(searchMaxResults, 1, 25, "searchMaxResults");
        URI firecrawlUri = URI.create(firecrawlBaseUrl);
        boolean secureFirecrawl = "https".equalsIgnoreCase(firecrawlUri.getScheme())
                && firecrawlUri.getHost() != null;
        boolean localFirecrawl = "http".equalsIgnoreCase(firecrawlUri.getScheme())
                && isLoopbackName(firecrawlUri.getHost());
        if (!secureFirecrawl && !localFirecrawl) {
            throw new IllegalArgumentException(
                    "firecrawlBaseUrl must use HTTPS (HTTP is allowed only for a local mock server)");
        }
        firecrawlBaseUrl = firecrawlBaseUrl.replaceAll("/+$", "");
        URI wikiUri = URI.create(wikiApiUrl);
        boolean officialWiki = "https".equalsIgnoreCase(wikiUri.getScheme())
                && "minecraft.wiki".equalsIgnoreCase(wikiUri.getHost())
                && "/api.php".equals(wikiUri.getPath());
        boolean localWiki = "http".equalsIgnoreCase(wikiUri.getScheme()) && isLoopbackName(wikiUri.getHost());
        if ((!officialWiki && !localWiki) || wikiUri.getUserInfo() != null
                || wikiUri.getQuery() != null || wikiUri.getFragment() != null) {
            throw new IllegalArgumentException("wikiApiUrl must be the official English Minecraft Wiki API or a loopback mock");
        }
        requireRange(logRetentionDays, 1, 3650, "logRetentionDays");
    }

    String readApiKey(Path configDirectory) throws IOException {
        String environmentFile = System.getenv("MC_AI_ASSISTANT_SECRET_FILE");
        String configuredFile = environmentFile == null || environmentFile.isBlank() ? secretFile : environmentFile;
        if (configuredFile != null && !configuredFile.isBlank()) {
            Path path = Path.of(configuredFile);
            if (!path.isAbsolute()) {
                path = configDirectory.resolve(path).normalize();
            }
            return normalizeApiKey(Files.readString(path, StandardCharsets.UTF_8), "Model provider");
        }

        String environmentKey = System.getenv("OPENROUTER_API_KEY");
        return normalizeApiKey(environmentKey, "Model provider");
    }

    String readFirecrawlApiKey(Path configDirectory) throws IOException {
        String environmentFile = System.getenv("MC_AI_ASSISTANT_FIRECRAWL_SECRET_FILE");
        String configuredFile = environmentFile == null || environmentFile.isBlank()
                ? firecrawlSecretFile : environmentFile;
        if (configuredFile != null && !configuredFile.isBlank()) {
            Path path = Path.of(configuredFile);
            if (!path.isAbsolute()) {
                path = configDirectory.resolve(path).normalize();
            }
            return normalizeApiKey(Files.readString(path, StandardCharsets.UTF_8), "Firecrawl");
        }
        return normalizeApiKey(System.getenv("FIRECRAWL_API_KEY"), "Firecrawl");
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static void requireRange(int value, int minimum, int maximum, String name) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(name + " must be between " + minimum + " and " + maximum);
        }
    }

    private static boolean isLoopbackName(String host) {
        return host != null && (host.equalsIgnoreCase("localhost") || host.equals("127.0.0.1")
                || host.equals("::1") || host.equals("[::1]"));
    }

    private static String normalizeApiKey(String value, String service) throws IOException {
        if (value == null || value.isBlank()) {
            return "";
        }
        String key = value.strip();
        if (key.chars().anyMatch(Character::isWhitespace)) {
            throw new IOException(service + " API key must be a single token without whitespace");
        }
        return key;
    }
}
