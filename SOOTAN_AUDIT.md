# Sootan PDF Editor: implementation audit

Status: implementation underway on `privacy-core`. See README, CORE_FEATURES.md and VERIFICATION.md for the resulting build and evidence.

Upstream baseline: `684608d143779bf3a8ffc67639f077a1202c62ff`.

## Licensing and access decision

The user explicitly approved removal of built-in login/accounts/MFA/API keys and remote/commercial application integrations for a local-use open-source core. No replacement authentication is added. The separately licensed paths named in the root LICENSE are removed from the working tree. Historic Git commits retain upstream history and its original licences; source archives for this branch should be created from its current tree.

Core has no equivalent proprietary SecurityFilterChain. Existing CORS behavior is retained and does not provide authentication. Local defaults use loopback binding, with only a loopback host-port mapping in compose. PDF sanitization, redaction, encryption, signature verification and secure file/temp handling remain in core. Security equivalence for remote application access is not claimed.

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

## Implementation evidence

See VERIFICATION.md for achieved source reductions, build/test results, and explicit gaps in runtime/container measurements. The historical baseline above is not a runtime benchmark.
