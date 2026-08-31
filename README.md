# Agentic API Builder

An agent-driven workflow that turns structured API specifications into validated Spring Boot implementations.

## Overview

Agentic API Builder is an experimental repository for designing and evaluating a deterministic API-generation workflow. API requirements are expressed as structured JSON specifications, while Markdown-based agent instructions define how an implementation must be analyzed, planned, changed, tested, and reported.

The project is a working research and development example. It demonstrates the workflow and its validation controls, but it does not claim production readiness.

## Workflow

1. Define an API operation in `input/api-spec.json`.
2. Validate the input against the contract in `agents/api-generator.md`.
3. Analyze the existing project and make evidence-based reuse or extension decisions.
4. Produce an implementation plan with an explicit file scope.
5. Implement only the planned changes and add traceable tests.
6. Run the test suite and clean package build.
7. Compare actual changes with the planned scope and produce a structured report.

## Current Features

- Versioned agent instructions with mandatory execution phases and stop conditions
- Structured acceptance criteria and business rules
- Evidence-based component decisions and implementation planning
- File-scope protection and post-implementation scope verification
- Acceptance-criterion traceability through tests, commands, builds, or inspection
- Mandatory test and build validation before a task can report success

As experimental examples, the current application supports creating customers, retrieving customers by ID, deleting customers, request validation, case-insensitive email uniqueness, and appropriate HTTP responses for invalid, missing, or conflicting requests. It also retains a simple `/hello` endpoint.

## Tech Stack

- Java 21
- Spring Boot 4.1.1
- Spring Web MVC
- Jakarta Bean Validation
- Maven Wrapper
- JUnit and Spring MockMvc

## Project Structure

```text
agents/api-generator.md   Agent workflow and validation specification
input/api-spec.json       Structured API operation input
src/main/java/            Spring Boot application and API implementation
src/test/java/            Application and endpoint tests
pom.xml                   Maven project configuration
```

## Agent Workflow

The current Version 3 specification requires eight ordered phases: input validation, project analysis, implementation planning, implementation, test generation, test execution, build validation, and final reporting. Existing components must be reused or safely extended when possible, and every decision requires concrete repository evidence.

The agent records its planned file scope before implementation. Any necessary scope expansion must be planned and explained before another file is changed. Existing public behavior must remain intact unless the input explicitly requests a change.

## Validation Criteria

A generated change can report success only when:

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

This repository currently evaluates the workflow through incremental customer API experiments, including component creation, safe extension of existing components, business-rule enforcement, regression protection, and detection of already-satisfied requirements. The customer endpoints are examples used to exercise the agent specification, not a complete production customer-management system.
