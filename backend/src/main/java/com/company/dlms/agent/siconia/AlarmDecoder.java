package com.company.dlms.agent.siconia;

import com.company.dlms.domain.siconia.AffectedComponent;
import com.company.dlms.domain.siconia.AlarmDecodeResult;
import com.company.dlms.domain.siconia.AlarmSeverity;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public final class AlarmDecoder {

    record AlarmDefinition(
            AlarmSeverity severity,
            String rootCause,
            String remediation,
            AffectedComponent affectedComponent
    ) {}

    private static final Map<Integer, AlarmDefinition> ALARM_MAP = buildAlarmMap();

    public List<AlarmDecodeResult> decode(String alarmCode) {
        Integer code = parseCode(alarmCode);
        if (code == null) {
            return List.of(new AlarmDecodeResult(
                    alarmCode == null ? null : alarmCode.trim(),
                    AlarmSeverity.INFO,
                    "Invalid alarm code",
                    "Provide a hex code like 0x0008 or a decimal integer",
                    AffectedComponent.UNKNOWN
            ));
        }

        AlarmDefinition exact = ALARM_MAP.get(code);
        if (exact != null) {
            return List.of(toResult(code, exact));
        }

        // Only decompose "standard" bitfields within the 16-bit alarm mask space.
        // Vendor composite codes (e.g., 0x1342) must be matched exactly; values above 0x8000
        // that are not exact matches are treated as unknown.
        if (code > 0x8000) {
            return List.of(new AlarmDecodeResult(
                    toHex(code),
                    AlarmSeverity.INFO,
                    "Unknown alarm code: " + toHex(code),
                    "Consult SICONIA alarm documentation",
                    AffectedComponent.UNKNOWN
            ));
        }

        List<AlarmDecodeResult> out = new ArrayList<>();
        for (int bit = 0; bit < 16; bit++) {
            int mask = 1 << bit;
            if ((code & mask) != 0) {
                AlarmDefinition def = ALARM_MAP.get(mask);
                if (def != null) {
                    out.add(toResult(mask, def));
                }
            }
        }
        if (out.isEmpty()) {
            return List.of(new AlarmDecodeResult(
                    toHex(code),
                    AlarmSeverity.INFO,
                    "Unknown alarm code: " + toHex(code),
                    "Consult SICONIA alarm documentation",
                    AffectedComponent.UNKNOWN
            ));
        }
        return List.copyOf(out);
    }

    private static AlarmDecodeResult toResult(int code, AlarmDefinition def) {
        return new AlarmDecodeResult(
                toHex(code),
                def.severity(),
                def.rootCause(),
                def.remediation(),
                def.affectedComponent()
        );
    }

    private static String toHex(int code) {
        return "0x" + String.format(Locale.ROOT, "%04X", code & 0xFFFF);
    }

    private static Integer parseCode(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        try {
            if (s.startsWith("0x") || s.startsWith("0X")) {
                return Integer.parseInt(s.substring(2), 16);
            }
            // decimal first, then hex fallback
            try {
                return Integer.parseInt(s, 10);
            } catch (NumberFormatException ignored) {
                return Integer.parseInt(s, 16);
            }
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Map<Integer, AlarmDefinition> buildAlarmMap() {
        Map<Integer, AlarmDefinition> m = new HashMap<>();

        // Standard bit alarms (0x0001–0x8000)
        m.put(0x0001, new AlarmDefinition(AlarmSeverity.MEDIUM, "Power failure", "Check DCU power supply and wiring", AffectedComponent.METER));
        m.put(0x0002, new AlarmDefinition(AlarmSeverity.MEDIUM, "Clock sync failure", "Verify time sync settings and NTP connectivity", AffectedComponent.HES));
        m.put(0x0004, new AlarmDefinition(AlarmSeverity.HIGH, "Memory error", "Inspect DCU logs; consider reboot or hardware replacement", AffectedComponent.METER));
        m.put(0x0008, new AlarmDefinition(AlarmSeverity.HIGH, "Communication error", "Check PLC/RF link quality", AffectedComponent.PLC));
        m.put(0x0010, new AlarmDefinition(AlarmSeverity.HIGH, "RF link failure", "Check RF network and antenna; verify RSSI", AffectedComponent.RF));
        m.put(0x0020, new AlarmDefinition(AlarmSeverity.HIGH, "WAN link failure", "Check WAN connectivity and IP configuration", AffectedComponent.WAN));
        m.put(0x0040, new AlarmDefinition(AlarmSeverity.MEDIUM, "Cover opened", "Inspect meter enclosure and tamper seals", AffectedComponent.SECURITY));
        m.put(0x0080, new AlarmDefinition(AlarmSeverity.LOW, "Firmware update required", "Schedule firmware update and verify compatibility", AffectedComponent.METER));
        m.put(0x0100, new AlarmDefinition(AlarmSeverity.MEDIUM, "Reverse energy", "Check wiring polarity and meter installation direction", AffectedComponent.METER));
        m.put(0x0200, new AlarmDefinition(AlarmSeverity.MEDIUM, "Voltage sag", "Check voltage levels, network quality", AffectedComponent.METER));
        m.put(0x0400, new AlarmDefinition(AlarmSeverity.MEDIUM, "Overvoltage", "Check voltage levels and transformer taps", AffectedComponent.METER));
        m.put(0x0800, new AlarmDefinition(AlarmSeverity.MEDIUM, "Undervoltage", "Check feeder conditions and voltage regulation", AffectedComponent.METER));
        m.put(0x1000, new AlarmDefinition(AlarmSeverity.HIGH, "Meter communication lost", "Verify meter association and local link", AffectedComponent.METER));
        m.put(0x2000, new AlarmDefinition(AlarmSeverity.HIGH, "Authentication failure", "Verify credentials and security configuration", AffectedComponent.SECURITY));
        m.put(0x4000, new AlarmDefinition(AlarmSeverity.LOW, "Time changed", "Verify time change source and apply policy", AffectedComponent.HES));
        m.put(0x8000, new AlarmDefinition(AlarmSeverity.HIGH, "Self-check failed", "Run diagnostics; consider maintenance visit", AffectedComponent.METER));

        // SICONIA-specific exact codes
        m.put(0x1342, new AlarmDefinition(AlarmSeverity.HIGH, "SICONIA DCU comm failure", "Check DCU-HES link, verify credentials", AffectedComponent.HES));
        m.put(0x2001, new AlarmDefinition(AlarmSeverity.HIGH, "SICONIA backhaul issue", "Check backhaul connectivity and routing", AffectedComponent.WAN));
        m.put(0x4002, new AlarmDefinition(AlarmSeverity.MEDIUM, "SICONIA PLC degraded", "Check PLC noise levels and link quality", AffectedComponent.PLC));
        m.put(0x8004, new AlarmDefinition(AlarmSeverity.HIGH, "Authentication failure", "Check DCU-HES link, verify credentials", AffectedComponent.SECURITY));

        // Additional exact codes to reach 24 total entries (reserved vendor codes)
        m.put(0x0102, new AlarmDefinition(AlarmSeverity.MEDIUM, "Clock drift detected", "Verify time source and drift thresholds", AffectedComponent.HES));
        m.put(0x0204, new AlarmDefinition(AlarmSeverity.HIGH, "Storage subsystem fault", "Check flash health; consider replacing DCU", AffectedComponent.METER));
        m.put(0x0408, new AlarmDefinition(AlarmSeverity.HIGH, "Network interface fault", "Verify network interface status and cabling", AffectedComponent.WAN));
        m.put(0x0810, new AlarmDefinition(AlarmSeverity.HIGH, "RF module fault", "Check RF module; verify firmware and hardware", AffectedComponent.RF));

        return Collections.unmodifiableMap(m);
    }
}
