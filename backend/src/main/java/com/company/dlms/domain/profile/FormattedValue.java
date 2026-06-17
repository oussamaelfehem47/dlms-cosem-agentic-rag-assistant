package com.company.dlms.domain.profile;

import java.io.Serializable;
import java.math.BigDecimal;

public record FormattedValue(
        Object rawValue,
        BigDecimal scaledValue,
        String displayString,
        String unit
) implements Serializable {}
