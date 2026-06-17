package com.company.dlms.agent;

import com.company.dlms.agent.decoder.StmFieldExtractor;
import com.company.dlms.domain.AnomalyEvent;
import com.company.dlms.domain.AnomalySeverity;
import com.company.dlms.domain.decoder.ApduType;
import com.company.dlms.domain.decoder.DecodeResult;
import com.company.dlms.workflow.StmSnapshot;
import com.company.dlms.workflow.WorkflowState;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Deterministic anomaly detection node.
 * Implements 7 rules for protocol violation detection per spec:
 *   FC-001: Frame Counter Regression (CRITICAL)
 *   FC-002: Frame Counter Near Rollover (HIGH)
 *   FC-003: Frame Counter Reuse (CRITICAL) — additional safeguard
 *   SEC-001: Security Suite Mismatch (CRITICAL)
 *   SEC-002: AARE Authentication Failure (CRITICAL)
 *   ASSOC-001: AARQ Without Prior SNRM (HIGH)
 *   PDU-001: PDU Size Exceeds Negotiated Max (HIGH)
 *   PD-001: GBT Partial / Timeout (MEDIUM)
 */
@Component
public class AnomalyDetectionNode {

    public WorkflowState process(WorkflowState state) {
        if (!(state.decodeResult() instanceof DecodeResult dr)) {
            return state;
        }

        StmSnapshot prev = state.stmSnapshot();
        List<AnomalyEvent> found = new ArrayList<>();

        // FC-001: Frame Counter Regression (CRITICAL)
        checkFcRegression(state, prev, found);

        // FC-002: Frame Counter Near Rollover (HIGH)
        checkFcRollover(state, found);

        // FC-003: Frame Counter Reuse (CRITICAL) — additional safeguard
        checkFcReuse(state, prev, found);

        // SEC-001: Security Suite Mismatch (CRITICAL)
        checkSecuritySuiteMismatch(state, prev, found);

        // SEC-002: AARE Authentication Failure (CRITICAL)
        checkAareAuthFailure(dr, found);

        // ASSOC-001: AARQ Without Prior SNRM (HIGH)
        checkAssociationWithoutSnrm(dr.apduType(), prev, found);

        // PDU-001: PDU Size Exceeds Negotiated Max (HIGH)
        checkPduSizeViolation(dr, prev, found);

        // PD-001: GBT Partial / Timeout (MEDIUM)
        checkGbtPartial(dr, found);

        if (found.isEmpty()) {
            return state;
        }

        // Append formatted anomaly strings to existing anomalies
        List<String> anomalyStrings = new ArrayList<>();
        if (state.anomalies() != null) {
            anomalyStrings.addAll(state.anomalies());
        }
        for (AnomalyEvent event : found) {
            anomalyStrings.add(event.formatted());
        }

        return state.toBuilder()
                .anomalies(anomalyStrings)
                .build();
    }

    /**
     * FC-001: Frame Counter Regression
     * Fires when current frameCounter < previous STM frameCounter.
     * Severity: CRITICAL — possible replay attack.
     */
    private void checkFcRegression(WorkflowState state, StmSnapshot prev, List<AnomalyEvent> found) {
        Long current = parseLong(state.frameCounter());
        if (prev != null && prev.frameCounter() != null && current != null && current < prev.frameCounter()) {
            found.add(AnomalyEvent.of("FC-001", AnomalySeverity.CRITICAL,
                    "Frame counter decreased from " + prev.frameCounter() + " to " + current
                            + " — possible replay attack",
                    current, Map.of("previous", prev.frameCounter(), "current", current)));
        }
    }

    /**
     * FC-002: Frame Counter Near Rollover
     * Fires when frameCounter >= 0xFFFFFFF0 (near 32-bit rollover).
     * Severity: HIGH.
     */
    private void checkFcRollover(WorkflowState state, List<AnomalyEvent> found) {
        Long current = parseLong(state.frameCounter());
        if (current != null && current >= 0xFFFFFFF0L) {
            found.add(AnomalyEvent.of("FC-002", AnomalySeverity.HIGH,
                    "Frame counter approaching rollover: " + String.format("0x%08X", current),
                    current, Map.of("value", current)));
        }
    }

