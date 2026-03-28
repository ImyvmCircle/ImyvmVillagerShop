# AGENTS.md (Agent Operating Rules)

This file defines **mandatory rules** for any automated agent making changes in this repository.
If you cannot follow a rule, **stop and ask for clarification** before making changes.

## 0) Scope
- Target platform: Fabric
- Minecraft: 1.21
- JDK: 21
- Build tool: Gradle + ShadowJar
- Entrypoint: `com.imyvm.villagerShop.VillagerShopMain`
- Run task: `./gradlew runServer`

## 1) Non-negotiable constraints (DO / DO NOT)

### DO
- Keep changes minimal and directly related to the requested task.
- Prefer Kotlin for new code unless interop requires Java.
- Ensure changes are buildable with:
  - `./gradlew build`
  - and when relevant: `./gradlew runServer`
- When adding docs, ensure the doc is **backed by repository reality** (tasks, paths, class names, versions).

### DO NOT
- Do **not** add or modify documentation that references commands/tasks/files that do not exist in this repository.
  - Example: do not mention `./gradlew runClient` unless it actually exists.
- Do **not** create “placeholder docs” such as:
  - “TBD”, “fill in later”, “(add details here)”, “…” or invented sections.
- Do **not** invent features, configuration keys, permissions, commands, or endpoints.
- Do **not** change the target platform (Fabric) or supported MC/JDK versions.
- Do **not** introduce new build tools (no Maven migration), new language (no Scala), or unrelated frameworks.
- Do **not** upgrade major dependency versions unless explicitly requested.

## 2) Documentation validity rules (anti-hallucination policy)
Any documentation change MUST satisfy all of the following:

1. **Verifiable**: every command, Gradle task name, and file path mentioned must exist.
2. **Concrete**: include exact commands and expected outputs/locations when possible (e.g. jar in `build/libs`).
3. **Consistent**: match the repo’s actual platform (Fabric), versions (MC 1.21, JDK 21), and entrypoint.

If any of these cannot be verified from the repository contents, the agent must:
- either remove the unverified content
- or ask the maintainer for the missing information

## 3) Acceptance checklist (must be stated in PR/summary)
Before finalizing, the agent must provide:

- [ ] What changed (1–5 bullet points)
- [ ] How to build: `./gradlew build` (pass/fail)
- [ ] How to package: `./gradlew shadowJar` (pass/fail)
- [ ] How to run locally (if relevant): `./gradlew runServer` (pass/fail)
- [ ] Any config/DB impact (Exposed; H2/PG/MySQL)
- [ ] Manual test steps (if no automated tests)

## 4) Safety & DB constraints
- Never commit secrets (DB passwords, tokens).
- Avoid DB calls on the main thread (risking tick lag).
- Exposed is the only supported DB access layer unless explicitly approved.

## 5) When to stop and ask
Stop and ask for clarification when:
- the task requires a new config key but the config format/location cannot be found
- the Gradle task referenced by the request is missing
- the requested change affects DB schema but no migration strategy exists
- you are uncertain whether the behavior should be Fabric-appropriate vs Bukkit-like

## 6) Preferred change strategy
- Small PRs: one purpose, few files.
- Keep backward compatibility unless explicitly breaking.
- Add tests when the repo already has a test harness; otherwise include manual verification steps.