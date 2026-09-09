---
name: new-entity
description: Adds a new JPA entity, repository, Liquibase migration, DTOs, and controller endpoints. Use when defining a new domain model, adding a database table, or creating CRUD REST endpoints.
---

## Steps

1. **Liquibase migration** — Create `src/main/resources/db/changelog/v0.1/NNN-create-<entity>.yaml`. Use `uuid` PK with `gen_random_uuid()`, `deleteCascade` on FKs, and `addUniqueConstraint` where needed.

2. **JPA entity** — Create `model/<Entity>.java` with `@Entity`, `@Table`, `@Id @GeneratedValue(strategy = UUID)`. Use `Instant` for timestamps, `@PrePersist`/`@PreUpdate` for audit fields. Constructor injection only (GraalVM AOT).

3. **Repository** — Create `repository/<Entity>Repository.java` extending `JpaRepository<Entity, UUID>`. Add derived query methods as needed.

4. **DTOs and controller** — Follow [`rest-api-endpoint.md`](rest-api-endpoint.md): record DTOs with compact constructors (`Objects.requireNonNull(...)`), `@Schema` annotations, and `@Tag`/`@Operation`/`@ApiResponses` on endpoints.

5. **Tests** — Add a `repository/<Entity>MappingTest.java` (annotated with `@SpringIntegrationTest` and `@Transactional`) covering save, find, and delete. Controller tests follow [`rest-api-endpoint.md`](rest-api-endpoint.md). Run `./mvnw test`.
