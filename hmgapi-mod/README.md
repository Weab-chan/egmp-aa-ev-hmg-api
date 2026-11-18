# EGMP as an HMG-API add-on

## Components

| Part | Purpose |
|---|---|
| `mod.json` | `egmp-aa-ev`, private version, exact firmware admission, five hook declarations, native recipes, EV fuel/connector metadata, services and conditioning capability |
| `hooks.jar` | Java hooks/data readers inside GPM; exact-stock shutdown gate before native free; native patch read-back before offering sensor 23; one sender per session |
| `recipes/` | Guarded AA in-memory EV patches; `dbfe7bd2` is the exact saved Ioniq 6 library |
| `relay.jar` | System-UID service issuing conditioning requests through HMG-API's permission/state checks when separately enabled |
| `scripts/offload.sh` | Copies bounded EV logs to the owner's `egmp-aa-ev-logs` USB folder |
| `settings.json` | Energy model, live data and EV logs on by default; **preconditioning off**; thresholds remain configurable |
| `test/fixtures/` | Source-only host integration inputs, excluded from the distributable package |

Stock APK and library files stay unchanged on disk. The installed mod, settings and EV logs do write under HMG-API's data folders. Native recipes alter AA process memory, so the add-on can still cause an AA crash or connection regression.

## Build, tests and packaging

Use the [pinned SDK instructions](FORK-BUILD.md). No HMG-API checkout or stock firmware is needed for normal CI. Existing `gpm-ev/` classes are reused for model/log/route logic; proprietary app stubs are compile-only.

Each car package is signed locally with the retained private author key. CI uses disposable keys only to test installation gates; those fixtures are never offered for car installation. Never edit a signed ZIP in place or reuse an older version number.

## Runtime evidence

Useful HMG-API/Android tags are `[egmp-aa-ev]`, `EvModelSender`, `EvLiveData`, `EvCar`, `EvDriveLog`, `EvLogger`, and `EvPrecond`. Look for `exact-stock SensorSource.shutdown lifecycle guard installed`, the native patch applied/read-back result and `energy model sent`, then compare actual values with the dash. `energy model off: the Android Auto library patch isn't in place` means sensor 23/model sending remain off. Its detailed reason distinguishes a missing patch from an unreadable mapping.

The relay log is useful only for the separate preconditioning test. The first routing/data test keeps that setting off and does not grant the vehicle-command permission.
