package com.company.dlms.workflow;

import com.company.dlms.domain.DlmsIntent;
import com.company.dlms.domain.InputClass;
import com.company.dlms.domain.answer.ExplanationMode;
import com.company.dlms.domain.answer.ToolProvenance;
import com.company.dlms.domain.orchestration.OrchestrationMode;
import com.company.dlms.domain.orchestration.StrategyMetadata;
import com.company.dlms.domain.orchestration.ToolTraceEntry;

import java.io.Serializable;
import java.util.List;

public record ArtifactResultPayload(
        String artifactId,
        int index,
        ArtifactSource source,
        String filename,
        String rawInput,
        InputClass inputClass,
        DlmsIntent intent,
        Object decodeResult,
        Object siconiaResult,
        String explanation,
        StrategyMetadata strategyMetadata,
        OrchestrationMode orchestrationMode,
        Boolean plannerUsed,
        List<ToolTraceEntry> toolTrace,
        String plannerFallbackReason,
        ExplanationMode explanationMode,
        ToolProvenance toolProvenance
) implements Serializable {}
