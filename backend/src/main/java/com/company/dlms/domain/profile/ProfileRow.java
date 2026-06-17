package com.company.dlms.domain.profile;

import java.io.Serializable;
import java.util.List;

public record ProfileRow(
        String timestamp,
        List<ProfileCell> cells
) implements Serializable {}
