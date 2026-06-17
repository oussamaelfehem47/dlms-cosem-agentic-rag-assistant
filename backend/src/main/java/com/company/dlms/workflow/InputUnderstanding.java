package com.company.dlms.workflow;

import com.company.dlms.agent.dlms.DlmsInputNormalization;
import com.company.dlms.agent.siconia.SiconiaInputNormalization;
import com.company.dlms.domain.DlmsIntent;
import com.company.dlms.domain.InputClass;
import com.company.dlms.domain.orchestration.OrchestrationMode;
import com.company.dlms.domain.orchestration.StrategyMetadata;

public record InputUnderstanding(
        InputClass inputClass,
        DlmsIntent intent,
        DlmsInputNormalization dlmsNormalization,
        SiconiaInputNormalization siconiaNormalization,
        StrategyMetadata strategyMetadata,
        OrchestrationMode orchestrationMode
) {}
