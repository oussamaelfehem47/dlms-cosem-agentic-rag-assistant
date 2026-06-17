package com.company.dlms.domain.profile;

import java.io.Serializable;
import java.util.List;

public record ProfileResult(
        ProfileType profileType,
        List<ProfileColumn> columns,
        List<ProfileRow> rows,
        int captureObjectCount,
        int entryCount,
        String obis
) implements Serializable {}
