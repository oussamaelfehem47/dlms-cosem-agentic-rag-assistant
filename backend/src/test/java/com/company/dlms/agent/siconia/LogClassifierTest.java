package com.company.dlms.agent.siconia;

import com.company.dlms.domain.siconia.IssueCategory;
import com.company.dlms.domain.siconia.LogAnalysis;
import com.company.dlms.domain.siconia.LogLayer;
import com.company.dlms.domain.siconia.LogSeverity;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LogClassifierTest {

    private final LogClassifier classifier = new LogClassifier();

    @Test
    void pure_wan_lines_dominant_layer_wan() {
        String log = """
                WAN timeout to gateway
                ip link down
                tcp retry
                """;
        LogAnalysis a = classifier.classify(log);
        assertThat(a.dominantLayer()).isEqualTo(LogLayer.WAN);
        assertThat(a.lineCount()).isEqualTo(3);
    }

    @Test
    void mixed_dlms_and_wan_tie_breaker_prefers_dlms() {
        String log = """
                WAN timeout to gateway
                DLMS AARQ rejected by meter
                """;
        LogAnalysis a = classifier.classify(log);
        assertThat(a.dominantLayer()).isEqualTo(LogLayer.DLMS);
    }

    @Test
    void error_keyword_sets_highest_severity_error() {
        String log = """
                connected
                ERROR association failed
                """;
        LogAnalysis a = classifier.classify(log);
        assertThat(a.highestSeverity()).isEqualTo(LogSeverity.ERROR);
        assertThat(a.errorLineCount()).isEqualTo(1);
    }

    @Test
    void timeout_keyword_sets_connectivity_category_present() {
        LogAnalysis a = classifier.classify("WAN timeout to gateway");
        assertThat(a.issueCategories()).contains(IssueCategory.CONNECTIVITY);
    }

    @Test
    void aarq_keyword_sets_association_category_and_dlms_layer_detected() {
        LogAnalysis a = classifier.classify("DLMS AARQ rejected");
        assertThat(a.issueCategories()).contains(IssueCategory.ASSOCIATION);
        assertThat(a.dominantLayer()).isEqualTo(LogLayer.DLMS);
    }

    @Test
    void aarq_rejected_sets_security_category_too() {
        LogAnalysis a = classifier.classify("2024-03-20 11:00:01 [DLMS] ERROR: AARQ rejected by meter");
        assertThat(a.issueCategories()).contains(IssueCategory.ASSOCIATION, IssueCategory.SECURITY);
        assertThat(a.dominantLayer()).isEqualTo(LogLayer.DLMS);
        assertThat(a.highestSeverity()).isEqualTo(LogSeverity.ERROR);
    }

    @Test
    void case_insensitive_timeout_matches_uppercase() {
        LogAnalysis lower = classifier.classify("timeout");
        LogAnalysis upper = classifier.classify("TIMEOUT");
        assertThat(upper.issueCategories()).isEqualTo(lower.issueCategories());
        assertThat(upper.highestSeverity()).isEqualTo(lower.highestSeverity());
    }

    @Test
    void security_keyword_authentication_failed_sets_security_category() {
        LogAnalysis a = classifier.classify("authentication failed");
        assertThat(a.issueCategories()).contains(IssueCategory.SECURITY);
    }

    @Test
    void crc_mismatch_sets_frame_integrity_category() {
        LogAnalysis a = classifier.classify("CRC mismatch");
        assertThat(a.issueCategories()).contains(IssueCategory.FRAME_INTEGRITY);
    }

    @Test
    void mixed_layers_plc_wins_by_frequency() {
        String log = """
                PLC g3 link degraded
                PLC retry
                HES session opened
                """;
        LogAnalysis a = classifier.classify(log);
        assertThat(a.dominantLayer()).isEqualTo(LogLayer.PLC);
    }
}
