package com.company.dlms.infrastructure.mcp;

import java.util.List;

/**
 * Constants for all MCP tool names.
 * These strings must match exactly with the MCP server tool registrations.
 */
public interface McpTools {
    String DLMS_PARSE_HDLC     = "dlms.parse_hdlc";
    String DLMS_DECODE_AXDR    = "dlms.decode_axdr";
    String DLMS_RESOLVE_OBIS   = "dlms.resolve_obis";
    String DLMS_ASSEMBLE_GBT   = "dlms.assemble_gbt";
    String SICONIA_DECODE_ALARM = "siconia.decode_alarm";
    String SICONIA_PARSE_XML   = "siconia.parse_xml";
    String SICONIA_CLASSIFY_LOG = "siconia.classify_log";
    String CONFLUENCE_SEARCH   = "confluence.search";
    String CONFLUENCE_GET_PAGE = "confluence.get_page";

    List<String> ALL = List.of(
            DLMS_PARSE_HDLC,
            DLMS_DECODE_AXDR,
            DLMS_RESOLVE_OBIS,
            DLMS_ASSEMBLE_GBT,
            SICONIA_DECODE_ALARM,
            SICONIA_PARSE_XML,
            SICONIA_CLASSIFY_LOG,
            CONFLUENCE_SEARCH,
            CONFLUENCE_GET_PAGE
    );
}
