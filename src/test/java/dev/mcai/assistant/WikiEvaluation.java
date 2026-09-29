package dev.mcai.assistant;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Opt-in, public-API-only retrieval evaluation. Never reads model credentials or invokes a model. */
public final class WikiEvaluation {
    private static final String API = "https://minecraft.wiki/api.php";
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    record Example(String id, String category, String question, String context,
                   WikiQuery query, String expectedTitle, List<String> expectedSections,
                   long verifiedRevision, List<String> evidenceTerms, String expectedBehavior,
                   String expectationNote) { }
    record Dataset(String provenance, List<Example> examples) { }
    record TopicSnapshot(String topic, List<MediaWikiClient.Hit> candidates,
                         List<MediaWikiClient.Page> pages, long elapsedMillis,
                         int searchAttempts, int fetchAttempts, String failure) { }
    record Snapshot(String api, String capturedAt, String attribution, String licenseUrl,
                    List<TopicSnapshot> topics) { }
    record Candidate(long pageId, String title, String resolvedAlias, long revision,
                     String source, int extractedCodePoints, List<String> availableSections) { }
    record Selected(String title, long revision, String source, String heading, int score, String body) { }
    record RankingResult(boolean candidatePageHit, boolean canonicalTopicAvailable,
                         boolean expectedSectionSelected, boolean expectedEvidenceSelected,
                         boolean correctFilterRejection, boolean confirmedExtractGap, String outcome,
                         int examinedChunks, Map<WikiCache.Filter, Integer> exclusions,
                         List<Selected> selected) { }
    record CaseResult(Example example, String sourceVerification, List<Candidate> candidates,
                      RankingResult beforeWithoutAliases, RankingResult afterVerifiedAliases,
                      long rankingMillis, long capturedTopicMillis, int capturedTopicAttempts,
                      int outboundAttemptsThisCase, String answerEvaluation) { }

    private WikiEvaluation() { }

    public static void main(String[] args) throws Exception {
        if (args.length == 0 || !(args[0].equals("--live") || args[0].equals("--replay"))) {
            throw new IllegalArgumentException("Use --live (public Wiki only) or --replay (frozen build snapshot).");
        }
        boolean live = args[0].equals("--live");
        Path output = Path.of(args.length > 1 ? args[1] : "build/wiki-evaluation").toAbsolutePath().normalize();
        Files.createDirectories(output);
        Dataset dataset;
        try (var stream = WikiEvaluation.class.getResourceAsStream("/wiki-evaluation.json")) {
            if (stream == null) {
                throw new IOException("Missing curated evaluation dataset");
            }
            dataset = JSON.fromJson(new InputStreamReader(stream, StandardCharsets.UTF_8), Dataset.class);
        }
        if (dataset == null || dataset.examples() == null || dataset.examples().size() < 15) {
            throw new IllegalArgumentException("At least 15 curated cases required");
        }
        Path snapshotFile = output.resolve("snapshot.json");
        Snapshot snapshot = live ? capture(dataset) : JSON.fromJson(Files.readString(snapshotFile), Snapshot.class);
        if (!API.equals(snapshot.api())) {
            throw new IllegalArgumentException("Snapshot is not from the configured public Wiki");
        }
        if (live) {
            Files.writeString(snapshotFile, JSON.toJson(snapshot), StandardCharsets.UTF_8);
        }
        Map<String, TopicSnapshot> topics = new LinkedHashMap<>();
        snapshot.topics().forEach(topic -> topics.put(topic.topic(), topic));
        List<CaseResult> results = new ArrayList<>();
        var chargedTopics = new java.util.HashSet<String>();
        for (Example example : dataset.examples()) {
            TopicSnapshot topic = topics.get(example.query().topic());
            if (topic == null) {
                throw new IllegalArgumentException("No frozen snapshot for " + example.query().topic());
            }
            long started = System.nanoTime();
            List<MediaWikiClient.Page> withoutAliases = topic.pages().stream().map(page ->
                    new MediaWikiClient.Page(page.pageId(), page.title(), page.url(), page.revisionId(),
                            page.fetchedAt(), page.text())).toList();
            RankingResult before = rank(output, example, topic, withoutAliases, "before");
            RankingResult after = rank(output, example, topic, topic.pages(), "after");
            List<Candidate> candidates = topic.candidates().stream().map(hit -> {
                var page = topic.pages().stream().filter(item -> item.pageId() == hit.pageId()).findFirst().orElse(null);
                return new Candidate(hit.pageId(), hit.title(), hit.resolvedAlias(), page == null ? 0 : page.revisionId(),
                        page == null ? "" : page.permalink().toString(), page == null ? 0
                        : page.text().codePointCount(0, page.text().length()), page == null ? List.of()
                        : WikiCache.chunks(page).stream().map(WikiCache.Chunk::heading).distinct().toList());
            }).toList();
            var expected = topic.pages().stream().filter(page -> sameTitle(page.title(), example.expectedTitle()))
                    .findFirst().orElse(null);
            String verification = example.verifiedRevision() == 0 ? "PENDING_MANUAL_REVISION_REVIEW"
                    : expected == null ? "EXPECTED_PAGE_NOT_FETCHED"
                    : expected.revisionId() == example.verifiedRevision() ? "MATCHES_MANUALLY_REVIEWED_REVISION"
                    : "REVISION_CHANGED_REVIEW_REQUIRED";
            int attempts = topic.searchAttempts() + topic.fetchAttempts();
            results.add(new CaseResult(example, verification, candidates, before, after,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), topic.elapsedMillis(), attempts,
                    live && chargedTopics.add(topic.topic()) ? attempts : 0,
                    "NOT_RUN: no model rewrite, generated answer, recipe refusal, or in-game interaction evaluated"));
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("mode", live ? "LIVE_PUBLIC_API" : "FROZEN_OFFLINE_REPLAY");
        report.put("createdAt", Instant.now().toString());
        report.put("snapshotCapturedAt", snapshot.capturedAt());
        report.put("datasetProvenance", dataset.provenance());
        report.put("comparisonScope", "Same current search candidates and frozen extracts; only API-confirmed aliases are removed in before. This is not a replay of historical search or model rewriting.");
        report.put("limits", "At most one search and three separate page fetches per distinct topic; repeated topics and page IDs reused; no model requests, no credentials, no retries.");
        report.put("outboundAttemptsThisRun", results.stream().mapToInt(CaseResult::outboundAttemptsThisCase).sum());
        report.put("capturedOutboundAttempts", snapshot.topics().stream()
                .mapToInt(topic -> topic.searchAttempts() + topic.fetchAttempts()).sum());
        report.put("before", summarize(results, false));
        report.put("after", summarize(results, true));
        report.put("cases", results);
        Files.writeString(output.resolve("results.json"), JSON.toJson(report), StandardCharsets.UTF_8);
        System.out.println("Wiki evaluation: " + results.size() + " cases, " + snapshot.topics().size()
                + " topics; actual outbound attempts=" + report.get("outboundAttemptsThisRun"));
        System.out.println("Before=" + JSON.toJson(report.get("before")));
        System.out.println("After=" + JSON.toJson(report.get("after")));
        System.out.println("Evidence: " + output.resolve("results.json"));
    }

