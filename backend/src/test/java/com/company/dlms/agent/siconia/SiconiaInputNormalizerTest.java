package com.company.dlms.agent.siconia;

import com.company.dlms.domain.InputClass;
import com.company.dlms.domain.siconia.ParseProvenance;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SiconiaInputNormalizerTest {

    private final SiconiaInputNormalizer normalizer = new SiconiaInputNormalizer();

    @Test
    void directXmlIsClassifiedAsStructuredDirect() {
        String xml = "<Event timestamp=\"2024-01-15T10:30:00Z\"><Alarm code=\"0x1342\" severity=\"critical\"/><Source device=\"DCU-01\"/></Event>";

        SiconiaInputNormalization normalization = normalizer.normalize(xml, InputClass.QUERY);

        assertThat(normalization).isNotNull();
        assertThat(normalization.normalizedInput()).isEqualTo(xml);
        assertThat(normalization.inputClass()).isEqualTo(InputClass.XML_TRACE);
        assertThat(normalization.provenance()).isEqualTo(ParseProvenance.STRUCTURED_DIRECT);
        assertThat(normalization.warnings()).isEmpty();
    }

    @Test
    void wrappedXmlIsExtractedAsHeuristicStructuredInput() {
        String wrapped = "Please inspect this SICONIA trace:\n<Event timestamp=\"2024-01-15T10:30:00Z\"><Alarm code=\"0x1342\" severity=\"critical\"/><Source device=\"DCU-01\"/></Event>\nThanks.";

        SiconiaInputNormalization normalization = normalizer.normalize(wrapped, InputClass.QUERY);

        assertThat(normalization).isNotNull();
        assertThat(normalization.normalizedInput()).startsWith("<Event ");
        assertThat(normalization.inputClass()).isEqualTo(InputClass.XML_TRACE);
        assertThat(normalization.provenance()).isEqualTo(ParseProvenance.STRUCTURED_HEURISTIC);
        assertThat(normalization.extractorNote()).contains("embedded XML");
    }

    @Test
    void proseAlarmCodeIsExtractedWithoutHardcodingSentenceTemplate() {
        SiconiaInputNormalization normalization = normalizer.normalize(
                "critical 0x1342 on concentrator DCU-01",
                InputClass.QUERY
        );

        assertThat(normalization).isNotNull();
        assertThat(normalization.normalizedInput()).isEqualTo("0x1342");
        assertThat(normalization.inputClass()).isEqualTo(InputClass.ALARM_CODE);
        assertThat(normalization.provenance()).isEqualTo(ParseProvenance.STRUCTURED_HEURISTIC);
    }

    @Test
    void wrappedLogBlockKeepsTheDominantLogPayload() {
        String wrapped = """
                Please review these SICONIA logs:
                2024-01-15 10:30:00 [WAN] ERROR Connection timeout
                2024-01-15 10:30:01 [WAN] WARN Retrying
                """;

        SiconiaInputNormalization normalization = normalizer.normalize(wrapped, InputClass.QUERY);

        assertThat(normalization).isNotNull();
        assertThat(normalization.inputClass()).isEqualTo(InputClass.LOG_BLOCK);
        assertThat(normalization.normalizedInput()).contains("[WAN] ERROR Connection timeout");
        assertThat(normalization.provenance()).isEqualTo(ParseProvenance.STRUCTURED_HEURISTIC);
    }
}
