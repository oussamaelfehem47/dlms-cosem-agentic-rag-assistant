package com.company.dlms.agent;

import com.company.dlms.domain.InputClass;
import com.company.dlms.domain.SessionEvent;
import com.company.dlms.domain.decoder.ApduType;
import com.company.dlms.domain.decoder.DecodeResult;
import com.company.dlms.domain.decoder.FrameType;
import com.company.dlms.domain.decoder.HdlcFrame;
import com.company.dlms.domain.decoder.UFrameType;
import com.company.dlms.workflow.WorkflowState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class SessionEventServiceTest {

    private final SessionEventService service = new SessionEventService();

    @Test
    void buildDecodeEvent_populatesApduTypeAndStage() {
        DecodeResult dr = new DecodeResult(mock(HdlcFrame.class), ApduType.AARQ, null, List.of(), false, "00", List.of());
        WorkflowState state = WorkflowState.empty("s1", "c1", "00")
                .toBuilder()
                .decodeResult(dr)
                .associationState("ASSOCIATING")
                .build();

        SessionEvent event = service.buildDecodeEvent(state);

        assertEquals("s1", event.sessionId());
        assertEquals("AARQ", event.apduType());
        assertEquals("COMPLETE", event.decodeStage());
        assertEquals("ASSOCIATING", event.associationState());
    }

    @Test
    void buildDecodeEvent_gbtPartial_setsPartialStage() {
        DecodeResult dr = new DecodeResult(mock(HdlcFrame.class), ApduType.GBT, null, List.of(), true, "00", List.of());
        WorkflowState state = WorkflowState.empty("s1", "c1", "00").withDecodeResult(dr);

        SessionEvent event = service.buildDecodeEvent(state);

        assertEquals("GBT_PARTIAL", event.decodeStage());
    }

    @Test
    void buildSiconiaEvent_populatesApduTypePrefix() {
        WorkflowState state = WorkflowState.empty("s1", "c1", "trace")
                .toBuilder()
                .inputClass(InputClass.ALARM_CODE)
                .build();

        SessionEvent event = service.buildSiconiaEvent(state);

        assertEquals("SICONIA_ALARM_CODE", event.apduType());
        assertEquals("SICONIA_ANALYSIS", event.decodeStage());
    }

    @Test
    void buildDecodeEvent_usesControlFrameSubtypeForNarrativeWhenNoApduExists() {
        DecodeResult dr = new DecodeResult(
                new HdlcFrame(FrameType.U_FRAME, UFrameType.SNRM, null, 1, 1, null, true, null),
                ApduType.UNKNOWN,
                null,
                List.of(),
                false,
                "7EA00A030383CD6F7E",
                List.of()
        );
        WorkflowState state = WorkflowState.empty("s1", "c1", "7EA00A030383CD6F7E")
                .toBuilder()
                .decodeResult(dr)
                .associationState("ASSOCIATING")
                .build();

        SessionEvent event = service.buildDecodeEvent(state);

        assertEquals("U_FRAME (SNRM)", event.apduType());
        assertEquals("ASSOCIATING", event.associationState());
    }

    @Test
    void buildDecodeEvent_labelsIFrameWithApduSubtype() {
        DecodeResult dr = new DecodeResult(
                new HdlcFrame(FrameType.I_FRAME, null, null, 16, 1, new byte[0], true, null),
                ApduType.AARE,
                null,
                List.of(),
                false,
                "7EA0",
                List.of()
        );
        WorkflowState state = WorkflowState.empty("s1", "c1", "7EA0")
                .toBuilder()
                .decodeResult(dr)
                .associationState("ASSOCIATED")
                .build();

        SessionEvent event = service.buildDecodeEvent(state);

        assertEquals("I_FRAME (AARE)", event.apduType());
    }
}
