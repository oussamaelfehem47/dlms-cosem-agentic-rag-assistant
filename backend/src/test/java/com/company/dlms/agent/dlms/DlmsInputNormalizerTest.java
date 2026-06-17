package com.company.dlms.agent.dlms;

import com.company.dlms.domain.InputClass;
import com.company.dlms.domain.decoder.DlmsNormalizedKind;
import com.company.dlms.domain.siconia.ParseProvenance;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DlmsInputNormalizerTest {

    private final DlmsInputNormalizer normalizer = new DlmsInputNormalizer();

    @Test
    void directFrameHexIsClassifiedAsStructuredDirect() {
        String frame = "7EA00A030383CD6F7E";

        DlmsInputNormalization normalization = normalizer.normalize(frame, InputClass.HEX_FRAME);

        assertThat(normalization).isNotNull();
        assertThat(normalization.normalizedInput()).isEqualTo(frame);
        assertThat(normalization.kind()).isEqualTo(DlmsNormalizedKind.FRAME_HEX);
        assertThat(normalization.provenance()).isEqualTo(ParseProvenance.STRUCTURED_DIRECT);
        assertThat(normalization.ambiguous()).isFalse();
    }

    @Test
    void wrappedFrameHexIsRecoveredAsHeuristicStructuredInput() {
        DlmsInputNormalization normalization = normalizer.normalize(
                "Decode this HDLC frame: 7EA00A030383CD6F7E",
                InputClass.QUERY
        );

        assertThat(normalization).isNotNull();
        assertThat(normalization.normalizedInput()).isEqualTo("7EA00A030383CD6F7E");
        assertThat(normalization.kind()).isEqualTo(DlmsNormalizedKind.FRAME_HEX);
        assertThat(normalization.provenance()).isEqualTo(ParseProvenance.STRUCTURED_HEURISTIC);
        assertThat(normalization.extractorNote()).contains("embedded HDLC frame");
    }

    @Test
    void wrappedObisQueryIsRecoveredWithoutGenericRetrievalGuessing() {
        DlmsInputNormalization normalization = normalizer.normalize(
                "What is OBIS 1.0.1.8.0.255?",
                InputClass.QUERY
        );

        assertThat(normalization).isNotNull();
        assertThat(normalization.normalizedInput()).isEqualTo("1.0.1.8.0.255");
        assertThat(normalization.kind()).isEqualTo(DlmsNormalizedKind.OBIS_QUERY);
        assertThat(normalization.provenance()).isEqualTo(ParseProvenance.STRUCTURED_HEURISTIC);
    }

    @Test
    void wrappedApduHexIsRecoveredAsDeterministicPayload() {
        DlmsInputNormalization normalization = normalizer.normalize(
                "Decode APDU C4020109060100010800FF",
                InputClass.QUERY
        );

        assertThat(normalization).isNotNull();
        assertThat(normalization.normalizedInput()).isEqualTo("C4020109060100010800FF");
        assertThat(normalization.kind()).isEqualTo(DlmsNormalizedKind.APDU_HEX);
        assertThat(normalization.provenance()).isEqualTo(ParseProvenance.STRUCTURED_HEURISTIC);
    }

    @Test
    void wrappedAxdrHexIsRecoveredAsDeterministicPayload() {
        DlmsInputNormalization normalization = normalizer.normalize(
                "Explain AXDR payload 020109060100010800FF",
                InputClass.QUERY
        );

        assertThat(normalization).isNotNull();
        assertThat(normalization.normalizedInput()).isEqualTo("020109060100010800FF");
        assertThat(normalization.kind()).isEqualTo(DlmsNormalizedKind.AXDR_HEX);
        assertThat(normalization.provenance()).isEqualTo(ParseProvenance.STRUCTURED_HEURISTIC);
    }

    @Test
    void bareHexPayloadThatContains07e8IsNotForcedIntoFrameDecode() {
        DlmsInputNormalization normalization = normalizer.normalize(
                "1907E80416010E1E0000003C00",
                InputClass.HEX_FRAME
        );

        assertThat(normalization).isNotNull();
        assertThat(normalization.normalizedInput()).isEqualTo("1907E80416010E1E0000003C00");
        assertThat(normalization.kind()).isEqualTo(DlmsNormalizedKind.AXDR_HEX);
        assertThat(normalization.provenance()).isEqualTo(ParseProvenance.STRUCTURED_DIRECT);
        assertThat(normalization.ambiguous()).isFalse();
    }

    @Test
    void genericPayloadLeadInStillRecoversDeterministicAxdrCandidate() {
        DlmsInputNormalization normalization = normalizer.normalize(
                "payload 1907E80416010E1E0000003C00",
                InputClass.QUERY
        );

        assertThat(normalization).isNotNull();
        assertThat(normalization.normalizedInput()).isEqualTo("1907E80416010E1E0000003C00");
        assertThat(normalization.kind()).isEqualTo(DlmsNormalizedKind.AXDR_HEX);
        assertThat(normalization.provenance()).isEqualTo(ParseProvenance.STRUCTURED_HEURISTIC);
    }

    @Test
    void explicitShortAxdrPayloadsStayOnTheDeterministicAxdrPath() {
        DlmsInputNormalization axdrNull = normalizer.normalize("Explain AXDR payload 00", InputClass.QUERY);
        DlmsInputNormalization axdrBoolean = normalizer.normalize("Explain AXDR payload 0301", InputClass.QUERY);
        DlmsInputNormalization axdrUnknownTag = normalizer.normalize("Explain AXDR payload 99010203", InputClass.QUERY);
        DlmsInputNormalization axdrTruncated = normalizer.normalize("Explain AXDR payload 05FFFF", InputClass.QUERY);

        assertThat(axdrNull).isNotNull();
        assertThat(axdrNull.normalizedInput()).isEqualTo("00");
        assertThat(axdrNull.kind()).isEqualTo(DlmsNormalizedKind.AXDR_HEX);

        assertThat(axdrBoolean).isNotNull();
        assertThat(axdrBoolean.normalizedInput()).isEqualTo("0301");
        assertThat(axdrBoolean.kind()).isEqualTo(DlmsNormalizedKind.AXDR_HEX);

        assertThat(axdrUnknownTag).isNotNull();
        assertThat(axdrUnknownTag.normalizedInput()).isEqualTo("99010203");
        assertThat(axdrUnknownTag.kind()).isEqualTo(DlmsNormalizedKind.AXDR_HEX);

        assertThat(axdrTruncated).isNotNull();
        assertThat(axdrTruncated.normalizedInput()).isEqualTo("05FFFF");
        assertThat(axdrTruncated.kind()).isEqualTo(DlmsNormalizedKind.AXDR_HEX);
    }

    @Test
    void explicitAxdrPromptOverridesApduAutoClassification() {
        DlmsInputNormalization axdrNormalization = normalizer.normalize(
                "Explain AXDR payload 0102030111FF",
                InputClass.QUERY
        );
        DlmsInputNormalization apduNormalization = normalizer.normalize(
                "Decode APDU 0102030111FF",
                InputClass.QUERY
        );

        assertThat(axdrNormalization).isNotNull();
        assertThat(axdrNormalization.normalizedInput()).isEqualTo("0102030111FF");
        assertThat(axdrNormalization.kind()).isEqualTo(DlmsNormalizedKind.AXDR_HEX);

        assertThat(apduNormalization).isNotNull();
        assertThat(apduNormalization.normalizedInput()).isEqualTo("0102030111FF");
        assertThat(apduNormalization.kind()).isEqualTo(DlmsNormalizedKind.APDU_HEX);
    }

    @Test
    void multipleCompetingFramesReturnAmbiguousNormalizationInsteadOfGuessing() {
        DlmsInputNormalization normalization = normalizer.normalize(
                "Decode one of these frames: 7EA00A030383CD6F7E and 7EA00A0101934D7E",
                InputClass.QUERY
        );

        assertThat(normalization).isNotNull();
        assertThat(normalization.kind()).isEqualTo(DlmsNormalizedKind.FRAME_HEX);
        assertThat(normalization.normalizedInput()).isNull();
        assertThat(normalization.ambiguous()).isTrue();
        assertThat(normalization.warnings()).isNotEmpty();
    }
}
