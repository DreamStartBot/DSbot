# Microbot

RuneLite fork with a hidden always-on plugin hosting automation scripts. Composite Gradle build, Java 11 target (JDK 17+ to develop).

## Build / validate
- Compile: `./gradlew :client:compileJava`
- Full: `./gradlew buildAll`
- Shaded jar: `./gradlew :client:assemble`
- Tests (opt-in): `:client:runUnitTests`, `:client:runTests`, `:client:runIntegrationTest` (needs running game)

## Non-negotiable rules
- Never instantiate caches/queryables directly — use `Microbot.getRs2XxxCache().query()` / `.getStream()`. See `runelite-client/src/main/java/net/runelite/client/plugins/microbot/api/QUERYABLE_API.md`.
- Never block/sleep on the client thread.
- Never use static sleeps to wait for game state — use `sleepUntil(condition, timeoutMs)`.
- Keep `MicrobotPlugin` hidden/always-on; don't break its config panel wiring.
- Respect existing Checkstyle/Lombok patterns; don't weaken security (telemetry tokens, HTTP clients).
- Minimal logging; no PII or session identifiers.

## Local changes to upstream files
- Any edit to a file that already exists in the repo (tracked by git) must be wrapped in a `// ===================== [MICROBOT 本地改动] <主题> =====================` marker, closed with `[MICROBOT 本地改动 END]`. This applies to every pre-existing file, not just the ones under RuneLite's own packages — a file in a microbot package is still pre-existing code and still needs marking.
- The comments inside such a marker must be in Chinese and must state: what upstream does, what the local change does, and how to resolve the spot when an upstream merge conflicts.
- Comments written in this repository are Chinese. Keep upstream code itself untouched.
- Record every change in `docs/LOCAL_PATCHES.md`; keep that list in sync when adding or removing a change.
- Locate all change points with `rg -n "MICROBOT 本地改动" runelite-client/`.

## Review priority
- **P0:** client crashes, client-thread blocking, login/world-hop breakage, cache invariant corruption, credential/token exposure.
- **P1:** script loop timing, overlay correctness, plugin discovery/config, shaded-jar packaging, build reproducibility.

## Runtime tooling
- `./microbot-cli` (JSON output) — see `docs/MICROBOT_CLI.md`, HTTP API `docs/AGENT_SERVER.md`, full tool list `docs/AGENT_SCRIPT_TOOLS.md`.
- Agent Server plugin runs on port 8081 by default.
- Offline client-thread lookup: `./microbot-cli ct <method>`.
- Test mode: `-Dmicrobot.test.mode=true -Dmicrobot.test.script=<PluginName>` → results in `~/.runelite/test-results/`. Protocol: `docs/AGENTIC_TESTING_LOOP.md`.

## Releases
- Before merging `development` -> `main` (which runs `release.yml`), run `scripts/run-release-e2e.sh full` (or `quick` at minimum) against the release candidate and paste the generated `summary.md` into the release PR. See `docs/RELEASE_E2E.md`.

## In-game settings
Use the settings search bar — tab indices shift on updates. Verify changes via `./microbot-cli varbit <id>`.

## Before touching `microbot/util/`
Read `docs/entity-guides/README.md`. Add a gotcha there when you fix an entity-assumption bug.

## Docs maintenance
- Keep root `README.md`, `docs/README.md`, and `docs/INDEX.md` short routing pages.
- Put volatile command details, API examples, endpoint lists, generated inventories, and screenshots in the narrowest owning doc.
- Prefer links to owner docs over copying the same guidance into multiple high-level files.

## Deeper guides
- Script authoring & threading: `runelite-client/.../microbot/AGENTS.md`
- State machines (use for 3+ phase scripts): `.../microbot/statemachine/AGENTS.md`
- Architecture: `docs/ARCHITECTURE.md`, `docs/decisions/`
- Setup: `docs/development.md`, `docs/installation.md`
