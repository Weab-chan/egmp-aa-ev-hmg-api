# Ioniq 6 and Kia EV6 Canada: EV routing for Android Auto


| Car | Reviewed firmware |
|---|---|
| Hyundai Ioniq 6 | `CEEV.CAN.0000.V015.011.260206` |
| Kia EV6 | `CVEV.CAN.0000.V015.011.260206` |

**This is an add-on to the working wireless setup.** It requires HMG-API **1.1.0-beta.1 or newer** and Wireless CarPlay + Android Auto **2.2.0-beta.101 or newer**. Keep the installed wireless mod, its working settings, and the checked car map. This package does not replace them or install a map. The private repository and retained signing identity are unchanged despite its original Ioniq 6 repository name.

The owner reported that Ioniq 6 **beta.101** works, including the EV add-on, wireless Android Auto and Google Maps dash directions. EV6 wireless AA and dash directions also work with its existing setup. **The new combined beta.102 has not been tested in either car.** Automatic preconditioning, CarPlay and HUD behavior are not claimed as validated.

## What changed in beta.102

- Both signed metadata and NEMESIS descriptors accept **CEEV and CVEV / CAN / 260206**, with a new version so the installer can distinguish this candidate.
- The shutdown guard admits the two exact reviewed GPM ODEX fingerprints. Copied EV6 lifecycle classes match the reviewed Ioniq 6 implementation; method-shape checks and the per-source gate remain intact. Unknown fingerprints still leave EV sending off.
- Existing EV and wireless TBT recipes are tested together for both native-library builds. No new EV patch offsets or bytes were introduced for the EV6.
- Builds retain the checksum-pinned **HMG-API 1.1.0-beta.1 released SDK**, dependency checks and signing identity.
- **Automatic preconditioning defaults off. Existing saved settings survive upgrades.** The connected EV6 currently has it saved **on**, so its owner must turn it **off before installing beta.102**. There is no automatic settings reset or migration.

See the [Ioniq 6 evidence](docs/CANADIAN-IONIQ6.md), [EV6 evidence](docs/CANADIAN-EV6.md), and [sanitized beta.102 validation record](docs/validation/can-beta102.json). Other model/region/firmware combinations are not admitted by this package.

The EV6's existing compatibility override survives upgrades too. Beta.102 no longer needs that bypass, but it does not automatically remove it or edit the core's settings.

## First test of the combined build

1. Before installation, turn **preconditioning OFF** in the existing EV add-on. Confirm it remains off after upgrading. This is necessary on the EV6 because its saved on setting takes precedence over the new-package default.
2. Keep wireless beta.101 installed and enabled with its working turn-by-turn/firewall settings. The EV6 currently works with the optional firewall rule off; this add-on does not change that setting. Keep the correct checked map for each car.
3. Put the unopened plain ZIP in `HMGAPI/updates/` on the USB stick. While parked, choose **Check USB updates**. Confirm **EV routing for Android Auto (Ioniq 6 + EV6 Canada test)**, **0.5.1-beta.102**, and recognition of the firmware. A compatibility override must not be needed for either target.
4. Upgrade the existing `egmp-aa-ev` mod normally using the same retained signer; do not uninstall the working wireless mod. Enable the add-on, Apply and restart when HMG-API requests it. These are owner-operated steps; the build does not perform them.
5. Reconnect AA and confirm Google Maps directions still reach the dash. Check live battery/range values and EV-routing behavior, then disconnect/reconnect once to validate session cleanup.
6. Copy `[egmp-aa-ev]`, `EvModelSender`, `EvLiveData`, `EvCar` and `EvPrecond` logs plus installed version/settings. Look for the exact-stock lifecycle guard, native patch read-back and `energy model sent`. A phone UI result alone does not establish every hook's behavior.

If the installer reports a signature conflict, stop and identify the installed EV add-on's signer. Do not bypass signature checks or remove working wireless. Do not run the legacy `aa.ev.model`/Leafia installer alongside this mod.

If the earlier `.xml.tmp` rename error recurs, copy the logs before retrying. If AA regresses, disable **egmp-aa-ev**, Apply and restart, leaving the working wireless package installed.

## Scope and limits

The add-on changes AA process memory and hooks Java methods at runtime. Offline validation cannot guarantee that AA or the head unit will not fail or restart. It does not flash firmware, replace stock APK/library files or add firewall rules. HMG-API stores module files/settings, and the EV logger writes bounded logs. The inherited energy/charging model still needs calibration for each car; working hooks do not establish accurate arrival-charge estimates.

Preconditioning requires separate validation. Its relay checks current ownership before stopping a request, but the core's separate status and stop calls do not provide an atomic ownership guarantee. Keep it off during this data/routing test. No vehicle-command permission is required for that initial test.

EV logs can be copied by creating an `egmp-aa-ev-logs` folder on USB. Keep logs private. Public issue links in historical upstream documents are not instructions to publish vehicle data.

## Build and provenance

See [FORK-BUILD.md](hmgapi-mod/FORK-BUILD.md) for the SDK, tests, signing and publication workflow. CI builds/tests this preparation branch and main, but does not publish disposable-key packages for car installation.

Source-only fixtures cover both maps and the relevant wireless metadata/recipes. They are not installable payloads and contain no manufacturer firmware or raw vehicle logs. `BUILD.json` identifies beta.102 validation separately from the earlier Ioniq 6 owner report, and `SHA256SUMS` identifies the exact artifacts. The retained private key stays outside the repository.

This repository continues [AwsomeFox's HMG-API port](https://github.com/AwsomeFox/egmp-aa-ev-hmg-api) of [willemcvu's EGMP project](https://github.com/willemcvu/egmp-aa-ev-feat). Original `gpm-ev/`, `packages/` and legacy tools remain for provenance, not this installation. [ENERGY-MODEL](docs/ENERGY-MODEL.md), [LOGGING](docs/LOGGING.md) and [HMG-API-COMPARISON](docs/HMG-API-COMPARISON.md) retain historical context; the current defaults and validation scope on this page take precedence.

[AGPL-3.0](LICENSE). Existing `third_party/lpatch` retains its GPL-3.0 license. Credit to the original authors, HMG-API, NEMESIS and contributors named in source history.
