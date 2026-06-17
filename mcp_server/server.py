"""
DLMS/COSEM + SICONIA MCP Tool Server — Cleaned for Java Backend

This server proxies all tool invocations to the Java Spring Boot backend
via HTTP POST requests. No Python decoder modules are imported.

  POST /messages     — JSON-RPC 2.0 tool executor
  GET  /health       — health/tool inventory check (exempt from rate limiting)
  GET  /sse          — (no-op stub)

Port: 8001 (internal Docker network)
"""
import os
import json
import time
import asyncio
from collections import defaultdict
from loguru import logger
from typing import Any, Optional

import httpx

from fastapi import FastAPI, Request, HTTPException
from fastapi.responses import JSONResponse
from fastapi.middleware.cors import CORSMiddleware
from starlette.middleware.base import BaseHTTPMiddleware

# ── Configuration ──────────────────────────────────────────────────────────────
BACKEND_URL = os.environ.get("BACKEND_URL", "http://backend:8000")
TOOL_TIMEOUT = float(os.environ.get("MCP_TOOL_TIMEOUT", "30"))

# ── Tool definitions ───────────────────────────────────────────────────────────
# Each tool maps to a Java backend endpoint: POST /api/mcp/tools/{name}
TOOLS: list[dict[str, Any]] = [
    {
        "name": "dlms.parse_hdlc",
        "description": "Parse a raw DLMS/COSEM HDLC frame from hex string. Returns all frame fields including addresses, control field, and CRC validation.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "frame_hex": {
                    "type": "string",
                    "description": "Complete HDLC frame as hex string. Must start/end with 7E."
                }
            },
            "required": ["frame_hex"]
        }
    },
    {
        "name": "dlms.decode_apdu",
        "description": "Extract LLC header and classify APDU type from the HDLC information field hex.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "information_hex": {
                    "type": "string",
                    "description": "HDLC information field as hex (from parse_hdlc output)."
                }
            },
            "required": ["information_hex"]
        }
    },
    {
        "name": "dlms.decode_axdr",
        "description": "Recursively decode AXDR-encoded DLMS attribute data. Handles all primitive types, structures, and arrays.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "axdr_hex": {
                    "type": "string",
                    "description": "AXDR data as hex string."
                },
                "offset": {
                    "type": "integer",
                    "description": "Byte offset to start decoding from (default 0)."
                }
            },
            "required": ["axdr_hex"]
        }
    },
    {
        "name": "dlms.resolve_obis",
        "description": "Resolve an OBIS code to its Interface Class, attribute definitions, and measurement semantics using the DLMS Knowledge Graph.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "obis_str": {
                    "type": "string",
                    "description": "OBIS code in A.B.C.D.E.F notation, e.g. '1.0.1.8.0.255'"
                }
            },
            "required": ["obis_str"]
        }
    },
    {
        "name": "dlms.assemble_gbt",
        "description": "Reassemble a complete DLMS profile from all GBT blocks and decode the result.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "blocks": {
                    "type": "array",
                    "description": "List of {block_number, last_block, data_hex} dicts.",
                    "items": {
                        "type": "object",
                        "properties": {
                            "block_number": {"type": "integer"},
                            "last_block": {"type": "boolean"},
                            "data_hex": {"type": "string"}
                        }
                    }
                }
            },
            "required": ["blocks"]
        }
    },
    {
        "name": "siconia.parse_xml",
        "description": "Sanitize and parse a SICONIA HES/DCU XML trace. Returns structured summary of sessions, events, alarms, and errors.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "xml_content": {
                    "type": "string",
                    "description": "Raw SICONIA XML trace content."
                }
            },
            "required": ["xml_content"]
        }
    },
    {
        "name": "siconia.decode_alarm",
        "description": "Decode a SICONIA DCU alarm code to root cause, severity, and remediation steps.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "alarm_code": {
                    "type": "string",
                    "description": "Hex string e.g. '0x1342' or decimal string."
                }
            },
            "required": ["alarm_code"]
        }
    },
    {
        "name": "siconia.classify_log",
        "description": "Classify a multi-line DCU/HES log block by communication layer and event type. Returns structured summary.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "log_text": {
                    "type": "string",
                    "description": "Raw DCU/HES log content (multi-line)."
                }
            },
            "required": ["log_text"]
        }
    },
]

