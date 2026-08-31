# Agentic API Builder

An experimental, agent-driven workflow for transforming existing Java and Spring Boot API codebases according to structured source and target rules.

## Overview

Agentic API Builder explores a deterministic pipeline for analyzing, migrating, and validating existing Java and Spring Boot APIs. Its intended purpose is to transform an existing codebase toward a defined target structure or API contract while preserving required behavior and controlling change scope.

The project currently represents requirements with structured JSON API specifications and controls agent behavior with Markdown-based instructions. It is a research and development project and does not claim production readiness.

## Current State

The current implementation uses one agent specification: `agents/api-generator.md`. That file contains the complete workflow in a single Version 3 specification, including input validation, project analysis, planning, implementation, test generation, test execution, build validation, and final reporting.

Current experiments validate foundational concepts that will support the broader migration pipeline:

- evidence-based project analysis;
- `REUSE_EXISTING`, `EXTEND_EXISTING`, `CREATE_NEW`, and `MANUAL_REVIEW_REQUIRED` decisions;
- implementation planning before code changes;
- explicit planned file scope and post-change scope verification;
- structured business rules and acceptance-criteria traceability;
- implementation and regression testing; and
- mandatory test and build validation before success can be reported.

The included customer APIs are experimental examples rather than the project's primary product. They currently demonstrate customer creation, lookup and deletion, request validation, case-insensitive email uniqueness, regression protection, and appropriate HTTP status handling. A simple `/hello` endpoint is also retained.

The repository does not yet implement a multi-agent orchestrator or separate specialist agents.

## Current Workflow

1. Define an operation in `input/api-spec.json`.
2. Validate the structured input using `agents/api-generator.md`.
3. Analyze the existing project and collect evidence.
4. Produce an implementation plan and record the allowed file scope.
5. Implement only planned changes and add traceable tests.
6. Run tests followed by a clean package build.
7. Compare actual changes with the plan and produce a structured report.

## Target Architecture

The intended architecture is a staged, multi-agent migration and transformation pipeline coordinated by a master orchestrator:

```text
MASTER.md
    |
    +-- source-analysis agent
    +-- target-analysis agent
    +-- migration-planning agent
    +-- implementation agent(s)
    +-- test/validation agent
    +-- final structured report
```

`MASTER.md` is planned to orchestrate the workflow. It will coordinate specialized agents, enforce execution order, pass structured outputs between stages, and stop or block processing when required information, evidence, or validation is missing.

As the workflow matures, responsibilities will be separated so that source analysis describes the existing system, target analysis interprets the desired architecture and contracts, migration planning maps the transition, implementation agents perform bounded changes, and test/validation verifies the result. Specialized stages may exchange structured JSON inputs and outputs to make decisions traceable and machine-verifiable.

This target architecture is planned work; `MASTER.md` and the specialized agent pipeline are not currently implemented.

## Tech Stack

- Java 21
- Spring Boot 4.1.1
- Spring Web MVC
- Jakarta Bean Validation
- Maven Wrapper
- JUnit and Spring MockMvc

## Project Structure

```text
agents/api-generator.md   Current single-agent workflow specification
input/api-spec.json       Current structured API operation input
src/main/java/            Spring Boot application and API implementation
src/test/java/            Application and endpoint tests
pom.xml                   Maven project configuration
```

## Validation Criteria

Under the current single-agent specification, a change can report success only when:

- all required phases complete successfully;
- every mandatory acceptance criterion is directly verified;
- `./mvnw test` passes;
- `./mvnw clean package` passes;
- actual changes remain within the planned file scope; and
- no blocking question or manual-review decision remains.

Run the validation commands from the repository root:

```bash
./mvnw test
./mvnw clean package
```

## Experimental Status

This repository is experimental. The completed customer API exercises test the controls in the current single-agent specification, including component creation, safe extension, business-rule enforcement, regression protection, and detection of already-satisfied requirements.

The larger source-to-target migration pipeline, `MASTER.md` orchestrator, specialized agents, and inter-stage JSON contracts remain planned work. The current implementation should be treated as a validation foundation for that direction, not as a finished migration platform or production-ready system.
