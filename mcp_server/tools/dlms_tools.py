"""
DLMS/COSEM MCP Tool Implementations — Phase 6C
Five tools that proxy to the Java Spring Boot backend.
No Python decoder modules are imported.
"""
import os
import json
from loguru import logger
from typing import Any

import httpx
from mcp.server.fastmcp import FastMCP

BACKEND_URL = os.environ.get("BACKEND_URL", "http://backend:8000")
TOOL_TIMEOUT = float(os.environ.get("MCP_TOOL_TIMEOUT", "30"))


async def _call_backend(tool_name: str, arguments: dict) -> dict[str, Any]:
    """Proxy a tool call to the Java backend."""
    url = f"{BACKEND_URL}/api/mcp/tools/{tool_name}"
    async with httpx.AsyncClient(timeout=TOOL_TIMEOUT) as client:
        try:
            response = await client.post(url, json=arguments)
            response.raise_for_status()
            return response.json()
        except httpx.TimeoutException:
            return {"error": f"Backend tool '{tool_name}' timed out after {TOOL_TIMEOUT}s"}
        except httpx.HTTPStatusError as e:
            try:
                detail = e.response.json()
            except Exception:
                detail = {"error": e.response.text}
            return {"error": f"Backend returned {e.response.status_code}: {detail}"}
        except httpx.RequestError as e:
            return {"error": f"Cannot reach backend at {BACKEND_URL}: {e}"}


def register(mcp: FastMCP):
    """Register all DLMS tools with the MCP server instance."""

    @mcp.tool(
        name="dlms.parse_hdlc",
        description="Parse a raw DLMS/COSEM HDLC frame from hex string. Returns all frame fields including addresses, control field, and CRC validation."
    )
    async def parse_hdlc(frame_hex: str) -> dict[str, Any]:
        return await _call_backend("dlms.parse_hdlc", {"frame_hex": frame_hex})

    @mcp.tool(
        name="dlms.decode_apdu",
        description="Extract LLC header and classify APDU type from the HDLC information field hex."
    )
    async def decode_apdu(information_hex: str) -> dict[str, Any]:
        return await _call_backend("dlms.decode_apdu", {"information_hex": information_hex})

    @mcp.tool(
        name="dlms.decode_axdr",
        description="Recursively decode AXDR-encoded DLMS attribute data. Handles all primitive types, structures, and arrays."
    )
    async def decode_axdr(axdr_hex: str, offset: int = 0) -> dict[str, Any]:
        return await _call_backend("dlms.decode_axdr", {"axdr_hex": axdr_hex, "offset": offset})

    @mcp.tool(
        name="dlms.resolve_obis",
        description="Resolve an OBIS code to its Interface Class, attribute definitions, and measurement semantics using the DLMS Knowledge Graph."
    )
    async def resolve_obis(obis_str: str) -> dict[str, Any]:
        return await _call_backend("dlms.resolve_obis", {"obis_str": obis_str})

    @mcp.tool(
        name="dlms.assemble_gbt",
        description="Reassemble a complete DLMS profile from all GBT blocks and decode the result. Caller must collect all blocks before calling."
    )
    async def assemble_gbt(blocks: list) -> dict[str, Any]:
        return await _call_backend("dlms.assemble_gbt", {"blocks": blocks})

    logger.info("DLMS tools registered (proxied to Java backend): parse_hdlc, decode_apdu, decode_axdr, resolve_obis, assemble_gbt")