# Build lookup maps
_TOOL_MAP: dict[str, dict] = {t["name"]: t for t in TOOLS}


# ── HTTP client ────────────────────────────────────────────────────────────────

async def call_backend_tool(tool_name: str, arguments: dict) -> dict:
    """Call the Java backend's MCP tool endpoint via HTTP POST."""
    url = f"{BACKEND_URL}/api/mcp/tools/{tool_name}"
    async with httpx.AsyncClient(timeout=TOOL_TIMEOUT) as client:
        try:
            response = await client.post(url, json=arguments)
            response.raise_for_status()
            return response.json()
        except httpx.TimeoutException:
            raise TimeoutError(f"Backend tool '{tool_name}' timed out after {TOOL_TIMEOUT}s")
        except httpx.HTTPStatusError as e:
            try:
                detail = e.response.json()
            except Exception:
                detail = {"error": e.response.text}
            raise RuntimeError(f"Backend returned {e.response.status_code}: {detail}")
        except httpx.RequestError as e:
            raise RuntimeError(f"Cannot reach backend at {BACKEND_URL}: {e}")


# ── FastAPI app ───────────────────────────────────────────────────────────────

app = FastAPI(
    title="DLMS MCP Tool Server",
    description="JSON-RPC 2.0 tool server proxying to Java backend",
    version="2.0.0",
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)


# Rate limit middleware — exempts /health from rate counting so the UI always
# sees MCP connectivity status even when the rate window is saturated by tool calls.
class RateLimitMiddleware(BaseHTTPMiddleware):
    def __init__(self, app, max_requests: int = 300, window_seconds: int = 60):
        super().__init__(app)
        self.max_requests = max_requests
        self.window = window_seconds
        self._counts: dict = defaultdict(list)

    async def dispatch(self, request: Request, call_next):
        # Health checks always pass through — the frontend polls every 30s
        # and must never get rate-limited or the UI shows "MCP Offline".
        if request.url.path == "/health":
            return await call_next(request)
        ip = request.client.host if request.client else "unknown"
        now = time.time()
        self._counts[ip] = [t for t in self._counts[ip] if now - t < self.window]
        if len(self._counts[ip]) >= self.max_requests:
            return JSONResponse({"error": "Rate limit exceeded"}, status_code=429)
        self._counts[ip].append(now)
        return await call_next(request)

app.add_middleware(RateLimitMiddleware)


@app.get("/health")
async def health():
    """Health check — also probes backend connectivity."""
    backend_ok = False
    backend_error = None
    try:
        async with httpx.AsyncClient(timeout=5) as client:
            resp = await client.get(f"{BACKEND_URL}/api/mcp/health")
            if resp.status_code == 200:
                backend_ok = True
            else:
                backend_error = f"HTTP {resp.status_code}"
    except Exception as e:
        backend_error = str(e)

    return {
        "status": "ok",
        "server": "dlms-cosem-tool-server",
        "version": "2.0.0",
        "tools_registered": len(TOOLS),
        "tool_names": [t["name"] for t in TOOLS],
        "backend": {
            "url": BACKEND_URL,
            "reachable": backend_ok,
            "error": backend_error,
        }
    }


@app.get("/sse")
async def sse_stub():
    """SSE endpoint stub — this server uses HTTP/JSON-RPC not SSE transport."""
    return {"info": "This server uses POST /messages for JSON-RPC 2.0 tool calls."}


