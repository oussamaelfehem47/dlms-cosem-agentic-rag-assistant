package com.company.dlms.domain.decoder;

import java.io.Serializable;

public record ObisResolution(
        String obis,
        String description,
        Integer ic,
        String unit,
        Integer scaler,
        ResolutionTier tierUsed
) implements Serializable {}
