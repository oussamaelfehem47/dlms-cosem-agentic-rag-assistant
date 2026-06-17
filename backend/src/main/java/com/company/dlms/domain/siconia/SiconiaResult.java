package com.company.dlms.domain.siconia;

import java.io.Serializable;
import java.util.List;

public record SiconiaResult(
        SiconiaXmlTrace xmlTrace,
        List<AlarmDecodeResult> alarmResults,
        LogAnalysis logAnalysis,
        String inputClass,
        SiconiaProcessingMetadata processingMetadata
) implements Serializable {
    public SiconiaResult(
            SiconiaXmlTrace xmlTrace,
            List<AlarmDecodeResult> alarmResults,
            LogAnalysis logAnalysis,
            String inputClass
    ) {
        this(xmlTrace, alarmResults, logAnalysis, inputClass, null);
    }
}
