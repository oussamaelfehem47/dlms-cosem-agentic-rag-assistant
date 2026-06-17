# DLMS/COSEM Hybrid Agentic RAG Assistant

An offline assistant for DLMS/COSEM protocol analysis and SICONIA HES/DCU troubleshooting. The platform combines deterministic Java decoding, hybrid retrieval, session memory, anomaly detection, and bounded LLM planning so engineers can investigate frames, payloads, alarms, XML traces, and support documentation inside controlled environments.

## Current Snapshot

- 5-container Docker deployment: `dlms-ui`, `dlms-nginx`, `dlms-backend`, `dlms-postgres`, `dlms-mcp-server`
- Ollama runs on the host machine, not inside Docker
- Default local chat model: `qwen2.5:3b`
- Hybrid orchestration with 4 modes:
  - `DETERMINISTIC_FAST_PATH`
  - `STRUCTURED_PLUS_AGENTIC`
  - `NATURAL_LANGUAGE_AGENTIC`
  - `AMBIGUOUS_SAFE_FALLBACK`
- Session follow-ups use `SESSION_RECALL` as a first-class strategy
- Knowledge Graph: `51 nodes`, `28 edges`
- Retrieval corpus: `8,659 DLMS` chunks + `2,516 Confluence` chunks
- Hybrid retrieval weighting: `0.7 x vector + 0.3 x BM25`
- Anomaly detection includes 8 rules for replay, security, association, and PDU integrity checks

## What The Assistant Can Do

- Decode HDLC, APDU, AXDR, and OBIS content with deterministic Java logic
- Analyze SICONIA alarm codes, XML traces, and operational logs
- Answer DLMS documentation and security questions with grounded retrieval
- Persist session memory for follow-up questions such as previous frame type or last OBIS
- Show a safe `How I answered` trace with orchestration mode, strategy, tools used, and trust level
- Support RBAC, conversation persistence, exports, admin monitoring, and audit logging

## Why This Is A Hybrid Agentic RAG System

The system is intentionally split into two layers:

- Deterministic protocol truth:
  HDLC parsing, APDU classification, AXDR decoding, OBIS resolution, XML parsing, alarm decoding, and anomaly detection are implemented in code and remain authoritative.
- Agentic grounded explanation:
  the LLM plans retrieval and memory access for natural-language or mixed prompts, then explains only facts that are supported by deterministic results or retrieved evidence.

This keeps protocol-critical interpretation safe while still making the assistant conversational and context-aware.

## Runtime Topology

```text
Browser
  -> dlms-nginx :3000
     -> /      -> dlms-ui :80
     -> /api/* -> dlms-backend :8000

dlms-backend
  -> dlms-postgres :5432
  -> dlms-mcp-server :8001
  -> Ollama on host

rag/ ingestion pipeline
  -> dlms-postgres
```

## Orchestration Modes

### 1. Deterministic Fast Path

Used when the input is clearly a payload or structured artifact, for example:

- raw HDLC frame
- direct APDU such as `C4020109060100010800FF`
- AXDR primitive such as `00` or `03 01`
- direct alarm code such as `0x1342`
- XML trace or multi-line operational log

The backend executes the deterministic tool immediately, then adds a grounded explanation layer when appropriate.

### 2. Structured Plus Agentic

Used when the user provides a strong structured input plus a natural-language request, for example:

- `7EA00A030383CD6F7E what does this do?`
- `Decode APDU C4020109060100010800FF and explain what object was returned`

The deterministic result comes first. The planner may then use retrieval or session tools to enrich the answer.

### 3. Natural-Language Agentic

Used for pure questions such as:

- `What is AARQ in DLMS?`
- `How does replay protection work?`
- `what OBIS code was in the last response?`

The planner chooses the best internal tools, typically retrieval and session memory, before answer generation.

### 4. Ambiguous Safe Fallback

Used when the input could match multiple structured families and the backend does not have enough evidence to pick one safely. In that case, the system refuses to guess.

## Main Components

### `backend/`

Reactive Spring Boot application with orchestration, streaming APIs, deterministic decode logic, grounded answer shaping, auth, audit logging, persistence, and admin endpoints.

### `ui/`

React + Ionic frontend with chat, decode panels, `How I answered` trace, session persistence, search, export, RBAC-aware admin pages, and upload support.

### `mcp_server/`

FastAPI MCP transport exposing deterministic DLMS and SICONIA tools.

### `rag/`

Offline ingestion pipeline for chunking, embedding, weighting, and loading DLMS plus Confluence knowledge into PostgreSQL/pgvector.

## Repository Layout

```text
.
|-- backend/
|-- ui/
|-- mcp_server/
|-- rag/
|-- docker-compose.yml
`-- README.md
```

## Quick Start

### Prerequisites

- Docker Desktop
- Java 25
- Maven 3.9+
- Node 20+ for local frontend development
- Ollama installed on the host

Install the required Ollama models on the host:

```powershell
ollama pull qwen2.5:3b
ollama pull nomic-embed-text
```

### 1. Create the backend environment file

```powershell
Copy-Item backend/.env.example backend/.env
```

Update the secrets in `backend/.env` before using the stack outside local development.

### 2. Package the backend

```powershell
cd backend
mvn clean package -DskipTests
cd ..
```

### 3. Start the stack

```powershell
docker-compose up --build -d
```

### 4. Verify

```powershell
docker ps
curl http://localhost:3000/api/actuator/health
curl http://localhost:3000/api/mcp/health
```

### Main URLs

- App: `http://localhost:3000`
- Health: `http://localhost:3000/api/actuator/health`
- MCP health: `http://localhost:3000/api/mcp/health`
- PostgreSQL host port: `localhost:5433`

## Development Commands

### Backend

```powershell
cd backend
mvn clean test
```

### Frontend

```powershell
cd ui
npm install
npm run dev
```

## Test Status

- Backend automated suite: `644` tests passing
- Frontend unit suite: `87` tests passing

Typical commands:

```powershell
cd backend
mvn clean test

cd ../ui
npm run test.unit -- --run
```

The frontend repository also contains Cypress and Playwright coverage for browser scenarios.

## Security And Deployment Notes

- Runtime is designed for offline or restricted-network environments
- Deterministic parsing is never delegated to the LLM
- JWT auth, RBAC, audit logging, and output filtering are part of the default stack
- `SESSION_ENCRYPTION_KEY` must remain exactly 16 characters
- Ollama is intentionally external to Docker so the deployment can reuse a stronger local or enterprise model later without changing the application containers

## Public Snapshot Notes

This public code snapshot intentionally excludes private or bulky project assets such as:

- report sources and generated PDFs
- internal docs and specs
- Confluence exports and processed knowledge packs
- local auth state, build outputs, crash logs, and virtual environments

The goal is to keep the repository focused on the runnable codebase.
