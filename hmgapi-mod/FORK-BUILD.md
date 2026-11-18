# Build and publish the combined Canadian Ioniq 6 / EV6 test

The combined **0.5.1-beta.102** build uses the released HMG-API SDK **1.1.0-beta.1**, pinned by archive SHA-256 and all 46 extracted-file hashes in `hmgapi-sdk.json`. The build verifies those files before using them. Both intended setups use that core version; building against a locally modified core could hide an API mismatch. The package admits only `CEEV / CAN / 260206` and `CVEV / CAN / 260206` and retains wireless `>=2.2.0-beta.101` as its dependency.

## Build

Requirements: JDK 17, Python 3, zip/unzip, Android SDK with `d8` and `android.jar`. On macOS, put GNU coreutils on PATH for the upstream SDK packager's `touch -d`, `md5sum` and related host commands. These commands run on the computer only.

```sh
python3 tools/fetch-sdk.py --out .cache/hmgapi-sdk
export HMGAPI_SDK="$PWD/.cache/hmgapi-sdk"
export ANDROID_HOME=/path/to/android-sdk
sh hmgapi-mod/build.sh
sh hmgapi-mod/test/run.sh
HMGAPI_FLOOR_POINTS="$HMGAPI_SDK/points.txt" python3 "$HMGAPI_SDK/bin/check-points.py" hmgapi-mod
sh "$HMGAPI_SDK/bin/check-scripts.sh" build/mod/egmp-aa-ev
```

`build/mod/egmp-aa-ev/` is the unsigned module, including only its compiled hook/service jars, metadata, recipes and service script. The tests use the released engine and source-only fixtures for both cars. No head-unit connection or manufacturer files are needed for ordinary host tests. Optional exact-stock coexistence checks:

```sh
CEEV_STOCK_LIBRARY=/private/path/libautoreceiver_infobank.so sh hmgapi-mod/test/ceev-native-test.sh
EV6_STOCK_LIBRARY=/private/path/ev6/libautoreceiver_infobank.so sh hmgapi-mod/test/ev6-native-test.sh
```

Each extra test checks the expected binary fingerprint, reads the private copy and operates on in-memory arrays; it does not modify the copy. Use the correct library for each car. Keep all firmware and captures outside Git and release assets.

## Retained signing identity

Use the owner's retained private author key outside this repository, mode `600`, with its `.pub` alongside it. The builder enforces public author UUID `c2318db4-4435-830e-9ef0-297ce6a863fc`, the existing private author identity. Do not print or upload private key material or store it in Actions artifacts. Never replace the retained key with a disposable CI key.

Commit the reviewed source first, then:

```sh
python3 tools/package-private.py --key /private/path/author.key --out /private/path/new-test-artifacts
```

The output directory must not exist. The builder rebuilds from clean committed source, signs, verifies, packages both NEMESIS forms, and writes `SHA256SUMS` and `BUILD.json`. It enforces exactly both target models in the signed manifest and exactly `CEEV.CAN.*.260206` plus `CVEV.CAN.*.260206` in the NEMESIS descriptor, preserving the signed inner ZIP byte for byte. Core/wireless floors, preconditioning-off default, retained author and SDK checks are enforced before publication.

The builder is explicitly scoped to `0.5.1-beta.102`, module ID `egmp-aa-ev`. A future candidate requires increasing the version and reviewing the corresponding builder contract. Preserve the exact Ioniq 6 beta.101 artifact and tag; do not overwrite or retag them.

`BUILD.json` marks beta.102 as not tested in either car and records the earlier Ioniq 6 beta.101 owner report separately. It also records that settings are preserved: the EV6's existing saved preconditioning-on value survives an upgrade. Release notes must instruct the owner to turn it off **before installation**, then confirm it remains off. Neither this builder nor the package resets or migrates that setting automatically.

The existing retained-key beta.101 EV add-on can be upgraded normally. A signature conflict requires identifying the installed signer; do not bypass verification. This is separate from the already-working wireless mod, which should remain installed.

## Private publication

1. Confirm repository visibility is private and CI passes on the source commit.
2. Use private candidate tag `can-260206-ev-beta.102`, pointing at that source commit in the existing private repository.
3. Create a draft prerelease with the three signed ZIPs, checksums, build record and sanitized test notes. Do not mark it Latest or describe EV routing as car-validated.
4. Download the draft assets and compare every SHA-256 with the originals before publishing.
5. Preserve that exact candidate after each car test; add a dated validation report with only the model, version and features actually confirmed. Ioniq 6 beta.101 success does not validate beta.102 on either model. A code change requires another version and test.

The inherited release workflow has been replaced by read-only CI for `main` and `ev6-can-260206-preparation`, plus pull requests and manual runs. Publishing a release does not trigger a second signing/build job, and CI never exposes disposable-key car downloads. The working wireless package is linked as a prerequisite, not repackaged or modified by this repo.
