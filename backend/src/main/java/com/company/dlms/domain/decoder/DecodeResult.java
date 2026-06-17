package com.company.dlms.domain.decoder;

import java.io.Serializable;
import java.util.List;

public record DecodeResult(
        HdlcFrame hdlcFrame,
        ApduType apduType,
        AxdrValue axdrTree,
        List<ObisResolution> obisResolutions,
        boolean gbtPartial,
        String rawHex,
        List<String> parseErrors,
        DlmsProcessingMetadata processingMetadata
) implements Serializable {
    public DecodeResult(
            HdlcFrame hdlcFrame,
            ApduType apduType,
            AxdrValue axdrTree,
            List<ObisResolution> obisResolutions,
            boolean gbtPartial,
            String rawHex,
            List<String> parseErrors
    ) {
        this(hdlcFrame, apduType, axdrTree, obisResolutions, gbtPartial, rawHex, parseErrors, null);
    }
}
