# Host integration fixtures — not installable

These files are only inputs to `hmgapi-mod/test/engine-test.sh`. They are never
copied into a built or signed EV module. No manufacturer binaries or car logs
are included.

* `maps/hyundai/ceev/can/260206` is the custom core hook map verified on
  `CEEV.CAN.0000.V015.011.260206` (351/351 points, 2026-11-09). It is copied from
  the separate local HMG-API map work. This EV module does not install that map.
* `maps/kia/ev6/can/260206` is copied byte-for-byte from released HMG-API
  `a9ab444b4cc5b7b29e93727964bc99fc4ac44936` (1.1.0-beta.1). Its source metadata
  remains unchanged, including the original `verified: null`; a separate
  read-only check on the owner's `CVEV.CAN.0000.V015.011.260206` reported
  351/351 points verified. These public source JSON maps contain no firmware.

The test creates text stand-ins for stock map files and a `hooks.jar` placeholder
inside an isolated temporary host root. It adjusts only that temporary map's
checksums to the stand-ins, and creates a matching, explicitly marked synthetic
`map-check.json` receipt inside that temporary root. This models a unit whose
map check passed; it is not real hook verification and is never shipped. The
placeholder is never executed and cannot be
installed. Engine plan/apply validates declarations, feature files, dependency
rules and coexistence, not Android hook loading or native code on a real car.
Native recipe guard/coexistence tests are separate. Each product has an isolated
host root. The test preserves the established wireless settings: firewall ON for
Ioniq 6, firewall OFF for EV6, and TBT ON with standard-turn formatting OFF for
both. Applying or disabling the EV add-on must leave those settings unchanged.

The EV module's empty `patches.json` is intentional: HMG-API 1.1.0-beta.1
otherwise skips dependency/version checks for hook-only modules during plan/apply.
Its `changes` array is empty. The integration test verifies that the combined
Apply owns exactly three file targets, all from Wireless, and that missing,
disabled or older Wireless versions prevent the EV plan from applying.
