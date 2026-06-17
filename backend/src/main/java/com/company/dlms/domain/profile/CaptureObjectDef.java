package com.company.dlms.domain.profile;

import java.io.Serializable;

public record CaptureObjectDef(
        int classId,
        String logicalName,
        int attributeIndex,
        int dataIndex
) implements Serializable {}
