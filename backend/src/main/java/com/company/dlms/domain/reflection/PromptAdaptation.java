package com.company.dlms.domain.reflection;

public record PromptAdaptation(
    String intent,
    String additionalInstruction,
    double triggerRate,
    boolean active
) {}
