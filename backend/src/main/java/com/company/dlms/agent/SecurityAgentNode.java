package com.company.dlms.agent;

import com.company.dlms.agent.decoder.StmFieldExtractor;
import com.company.dlms.domain.decoder.ApduType;
import com.company.dlms.domain.decoder.DecodeResult;
import com.company.dlms.domain.rag.IntentType;
import com.company.dlms.domain.rag.RetrievalResult;
import com.company.dlms.infrastructure.rag.RetrievalService;
import com.company.dlms.workflow.WorkflowState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;
import java.util.Locale;

@Component
public class SecurityAgentNode {

    private static final Logger log = LoggerFactory.getLogger(SecurityAgentNode.class);
    private static final Duration RETRIEVAL_TIMEOUT = Duration.ofSeconds(15);
    private final RetrievalService retrievalService;

    public SecurityAgentNode(RetrievalService retrievalService) {
        this.retrievalService = retrievalService;
    }

    public WorkflowState process(WorkflowState state) {
        log.debug("SecurityAgentNode running sessionId={} intent={}", state.sessionId(), state.intent());

        WorkflowState next = state;

        try {
            List<RetrievalResult> results = retrievalService.retrieve(state.rawInput(), IntentType.SECURITY_EXPLAIN, 10)
                    .collectList()
                    .subscribeOn(Schedulers.boundedElastic())
                    .block(RETRIEVAL_TIMEOUT);

            if (results != null) {
                next = next.toBuilder().retrievalResults(results).build();
            } else {
                next = next.addError("Security retrieval timed out");
            }
        } catch (Exception e) {
            log.error("Security retrieval failed for sessionId={}", state.sessionId(), e);
            next = next.addError("Security retrieval failed: " + e.getMessage());
        }

        try {
            String securityContextSummary = buildSecurityContextSummary(next);
            if (securityContextSummary != null && !securityContextSummary.isBlank()) {
                next = next.withSecurityContextSummary(securityContextSummary);
            }
        } catch (Exception e) {
            log.warn("Security context extraction failed for sessionId={}: {}", state.sessionId(), e.getMessage());
            next = next.addError("Security context extraction failed: " + e.getMessage());
        }

        return next;
    }

    private String buildSecurityContextSummary(WorkflowState state) {
        String suiteSummary = resolveSuiteSummary(state);
        boolean frameCounterPresent = hasText(state.frameCounter()) || hasText(state.frameCounterHex());
        String cipheredApduType = null;
        Integer aareDiagnostic = null;

        if (state.decodeResult() instanceof DecodeResult decodeResult) {
            if (isCipheredApdu(decodeResult.apduType())) {
                cipheredApduType = decodeResult.apduType().name();
            }
            if (decodeResult.apduType() == ApduType.AARE) {
                aareDiagnostic = StmFieldExtractor.extractAareResultDiagnostic(decodeResult.axdrTree());
            }
        }

        if (suiteSummary == null && !frameCounterPresent && cipheredApduType == null && aareDiagnostic == null) {
            return null;
        }

        StringBuilder summary = new StringBuilder();
        if (suiteSummary != null) {
            summary.append("- Security Suite: ").append(suiteSummary).append("\n");
        }
        summary.append("- Frame Counter: ").append(frameCounterPresent ? "present" : "absent").append("\n");
        summary.append("- Ciphered APDU: ")
                .append(cipheredApduType != null ? "yes (" + cipheredApduType + ")" : "no");
        if (aareDiagnostic != null) {
            summary.append("\n- AARE Diagnostic: ").append(aareDiagnostic);
        }
        return summary.toString();
    }

    private String resolveSuiteSummary(WorkflowState state) {
        String rawSuite = state.securitySuite();
        if ((!hasText(rawSuite)) && state.decodeResult() instanceof DecodeResult decodeResult
                && decodeResult.apduType() == ApduType.AARQ) {
            Integer extractedSuite = StmFieldExtractor.extractSecuritySuite(decodeResult.axdrTree());
            rawSuite = extractedSuite != null ? String.valueOf(extractedSuite) : null;
        }
        if (!hasText(rawSuite)) {
            return null;
        }
        Integer suite = parseSuiteNumber(rawSuite);
        if (suite == null) {
            return rawSuite.trim();
        }
        return switch (suite) {
            case 0 -> "Suite 0 (no security)";
            case 1 -> "Suite 1 (authentication + encryption, AES-GCM-128)";
            case 2 -> "Suite 2 (high-security authenticated key agreement)";
            case 3 -> "Suite 3 (high-security authenticated key agreement, stronger profile)";
            default -> "Suite " + suite;
        };
    }

    private Integer parseSuiteNumber(String rawSuite) {
        String trimmed = rawSuite == null ? null : rawSuite.trim();
        if (!hasText(trimmed)) {
            return null;
        }
        try {
            if (trimmed.toLowerCase(Locale.ROOT).startsWith("suite ")) {
                return Integer.parseInt(trimmed.substring(6).trim());
            }
            return Integer.parseInt(trimmed);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private boolean isCipheredApdu(ApduType apduType) {
        return apduType == ApduType.GENERAL_GLO_CIPHERING
                || apduType == ApduType.GENERAL_DED_CIPHERING
                || apduType == ApduType.GLO_GET_REQUEST
                || apduType == ApduType.GLO_GET_RESPONSE
                || apduType == ApduType.GLO_SET_REQUEST
                || apduType == ApduType.GLO_SET_RESPONSE
                || apduType == ApduType.GLO_ACTION_REQUEST
                || apduType == ApduType.GLO_ACTION_RESPONSE;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
