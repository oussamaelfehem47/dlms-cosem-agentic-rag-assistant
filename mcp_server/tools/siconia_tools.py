"""
SICONIA MCP Tool Implementations — Phase 6E
Three tools that proxy to the Java Spring Boot backend.
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
    """Register all SICONIA tools with the MCP server instance."""

    @mcp.tool(
        name="siconia.parse_xml",
        description="Sanitize and parse a SICONIA HES/DCU XML trace. Returns structured summary of sessions, events, alarms, and errors."
    )
    async def parse_xml(xml_content: str) -> dict[str, Any]:
        return await _call_backend("siconia.parse_xml", {"xml_content": xml_content})

    @mcp.tool(
        name="siconia.decode_alarm",
        description="Decode a SICONIA DCU alarm code to root cause, severity, and remediation steps."
    )
    async def decode_alarm(alarm_code: str) -> dict[str, Any]:
        return await _call_backend("siconia.decode_alarm", {"alarm_code": alarm_code})

    @mcp.tool(
        name="siconia.classify_log",
        description="Classify a multi-line DCU/HES log block by communication layer and event type. Returns structured summary."
    )
    async def classify_log(log_text: str) -> dict[str, Any]:
        return await _call_backend("siconia.classify_log", {"log_text": log_text})

    logger.info("SICONIA tools registered (proxied to Java backend): parse_xml, decode_alarm, classify_log")