    /**
     * FC-003: Frame Counter Reuse (additional safeguard, not in original spec)
     * Fires when current frameCounter equals the previous stored value.
     * Severity: CRITICAL — possible replay attack.
     */
    private void checkFcReuse(WorkflowState state, StmSnapshot prev, List<AnomalyEvent> found) {
        Long current = parseLong(state.frameCounter());
        if (prev != null && prev.frameCounter() != null && current != null
                && current.equals(prev.frameCounter())) {
            found.add(AnomalyEvent.of("FC-003", AnomalySeverity.CRITICAL,
                    "Frame counter reuse detected: " + current + " — possible replay attack",
                    current, Map.of("value", current)));
        }
    }

    /**
     * SEC-001: Security Suite Mismatch
     * Fires when current securitySuite differs from STM securitySuite mid-session.
     * Severity: CRITICAL.
     */
    private void checkSecuritySuiteMismatch(WorkflowState state, StmSnapshot prev, List<AnomalyEvent> found) {
        Integer current = parseInt(state.securitySuite());
        if (prev != null && prev.securitySuite() != null && current != null
                && !current.equals(prev.securitySuite())) {
            found.add(AnomalyEvent.of("SEC-001", AnomalySeverity.CRITICAL,
                    "Security suite changed from " + prev.securitySuite() + " to " + current
                            + " mid-session",
                    null, Map.of("previous", prev.securitySuite(), "current", current)));
        }
    }

    /**
     * SEC-002: AARE Authentication Failure
     * Fires when apduType is AARE and the AXDR tree contains a
     * result-source-diagnostic field with value != 0.
     * Severity: CRITICAL.
     */
    private void checkAareAuthFailure(DecodeResult dr, List<AnomalyEvent> found) {
        if (dr.apduType() == ApduType.AARE && dr.axdrTree() != null) {
            Integer diagnostic = StmFieldExtractor.extractAareResultDiagnostic(dr.axdrTree());
            if (diagnostic != null && diagnostic != 0) {
                found.add(AnomalyEvent.of("SEC-002", AnomalySeverity.CRITICAL,
                        "AARE authentication failure — result-source-diagnostic: " + diagnostic,
                        null, Map.of("diagnostic", diagnostic)));
            }
        }
    }

    /**
     * ASSOC-001: AARQ Without Prior SNRM
     * Fires when apduType is AARQ and STM associationState is neither
     * CONNECTING, ASSOCIATING nor ASSOCIATED (i.e., no SNRM was seen first).
     * Severity: HIGH.
     */
    private void checkAssociationWithoutSnrm(ApduType apduType, StmSnapshot prev, List<AnomalyEvent> found) {
        if (apduType == ApduType.AARQ) {
            String prevState = prev != null ? prev.associationState() : null;
            if (!"CONNECTING".equals(prevState)
                    && !"ASSOCIATING".equals(prevState)
                    && !"ASSOCIATED".equals(prevState)) {
                found.add(AnomalyEvent.of("ASSOC-001", AnomalySeverity.HIGH,
                        "Association request without prior SNRM — unexpected sequence",
                        null, Map.of("previousState", String.valueOf(prevState))));
            }
        }
    }

    /**
     * PDU-001: PDU Size Exceeds Negotiated Maximum
     * Fires when STM maxPduSize is non-null and raw frame byte length exceeds it.
     * Severity: HIGH.
     */
    private void checkPduSizeViolation(DecodeResult dr, StmSnapshot prev, List<AnomalyEvent> found) {
        if (prev != null && prev.maxPduSize() != null && dr.rawHex() != null) {
            int frameBytes = dr.rawHex().replaceAll("\\s", "").length() / 2;
            if (frameBytes > prev.maxPduSize()) {
                found.add(AnomalyEvent.of("PDU-001", AnomalySeverity.HIGH,
                        "Frame length " + frameBytes + " bytes exceeds negotiated max PDU size "
                                + prev.maxPduSize(),
                        null, Map.of("actual", frameBytes, "maximum", prev.maxPduSize())));
            }
        }
    }

    /**
     * PD-001: GBT Partial Decode
     * Fires when decodeResult.gbtPartial is true.
     * Severity: MEDIUM.
     */
    private void checkGbtPartial(DecodeResult dr, List<AnomalyEvent> found) {
        if (dr.gbtPartial()) {
            found.add(AnomalyEvent.of("PD-001", AnomalySeverity.MEDIUM,
                    "GBT assembly incomplete — waiting for remaining blocks",
                    null, Map.of()));
        }
    }

    private static Long parseLong(String val) {
        if (val == null || val.isBlank()) return null;
        try { return Long.parseLong(val); } catch (Exception e) { return null; }
    }

    private static Integer parseInt(String val) {
        if (val == null || val.isBlank()) return null;
        try { return Integer.parseInt(val); } catch (Exception e) { return null; }
    }
}
