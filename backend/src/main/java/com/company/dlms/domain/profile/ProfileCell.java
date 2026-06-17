package com.company.dlms.domain.profile;

import java.io.Serializable;
import java.math.BigDecimal;

public record ProfileCell(
        int columnIndex,
        Object rawValue,
        BigDecimal scaledValue,
        String displayString,
        String unit
) implements Serializable {}
