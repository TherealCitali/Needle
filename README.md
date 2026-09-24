<div align="center">

# Needle

**A 14 MB-class language model that runs entirely on your Android phone and does what you say.**

Chat with it, control the hardware, and let it drive the screen — no cloud, no API key, no Termux.

</div>

---

Every push builds a **signed, installable APK** in GitHub Actions:

| Where | What |
| --- | --- |
| **Releases ▸ `needle-latest`** | always the newest signed APK from `main` |
| **Releases ▸ `needle-build-<n>`** | one signed APK per build (last 10 kept) |
| **Actions ▸ a run ▸ Artifacts** | `Needle-1.0.<n>-<sha>.apk` + `SHA256SUMS` |

The workflow verifies the APK's signature with `apksigner` before it uploads anything, so
every artefact it hands you is signed and ready to install. Android will warn about
sideloading — that is normal for an app installed outside the Play Store.

## What it does

Three parts, one app, all local:

**1. Chat that acts.** Ask in plain English. [Needle 3](https://huggingface.co/Cactus-Compute/needle3)
by Cactus Compute runs on the phone, picks the tool that matches your sentence, and the app
performs it natively:

> *"turn on the flashlight"* · *"what's my battery at?"* · *"vibrate for 1 second"* ·
> *"copy Hello World to clipboard"* · *"where am I right now?"* · *"what network am I on?"* ·
> *"send sms to +91… saying hello"* · *"open WhatsApp"* · *"take a screenshot"* · *"read my last 3 texts"*

Before, those commands went through Termux and the Termux:API app. They are now native Android
calls, so nothing else has to be installed.

**2. Screen automation.** Describe a job and the app reviews a plan with you, then carries it out
through an Accessibility Service — one validated action at a time, with a floating **Stop** pill,
safety classification, redaction of passwords and OTPs, and a confirmation before anything
high-risk:

> *"open Settings and turn on battery saver"* · *"open Chrome and search Kotlin coroutines"* ·
> *"draft a WhatsApp message to John saying I'll be there in 10 minutes, don't send it"*

The decision step uses the same on-device model. If you also have the standalone
[TaskPilot](https://github.com/TherealCitali/TaskPilot) app installed, the Automate tab offers a
hand-off that copies the command and opens it.

**3. Remote control.** Text your phone through a Telegram bot; the messages run the same
on-device model and answer in the chat. Useful when the phone is in another room or another city.

## First run

1. **Install the APK** from the latest release or a workflow artefact.
2. **Download the model** — 35 MB, once. Tap *Download* on the Chat tab (or Settings → On-device
   model). It is checksum-verified against the hash the build was compiled with, resumes if it
   drops, and can also be imported from a `.cact` file for air-gapped installs. After that the app
   is fully offline.
3. **Grant what you want to use** — Tools tab lists every permission with a one-tap *Grant missing*.
   Nothing is required: the chat still answers without them.
4. **For screen automation** — Settings → *Accessibility settings* → enable **Needle screen
   automation**.

## How it is built

```
android/                                  Gradle project (the APK)
├── app/src/main/cpp/                     JNI bridge + CMake
│   ├── needle_jni.c                      locks, mmap of the weights, C API calls
│   └── needle.h                          Cactus Compute's public header (Apache-2.0)
├── app/src/main/java/dev/citali/needle/
│   ├── engine/                           model download + verification, tool schemas,
│   │                                     the session and tool-calling loop, the automation brain
│   ├── tools/                            native equivalents of the termux-* commands
│   ├── pilot/                            TaskPilot's agent, safety policy, accessibility
│   │                                     service, redaction, overlays and stores (MIT)
│   ├── remote/                           Telegram long-poll + foreground service
│   └── ui/                               Compose screens
├── keystore/needle-release.p12           the project signing key (see below)
└── gradle/wrapper/                       Gradle 8.9
.github/workflows/build-apk.yml           signed APK on every push
```

**The engine.** Cactus Compute publishes the Needle runtime as a static archive per Android ABI.
CMake fetches it at configure time, verifies the SHA-256 published by Hugging Face, and links it
into `libneedlejni.so`, so the APK carries a real inference engine and the weights stay a one-time
download. `needle_init` builds the static prefix from a system prompt plus the tool schemas — the
same contract the Python package uses — and the decode grammar then guarantees that every reply
is a valid tool call.

**The tools.** ~25 tools across four packs (Essentials, Device & screen, Calls & messages,
Camera & identity). Every declared tool shares the model's context, so packs can be switched off;
if the catalogue still does not fit, the app falls back to the Essentials set and says so.

**The automation core** is TaskPilot's, vendored and rewired: `AgentEngine`, `SafetyPolicy`,
`UiTree`/`Redactor`, `NeedleAccessibilityService`, the overlays and the DataStore/Keystore-backed
settings. Only the decision step changed — instead of an OpenAI-compatible endpoint, the
automation actions are declared to Needle as tools (`tap`, `type_text`, `swipe`, `press_key`,
`open_app`, `ask_user`, `task_complete`, `task_failed`) and the returned call is converted into the
action contract the existing loop, policy and executor already validated. A remote
OpenAI-compatible provider and the deterministic built-in routines remain as fallbacks.

## Signing

Release APKs are always signed. The repository ships a **project keystore**
(`android/keystore/needle-release.p12`, alias `needle`, store password `needle-release`) so a fresh
clone, a fork or a pull request can all produce an installable APK with no setup. That key is
public by design — good for sideloading, **not** for Play Store uploads.

For a private key, set these four repository secrets and the workflow uses them instead:

| Secret | Meaning |
| --- | --- |
| `KEYSTORE` | base64 of a `.p12`/`.jks` (`base64 -w0 my.p12`) |
| `KEYSTORE_PASSWORD` | store password |
| `KEY_ALIAS` | key alias |
| `KEY_PASSWORD` | key password |

The same four values can be passed to a local build as the environment variables
`NEEDLE_KEYSTORE_FILE`, `NEEDLE_KEYSTORE_PASSWORD`, `NEEDLE_KEY_ALIAS` and `NEEDLE_KEY_PASSWORD`.
If the committed keystore cannot be read, the workflow generates a fresh PKCS#12 for that single
build and warns — a build never fails for want of a signature.

Two more knobs: `NEEDLE_VERSION_CODE` / `NEEDLE_VERSION_NAME` (the workflow sets these from the run
number), and `NEEDLE_ALLOW_STUB=ON`, which builds without the engine for ABIs Cactus does not
publish — the app then says so instead of pretending to think.

## What a build does

Each run of the workflow (`.github/workflows/build-apk.yml`) does this, in order:

1. installs the SDK, build-tools, NDK and CMake the app needs;
2. downloads `libneedle.a` from `Cactus-Compute/needle3` and checks its SHA-256;
3. downloads `needle3.cact`, hashes it, and passes that checksum and size into Gradle — so the
   checksum an APK enforces on first run is always the checksum of the bytes that are really
   published;
4. builds `:app:assembleRelease` with the release keystore;
5. asserts the APK's package name, its native libraries and `arm64-v8a` native code, then verifies
   the signature with `apksigner` and writes `SHA256SUMS`;
6. uploads `Needle-1.0.<run>-<sha>.apk` as an artifact, publishes the `needle-build-<run>`
   pre-release (newest 10 kept) and, on `main`, refreshes `needle-latest`;
7. runs the JVM unit tests — the tool-schema shape the engine's grammar compiles and the safety
   policy that guards screen automation. They run last on purpose: a failing test turns the build
   red, but it can never be the reason a release is missing its APK.

Every build also records what it resolved (weights hash, APK hash) on the `ci-logs` branch.

## Building it yourself

```bash
# JDK 17, Android SDK (platform 35, build-tools 35.0.0), NDK 27.2.12479018, CMake 3.22.1
cd android
./gradlew :app:assembleRelease          # signed → app/build/outputs/apk/release/
./gradlew :app:assembleDebug            # installable side-by-side build (.debug suffix)
./gradlew :app:testReleaseUnitTest      # JVM tests (tool schemas, safety policy)
```

CMake downloads `libneedle.a` on the first build and caches it in `app/src/main/cpp/prebuilt/`.
The release APK is a single build for `arm64-v8a`. Cactus also publishes a 32-bit
`armeabi-v7a` archive, but it was compiled against an older libc++ and calls an internal helper
(`std::__hash_memory`) that current NDKs no longer provide, so it cannot be linked; run a build
with `NEEDLE_ABIS=arm64-v8a,armeabi-v7a` if you want to try it against a different NDK.

## Privacy and safety

- The model, the downloaded weights, the accessibility snapshots and the task history never leave
  the phone. There is no analytics and no telemetry.
- Accessibility-tree values are redacted before the model sees them: password fields and
  credential-shaped strings (OTP, card numbers, PINs, IDs) are masked.
- `SafetyPolicy` classifies every action; typing into a sensitive field or auto-typing a credential
  is refused outright, and sending, deleting, paying, installing or granting permissions asks again.
- The **Stop** pill is always reachable while a task runs, and the loop stops rather than guessing
  after five failures or a frozen screen.
- The only network calls are the one-time model download, Telegram if you enable it, and an
  AI provider if you configure one.

## Limitations

- **arm64 only.** The released APK contains the engine for 64-bit ARM devices. Cactus Compute
  publishes the Android engine for `arm64-v8a` and `armeabi-v7a`, and the 32-bit archive no longer
  links against current NDKs; an x86 emulator build has no engine at all.
- The Needle model is small on purpose. It is excellent at picking tools and filling arguments and
  it says so when a request is out of scope; it is not a general chatbot.
- Secure screens, WebViews and apps that block accessibility cannot be automated — the loop pauses
  and tells you instead of guessing.
- Camera capture and fingerprint checks need the app on screen; everything else works in the
  background.
- Changing the screen brightness needs the "Modify system settings" permission (Tools tab).
- Wi-Fi `ssid`/scan results need location permission, an Android restriction, not a choice.

## The original Termux assistant

The Python app this repository started as is still here and unchanged: `app.py` (Flask web UI +
Telegram bot) and `termux_needle.py` (CLI), using the `cactus-needle` package and the `termux-*`
commands. See the git history for its setup guide, or:

```bash
pip install -r requirements.txt
python3 app.py                            # web UI on http://127.0.0.1:5000
python3 termux_needle.py                  # CLI
```

The Android app is the same idea without the Termux dependency.

## Licences

- **Needle** — Copyright (c) Cactus Compute, Inc., Apache-2.0. The engine is fetched and
  checksum-verified at build time; `needle.h` is vendored.
- **TaskPilot** — Copyright (c) 2026 TherealCitali, MIT. The screen-automation core in
  `android/app/src/main/java/dev/citali/needle/pilot/` is adapted from it with the licence intact.
- Details and the modified-file list: [`NOTICE.md`](NOTICE.md), [`licenses/`](licenses/).
