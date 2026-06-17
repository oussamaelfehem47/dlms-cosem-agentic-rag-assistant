package com.company.dlms.workflow;

import com.company.dlms.agent.RouterAgent;
import com.company.dlms.agent.dlms.DlmsInputNormalization;
import com.company.dlms.agent.dlms.DlmsInputNormalizer;
import com.company.dlms.agent.siconia.SiconiaInputNormalization;
import com.company.dlms.agent.siconia.SiconiaInputNormalizer;
import com.company.dlms.domain.CasualQueryClassifier;
import com.company.dlms.domain.DlmsIntent;
import com.company.dlms.domain.InputClass;
import com.company.dlms.domain.decoder.DlmsNormalizedKind;
import com.company.dlms.domain.orchestration.OrchestrationMode;
import com.company.dlms.domain.orchestration.StrategyCandidate;
import com.company.dlms.domain.orchestration.StrategyKey;
import com.company.dlms.domain.orchestration.StrategyMetadata;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class InputUnderstandingService {

    private static final Pattern FRAME_PATTERN = Pattern.compile("(?i)7E(?:[0-9A-F]{2}|[\\s:]){5,}7E");
    private static final Pattern OBIS_PATTERN = Pattern.compile("\\b\\d{1,3}(?:\\.\\d{1,3}){5}\\b");
    private static final Pattern DIRECT_ALARM_PATTERN = Pattern.compile("(?i)^0x[0-9a-f]{2,4}$");
    private static final Pattern HEX_TOKEN_PATTERN = Pattern.compile(
            "(?i)(?<![A-Z0-9])(?:0x)?(?:(?=[0-9A-F]*\\d)[0-9A-F]{2,}|(?:[0-9A-F]{2}(?:[\\s:][0-9A-F]{2})+))(?![A-Z0-9])"
    );
    private static final Pattern XML_MARKER_PATTERN = Pattern.compile("(?i)<\\??(?:xml|[a-z_][\\w:-]*)");
    private static final Pattern LOG_PATTERN = Pattern.compile("(?i)\\[[A-Z_]+]\\s+(TRACE|DEBUG|INFO|WARN|ERROR|CRITICAL|FATAL)\\b");
    private static final Pattern SECURITY_QUESTION_SIGNAL_PATTERN = Pattern.compile(
            "\\b(lls|hls|gmac|authentication|security\\s*suite|encryption|frame\\s*counter|replay|challenge|"
                    + "key\\s*agreement|suite\\s*[0-3]|ciphering|security\\s*policy|counter\\s*mismatch|"
                    + "association\\s*rejected|aare\\s*diagnostic|diagnostic\\s*\\d+)\\b",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern AGENTIC_EXPLANATION_PATTERN = Pattern.compile(
            "\\b(and\\s+explain|what\\s+does\\s+(this|that|it)\\s+do|why\\s+did\\s+(this|that|it)\\s+fail|"
                    + "why\\s+was\\s+(this|that|it)\\s+sent|what\\s+happens\\s+next|in\\s+context|"
                    + "which\\s+alarm\\s+matters\\s+most|what\\s+object\\s+(it\\s+)?returned|"
                    + "what\\s+object\\s+was\\s+returned|which\\s+object\\s+was\\s+returned)\\b",
            Pattern.CASE_INSENSITIVE
    );
    private static final double AMBIGUITY_DELTA = 0.08d;

    private final DlmsInputNormalizer dlmsInputNormalizer;
    private final SiconiaInputNormalizer siconiaInputNormalizer;
    private final RouterAgent routerAgent;
    private final FollowUpResolver followUpResolver;

    public InputUnderstandingService(
            DlmsInputNormalizer dlmsInputNormalizer,
            SiconiaInputNormalizer siconiaInputNormalizer,
            RouterAgent routerAgent,
            FollowUpResolver followUpResolver
    ) {
        this.dlmsInputNormalizer = dlmsInputNormalizer;
        this.siconiaInputNormalizer = siconiaInputNormalizer;
        this.routerAgent = routerAgent;
        this.followUpResolver = followUpResolver;
    }

    public InputUnderstanding understand(String rawInput, InputClass hintedInputClass) {
        String input = rawInput == null ? "" : rawInput.trim();
        InputClass sanitizedHint = sanitizeHint(hintedInputClass);

        if (input.isBlank()) {
            return new InputUnderstanding(
                    InputClass.QUERY,
                    DlmsIntent.UNKNOWN,
                    null,
                    null,
                    new StrategyMetadata(
                            StrategyKey.UNKNOWN,
                            StrategyKey.UNKNOWN.label(),
                            0.0,
                            false,
                            false,
                            List.of(),
                            List.of()
                    ),
                    OrchestrationMode.DETERMINISTIC_FAST_PATH
            );
        }

        if (CasualQueryClassifier.isCasualNonTechnicalQuery(input)) {
            StrategyCandidate candidate = new StrategyCandidate(
                    StrategyKey.CASUAL_CHAT,
                    StrategyKey.CASUAL_CHAT.label(),
                    0.99,
                    "Matched the casual/courtesy classifier before any tool routing.",
                    false,
                    false,
                    InputClass.QUERY.name(),
                    null,
                    null,
                    null,
                    List.of()
            );
            return new InputUnderstanding(
                    InputClass.QUERY,
                    DlmsIntent.UNKNOWN,
                    null,
                    null,
                    new StrategyMetadata(
                            StrategyKey.CASUAL_CHAT,
                            StrategyKey.CASUAL_CHAT.label(),
                            candidate.confidence(),
                            false,
                            false,
                            List.of(candidate),
                            List.of()
                    ),
                    OrchestrationMode.DETERMINISTIC_FAST_PATH
            );
        }

        if (shouldSkipCandidateInference(input)) {
            return directNaturalLanguageUnderstanding(input);
        }

        DlmsInputNormalization dlmsNormalization = dlmsInputNormalizer.normalize(input, sanitizedHint);
        SiconiaInputNormalization siconiaNormalization = siconiaInputNormalizer.normalize(input, sanitizedHint);

        if (matchesDirectAlarmPattern(input) && isAlarmNormalization(siconiaNormalization)) {
            return directAlarmUnderstanding(siconiaNormalization);
        }

        List<StrategyCandidate> candidates = new ArrayList<>();
        candidates.addAll(buildDlmsCandidates(input, dlmsNormalization));
        StrategyCandidate siconiaCandidate = buildSiconiaCandidate(siconiaNormalization);
        if (siconiaCandidate != null) {
            candidates.add(siconiaCandidate);
        }

        DlmsIntent semanticIntent = routerAgent.route(input, InputClass.QUERY, effectiveDlmsNormalization(dlmsNormalization)).intent();
        StrategyCandidate semanticCandidate = buildSemanticCandidate(semanticIntent);
        if (semanticCandidate != null
                && candidates.stream().noneMatch(existing -> existing.strategy() == semanticCandidate.strategy())) {
            candidates.add(semanticCandidate);
        }

        candidates.sort(Comparator.comparingDouble(StrategyCandidate::confidence).reversed());

        StrategyDecision decision = selectDecision(candidates);
        DlmsInputNormalization selectedDlms = isDlmsStrategy(decision.selectedStrategy()) ? effectiveDlmsNormalization(dlmsNormalization) : null;
        SiconiaInputNormalization selectedSiconia = isSiconiaStrategy(decision.selectedStrategy()) ? siconiaNormalization : null;

        return new InputUnderstanding(
                decision.inputClass(),
                decision.intent(),
                selectedDlms,
                selectedSiconia,
                decision.metadata(),
                selectOrchestrationMode(input, decision.selectedStrategy(), decision.metadata())
        );
    }

    private InputClass sanitizeHint(InputClass hintedInputClass) {
        if (hintedInputClass == null) {
            return InputClass.QUERY;
        }
        return switch (hintedInputClass) {
            case XML_TRACE, ALARM_CODE, LOG_BLOCK -> hintedInputClass;
            default -> InputClass.QUERY;
        };
    }

    private DlmsInputNormalization effectiveDlmsNormalization(DlmsInputNormalization normalization) {
        if (normalization == null || normalization.ambiguous()) {
            return null;
        }
        return normalization;
    }

    private boolean shouldSkipCandidateInference(String input) {
        return !containsHexPayloadToken(input)
                && !XML_MARKER_PATTERN.matcher(input).find()
                && !matchesDirectAlarmPattern(input)
                && !LOG_PATTERN.matcher(input).find();
    }

    private boolean containsHexPayloadToken(String input) {
        return HEX_TOKEN_PATTERN.matcher(input).find();
    }

    private boolean matchesDirectAlarmPattern(String input) {
        return DIRECT_ALARM_PATTERN.matcher(input).matches();
    }

    private boolean isSecurityQuestion(String input) {
        return SECURITY_QUESTION_SIGNAL_PATTERN.matcher(input).find();
    }

    private boolean isAlarmNormalization(SiconiaInputNormalization normalization) {
        return normalization != null && normalization.inputClass() == InputClass.ALARM_CODE;
    }

    private InputUnderstanding directNaturalLanguageUnderstanding(String input) {
        String routingInput = NaturalLanguageRoutingNormalizer.normalize(input);

        if (CasualQueryClassifier.isAssistantCapabilityQuestion(routingInput)) {
            StrategyCandidate candidate = new StrategyCandidate(
                    StrategyKey.CASUAL_CHAT,
                    StrategyKey.CASUAL_CHAT.label(),
                    0.92,
                    "Matched an assistant capability/help question without structured payload markers.",
                    false,
                    false,
                    InputClass.QUERY.name(),
                    null,
                    null,
                    null,
                    List.of()
            );
            return new InputUnderstanding(
                    InputClass.QUERY,
                    DlmsIntent.UNKNOWN,
                    null,
                    null,
                    new StrategyMetadata(
                            StrategyKey.CASUAL_CHAT,
                            StrategyKey.CASUAL_CHAT.label(),
                            candidate.confidence(),
                            false,
                            false,
                            List.of(candidate),
                            List.of()
                    ),
                    OrchestrationMode.DETERMINISTIC_FAST_PATH
            );
        }

        if (followUpResolver.isFollowUpQuestion(routingInput)) {
            StrategyCandidate candidate = new StrategyCandidate(
                    StrategyKey.SESSION_RECALL,
                    StrategyKey.SESSION_RECALL.label(),
                    0.84,
                    "Matched a session follow-up question about a previous decoded result; session context should answer before retrieval.",
                    false,
                    false,
                    InputClass.QUERY.name(),
                    null,
                    null,
                    null,
                    List.of()
            );
            return new InputUnderstanding(
                    InputClass.QUERY,
                    DlmsIntent.UNKNOWN,
                    null,
                    null,
                    new StrategyMetadata(
                            StrategyKey.SESSION_RECALL,
                            StrategyKey.SESSION_RECALL.label(),
                            candidate.confidence(),
                            false,
                            false,
                            List.of(candidate),
                            List.of()
                    ),
                    OrchestrationMode.NATURAL_LANGUAGE_AGENTIC
            );
        }

        if (CasualQueryClassifier.isQuestionPhrasing(routingInput)) {
            return directQuestionUnderstanding(routingInput);
        }

        DlmsIntent semanticIntent = routerAgent.route(routingInput, InputClass.QUERY, null).intent();
        StrategyKey strategy = switch (semanticIntent) {
            case SECURITY_EXPLAIN -> StrategyKey.SECURITY_EXPLAIN;
            case DOCUMENTATION -> StrategyKey.DOCUMENTATION;
            default -> StrategyKey.UNKNOWN;
        };
        double confidence = switch (strategy) {
            case SECURITY_EXPLAIN -> 0.74;
            case DOCUMENTATION -> 0.68;
            default -> 0.60;
        };
        String rationale = switch (strategy) {
            case SECURITY_EXPLAIN -> "No structured payload markers were detected, so the input was routed as a natural-language DLMS security question.";
            case DOCUMENTATION -> "No structured payload markers were detected, so the input was routed as a natural-language documentation question.";
            default -> "No structured payload markers were detected, so the input was routed as a natural-language assistant query.";
        };
        StrategyCandidate candidate = new StrategyCandidate(
                strategy,
                strategy.label(),
                confidence,
                rationale,
                false,
                false,
                InputClass.QUERY.name(),
                null,
                null,
                null,
                List.of()
        );
        return new InputUnderstanding(
                InputClass.QUERY,
                semanticIntent,
                null,
                null,
                new StrategyMetadata(
                        strategy,
                        strategy.label(),
                        confidence,
                        false,
                        false,
                        List.of(candidate),
                        List.of()
                ),
                naturalLanguageModeFor(strategy)
        );
    }

    private InputUnderstanding directQuestionUnderstanding(String input) {
        DlmsIntent semanticIntent = routerAgent.route(input, InputClass.QUERY, null).intent();
        DlmsIntent resolvedIntent = switch (semanticIntent) {
            case SECURITY_EXPLAIN, DOCUMENTATION, OBIS_LOOKUP, SICONIA_TROUBLESHOOT -> semanticIntent;
            case FRAME_DECODE, APDU_ANALYSIS, PROFILE_DECODE, UNKNOWN ->
                    isSecurityQuestion(input) ? DlmsIntent.SECURITY_EXPLAIN : DlmsIntent.DOCUMENTATION;
        };

        StrategyKey strategy = switch (resolvedIntent) {
            case SECURITY_EXPLAIN -> StrategyKey.SECURITY_EXPLAIN;
            case DOCUMENTATION -> StrategyKey.DOCUMENTATION;
            case OBIS_LOOKUP -> StrategyKey.DLMS_OBIS_LOOKUP;
            case SICONIA_TROUBLESHOOT -> StrategyKey.SICONIA_LOG_ANALYSIS;
            default -> StrategyKey.UNKNOWN;
        };
        double confidence = switch (resolvedIntent) {
            case SECURITY_EXPLAIN -> 0.90;
            case OBIS_LOOKUP -> 0.88;
            case SICONIA_TROUBLESHOOT -> 0.86;
            case DOCUMENTATION -> 0.86;
            default -> 0.60;
        };
        String rationale = switch (resolvedIntent) {
            case SECURITY_EXPLAIN ->
                    "Detected a natural-language security question with no structured payload markers, so decode candidates were suppressed.";
            case OBIS_LOOKUP ->
                    "Detected a natural-language OBIS question with no structured payload markers, so the OBIS lookup path won directly.";
            case SICONIA_TROUBLESHOOT ->
                    "Detected a natural-language SICONIA troubleshooting question with no structured payload markers, so retrieval-backed analysis won directly.";
            case DOCUMENTATION ->
                    "Detected a question-led natural-language protocol query with no structured payload markers, so documentation won over decode candidates.";
            default ->
                    "Detected a natural-language question with no structured payload markers.";
        };
        StrategyCandidate candidate = new StrategyCandidate(
                strategy,
                strategy.label(),
                confidence,
                rationale,
                false,
                false,
                InputClass.QUERY.name(),
                null,
                null,
                null,
                List.of()
        );
        return new InputUnderstanding(
                InputClass.QUERY,
                resolvedIntent,
                null,
                null,
                new StrategyMetadata(
                        strategy,
                        strategy.label(),
                        confidence,
                        false,
                        false,
                        List.of(candidate),
                        List.of()
                ),
                naturalLanguageModeFor(strategy)
        );
    }

    private InputUnderstanding directAlarmUnderstanding(SiconiaInputNormalization normalization) {
        StrategyCandidate candidate = new StrategyCandidate(
                StrategyKey.SICONIA_ALARM_ANALYSIS,
                StrategyKey.SICONIA_ALARM_ANALYSIS.label(),
                0.97,
                normalization.extractorNote() == null || normalization.extractorNote().isBlank()
                        ? "Matched a direct SICONIA alarm code input."
                        : normalization.extractorNote(),
                true,
                false,
                InputClass.ALARM_CODE.name(),
                null,
                normalization.provenance().name(),
                normalization.normalizedInput(),
                normalization.warnings()
        );
        return new InputUnderstanding(
                InputClass.ALARM_CODE,
                DlmsIntent.SICONIA_TROUBLESHOOT,
                null,
                normalization,
                new StrategyMetadata(
                        StrategyKey.SICONIA_ALARM_ANALYSIS,
                        StrategyKey.SICONIA_ALARM_ANALYSIS.label(),
                        candidate.confidence(),
                        false,
                        false,
                        List.of(candidate),
                        normalization.warnings()
                ),
                OrchestrationMode.DETERMINISTIC_FAST_PATH
        );
    }

    private OrchestrationMode naturalLanguageModeFor(StrategyKey strategy) {
        return switch (strategy) {
            case CASUAL_CHAT -> OrchestrationMode.DETERMINISTIC_FAST_PATH;
            case SESSION_RECALL, SECURITY_EXPLAIN, DOCUMENTATION -> OrchestrationMode.NATURAL_LANGUAGE_AGENTIC;
            default -> OrchestrationMode.NATURAL_LANGUAGE_AGENTIC;
        };
    }

    private OrchestrationMode selectOrchestrationMode(
            String rawInput,
            StrategyKey selectedStrategy,
            StrategyMetadata metadata
    ) {
        if (metadata != null && metadata.ambiguous()) {
            return OrchestrationMode.AMBIGUOUS_SAFE_FALLBACK;
        }
        if (selectedStrategy == null || selectedStrategy == StrategyKey.UNKNOWN) {
            return OrchestrationMode.AMBIGUOUS_SAFE_FALLBACK;
        }
        if (selectedStrategy == StrategyKey.CASUAL_CHAT) {
            return OrchestrationMode.DETERMINISTIC_FAST_PATH;
        }
        if (selectedStrategy == StrategyKey.SESSION_RECALL
                || selectedStrategy == StrategyKey.DOCUMENTATION
                || selectedStrategy == StrategyKey.SECURITY_EXPLAIN) {
            return OrchestrationMode.NATURAL_LANGUAGE_AGENTIC;
        }
        if (isStructuredStrategy(selectedStrategy)) {
            return requiresAgenticFollowThrough(rawInput)
                    ? OrchestrationMode.STRUCTURED_PLUS_AGENTIC
                    : OrchestrationMode.DETERMINISTIC_FAST_PATH;
        }
        return OrchestrationMode.NATURAL_LANGUAGE_AGENTIC;
    }

    private boolean isStructuredStrategy(StrategyKey strategy) {
        return strategy == StrategyKey.DLMS_FRAME_DECODE
                || strategy == StrategyKey.DLMS_APDU_DECODE
                || strategy == StrategyKey.DLMS_AXDR_DECODE
                || strategy == StrategyKey.DLMS_OBIS_LOOKUP
                || strategy == StrategyKey.SICONIA_XML_ANALYSIS
                || strategy == StrategyKey.SICONIA_ALARM_ANALYSIS
                || strategy == StrategyKey.SICONIA_LOG_ANALYSIS;
    }

    private boolean requiresAgenticFollowThrough(String rawInput) {
        if (rawInput == null || rawInput.isBlank()) {
            return false;
        }
        return AGENTIC_EXPLANATION_PATTERN.matcher(rawInput).find();
    }

    private List<StrategyCandidate> buildDlmsCandidates(String input, DlmsInputNormalization normalization) {
        if (normalization == null) {
            return List.of();
        }

        if (normalization.ambiguous()) {
            return switch (normalization.kind()) {
                case FRAME_HEX -> extractFrameCandidates(input, normalization);
                case OBIS_QUERY -> extractObisCandidates(input, normalization);
                case APDU_HEX, AXDR_HEX -> List.of(new StrategyCandidate(
                        mapDlmsStrategy(normalization.kind()),
                        labelForKind(normalization.kind()),
                        0.74,
                        normalization.extractorNote() == null ? "Recovered multiple DLMS payload candidates from one request." : normalization.extractorNote(),
                        true,
                        true,
                        InputClass.QUERY.name(),
                        normalization.kind().name(),
                        normalization.provenance().name(),
                        null,
                        normalization.warnings()
                ));
            };
        }

        return List.of(new StrategyCandidate(
                mapDlmsStrategy(normalization.kind()),
                labelForKind(normalization.kind()),
                scoreForDlms(normalization),
                rationaleForDlms(normalization),
                true,
                false,
                normalization.kind() == DlmsNormalizedKind.FRAME_HEX ? InputClass.HEX_FRAME.name() : InputClass.QUERY.name(),
                normalization.kind().name(),
                normalization.provenance().name(),
                normalization.normalizedInput(),
                normalization.warnings()
        ));
    }

    private StrategyCandidate buildSiconiaCandidate(SiconiaInputNormalization normalization) {
        if (normalization == null) {
            return null;
        }

        StrategyKey strategy = switch (normalization.inputClass()) {
            case XML_TRACE -> StrategyKey.SICONIA_XML_ANALYSIS;
            case ALARM_CODE -> StrategyKey.SICONIA_ALARM_ANALYSIS;
            case LOG_BLOCK -> StrategyKey.SICONIA_LOG_ANALYSIS;
            default -> null;
        };
        if (strategy == null) {
            return null;
        }

        double confidence = normalization.provenance() == com.company.dlms.domain.siconia.ParseProvenance.STRUCTURED_DIRECT
                ? 0.97
                : 0.88;
        String rationale = normalization.extractorNote() == null
                ? "Recovered a structured SICONIA input family."
                : normalization.extractorNote();

        return new StrategyCandidate(
                strategy,
                strategy.label(),
                confidence,
                rationale,
                true,
                false,
                normalization.inputClass().name(),
                null,
                normalization.provenance().name(),
                normalization.normalizedInput(),
                normalization.warnings()
        );
    }

    private StrategyCandidate buildSemanticCandidate(DlmsIntent intent) {
        if (intent == null) {
            return null;
        }
        return switch (intent) {
            case SECURITY_EXPLAIN -> new StrategyCandidate(
                    StrategyKey.SECURITY_EXPLAIN,
                    StrategyKey.SECURITY_EXPLAIN.label(),
                    0.66,
                    "Matched DLMS security and association-diagnostic language.",
                    false,
                    false,
                    InputClass.QUERY.name(),
                    null,
                    null,
                    null,
                    List.of()
            );
            case DOCUMENTATION -> new StrategyCandidate(
                    StrategyKey.DOCUMENTATION,
                    StrategyKey.DOCUMENTATION.label(),
                    0.58,
                    "Matched a conceptual/documentation question after deterministic parsing candidates were evaluated.",
                    false,
                    false,
                    InputClass.QUERY.name(),
                    null,
                    null,
                    null,
                    List.of()
            );
            case UNKNOWN -> new StrategyCandidate(
                    StrategyKey.UNKNOWN,
                    StrategyKey.UNKNOWN.label(),
                    0.10,
                    "No dominant deterministic or semantic strategy was found.",
                    false,
                    true,
                    InputClass.QUERY.name(),
                    null,
                    null,
                    null,
                    List.of()
            );
            default -> null;
        };
    }

    private StrategyDecision selectDecision(List<StrategyCandidate> candidates) {
        if (candidates.isEmpty()) {
            StrategyMetadata metadata = new StrategyMetadata(
                    StrategyKey.UNKNOWN,
                    StrategyKey.UNKNOWN.label(),
                    0.0,
                    false,
                    true,
                    List.of(),
                    List.of("No deterministic or retrieval-backed strategy candidate was found.")
            );
            return new StrategyDecision(InputClass.QUERY, DlmsIntent.UNKNOWN, StrategyKey.UNKNOWN, metadata);
        }

        StrategyCandidate top = candidates.getFirst();
        StrategyCandidate second = candidates.size() > 1 ? candidates.get(1) : null;
        boolean ambiguous = top.tentative()
                || (second != null && Math.abs(top.confidence() - second.confidence()) <= AMBIGUITY_DELTA);

        List<String> warnings = new ArrayList<>(top.warnings());
        if (ambiguous && second != null) {
            warnings.add("More than one interpretation remains plausible for this input.");
        }

        if (ambiguous) {
            StrategyMetadata metadata = new StrategyMetadata(
                    top.strategy(),
                    top.label(),
                    top.confidence(),
                    true,
                    true,
                    candidates.stream().limit(3).toList(),
                    warnings
            );
            return new StrategyDecision(InputClass.QUERY, DlmsIntent.UNKNOWN, top.strategy(), metadata);
        }

        StrategyMetadata metadata = new StrategyMetadata(
                top.strategy(),
                top.label(),
                top.confidence(),
                false,
                top.tentative(),
                candidates.stream().limit(3).toList(),
                warnings
        );
        return new StrategyDecision(mapInputClass(top.strategy()), mapIntent(top.strategy()), top.strategy(), metadata);
    }

    private List<StrategyCandidate> extractFrameCandidates(String input, DlmsInputNormalization normalization) {
        Set<String> frames = new LinkedHashSet<>();
        Matcher matcher = FRAME_PATTERN.matcher(input);
        while (matcher.find()) {
            String normalized = normalizeHex(matcher.group());
            if (!normalized.isBlank()) {
                frames.add(normalized);
            }
        }
        if (frames.isEmpty()) {
            return List.of(new StrategyCandidate(
                    StrategyKey.DLMS_FRAME_DECODE,
                    StrategyKey.DLMS_FRAME_DECODE.label(),
                    0.78,
                    "Recovered more than one HDLC frame candidate.",
                    true,
                    true,
                    InputClass.HEX_FRAME.name(),
                    normalization.kind().name(),
                    normalization.provenance().name(),
                    null,
                    normalization.warnings()
            ));
        }
        List<StrategyCandidate> candidates = new ArrayList<>();
        int index = 1;
        for (String frame : frames) {
            candidates.add(new StrategyCandidate(
                    StrategyKey.DLMS_FRAME_DECODE,
                    "HDLC frame candidate " + index,
                    0.78 - (index - 1) * 0.01,
                    "Recovered a plausible HDLC frame candidate from the request.",
                    true,
                    true,
                    InputClass.HEX_FRAME.name(),
                    normalization.kind().name(),
                    normalization.provenance().name(),
                    frame,
                    normalization.warnings()
            ));
            index += 1;
            if (index > 3) {
                break;
            }
        }
        return candidates;
    }

    private List<StrategyCandidate> extractObisCandidates(String input, DlmsInputNormalization normalization) {
        Set<String> matches = new LinkedHashSet<>();
        Matcher matcher = OBIS_PATTERN.matcher(input);
        while (matcher.find()) {
            matches.add(matcher.group());
        }
        if (matches.isEmpty()) {
            return List.of();
        }
        List<StrategyCandidate> candidates = new ArrayList<>();
        int index = 1;
        for (String obis : matches) {
            candidates.add(new StrategyCandidate(
                    StrategyKey.DLMS_OBIS_LOOKUP,
                    "OBIS candidate " + index,
                    0.76 - (index - 1) * 0.01,
                    "Recovered an OBIS code candidate from the request.",
                    true,
                    true,
                    InputClass.QUERY.name(),
                    normalization.kind().name(),
                    normalization.provenance().name(),
                    obis,
                    normalization.warnings()
            ));
            index += 1;
            if (index > 3) {
                break;
            }
        }
        return candidates;
    }

    private double scoreForDlms(DlmsInputNormalization normalization) {
        boolean direct = normalization.provenance() == com.company.dlms.domain.siconia.ParseProvenance.STRUCTURED_DIRECT;
        return switch (normalization.kind()) {
            case FRAME_HEX -> direct ? 0.99 : 0.91;
            case APDU_HEX -> direct ? 0.96 : 0.89;
            case AXDR_HEX -> direct ? 0.95 : 0.88;
            case OBIS_QUERY -> direct ? 0.94 : 0.87;
        };
    }

    private String rationaleForDlms(DlmsInputNormalization normalization) {
        if (normalization.extractorNote() != null && !normalization.extractorNote().isBlank()) {
            return normalization.extractorNote();
        }
        return switch (normalization.kind()) {
            case FRAME_HEX -> "Detected a structurally valid HDLC frame candidate.";
            case APDU_HEX -> "Detected a deterministic APDU payload candidate.";
            case AXDR_HEX -> "Detected a deterministic AXDR payload candidate.";
            case OBIS_QUERY -> "Detected a deterministic OBIS lookup candidate.";
        };
    }

    private StrategyKey mapDlmsStrategy(DlmsNormalizedKind kind) {
        return switch (kind) {
            case FRAME_HEX -> StrategyKey.DLMS_FRAME_DECODE;
            case APDU_HEX -> StrategyKey.DLMS_APDU_DECODE;
            case AXDR_HEX -> StrategyKey.DLMS_AXDR_DECODE;
            case OBIS_QUERY -> StrategyKey.DLMS_OBIS_LOOKUP;
        };
    }

    private String labelForKind(DlmsNormalizedKind kind) {
        return mapDlmsStrategy(kind).label();
    }

    private InputClass mapInputClass(StrategyKey strategy) {
        return switch (strategy) {
            case DLMS_FRAME_DECODE -> InputClass.HEX_FRAME;
            case SICONIA_XML_ANALYSIS -> InputClass.XML_TRACE;
            case SICONIA_ALARM_ANALYSIS -> InputClass.ALARM_CODE;
            case SICONIA_LOG_ANALYSIS -> InputClass.LOG_BLOCK;
            default -> InputClass.QUERY;
        };
    }

    private DlmsIntent mapIntent(StrategyKey strategy) {
        return switch (strategy) {
            case DLMS_FRAME_DECODE -> DlmsIntent.FRAME_DECODE;
            case DLMS_APDU_DECODE, DLMS_AXDR_DECODE -> DlmsIntent.APDU_ANALYSIS;
            case DLMS_OBIS_LOOKUP -> DlmsIntent.OBIS_LOOKUP;
            case SICONIA_XML_ANALYSIS, SICONIA_ALARM_ANALYSIS, SICONIA_LOG_ANALYSIS -> DlmsIntent.SICONIA_TROUBLESHOOT;
            case SECURITY_EXPLAIN -> DlmsIntent.SECURITY_EXPLAIN;
            case DOCUMENTATION -> DlmsIntent.DOCUMENTATION;
            default -> DlmsIntent.UNKNOWN;
        };
    }

    private boolean isDlmsStrategy(StrategyKey strategy) {
        return strategy == StrategyKey.DLMS_FRAME_DECODE
                || strategy == StrategyKey.DLMS_APDU_DECODE
                || strategy == StrategyKey.DLMS_AXDR_DECODE
                || strategy == StrategyKey.DLMS_OBIS_LOOKUP;
    }

    private boolean isSiconiaStrategy(StrategyKey strategy) {
        return strategy == StrategyKey.SICONIA_XML_ANALYSIS
                || strategy == StrategyKey.SICONIA_ALARM_ANALYSIS
                || strategy == StrategyKey.SICONIA_LOG_ANALYSIS;
    }

    private String normalizeHex(String raw) {
        return raw == null ? "" : raw.trim()
                .replaceFirst("(?i)^0x", "")
                .replace(" ", "")
                .replace(":", "")
                .replace("\n", "")
                .replace("\r", "")
                .toUpperCase(Locale.ROOT);
    }

    private record StrategyDecision(
            InputClass inputClass,
            DlmsIntent intent,
            StrategyKey selectedStrategy,
            StrategyMetadata metadata
    ) {}
}
