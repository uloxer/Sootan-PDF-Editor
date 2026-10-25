# Sootan PDF Editor: implementation audit

Status: pre-implementation. The application has not yet been rebranded or made telemetry-free. Do not treat this branch as a finished privacy distribution.

Upstream baseline: `684608d143779bf3a8ffc67639f077a1202c62ff`.

## Decision required

The root LICENSE excludes app/proprietary, app/saas, engine, frontend/src/processor, and frontend/src/editor/{proprietary,desktop,saas,cloud} from its MIT grant. Their individual licences must be respected. app/proprietary/LICENSE prohibits distribution of modified derivatives without applicable permission.

Existing server authentication, accounts, MFA, API keys, OAuth/SAML and session management reside in app/proprietary/src/main/java/stirling/software/proprietary/security. Core ConfigController disables login when the proprietary userService is absent; core has no equivalent SecurityFilterChain. Removing restricted modules therefore cannot preserve built-in authentication unchanged.

Implementation is paused until the owner chooses either a local-use open-source core with the authentication limitation documented, or clarification of permission to retain the restricted access controls. External access protection would be a separately configured deployment responsibility, not an existing application security feature.

PDF sanitization, redaction, password protection/encryption and signature tools reside in core and should be retained. This source audit does not establish runtime security equivalence.

## Baseline measurements

Git blob lengths, excluding Git history, generated output and untracked files:

| Category | Files | Bytes |
| --- | ---: | ---: |
| All tracked files | 8,371 | 209,944,276 |
| Restricted licence paths | 3,373 | 19,906,572 |
| Other paths under root MIT licence | 4,998 | 190,037,704 |
| Primary code in restricted paths | 2,620 | 12,459,537 |
| Primary code in other paths | 3,158 | 17,413,793 |

Primary code here means .java/.ts/.tsx/.py/.rs files under app/*/src/main/java, frontend/src, engine/src and frontend/src-tauri/src. Frontend colocated tests are included. Those language extensions anywhere in the repository total 41,211,293 bytes, including tests and tooling. These are distinct denominators, not directly interchangeable production-source measurements.

Restricted-module removal alone would reduce the defined primary-code subset by 41.7%, and total tracked bytes by 9.5%. These are estimates of prospective deletion, not achieved reductions. Retained assets include large CJK fonts and OpenCV; removing them would affect language fidelity or scan correction.

Backend executable, frontend bundle, Docker image, startup, idle RAM and PDF processing/rendering measurements are not yet available. No application build or tests were run in this audit. Windows has JDK 21; the repository requires JDK 25. Task and Docker were not available on PATH.

## Implementation plan after decision

1. Enforce STIRLING_FLAVOR=core across Gradle, Vite, Taskfiles and container builds; remove unconditional proprietary project inclusion and disable portal bundling.
2. Remove restricted modules and their imports/build entrypoints while preserving required notices and documenting unavailable features.
3. Remove backend PostHog configuration, services, dependencies and call sites; remove frontend PostHog/Scarf hooks, pixels, startup prompts, analytics configuration and dependencies.
4. Remove obsolete payment, cloud, account-linking and commercial UI code and packages. Regenerate lockfiles and adjust build/test configuration without deleting retained security tests.
5. Apply Sootan PDF Editor branding, simplify startup/navigation using existing core components, and retain advanced PDF tools.
6. Build and run relevant repository task checks, PDF/security regression tests and offline network checks. Compare reproducible before/after source, artefact and runtime measurements. Document missing results explicitly.
7. Provide a container build, installation instructions and retained/removed feature inventory. Pin the upstream baseline, fetch security patches from upstream, review licence boundaries and cherry-pick applicable MIT changes onto privacy-core with regression checks.

No production deployment or upstream pull request is authorised by this branch.
