package com.company.dlms.domain.siconia;

import java.io.Serializable;

public record AlarmDecodeResult(
        String code,
        AlarmSeverity severity,
        String rootCause,
        String remediation,
        AffectedComponent affectedComponent
) implements Serializable {}
