# Hybrid SOA-Agentic Geo-Time MCP Server & Adaptive UI

> **Transparency Disclaimer:** Every line of Java, SQL, YAML, and HTML/JavaScript code in this repository was generated via pair-programming with **Google AI**. My role was strictly that of the **architect and systems thinker**—defining the architectural patterns, connecting the infrastructure components, testing edge cases, and diagnosing latency bottlenecks.

---

## Overview

This project demonstrates how **Service-Oriented Architecture (SOA)** principles pair with modern **Agentic AI** patterns to build a sub-second, self-hosted **Adaptive UI**.

Instead of forcing every user interaction through a slow, chat-first text box, this application behaves like a deterministic web app when inputs are clear (`Gary, Indiana` -> immediate result card in ~500ms) and dynamically pops up a conversational clarification drawer only when genuine ambiguity (`Springfield` or `Franklin`) occurs.

---

## Architecture & Patterns Implemented

1. **Spring AI MCP Server (`geo-time-tools`):** Exposes strongly typed Java `record` contracts (`LocationMatch`, `TimeZoneInfo`, `LocationTimeReport`) over the Model Context Protocol.
2. **Hybrid Neuro-Symbolic Fast-Path:** Executes deterministic branching (`1` match, `2–5` virtual-thread parallel fan-out, `>5` guardrail) in compiled Java 21 bytecode while reserving the local LLM (`Qwen 2.5 7B` via **Bifrost**) for natural-language intent extraction and HITL reply normalization.
3. **Evaluator-Optimizer Self-Reflection:** A deterministic Java validator (`validateTimeReport`) verifies IANA time zones, ISO offsets, and U.S. county split-timezone rules (such as Lake County, Indiana -> `America/Chicago`).
4. **Stateful Human-in-the-Loop (HITL) Checkpoints:** Persists candidate lists into **PostgreSQL** (`agent_checkpoints`) when `>5` matches trigger the guardrail, allowing multi-turn pause and resume without re-querying upstream geocoding APIs.
5. **Three-Mode Adaptive Web UI (`src/main/resources/static/index.html`):**
   - **Mode 1 (Unambiguous):** Renders a clean, verified local-time card directly on the page.
   - **Mode 2 (Moderate Ambiguity, 2–5 matches):** Opens a disambiguation modal with clickable state option cards.
   - **Mode 3 (High Ambiguity, >5 matches):** Opens a conversational chat drawer bound to a PostgreSQL `checkpointId`.

---

## Tech Stack

- **Runtime & Framework:** Java 21 (Virtual Threads), Spring Boot 3.4+, Spring AI 1.0 (MCP Server & OpenAI-compatible `ChatClient`)
- **AI Gateway & Local Inference:** Bifrost AI Gateway (`http://localhost:18080`) routing to Ollama (`qwen2.5:7b`)
- **Persistence:** PostgreSQL + Spring Data JPA (`agent_checkpoints` table)
- **Frontend:** Vanilla HTML5, Tailwind CSS, Alpine.js (`http://localhost:8083`)

---

## Quick Start

### 1. Prerequisites
- Java 21+ and Maven
- PostgreSQL instance with a target database (e.g., `microservice_metrics`)
- [Ollama](https://ollama.com/) running locally with `qwen2.5:7b` pulled (`ollama pull qwen2.5:7b`)
- [Bifrost](https://github.com/maximhq/bifrost) configured with an `ollama` provider (`allow_private_network: true`)

### 2. Environment Variables
Set the following environment variables (or adjust defaults in `src/main/resources/application.yml`):

```bash
export DB_URL=jdbc:postgresql://localhost:5433/microservice_metrics
export DB_USERNAME=metrics_writer
export DB_PASSWORD=your_db_password
export BIFROST_URL=http://localhost:18080
export BIFROST_API_KEY=bifrost-local-key
export OLLAMA_MODEL=ollama/qwen2.5:7b
```

### 3. Run the Application
```bash
mvn spring-boot:run
```

Open **`http://localhost:8083`** in your browser and test the four built-in scenarios:
- **`Atlanta, Georgia`** -> Deterministic card mode (~500ms)
- **`Gary, Indiana`** -> Split-timezone county validation (`America/Chicago`)
- **`Springfield`** -> Parallel fan-out disambiguation modal
- **`Franklin`** -> Guardrail pause -> PostgreSQL HITL checkpoint -> conversational chat resume