    private static Snapshot capture(Dataset dataset) {
        List<TopicSnapshot> snapshots = new ArrayList<>();
        Map<Long, MediaWikiClient.Page> fetched = new LinkedHashMap<>();
        List<String> topics = dataset.examples().stream().map(example -> example.query().topic()).distinct().toList();
        try (MediaWikiClient client = new MediaWikiClient()) {
            for (String topic : topics) {
                long started = System.nanoTime();
                int fetchAttempts = 0;
                List<MediaWikiClient.Hit> candidates = List.of();
                List<MediaWikiClient.Page> pages = new ArrayList<>();
                String failure = "";
                try {
                    candidates = client.search(API, topic, TIMEOUT).get(25, TimeUnit.SECONDS);
                    for (var candidate : candidates) {
                        MediaWikiClient.Page page = fetched.get(candidate.pageId());
                        if (page == null) {
                            fetchAttempts++;
                            page = client.fetch(API, candidate.pageId(), System.currentTimeMillis(), TIMEOUT)
                                    .get(25, TimeUnit.SECONDS);
                            if (page != null) {
                                fetched.put(page.pageId(), page);
                            }
                        }
                        if (page != null) {
                            pages.add(page.withAlias(candidate.resolvedAlias()));
                        }
                    }
                } catch (Exception exception) {
                    // Preserve completed evidence and attempt accounting, without retrying or invoking a model.
                    Throwable cause = exception.getCause() == null ? exception : exception.getCause();
                    failure = cause.getClass().getSimpleName();
                    if (cause instanceof OpenRouterClient.ApiException apiFailure) {
                        failure += ":" + apiFailure.kind();
                    }
                }
                snapshots.add(new TopicSnapshot(topic, candidates, List.copyOf(pages),
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), 1, fetchAttempts, failure));
                System.out.println("Captured " + topic + ": candidates=" + candidates.size() + ", pages="
                        + pages.size() + ", attempts=" + (1 + fetchAttempts) + ", failure=" + failure);
            }
        }
        return new Snapshot(API, Instant.now().toString(), WikiCache.ATTRIBUTION, WikiCache.LICENSE_URL,
                List.copyOf(snapshots));
    }

    private static RankingResult rank(Path output, Example example, TopicSnapshot topic,
                                      List<MediaWikiClient.Page> pages, String variant) throws IOException {
        long fixedNow = pages.stream().mapToLong(MediaWikiClient.Page::fetchedAt).max().orElse(0);
        // Unique files for this run avoid importing state from previous cases. Frozen time permits TTL-neutral replay.
        Path directory = Files.createTempDirectory(output, example.id() + "-" + variant + "-");
        WikiCache cache = new WikiCache(directory.resolve("cache.json"), API,
                Clock.fixed(Instant.ofEpochMilli(fixedNow), ZoneOffset.UTC));
        cache.putAll(pages);
        WikiCache.Ranking ranking = cache.rank(example.query());
        boolean candidateHit = topic.candidates().stream().anyMatch(hit -> sameTitle(hit.title(), example.expectedTitle()));
        boolean section = ranking.chunks().stream().anyMatch(chunk -> sameTitle(chunk.page().title(), example.expectedTitle())
                && expectedSection(example, chunk));
        boolean evidence = ranking.chunks().stream().anyMatch(chunk -> sameTitle(chunk.page().title(), example.expectedTitle())
                && expectedSection(example, chunk) && example.evidenceTerms().stream().allMatch(term ->
                        chunk.body().toLowerCase(Locale.ROOT).contains(term.toLowerCase(Locale.ROOT))));
        boolean expectedFilter = example.expectedBehavior().equals("EDITION_FILTERED")
                || example.expectedBehavior().equals("VERSION_FILTERED");
        String outcome = !topic.failure().isBlank() ? "CAPTURE_FAILURE:" + topic.failure()
                : topic.candidates().isEmpty() ? "SEARCH_EMPTY" : pages.isEmpty() ? "EXTRACT_EMPTY"
                : ranking.chunks().isEmpty() ? ranking.failureReason() : "CHUNKS_AVAILABLE";
        boolean correctRejection = expectedFilter && candidateHit && ranking.chunks().isEmpty()
                && ranking.excluded().containsKey(WikiCache.Filter.valueOf(example.expectedBehavior()));
        boolean confirmedExtractGap = example.expectedBehavior().startsWith("INSUFFICIENT_") && pages.stream()
                .filter(page -> sameTitle(page.title(), example.expectedTitle()) && example.verifiedRevision() > 0
                        && page.revisionId() == example.verifiedRevision())
                .anyMatch(page -> WikiCache.chunks(page).stream().noneMatch(chunk -> expectedSection(example, chunk)));
        return new RankingResult(candidateHit, cache.hasTopic(example.query()), section, evidence, correctRejection, confirmedExtractGap,
                outcome, ranking.examined(), ranking.excluded(), ranking.chunks().stream().map(chunk ->
                new Selected(chunk.page().title(), chunk.page().revisionId(), chunk.page().permalink().toString(),
                        chunk.heading(), chunk.score(), chunk.body())).toList());
    }

    private static boolean expectedSection(Example example, WikiCache.Chunk chunk) {
        return example.expectedSections().stream().anyMatch(section -> chunk.heading().equals(section)
                || chunk.heading().startsWith(section + " > "));
    }

    private static boolean sameTitle(String left, String right) {
        return WikiQuery.normalizeTitle(left).equals(WikiQuery.normalizeTitle(right));
    }

    private static Map<String, Object> summarize(List<CaseResult> results, boolean after) {
        var rankings = results.stream().map(result -> after ? result.afterVerifiedAliases() : result.beforeWithoutAliases()).toList();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("cases", results.size());
        summary.put("candidatePageHits", rankings.stream().filter(RankingResult::candidatePageHit).count());
        summary.put("expectedSectionHits", rankings.stream().filter(RankingResult::expectedSectionSelected).count());
        summary.put("expectedEvidenceHits", rankings.stream().filter(RankingResult::expectedEvidenceSelected).count());
        summary.put("correctFilterRejections", rankings.stream().filter(RankingResult::correctFilterRejection).count());
        summary.put("expectedFilterCases", results.stream().filter(result -> result.example().expectedBehavior().endsWith("_FILTERED")).count());
        summary.put("answerableProseCases", results.stream().filter(result -> result.example().expectedBehavior().equals("ANSWER_WITH_EVIDENCE")).count());
        summary.put("confirmedExtractGaps", rankings.stream().filter(RankingResult::confirmedExtractGap).count());
        summary.put("expectedExtractGapCases", results.stream().filter(result -> result.example().expectedBehavior().startsWith("INSUFFICIENT_")).count());
        summary.put("manuallyReviewedRevisionMatches", results.stream()
                .filter(result -> result.sourceVerification().equals("MATCHES_MANUALLY_REVIEWED_REVISION")).count());
        summary.put("modelAnswerAccuracy", "NOT_EVALUATED");
        summary.put("modelCorrectRefusalCount", "NOT_EVALUATED");
        return summary;
    }
}
