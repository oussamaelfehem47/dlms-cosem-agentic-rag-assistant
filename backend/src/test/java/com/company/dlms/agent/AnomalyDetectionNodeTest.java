package com.company.dlms.agent;

import com.company.dlms.domain.decoder.*;
import com.company.dlms.workflow.StmSnapshot;
import com.company.dlms.workflow.WorkflowState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AnomalyDetectionNodeTest {

    private final AnomalyDetectionNode node = new AnomalyDetectionNode();

    // ── FC-001: Frame Counter Regression ──────────────────────────────────

    @Test
    void fcRegression_detected() {
        StmSnapshot prev = new StmSnapshot(100L, null, null, null);
        WorkflowState state = WorkflowState.empty("s1", "c1", "00")
                .toBuilder()
                .stmSnapshot(prev)
                .frameCounter("90")
                .decodeResult(dummyResult())
                .build();

        WorkflowState out = node.process(state);

        assertFalse(out.anomalies().isEmpty());
        assertTrue(out.anomalies().get(0).contains("FC-001"));
    }

    @Test
    void fcRegression_noAnomaly_whenCounterIncreased() {
        StmSnapshot prev = new StmSnapshot(100L, null, null, null);
        WorkflowState state = WorkflowState.empty("s1", "c1", "00")
                .toBuilder()
                .stmSnapshot(prev)
                .frameCounter("101")
                .decodeResult(dummyResult())
                .build();

        WorkflowState out = node.process(state);

        // FC-001 should NOT fire (101 > 100 is normal)
        assertTrue(out.anomalies() == null || out.anomalies().stream().noneMatch(a -> a.contains("FC-001")));
    }

    // ── FC-002: Frame Counter Near Rollover ──────────────────────────────

    @Test
    void fcRollover_detected_whenNearRollover() {
        WorkflowState state = WorkflowState.empty("s1", "c1", "00")
                .toBuilder()
                .frameCounter(String.valueOf(0xFFFFFFF5L))
                .decodeResult(dummyResult())
                .build();

        WorkflowState out = node.process(state);

        assertFalse(out.anomalies().isEmpty());
        assertTrue(out.anomalies().stream().anyMatch(a -> a.contains("FC-002")));
    }

    @Test
    void fcRollover_noAnomaly_whenBelowThreshold() {
        WorkflowState state = WorkflowState.empty("s1", "c1", "00")
                .toBuilder()
                .frameCounter("1000")
                .decodeResult(dummyResult())
                .build();

        WorkflowState out = node.process(state);

        assertTrue(out.anomalies() == null || out.anomalies().stream().noneMatch(a -> a.contains("FC-002")));
    }

    // ── FC-003: Frame Counter Reuse ──────────────────────────────────────

    @Test
    void fcReuse_detected() {
        StmSnapshot prev = new StmSnapshot(100L, null, null, null);
        WorkflowState state = WorkflowState.empty("s1", "c1", "00")
                .toBuilder()
                .stmSnapshot(prev)
                .frameCounter("100")
                .decodeResult(dummyResult())
                .build();

        WorkflowState out = node.process(state);

        assertFalse(out.anomalies().isEmpty());
        assertTrue(out.anomalies().stream().anyMatch(a -> a.contains("FC-003")));
    }

    @Test
    void fcReuse_noAnomaly_whenCounterChanged() {
        StmSnapshot prev = new StmSnapshot(100L, null, null, null);
        WorkflowState state = WorkflowState.empty("s1", "c1", "00")
                .toBuilder()
                .stmSnapshot(prev)
                .frameCounter("101")
                .decodeResult(dummyResult())
                .build();

        WorkflowState out = node.process(state);

        assertTrue(out.anomalies() == null || out.anomalies().stream().noneMatch(a -> a.contains("FC-003")));
    }

    // ── SEC-001: Security Suite Mismatch ────────────────────────────────

    @Test
    void securitySuiteMismatch_detected() {
        StmSnapshot prev = new StmSnapshot(null, 0, null, null);
        WorkflowState state = WorkflowState.empty("s1", "c1", "00")
                .toBuilder()
                .stmSnapshot(prev)
                .securitySuite("1")
                .decodeResult(dummyResult())
                .build();

        WorkflowState out = node.process(state);

        assertFalse(out.anomalies().isEmpty());
        assertTrue(out.anomalies().stream().anyMatch(a -> a.contains("SEC-001")));
    }

    @Test
    void securitySuiteMismatch_noAnomaly_whenSuiteUnchanged() {
        StmSnapshot prev = new StmSnapshot(null, 1, null, null);
        WorkflowState state = WorkflowState.empty("s1", "c1", "00")
                .toBuilder()
                .stmSnapshot(prev)
                .securitySuite("1")
                .decodeResult(dummyResult())
                .build();

        WorkflowState out = node.process(state);

        assertTrue(out.anomalies() == null || out.anomalies().stream().noneMatch(a -> a.contains("SEC-001")));
    }

    @Test
    void securitySuiteMismatch_noAnomaly_whenNoPreviousSuite() {
        // prev.securitySuite() is null — no baseline to compare against
        StmSnapshot prev = new StmSnapshot(null, null, null, null);
        WorkflowState state = WorkflowState.empty("s1", "c1", "00")
                .toBuilder()
                .stmSnapshot(prev)
                .securitySuite("1")
                .decodeResult(dummyResult())
                .build();

        WorkflowState out = node.process(state);

        assertTrue(out.anomalies() == null || out.anomalies().stream().noneMatch(a -> a.contains("SEC-001")));
    }

    // ── SEC-002: AARE Authentication Failure ─────────────────────────────

    @Test
    void aareAuthFailure_detected() {
        // AARE with axdrTree containing a diagnostic value != 0
        AxdrStructure aareBody = new AxdrStructure(List.of(
                new AxdrOctetString(new byte[]{0x01, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00}),
                new AxdrUint8((byte) 1)  // result-source-diagnostic = 1 (failure)
        ));
        DecodeResult dr = new DecodeResult(dummyFrame(), ApduType.AARE, aareBody, List.of(), false, "00", List.of());
        WorkflowState state = WorkflowState.empty("s1", "c1", "00").withDecodeResult(dr);

        WorkflowState out = node.process(state);

        assertFalse(out.anomalies().isEmpty());
        assertTrue(out.anomalies().stream().anyMatch(a -> a.contains("SEC-002")));
    }

    @Test
    void aareAuthFailure_noAnomaly_whenDiagnosticZero() {
        // AARE with diagnostic = 0 (accepted)
        AxdrStructure aareBody = new AxdrStructure(List.of(
                new AxdrOctetString(new byte[]{0x01, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00}),
                new AxdrUint8((byte) 0)  // result-source-diagnostic = 0 (success)
        ));
        DecodeResult dr = new DecodeResult(dummyFrame(), ApduType.AARE, aareBody, List.of(), false, "00", List.of());
        WorkflowState state = WorkflowState.empty("s1", "c1", "00").withDecodeResult(dr);

        WorkflowState out = node.process(state);

        assertTrue(out.anomalies() == null || out.anomalies().stream().noneMatch(a -> a.contains("SEC-002")));
    }

    @Test
    void aareAuthFailure_noAnomaly_whenNotAARE() {
        // Not AARE — no diagnostic check
        WorkflowState state = WorkflowState.empty("s1", "c1", "00")
                .toBuilder()
                .decodeResult(dummyResult())
                .build();

        WorkflowState out = node.process(state);

        assertTrue(out.anomalies() == null || out.anomalies().stream().noneMatch(a -> a.contains("SEC-002")));
    }

    // ── ASSOC-001: AARQ Without Prior SNRM ──────────────────────────────

    @Test
    void associationWithoutSnrm_detected() {
        // AARQ with previous state DISCONNECTED (no SNRM seen)
        StmSnapshot prev = new StmSnapshot(null, null, "DISCONNECTED", null);
        DecodeResult dr = new DecodeResult(dummyFrame(), ApduType.AARQ, null, List.of(), false, "00", List.of());
        WorkflowState state = WorkflowState.empty("s1", "c1", "00")
                .toBuilder()
                .stmSnapshot(prev)
                .decodeResult(dr)
                .build();

        WorkflowState out = node.process(state);

        assertFalse(out.anomalies().isEmpty());
        assertTrue(out.anomalies().stream().anyMatch(a -> a.contains("ASSOC-001")));
    }

    @Test
    void associationWithoutSnrm_noAnomaly_whenAlreadyAssociating() {
        // AARQ after SNRM (state already ASSOCIATING) — valid sequence
        StmSnapshot prev = new StmSnapshot(null, null, "ASSOCIATING", null);
        DecodeResult dr = new DecodeResult(dummyFrame(), ApduType.AARQ, null, List.of(), false, "00", List.of());
        WorkflowState state = WorkflowState.empty("s1", "c1", "00")
                .toBuilder()
                .stmSnapshot(prev)
                .decodeResult(dr)
                .build();

        WorkflowState out = node.process(state);

        assertTrue(out.anomalies() == null || out.anomalies().stream().noneMatch(a -> a.contains("ASSOC-001")));
    }

    @Test
    void associationWithoutSnrm_noAnomaly_whenAlreadyAssociated() {
        // AARQ while already associated (re-negotiation) — valid
        StmSnapshot prev = new StmSnapshot(null, null, "ASSOCIATED", null);
        DecodeResult dr = new DecodeResult(dummyFrame(), ApduType.AARQ, null, List.of(), false, "00", List.of());
        WorkflowState state = WorkflowState.empty("s1", "c1", "00")
                .toBuilder()
                .stmSnapshot(prev)
                .decodeResult(dr)
                .build();

        WorkflowState out = node.process(state);

        assertTrue(out.anomalies() == null || out.anomalies().stream().noneMatch(a -> a.contains("ASSOC-001")));
    }

    @Test
    void associationWithoutSnrm_noAnomaly_whenNotAARQ() {
        // GET_RESPONSE — not an association request
        StmSnapshot prev = new StmSnapshot(null, null, "DISCONNECTED", null);
        WorkflowState state = WorkflowState.empty("s1", "c1", "00")
                .toBuilder()
                .stmSnapshot(prev)
                .decodeResult(dummyResult())
                .build();

        WorkflowState out = node.process(state);

        assertTrue(out.anomalies() == null || out.anomalies().stream().noneMatch(a -> a.contains("ASSOC-001")));
    }

    // ── PDU-001: PDU Size Exceeds Negotiated Max ────────────────────────

    @Test
    void pduSizeViolation_detected() {
        // rawHex = "AABBCCDD" (4 bytes), maxPduSize = 2 → violation
        StmSnapshot prev = new StmSnapshot(null, null, null, 2);
        DecodeResult dr = new DecodeResult(dummyFrame(), ApduType.GET_RESPONSE, null, List.of(), false, "AABBCCDD", List.of());
        WorkflowState state = WorkflowState.empty("s1", "c1", "00")
                .toBuilder()
                .stmSnapshot(prev)
                .decodeResult(dr)
                .build();

        WorkflowState out = node.process(state);

        assertFalse(out.anomalies().isEmpty());
        assertTrue(out.anomalies().stream().anyMatch(a -> a.contains("PDU-001")));
    }

    @Test
    void pduSizeViolation_noAnomaly_whenWithinLimit() {
        // rawHex = "AABB" (2 bytes), maxPduSize = 4 → OK
        StmSnapshot prev = new StmSnapshot(null, null, null, 4);
        DecodeResult dr = new DecodeResult(dummyFrame(), ApduType.GET_RESPONSE, null, List.of(), false, "AABB", List.of());
        WorkflowState state = WorkflowState.empty("s1", "c1", "00")
                .toBuilder()
                .stmSnapshot(prev)
                .decodeResult(dr)
                .build();

        WorkflowState out = node.process(state);

        assertTrue(out.anomalies() == null || out.anomalies().stream().noneMatch(a -> a.contains("PDU-001")));
    }

    @Test
    void pduSizeViolation_noAnomaly_whenNoMaxPduSize() {
        // prev.maxPduSize() is null — no limit to check against
        StmSnapshot prev = new StmSnapshot(null, null, null, null);
        DecodeResult dr = new DecodeResult(dummyFrame(), ApduType.GET_RESPONSE, null, List.of(), false, "AABBCCDD", List.of());
        WorkflowState state = WorkflowState.empty("s1", "c1", "00")
                .toBuilder()
                .stmSnapshot(prev)
                .decodeResult(dr)
                .build();

        WorkflowState out = node.process(state);

        assertTrue(out.anomalies() == null || out.anomalies().stream().noneMatch(a -> a.contains("PDU-001")));
    }

    // ── PD-001: GBT Partial Decode ──────────────────────────────────────

    @Test
    void gbtPartial_detected() {
        DecodeResult dr = new DecodeResult(dummyFrame(), ApduType.GBT, null, List.of(), true, "00", List.of());
        WorkflowState state = WorkflowState.empty("s1", "c1", "00").withDecodeResult(dr);

        WorkflowState out = node.process(state);

        assertFalse(out.anomalies().isEmpty());
        assertTrue(out.anomalies().stream().anyMatch(a -> a.contains("PD-001")));
    }

    @Test
    void gbtPartial_noAnomaly_whenComplete() {
        DecodeResult dr = new DecodeResult(dummyFrame(), ApduType.GBT, null, List.of(), false, "00", List.of());
        WorkflowState state = WorkflowState.empty("s1", "c1", "00").withDecodeResult(dr);

        WorkflowState out = node.process(state);

        assertTrue(out.anomalies() == null || out.anomalies().stream().noneMatch(a -> a.contains("PD-001")));
    }

    // ── Edge Cases ──────────────────────────────────────────────────────

    @Test
    void cleanFrame_noAnomalies() {
        // Normal GET_RESPONSE — no anomalies expected
        StmSnapshot prev = new StmSnapshot(100L, 1, "ASSOCIATED", 2048);
        DecodeResult dr = new DecodeResult(dummyFrame(), ApduType.GET_RESPONSE, null, List.of(), false, "AABB", List.of());
        WorkflowState state = WorkflowState.empty("s1", "c1", "00")
                .toBuilder()
                .stmSnapshot(prev)
                .frameCounter("101")
                .securitySuite("1")
                .decodeResult(dr)
                .build();

        WorkflowState out = node.process(state);

        assertTrue(out.anomalies() == null || out.anomalies().isEmpty());
    }

    @Test
    void nullDecodeResult_passThrough() {
        // State with no decodeResult — should return state unchanged
        WorkflowState state = WorkflowState.empty("s1", "c1", "00");
        WorkflowState out = node.process(state);

        assertSame(state, out);
    }

    @Test
    void anomaliesList_nonNull_appendsNewAnomalies() {
        // Existing anomalies list should be preserved and new ones appended
        StmSnapshot prev = new StmSnapshot(100L, null, null, null);
        DecodeResult dr = new DecodeResult(dummyFrame(), ApduType.GET_RESPONSE, null, List.of(), false, "AA", List.of());
        WorkflowState state = WorkflowState.empty("s1", "c1", "00")
                .toBuilder()
                .stmSnapshot(prev)
                .frameCounter("90")  // triggers FC-001
                .anomalies(List.of("PREVIOUS_ANOMALY"))
                .decodeResult(dr)
                .build();

        WorkflowState out = node.process(state);

        assertTrue(out.anomalies().stream().anyMatch(a -> a.contains("PREVIOUS_ANOMALY")));
        assertTrue(out.anomalies().stream().anyMatch(a -> a.contains("FC-001")));
    }

    @Test
    void nullFrameCounter_doesNotTriggerFcRules() {
        // frameCounter is null — FC rules should be skipped gracefully
        StmSnapshot prev = new StmSnapshot(100L, null, null, null);
        DecodeResult dr = new DecodeResult(dummyFrame(), ApduType.GET_RESPONSE, null, List.of(), false, "AA", List.of());
        WorkflowState state = WorkflowState.empty("s1", "c1", "00")
                .toBuilder()
                .stmSnapshot(prev)
                .frameCounter(null)
                .decodeResult(dr)
                .build();

        WorkflowState out = node.process(state);

        assertTrue(out.anomalies() == null || out.anomalies().isEmpty());
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private HdlcFrame dummyFrame() {
        return new HdlcFrame(FrameType.I_FRAME, null, null, 1, 1, null, true, null);
    }

    private DecodeResult dummyResult() {
        return new DecodeResult(dummyFrame(), ApduType.GET_RESPONSE, null, List.of(), false, "00", List.of());
    }
}
