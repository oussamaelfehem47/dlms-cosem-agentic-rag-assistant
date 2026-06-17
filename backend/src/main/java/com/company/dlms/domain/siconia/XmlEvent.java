package com.company.dlms.domain.siconia;

import java.io.Serializable;

public record XmlEvent(
        String type,
        String code,
        String timestamp,
        String deviceId,
        String errorCode
) implements Serializable {}
