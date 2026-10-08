# FitnessApp — instructions for Claude Code

Personal Android gym app (Kotlin + Jetpack Compose) with a deterministic recommendation engine,
Supabase sync and data-driven challenges. Start with `PLAN.md`; `docs/DECISIONS.md` is the single
source of truth for every decision (IDs D*, F*, C*). Change a decision there first, with a changelog
entry, before changing dependent docs or code.

## Hard rules

- **No LLM calls in the app.** All recommendations come from the pure Kotlin module `:algo`.
- **`:algo` stays pure and deterministic**: no `android.*`, no system clock (inject it), no randomness,
  no IO, no `HashMap` iteration order, `StrictMath` for transcendental functions, stable sorts with id
  tie-breaks, canonical rounding (kg 0.01, s 0.1, ratio 1e-4). A test enforces the import rules.
- **Constants are data**: every tunable number lives in the versioned params JSON, never inline.
  Bump the module version and `ENGINE_VERSION` when behaviour changes.
- **Challenges are data**: never hardcode challenge logic (e.g. OAP) in app code; change the definition
  JSON and publish a new version.
- **Supabase project `nuvjdvpqibqeyafzgaye` is shared with a chess app.** Only touch the schemas
  `fitness`, `fitness_private`, `fitness_review`. Never run `supabase db push`, `supabase config push`,
  `supabase migration repair`, or `alter role authenticator ...`. Apply migrations through the
  Supabase MCP `apply_migration` only after the user has seen the SQL; run `get_advisors` afterwards.
  Never add triggers on `auth.users`. Never grant anything to `anon`.
- **Calendar writes** only touch events carrying the app's marker line, unless the user adopts one.
- Safety rules (pain protocol, no failure on weighted/one-arm pulls or loaded dips, load-jump caps,
  challenge hard constraints) are never overridden by user preferences or tuning.

## Where things are

- `PLAN.md` — overview, milestones, risks.
- `docs/ALGORITHMS.md`, `docs/ALGORITHMS_PLANNING.md` — engine contract and module specs.
- `docs/CHALLENGE_ENGINE.md`, `docs/OAP_CHALLENGE.md`, `docs/challenges/` — challenge engine and definitions.
- `docs/DATA_MODEL.md` — schema, RLS, sync, migrations, review views.
- `docs/UX.md` — screens and telemetry. `docs/ANDROID.md` — toolchain, platform, bootstrap commands.
- `docs/research/` — research notes with sources, critique, prototypes, free-exercise-db (Unlicense).

## Algorithm reviews

When asked to review the algorithms: follow `docs/REVIEW.md` (written in milestone M5). Read the
`fitness_review` views via the Supabase MCP (read-only), replay with `:sim`, report findings with
evidence, and propose params or code changes. Never apply parameter or definition changes without
the user's approval.
