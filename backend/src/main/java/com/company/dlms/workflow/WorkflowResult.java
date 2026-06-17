package com.company.dlms.workflow;

import com.company.dlms.domain.DlmsIntent;
import com.company.dlms.domain.InputClass;

import java.util.List;

public record WorkflowResult(
        String sessionId,
        DlmsIntent intent,
        InputClass inputClass,
        String explanation,
        boolean outputFiltered,
        boolean mcpUsed,
        List<String> anomalies,
        List<String> errors
) {}
