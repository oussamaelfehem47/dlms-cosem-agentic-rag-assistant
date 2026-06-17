package com.company.dlms.agent;

import com.company.dlms.domain.DlmsIntent;
import com.company.dlms.domain.InputClass;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RouterAgentTest {

    private final RouterAgent routerAgent = new RouterAgent();

    @Test
    void testHexFrameDetection_validHdlcFrame() {
        String input = "7EA023210313A5E5D20001000087D0D20009060000280000FF020209060000280000FF7E";
        var result = routerAgent.route(input);
        assertThat(result.inputClass()).isEqualTo(InputClass.HEX_FRAME);
        assertThat(result.intent()).isEqualTo(DlmsIntent.FRAME_DECODE);
    }

    @Test
    void testXmlTraceDetection() {
        String input = "<GetRequest><InvokeIdAndPriority>...</InvokeIdAndPriority></GetRequest>";
        var result = routerAgent.route(input);
        assertThat(result.inputClass()).isEqualTo(InputClass.XML_TRACE);
        assertThat(result.intent()).isEqualTo(DlmsIntent.SICONIA_TROUBLESHOOT);
    }

    @Test
    void testAlarmCodeDetection() {
        var result = routerAgent.route("DCU_COMM_FAIL");
        assertThat(result.inputClass()).isEqualTo(InputClass.ALARM_CODE);
        assertThat(result.intent()).isEqualTo(DlmsIntent.SICONIA_TROUBLESHOOT);
    }

    @Test
    void testHexAlarmCodeDetection_lowercase0x() {
        var result = routerAgent.route("0x1342");
        assertThat(result.inputClass()).isEqualTo(InputClass.ALARM_CODE);
        assertThat(result.intent()).isEqualTo(DlmsIntent.SICONIA_TROUBLESHOOT);
    }

    @Test
    void testHexAlarmCodeDetection_uppercase0X() {
        var result = routerAgent.route("0X0001");
        assertThat(result.inputClass()).isEqualTo(InputClass.ALARM_CODE);
        assertThat(result.intent()).isEqualTo(DlmsIntent.SICONIA_TROUBLESHOOT);
    }

    @Test
    void testHexAlarmCodeDetection_mixedCase() {
        var result = routerAgent.route("0xAbCd");
        assertThat(result.inputClass()).isEqualTo(InputClass.ALARM_CODE);
        assertThat(result.intent()).isEqualTo(DlmsIntent.SICONIA_TROUBLESHOOT);
    }

    @Test
    void testLogBlockDetection() {
        String input = """
                2026-04-21 10:00:00.000 ERROR dcu - link down
                2026-04-21 10:00:01.000 WARN  dcu - retrying
                2026-04-21 10:00:02.000 ERROR dcu - still down
                """;
        // Ensure >100 chars and multi-line
        input = input + "x".repeat(200);

        var result = routerAgent.route(input);
        assertThat(result.inputClass()).isEqualTo(InputClass.LOG_BLOCK);
        assertThat(result.intent()).isEqualTo(DlmsIntent.SICONIA_TROUBLESHOOT);
    }

    @Test
    void testDlmsTaggedLogBlockStaysSiconiaEvenWithAarqKeyword() {
        String input = "2024-03-20 11:00:01 [DLMS] ERROR: AARQ rejected by meter";
        var result = routerAgent.route(input);
        assertThat(result.inputClass()).isEqualTo(InputClass.LOG_BLOCK);
        assertThat(result.intent()).isEqualTo(DlmsIntent.SICONIA_TROUBLESHOOT);
    }

    @Test
    void testObisNotation() {
        var result = routerAgent.route("What does OBIS 1.0.1.8.0.255 mean?");
        assertThat(result.inputClass()).isEqualTo(InputClass.QUERY);
        assertThat(result.intent()).isEqualTo(DlmsIntent.OBIS_LOOKUP);
    }

    @Test
    void testFrameDecodeKeyword() {
        var result = routerAgent.route("decode this HDLC frame please");
        assertThat(result.inputClass()).isEqualTo(InputClass.QUERY);
        assertThat(result.intent()).isEqualTo(DlmsIntent.FRAME_DECODE);
    }

    @Test
    void testConceptualAarqQuestionRoutesToDocumentation() {
        var result = routerAgent.route("What is AARQ in DLMS?");
        assertThat(result.inputClass()).isEqualTo(InputClass.QUERY);
        assertThat(result.intent()).isEqualTo(DlmsIntent.DOCUMENTATION);
    }

    @Test
    void testPluralDifferenceQuestionForAarqAndAareRoutesToDocumentation() {
        var result = routerAgent.route("What are the differences between AARQ and AARE in DLMS?");
        assertThat(result.inputClass()).isEqualTo(InputClass.QUERY);
        assertThat(result.intent()).isEqualTo(DlmsIntent.DOCUMENTATION);
    }

    @Test
    void testDocumentationOnly() {
        var result = routerAgent.route("explain the green book structure");
        assertThat(result.inputClass()).isEqualTo(InputClass.QUERY);
        assertThat(result.intent()).isEqualTo(DlmsIntent.DOCUMENTATION);
    }

    @Test
    void testSecurityExplainHlsAuthentication() {
        var result = routerAgent.route("Explain HLS authentication");
        assertThat(result.inputClass()).isEqualTo(InputClass.QUERY);
        assertThat(result.intent()).isEqualTo(DlmsIntent.SECURITY_EXPLAIN);
    }

    @Test
    void testSecurityExplainSuiteOne() {
        var result = routerAgent.route("What is security suite 1?");
        assertThat(result.inputClass()).isEqualTo(InputClass.QUERY);
        assertThat(result.intent()).isEqualTo(DlmsIntent.SECURITY_EXPLAIN);
    }

    @Test
    void testSecurityExplainReplayProtection() {
        var result = routerAgent.route("How does replay protection work?");
        assertThat(result.inputClass()).isEqualTo(InputClass.QUERY);
        assertThat(result.intent()).isEqualTo(DlmsIntent.SECURITY_EXPLAIN);
    }

    @Test
    void testSecurityDiagnosisAareRejectedRoutesToSecurityExplain() {
        var result = routerAgent.route("AARE association rejected, diagnostic 6 - what does this mean?");
        assertThat(result.inputClass()).isEqualTo(InputClass.QUERY);
        assertThat(result.intent()).isEqualTo(DlmsIntent.SECURITY_EXPLAIN);
    }

    @Test
    void testUnknownFallback() {
        var result = routerAgent.route("hello");
        assertThat(result.inputClass()).isEqualTo(InputClass.QUERY);
        assertThat(result.intent()).isEqualTo(DlmsIntent.UNKNOWN);
    }

    @Test
    void testTiebreakerFrameVsApdu() {
        var result = routerAgent.route("decode this APDU from the HDLC frame");
        assertThat(result.intent()).isEqualTo(DlmsIntent.FRAME_DECODE);
    }

    @Test
    void testEmptyInput() {
        var result = routerAgent.route("");
        assertThat(result.inputClass()).isEqualTo(InputClass.QUERY);
        assertThat(result.intent()).isEqualTo(DlmsIntent.UNKNOWN);
    }

    @Test
    void testHexFrameWithSpaces() {
        var result = routerAgent.route("7E A0 23 21 03 13 A5 E5 7E");
        assertThat(result.inputClass()).isEqualTo(InputClass.HEX_FRAME);
    }

    @Test
    void testHexFrameWithoutLeading7E() {
        var result = routerAgent.route("A023210313A5E57E");
        assertThat(result.inputClass()).isEqualTo(InputClass.QUERY);
    }

    @Test
    void testDecodeThisFrameRemainsFrameDecode() {
        var result = routerAgent.route("Decode this frame");
        assertThat(result.intent()).isEqualTo(DlmsIntent.FRAME_DECODE);
    }

    @Test
    void testLocalOperationsInSiconiaRoutesToDocumentation() {
        var result = routerAgent.route("What is Local operations in SICONIA?");
        assertThat(result.inputClass()).isEqualTo(InputClass.QUERY);
        assertThat(result.intent()).isEqualTo(DlmsIntent.DOCUMENTATION);
    }

    @Test
    void uppercaseSiconiaWordInsideDocumentationQueryIsNotAlarmCode() {
        var result = routerAgent.route("SICONIA local operations");
        assertThat(result.inputClass()).isEqualTo(InputClass.QUERY);
        assertThat(result.intent()).isEqualTo(DlmsIntent.DOCUMENTATION);
    }

    @Test
    void testSiconiaHelmPageTitleRoutesToDocumentation() {
        var result = routerAgent.route("What is siconia & helm?");
        assertThat(result.inputClass()).isEqualTo(InputClass.QUERY);
        assertThat(result.intent()).isEqualTo(DlmsIntent.DOCUMENTATION);
    }

    @Test
    void testSiconiaTroubleshootingQueryStaysTroubleshooting() {
        var result = routerAgent.route("Why is the SICONIA meter unreachable after the retry loop?");
        assertThat(result.inputClass()).isEqualTo(InputClass.QUERY);
        assertThat(result.intent()).isEqualTo(DlmsIntent.SICONIA_TROUBLESHOOT);
    }

    @Test
    void testExplainHdlcFrameStructureRoutesToDocumentation() {
        var result = routerAgent.route("Explain HDLC frame structure");
        assertThat(result.inputClass()).isEqualTo(InputClass.QUERY);
        assertThat(result.intent()).isEqualTo(DlmsIntent.DOCUMENTATION);
    }

    @Test
    void testWhatIsHdlcRoutesToDocumentation() {
        var result = routerAgent.route("What is HDLC?");
        assertThat(result.inputClass()).isEqualTo(InputClass.QUERY);
        assertThat(result.intent()).isEqualTo(DlmsIntent.DOCUMENTATION);
    }

    @Test
    void testDecodeThisHdlcFrameRemainsFrameDecode() {
        var result = routerAgent.route("Decode this HDLC frame");
        assertThat(result.inputClass()).isEqualTo(InputClass.QUERY);
        assertThat(result.intent()).isEqualTo(DlmsIntent.FRAME_DECODE);
    }

    @Test
    void testDecodeWrappedHdlcFrameRemainsFrameDecode() {
        var result = routerAgent.route("Decode this HDLC frame: 7EA00A030383CD6F7E");
        assertThat(result.inputClass()).isEqualTo(InputClass.QUERY);
        assertThat(result.intent()).isEqualTo(DlmsIntent.FRAME_DECODE);
    }

    @Test
    void hintedWrappedXmlRoutesAsSiconiaTroubleshoot() {
        var result = routerAgent.route(
                "Please inspect this trace <Event timestamp=\"2024-01-15T10:30:00Z\"><Alarm code=\"0x1342\" severity=\"critical\"/></Event>",
                InputClass.XML_TRACE
        );
        assertThat(result.inputClass()).isEqualTo(InputClass.XML_TRACE);
        assertThat(result.intent()).isEqualTo(DlmsIntent.SICONIA_TROUBLESHOOT);
    }
}
