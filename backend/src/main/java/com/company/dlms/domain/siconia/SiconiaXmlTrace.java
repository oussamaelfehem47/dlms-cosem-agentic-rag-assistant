package com.company.dlms.domain.siconia;

import java.io.Serializable;
import java.util.List;

public record SiconiaXmlTrace(
        List<XmlEvent> events,
        List<String> parseErrors,
        String rawXml
) implements Serializable {}
