package com.company.dlms.agent;

import com.company.dlms.agent.decoder.ObisResolver;
import com.company.dlms.domain.DlmsIntent;
import com.company.dlms.domain.decoder.ApduType;
import com.company.dlms.domain.decoder.AxdrArray;
import com.company.dlms.domain.decoder.AxdrDateTime;
import com.company.dlms.domain.decoder.AxdrInt8;
import com.company.dlms.domain.decoder.AxdrOctetString;
import com.company.dlms.domain.decoder.AxdrStructure;
import com.company.dlms.domain.decoder.AxdrUint16;
import com.company.dlms.domain.decoder.AxdrUint32;
import com.company.dlms.domain.decoder.DecodeResult;
import com.company.dlms.domain.decoder.FrameType;
import com.company.dlms.domain.decoder.HdlcFrame;
import com.company.dlms.domain.decoder.ObisResolution;
import com.company.dlms.domain.decoder.ResolutionTier;
import com.company.dlms.domain.profile.ProfileResult;
import com.company.dlms.domain.profile.ProfileType;
import com.company.dlms.workflow.WorkflowState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProfileAgentNodeTest {

    @Mock
    private ObisResolver obisResolver;

    @InjectMocks
    private ProfileAgentNode profileAgentNode;

    @Test
    void process_validProfileBuildsProfileResult() {
        when(obisResolver.resolve("0.0.1.0.0.255", "s1"))
                .thenReturn(Mono.just(new ObisResolution("0.0.1.0.0.255", "Clock", 8, null, null, ResolutionTier.KG)));
        when(obisResolver.resolve("1.0.1.8.0.255", "s1"))
                .thenReturn(Mono.just(new ObisResolution("1.0.1.8.0.255", "Active Energy Delivered", 3, "kWh", -3, ResolutionTier.KG)));

        WorkflowState state = WorkflowState.empty("s1", "c1", "7EA0")
                .toBuilder()
                .intent(DlmsIntent.FRAME_DECODE)
                .decodeResult(loadProfileDecodeResult())
                .build();

        WorkflowState out = profileAgentNode.process(state);

        ProfileResult profile = out.profileResult();
        assertThat(profile).isNotNull();
        assertThat(profile.profileType()).isEqualTo(ProfileType.LOAD_PROFILE);
        assertThat(profile.columns()).hasSize(2);
        assertThat(profile.rows()).hasSize(1);
        assertThat(profile.rows().getFirst().timestamp()).isEqualTo("2026-05-07 12:30:00");
        assertThat(profile.rows().getFirst().cells().get(1).displayString()).isEqualTo("12.345 kWh");
    }

    @Test
    void process_withoutDecodeResult_returnsStateUnchanged() {
        WorkflowState state = WorkflowState.empty("s2", "c2", "noop");

        WorkflowState out = profileAgentNode.process(state);

        assertThat(out).isEqualTo(state);
    }

    @Test
    void process_nonGetResponse_returnsStateUnchanged() {
        WorkflowState state = WorkflowState.empty("s3", "c3", "noop")
                .withDecodeResult(new DecodeResult(
                        new HdlcFrame(FrameType.I_FRAME, null, null, 16, 1, new byte[0], true, new byte[0]),
                        ApduType.GET_REQUEST,
                        null,
                        List.of(),
                        false,
                        "7EA0",
                        List.of()
                ));

        WorkflowState out = profileAgentNode.process(state);

        assertThat(out.profileResult()).isNull();
        assertThat(out.errors()).isEmpty();
    }

    @Test
    void process_resolverFailure_setsErrorWithoutThrowing() {
        when(obisResolver.resolve("0.0.1.0.0.255", "s4")).thenReturn(Mono.error(new IllegalStateException("boom")));

        WorkflowState state = WorkflowState.empty("s4", "c4", "7EA0")
                .toBuilder()
                .intent(DlmsIntent.PROFILE_DECODE)
                .decodeResult(new DecodeResult(
                        new HdlcFrame(FrameType.I_FRAME, null, null, 16, 1, new byte[0], true, new byte[0]),
                        ApduType.GET_RESPONSE,
                        new AxdrStructure(List.of(
                                new AxdrArray(List.of(captureObject("0.0.1.0.0.255"))),
                                new AxdrArray(List.of(new AxdrStructure(List.of(new AxdrUint32(1)))))
                        )),
                        List.of(),
                        false,
                        "7EA0",
                        List.of()
                ))
                .build();

        WorkflowState out = profileAgentNode.process(state);

        assertThat(out.profileResult()).isNull();
        assertThat(out.errors()).anySatisfy(error -> assertThat(error).contains("Profile decode failed"));
    }

    private DecodeResult loadProfileDecodeResult() {
        return new DecodeResult(
                new HdlcFrame(FrameType.I_FRAME, null, null, 16, 1, new byte[0], true, new byte[0]),
                ApduType.GET_RESPONSE,
                new AxdrStructure(List.of(
                        new AxdrArray(List.of(
                                captureObject("0.0.1.0.0.255"),
                                captureObject("1.0.1.8.0.255")
                        )),
                        new AxdrArray(List.of(
                                new AxdrStructure(List.of(
                                        new AxdrDateTime(2026, (byte) 5, (byte) 7, (byte) 4, (byte) 12, (byte) 30, (byte) 0, (byte) 0, (short) 0, (byte) 0),
                                        new AxdrUint32(12345)
                                ))
                        ))
                )),
                List.of(new ObisResolution("1.0.99.1.0.255", "Load profile", 7, null, null, ResolutionTier.KG)),
                false,
                "7EA0",
                List.of()
        );
    }

    private AxdrStructure captureObject(String obis) {
        String[] parts = obis.split("\\.");
        byte[] bytes = new byte[6];
        for (int index = 0; index < parts.length; index++) {
            bytes[index] = (byte) Integer.parseInt(parts[index]);
        }
        return new AxdrStructure(List.of(
                new AxdrUint16(3),
                new AxdrOctetString(bytes),
                new AxdrInt8((byte) 2),
                new AxdrUint16(0)
        ));
    }
}
