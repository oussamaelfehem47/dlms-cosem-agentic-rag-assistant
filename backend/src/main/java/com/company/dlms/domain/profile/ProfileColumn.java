package com.company.dlms.domain.profile;

import java.io.Serializable;

public record ProfileColumn(
        int index,
        String obis,
        String description,
        int classId,
        int attributeIndex,
        String unit,
        Integer scaler
) implements Serializable {}
