---
name: rest-api-endpoint
description: Adds a new REST API endpoint with security, OpenAPI documentation, and integration tests. Use when creating new controller endpoints, extending an existing controller, or adding CRUD operations for a resource.
---

## Steps

1. **DTOs** — Create `record` types in `api/restdto/` for request/response bodies. Add `@Schema(description, example)` annotations for OpenAPI documentation.
   - *Prefer Compact Constructors*: Encourage Java Record compact constructors (`Objects.requireNonNull(...)`) over complex reflection-based validation annotations for DTO input validation. Compact constructors run natively during Jackson deserialization without requiring `RuntimeHintsRegistrar` reflection entries for GraalVM AOT native image compilation.

2. **Service layer** — Create `service/<Resource>Service.java` with Spring constructor injection. Implement business logic, repository calls, and password hashing (BCrypt) where needed.

3. **Controller** — Create or extend `api/rest/<Resource>Controller.java`:
   - `@RestController`, `@RequestMapping("/api/v1/<resource>")`
   - `@Tag(name = "...", description = "...")` on the class
   - `@Operation(summary, description)` + `@ApiResponses` on each method
   - `@Valid @RequestBody` on request DTOs
   - Return `ResponseEntity<T>` with appropriate status codes (201 for create, 204 for delete)

4. **Security** — Ensure appropriate security restrictions are applied
   - Except auth, all endpoints should require a logged in user.
   - Some endpoints may require admin privileges.

5. **Service unit tests** — If the service has business logic (validation, transformation, complex operations), create `test/.../service/<Resource>ServiceTest.java`. Test the logic in isolation without `@SpringBootTest`. See [`control-plane-testing.md`](control-plane-testing.md) for patterns.

6. **Integration tests** — Create `test/.../controller/<Resource>ControllerTest.java` using `@SpringIntegrationTest` (real database, no `@MockBean` on services). Cover every endpoint per the required-cases table in [`control-plane-testing.md`](control-plane-testing.md).

7. **Run** `./mvnw test` to perform final task validation (or `./mvnw test -Pfast` for rapid iteration only).
