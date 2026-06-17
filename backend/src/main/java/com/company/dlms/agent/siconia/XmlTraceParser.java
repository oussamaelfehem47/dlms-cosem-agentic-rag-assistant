package com.company.dlms.agent.siconia;

import com.company.dlms.domain.siconia.SiconiaXmlTrace;
import com.company.dlms.domain.siconia.XmlEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

@Component
public class XmlTraceParser {

    private static final Logger log = LoggerFactory.getLogger(XmlTraceParser.class);

    private final XMLInputFactory factory = XMLInputFactory.newInstance();

    public XmlTraceParser() {
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
    }

    public SiconiaXmlTrace parse(String xml) {
        List<XmlEvent> events = new ArrayList<>();
        List<String> parseErrors = new ArrayList<>();

        if (xml != null && (xml.contains("<!DOCTYPE") || xml.contains("<!ENTITY"))) {
            parseErrors.add("Input contains DOCTYPE or ENTITY declaration");
        }

        try {
            XMLStreamReader reader = factory.createXMLStreamReader(new StringReader(xml));
            EventBuilder current = null;
            int eventDepth = -1;
            int depth = 0;

            while (reader.hasNext()) {
                int eventType = reader.next();
                if (eventType == XMLStreamConstants.START_ELEMENT) {
                    depth++;
                    String localName = reader.getLocalName();
                    if ("event".equalsIgnoreCase(localName)) {
                        current = new EventBuilder();
                        eventDepth = depth;
                        current.type = firstPresent(attrIgnoreCase(reader, "type"), current.type);
                        current.code = firstPresent(attrIgnoreCase(reader, "code"), current.code);
                        current.timestamp = firstPresent(
                                attrIgnoreCase(reader, "timestamp"),
                                attrIgnoreCase(reader, "ts"),
                                attrIgnoreCase(reader, "time"),
                                current.timestamp
                        );
                        current.deviceId = firstPresent(
                                attrIgnoreCase(reader, "deviceid"),
                                attrIgnoreCase(reader, "device_id"),
                                attrIgnoreCase(reader, "device"),
                                attrIgnoreCase(reader, "source"),
                                current.deviceId
                        );
                        current.errorCode = firstPresent(
                                attrIgnoreCase(reader, "errorcode"),
                                attrIgnoreCase(reader, "severity"),
                                attrIgnoreCase(reader, "level"),
                                current.errorCode
                        );
                    } else if (current != null) {
                        applyNestedElement(current, localName, reader);
                    }
                } else if (eventType == XMLStreamConstants.END_ELEMENT) {
                    if (current != null && eventDepth == depth) {
                        if (current.hasMeaningfulData()) {
                            events.add(current.build());
                        }
                        current = null;
                        eventDepth = -1;
                    }
                    depth = Math.max(0, depth - 1);
                }
            }
            reader.close();
        } catch (XMLStreamException e) {
            log.debug("XML trace parse failed", e);
            parseErrors.add(safeMsg(e));
        }

        return new SiconiaXmlTrace(events, parseErrors, xml);
    }

    private void applyNestedElement(EventBuilder current, String localName, XMLStreamReader reader) {
        String lower = localName == null ? "" : localName.toLowerCase();
        if ("alarm".equals(lower)) {
            current.type = firstPresent(current.type, "ALARM");
            current.code = firstPresent(attrIgnoreCase(reader, "code"), current.code);
            current.errorCode = firstPresent(
                    attrIgnoreCase(reader, "severity"),
                    attrIgnoreCase(reader, "level"),
                    attrIgnoreCase(reader, "errorcode"),
                    current.errorCode
            );
        } else if ("source".equals(lower) || "device".equals(lower)) {
            current.deviceId = firstPresent(
                    attrIgnoreCase(reader, "device"),
                    attrIgnoreCase(reader, "deviceid"),
                    attrIgnoreCase(reader, "device_id"),
                    attrIgnoreCase(reader, "source"),
                    current.deviceId
            );
        } else if ("severity".equals(lower)) {
            current.errorCode = firstPresent(
                    attrIgnoreCase(reader, "value"),
                    attrIgnoreCase(reader, "severity"),
                    attrIgnoreCase(reader, "level"),
                    current.errorCode
            );
        } else if ("type".equals(lower)) {
            current.type = firstPresent(
                    attrIgnoreCase(reader, "value"),
                    attrIgnoreCase(reader, "name"),
                    current.type
            );
        }

        current.code = firstPresent(attrIgnoreCase(reader, "code"), current.code);
        current.timestamp = firstPresent(
                attrIgnoreCase(reader, "timestamp"),
                attrIgnoreCase(reader, "ts"),
                attrIgnoreCase(reader, "time"),
                current.timestamp
        );
        current.deviceId = firstPresent(
                attrIgnoreCase(reader, "deviceid"),
                attrIgnoreCase(reader, "device_id"),
                attrIgnoreCase(reader, "device"),
                current.deviceId
        );
        current.errorCode = firstPresent(
                attrIgnoreCase(reader, "errorcode"),
                attrIgnoreCase(reader, "severity"),
                attrIgnoreCase(reader, "level"),
                current.errorCode
        );
        current.type = firstPresent(attrIgnoreCase(reader, "type"), current.type);
    }

    private static String attrIgnoreCase(XMLStreamReader reader, String name) {
        for (int i = 0; i < reader.getAttributeCount(); i++) {
            if (name.equalsIgnoreCase(reader.getAttributeLocalName(i))) {
                return reader.getAttributeValue(i);
            }
        }
        return null;
    }

    private static String firstPresent(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String safeMsg(Throwable t) {
        String msg = t.getMessage();
        return msg != null ? msg : t.getClass().getSimpleName();
    }

    private static final class EventBuilder {
        private String type;
        private String code;
        private String timestamp;
        private String deviceId;
        private String errorCode;

        private boolean hasMeaningfulData() {
            return type != null || code != null || timestamp != null || deviceId != null || errorCode != null;
        }

        private XmlEvent build() {
            return new XmlEvent(type, code, timestamp, deviceId, errorCode);
        }
    }
}
