package com.company.dlms.agent;

import com.company.dlms.domain.SessionEvent;
import com.company.dlms.domain.decoder.FrameType;
import com.company.dlms.domain.decoder.DecodeResult;
import com.company.dlms.domain.siconia.SiconiaResult;
import com.company.dlms.workflow.WorkflowState;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

/**
 * Service for building SessionEvent objects from WorkflowState.
 */
@Component
public class SessionEventService {

    /**
     * Builds a SessionEvent from a decoded frame state.
     */
    public SessionEvent buildDecodeEvent(WorkflowState state) {
        DecodeResult dr = state.decodeResult() instanceof DecodeResult ? (DecodeResult) state.decodeResult() : null;
        
        return new SessionEvent(
                state.sessionId(),
                Instant.now(),
                state.narrativeContext() != null ? state.narrativeContext().size() + 1 : 1,
                eventLabel(dr),
                dr != null && dr.gbtPartial() ? "GBT_PARTIAL" : "COMPLETE",
                state.associationState() != null ? state.associationState() : "UNKNOWN",
                state.lastObis(),
                state.lastIc(),
                dr != null ? dr.parseErrors() : List.of(),
                Collections.emptyList(),
                state.anomalies() != null ? state.anomalies() : List.of()
        );
    }

    private String eventLabel(DecodeResult dr) {
        if (dr == null) {
            return "UNKNOWN";
        }
        if (dr.hdlcFrame() != null) {
            if (dr.hdlcFrame().frameType() == FrameType.U_FRAME) {
                return dr.hdlcFrame().uFrameType() != null
                        ? "U_FRAME (" + dr.hdlcFrame().uFrameType() + ")"
                        : "U_FRAME";
            }
            if (dr.hdlcFrame().frameType() == FrameType.S_FRAME) {
                return dr.hdlcFrame().sFrameType() != null
                        ? "S_FRAME (" + dr.hdlcFrame().sFrameType() + ")"
                        : "S_FRAME";
            }
            if (dr.hdlcFrame().frameType() == FrameType.I_FRAME) {
                return dr.apduType() != null && dr.apduType() != com.company.dlms.domain.decoder.ApduType.UNKNOWN
                        ? "I_FRAME (" + dr.apduType().name() + ")"
                        : "I_FRAME";
            }
        }
        return dr.apduType() != null ? dr.apduType().name() : "UNKNOWN";
    }

    /**
     * Builds a SessionEvent from a SICONIA analysis state.
     */
    public SessionEvent buildSiconiaEvent(WorkflowState state) {
        SiconiaResult sr = state.siconiaResult();
        
        return new SessionEvent(
                state.sessionId(),
                Instant.now(),
                state.narrativeContext() != null ? state.narrativeContext().size() + 1 : 1,
                "SICONIA_" + (state.inputClass() != null ? state.inputClass().name() : "UNKNOWN"),
                "SICONIA_ANALYSIS",
                state.associationState(),
                null,
                null,
                sr != null && sr.xmlTrace() != null ? sr.xmlTrace().parseErrors() : List.of(),
                Collections.emptyList(),
                state.anomalies() != null ? state.anomalies() : List.of()
        );
    }
}
