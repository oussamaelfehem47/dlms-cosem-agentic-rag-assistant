package com.company.dlms.agent.siconia;

import com.company.dlms.domain.siconia.SiconiaXmlTrace;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class XmlTraceParserTest {

    private final XmlTraceParser parser = new XmlTraceParser();

    @Test
    void valid_xml_with_3_events_parses_3_events() {
        String xml = """
                <trace>
                  <event type="SESSION_START" code="S1" timestamp="2026-01-01T00:00:00Z" deviceId="DCU-1" errorCode="0"/>
                  <event type="ALARM" code="0x0008" timestamp="2026-01-01T00:01:00Z" deviceId="DCU-1" errorCode="E12"/>
                  <event type="SESSION_END" code="S9" timestamp="2026-01-01T00:02:00Z" deviceId="DCU-1" errorCode="0"/>
                </trace>
                """;

        SiconiaXmlTrace trace = parser.parse(xml);
        assertThat(trace.events()).hasSize(3);
        assertThat(trace.parseErrors()).isEmpty();
        assertThat(trace.events().getFirst().type()).isEqualTo("SESSION_START");
        assertThat(trace.events().get(1).code()).isEqualTo("0x0008");
    }

    @Test
    void xml_with_doctype_adds_parse_error_and_does_not_throw() {
        String xml = """
                <!DOCTYPE foo [ <!ENTITY xxe SYSTEM "file:///etc/passwd"> ]>
                <trace>
                  <event type="ALARM" code="0x0001" timestamp="t" deviceId="d" errorCode="e"/>
                </trace>
                """;

        SiconiaXmlTrace trace = parser.parse(xml);
        assertThat(trace).isNotNull();
        assertThat(trace.parseErrors()).isNotEmpty();
    }

    @Test
    void malformed_mid_document_returns_partial_events_and_parse_errors() {
        String xml = """
                <trace>
                  <event type="ALARM" code="0x0001" timestamp="t1" deviceId="d1" errorCode="e1"/>
                  <event type="ALARM" code="0x0002" timestamp="t2" deviceId="d1" errorCode="e2">
                </trace>
                """;

        SiconiaXmlTrace trace = parser.parse(xml);
        assertThat(trace.events()).isNotEmpty();
        assertThat(trace.parseErrors()).isNotEmpty();
    }

    @Test
    void empty_xml_returns_empty_list_no_exception() {
        SiconiaXmlTrace trace = parser.parse("");
        assertThat(trace.events()).isEmpty();
        assertThat(trace.parseErrors()).isNotEmpty();
    }

    @Test
    void xml_with_2_alarm_codes_in_attributes_captures_both_events() {
        String xml = """
                <trace>
                  <event type="ALARM" code="0x0008" timestamp="t" deviceId="d" errorCode="e"/>
                  <event type="ALARM" code="0x0200" timestamp="t" deviceId="d" errorCode="e"/>
                </trace>
                """;
        SiconiaXmlTrace trace = parser.parse(xml);
        assertThat(trace.events()).hasSize(2);
        assertThat(trace.events().getFirst().code()).isEqualTo("0x0008");
        assertThat(trace.events().get(1).code()).isEqualTo("0x0200");
    }

    @Test
    void raw_xml_field_equals_original_input() {
        String xml = "<trace></trace>";
        SiconiaXmlTrace trace = parser.parse(xml);
        assertThat(trace.rawXml()).isEqualTo(xml);
    }

    @Test
    void nestedEventAlarmAndSourceElementsAreNormalizedIntoStructuredEventRows() {
        String xml = """
                <Event timestamp="2024-01-15T10:30:00Z">
                  <Alarm code="0x1342" severity="critical"/>
                  <Source device="DCU-01"/>
                </Event>
                """;

        SiconiaXmlTrace trace = parser.parse(xml);

        assertThat(trace.events()).hasSize(1);
        assertThat(trace.events().getFirst().timestamp()).isEqualTo("2024-01-15T10:30:00Z");
        assertThat(trace.events().getFirst().code()).isEqualTo("0x1342");
        assertThat(trace.events().getFirst().deviceId()).isEqualTo("DCU-01");
        assertThat(trace.events().getFirst().errorCode()).isEqualTo("critical");
        assertThat(trace.parseErrors()).isEmpty();
    }

    @Test
    void caseAndAliasVariationsStillProduceStructuredEvents() {
        String xml = """
                <event ts="2024-01-15T10:30:00Z" type="ALARM">
                  <device device_id="DCU-02"/>
                  <severity value="HIGH"/>
                  <alarm code="DCU_COMM_FAIL"/>
                </event>
                """;

        SiconiaXmlTrace trace = parser.parse(xml);

        assertThat(trace.events()).hasSize(1);
        assertThat(trace.events().getFirst().type()).isEqualTo("ALARM");
        assertThat(trace.events().getFirst().code()).isEqualTo("DCU_COMM_FAIL");
        assertThat(trace.events().getFirst().deviceId()).isEqualTo("DCU-02");
        assertThat(trace.events().getFirst().errorCode()).isEqualTo("HIGH");
    }
}
