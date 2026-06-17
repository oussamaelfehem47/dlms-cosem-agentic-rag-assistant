package com.company.dlms.agent.siconia;

import com.company.dlms.domain.siconia.AffectedComponent;
import com.company.dlms.domain.siconia.AlarmDecodeResult;
import com.company.dlms.domain.siconia.AlarmSeverity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AlarmDecoderTest {

    private final AlarmDecoder decoder = new AlarmDecoder();

    @Test
    void decodes_0x0001_powerFailure() {
        assertSingle(decoder.decode("0x0001"), "0x0001", AlarmSeverity.MEDIUM, AffectedComponent.METER, "Power failure");
    }

    @Test
    void decodes_0x0002_clockSyncFailure() {
        assertSingle(decoder.decode("0x0002"), "0x0002", AlarmSeverity.MEDIUM, AffectedComponent.HES, "Clock sync failure");
    }

    @Test
    void decodes_0x0004_memoryError() {
        assertSingle(decoder.decode("0x0004"), "0x0004", AlarmSeverity.HIGH, AffectedComponent.METER, "Memory error");
    }

    @Test
    void decodes_0x0008_communicationError() {
        assertSingle(decoder.decode("0x0008"), "0x0008", AlarmSeverity.HIGH, AffectedComponent.PLC, "Communication error");
    }

    @Test
    void decodes_0x0010_rfLinkFailure() {
        assertSingle(decoder.decode("0x0010"), "0x0010", AlarmSeverity.HIGH, AffectedComponent.RF, "RF link failure");
    }

    @Test
    void decodes_0x0020_wanLinkFailure() {
        assertSingle(decoder.decode("0x0020"), "0x0020", AlarmSeverity.HIGH, AffectedComponent.WAN, "WAN link failure");
    }

    @Test
    void decodes_0x0040_coverOpened() {
        assertSingle(decoder.decode("0x0040"), "0x0040", AlarmSeverity.MEDIUM, AffectedComponent.SECURITY, "Cover opened");
    }

    @Test
    void decodes_0x0080_firmwareUpdateRequired() {
        assertSingle(decoder.decode("0x0080"), "0x0080", AlarmSeverity.LOW, AffectedComponent.METER, "Firmware update required");
    }

    @Test
    void decodes_0x0100_reverseEnergy() {
        assertSingle(decoder.decode("0x0100"), "0x0100", AlarmSeverity.MEDIUM, AffectedComponent.METER, "Reverse energy");
    }

    @Test
    void decodes_0x0200_voltageSag() {
        assertSingle(decoder.decode("0x0200"), "0x0200", AlarmSeverity.MEDIUM, AffectedComponent.METER, "Voltage sag");
    }

    @Test
    void decodes_0x0400_overvoltage() {
        assertSingle(decoder.decode("0x0400"), "0x0400", AlarmSeverity.MEDIUM, AffectedComponent.METER, "Overvoltage");
    }

    @Test
    void decodes_0x0800_undervoltage() {
        assertSingle(decoder.decode("0x0800"), "0x0800", AlarmSeverity.MEDIUM, AffectedComponent.METER, "Undervoltage");
    }

    @Test
    void decodes_0x1000_meterCommunicationLost() {
        assertSingle(decoder.decode("0x1000"), "0x1000", AlarmSeverity.HIGH, AffectedComponent.METER, "Meter communication lost");
    }

    @Test
    void decodes_0x2000_authenticationFailure() {
        assertSingle(decoder.decode("0x2000"), "0x2000", AlarmSeverity.HIGH, AffectedComponent.SECURITY, "Authentication failure");
    }

    @Test
    void decodes_0x4000_timeChanged() {
        assertSingle(decoder.decode("0x4000"), "0x4000", AlarmSeverity.LOW, AffectedComponent.HES, "Time changed");
    }

    @Test
    void decodes_0x8000_selfCheckFailed() {
        assertSingle(decoder.decode("0x8000"), "0x8000", AlarmSeverity.HIGH, AffectedComponent.METER, "Self-check failed");
    }

    @Test
    void decodes_0x1342_siconiaDcuCommFailure() {
        assertSingle(decoder.decode("0x1342"), "0x1342", AlarmSeverity.HIGH, AffectedComponent.HES, "SICONIA DCU comm failure");
    }

    @Test
    void decodes_0x2001_siconiaBackhaulIssue() {
        assertSingle(decoder.decode("0x2001"), "0x2001", AlarmSeverity.HIGH, AffectedComponent.WAN, "SICONIA backhaul issue");
    }

    @Test
    void decodes_0x4002_siconiaPlcDegraded() {
        assertSingle(decoder.decode("0x4002"), "0x4002", AlarmSeverity.MEDIUM, AffectedComponent.PLC, "SICONIA PLC degraded");
    }

    @Test
    void decodes_0x8004_siconiaAuthenticationFailure() {
        assertSingle(decoder.decode("0x8004"), "0x8004", AlarmSeverity.HIGH, AffectedComponent.SECURITY, "Authentication failure");
    }

    @Test
    void decodes_0x0102_clockDriftDetected() {
        assertSingle(decoder.decode("0x0102"), "0x0102", AlarmSeverity.MEDIUM, AffectedComponent.HES, "Clock drift detected");
    }

    @Test
    void decodes_0x0204_storageSubsystemFault() {
        assertSingle(decoder.decode("0x0204"), "0x0204", AlarmSeverity.HIGH, AffectedComponent.METER, "Storage subsystem fault");
    }

    @Test
    void decodes_0x0408_networkInterfaceFault() {
        assertSingle(decoder.decode("0x0408"), "0x0408", AlarmSeverity.HIGH, AffectedComponent.WAN, "Network interface fault");
    }

    @Test
    void decodes_0x0810_rfModuleFault() {
        assertSingle(decoder.decode("0x0810"), "0x0810", AlarmSeverity.HIGH, AffectedComponent.RF, "RF module fault");
    }

    @Test
    void accepts_decimal_input_for_known_code_512_equals_0x0200() {
        assertSingle(decoder.decode("512"), "0x0200", AlarmSeverity.MEDIUM, AffectedComponent.METER, "Voltage sag");
    }

    @Test
    void bitfield_0x0003_yields_two_results_powerAndClock() {
        List<AlarmDecodeResult> results = decoder.decode("0x0003");
        assertThat(results).hasSize(2);
        assertThat(results).extracting(AlarmDecodeResult::code).containsExactlyInAnyOrder("0x0001", "0x0002");
    }

    @Test
    void bitfield_0x0142_yields_clock_cover_reverseEnergy() {
        List<AlarmDecodeResult> results = decoder.decode("0x0142");
        assertThat(results).hasSize(3);
        assertThat(results).extracting(AlarmDecodeResult::code)
                .containsExactlyInAnyOrder("0x0002", "0x0040", "0x0100");
    }

    @Test
    void unknown_0x9999_returns_info_unknown_component() {
        List<AlarmDecodeResult> results = decoder.decode("0x9999");
        assertThat(results).hasSize(1);
        assertThat(results.getFirst().severity()).isEqualTo(AlarmSeverity.INFO);
        assertThat(results.getFirst().affectedComponent()).isEqualTo(AffectedComponent.UNKNOWN);
        assertThat(results.getFirst().rootCause()).contains("Unknown alarm code");
    }

    private static void assertSingle(List<AlarmDecodeResult> results,
                                     String expectedCode,
                                     AlarmSeverity expectedSeverity,
                                     AffectedComponent expectedComponent,
                                     String expectedRootCause) {
        assertThat(results).hasSize(1);
        AlarmDecodeResult r = results.getFirst();
        assertThat(r.code()).isEqualTo(expectedCode);
        assertThat(r.severity()).isEqualTo(expectedSeverity);
        assertThat(r.affectedComponent()).isEqualTo(expectedComponent);
        assertThat(r.rootCause()).contains(expectedRootCause);
        assertThat(r.remediation()).isNotBlank();
    }
}