@app.get("/metrics")
async def metrics():
    """Prometheus-compatible metrics endpoint."""
    from starlette.responses import PlainTextResponse
    n_tools = len(TOOLS)
    return PlainTextResponse(
        "# HELP mcp_tools_registered Number of registered MCP tools\n"
        "# TYPE mcp_tools_registered gauge\n"
        f"mcp_tools_registered {n_tools}\n"
        "# HELP mcp_server_up MCP server availability (1 = up)\n"
        "# TYPE mcp_server_up gauge\n"
        "mcp_server_up 1\n"
    )


@app.post("/messages")
async def handle_message(request: Request):
    """JSON-RPC 2.0 endpoint — handles tools/call requests."""
    try:
        body = await request.json()
    except Exception:
        return JSONResponse(
            {"jsonrpc": "2.0", "id": None,
             "error": {"code": -32700, "message": "Parse error"}},
            status_code=400
        )

    req_id = body.get("id", 1)
    method = body.get("method", "")
    params = body.get("params", {})

    # ── tools/list ────────────────────────────────────────────────────────────
    if method == "tools/list":
        return JSONResponse({
            "jsonrpc": "2.0", "id": req_id,
            "result": {
                "tools": [
                    {
                        "name": t["name"],
                        "description": t["description"],
                        "inputSchema": t.get("inputSchema", {}),
                    }
                    for t in TOOLS
                ]
            }
        })

    # ── tools/call ────────────────────────────────────────────────────────────
    if method == "tools/call":
        tool_name = params.get("name", "")
        arguments = params.get("arguments", {})

        if tool_name not in _TOOL_MAP:
            return JSONResponse({
                "jsonrpc": "2.0", "id": req_id,
                "error": {"code": -32601, "message": f"Tool not found: {tool_name}"}
            })

        try:
            result = await asyncio.wait_for(
                call_backend_tool(tool_name, arguments),
                timeout=TOOL_TIMEOUT
            )
            return JSONResponse({
                "jsonrpc": "2.0", "id": req_id,
                "result": {
                    "content": [{"type": "text", "text": json.dumps(result)}],
                    "isError": False,
                }
            })
        except asyncio.TimeoutError:
            return JSONResponse({
                "jsonrpc": "2.0", "id": req_id,
                "error": {"code": -32000,
                          "message": f"Tool '{tool_name}' timed out after {TOOL_TIMEOUT}s"}
            })
        except TimeoutError as e:
            return JSONResponse({
                "jsonrpc": "2.0", "id": req_id,
                "error": {"code": -32000, "message": str(e)}
            })
        except RuntimeError as e:
            logger.error(f"Tool '{tool_name}' backend error: {e}")
            return JSONResponse({
                "jsonrpc": "2.0", "id": req_id,
                "error": {"code": -32000, "message": str(e)}
            })
        except Exception as e:
            logger.error(f"Tool '{tool_name}' raised: {type(e).__name__}: {e}")
            return JSONResponse({
                "jsonrpc": "2.0", "id": req_id,
                "error": {"code": -32603, "message": f"Internal error: {type(e).__name__}"}
            })

    # ── Unknown method ─────────────────────────────────────────────────────────
    return JSONResponse({
        "jsonrpc": "2.0", "id": req_id,
        "error": {"code": -32601, "message": f"Method not found: {method}"}
    })


# ── Entry point ───────────────────────────────────────────────────────────────

if __name__ == "__main__":
    import uvicorn

    host = os.environ.get("MCP_SERVER_HOST", "0.0.0.0")
    port = int(os.environ.get("MCP_SERVER_PORT", "8001"))

    logger.info(f"Starting DLMS MCP Tool Server v2.0 on {host}:{port}")
    logger.info(f"Backend URL: {BACKEND_URL}")
    logger.info(f"Tools registered: {[t['name'] for t in TOOLS]}")
    uvicorn.run("server:app", host=host, port=port, log_level="info", reload=False)
