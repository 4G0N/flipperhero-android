# FlipperHero for Android

An **Android port** of [FlipperHero](https://github.com/flipper-hero/ios-app) — the MIT-licensed
companion app for the [Flipper Zero](https://flipperzero.one/) that connects over Bluetooth LE,
speaks the Flipper's protobuf RPC, and wraps it in an AI agent that **asks for approval before any
action that changes state or transmits**.

This is a ground-up native rewrite in **Kotlin + Jetpack Compose**, not a GitHub fork of the Swift
sources (a fork graph wouldn't make sense across languages). If you found the iOS app and wished it
ran on Android, this is for you.

> **Target: GrapheneOS, not stock Android.** This port is built and tuned for a Pixel running
> [GrapheneOS](https://grapheneos.org/) with **no Google Play Services**. Concretely that means:
> everything works locally with no Google backend, there are **no Play Integrity / SafetyNet**
> attestation dependencies (they fail on GrapheneOS anyway), notifications are local (no FCM),
> shortcuts are AOSP App Shortcuts (not Google Assistant), and speech/TTS **detect the absence of a
> provider and degrade to text** rather than assuming one exists. It will still run on stock
> Android, but the design choices optimize for the de-Googled case.

## Status

Built and reviewed phase by phase. The safety-critical core, the Compose UI and the Android
platform layer are all in place, and the app has driven real agent-to-Flipper conversations on
hardware. It is a sideloaded debug build for now: there is no release signing or store listing.

| Phase | What | State |
|---|---|---|
| 0 | Read & understand the iOS sources | ✅ |
| 1 | Protobuf regenerated for Kotlin (protobuf-java) | ✅ |
| 2b | `:flipper` RPC/framing/device model + simulator, green on the JVM | ✅ |
| 3 | `:agent` safety core (risk, approval, engagement, fencing, audit, LLM) | ✅ |
| 4 | Compose UI (Chat / Device / Files / Remote / Settings) | ✅ code complete |
| 5 | Android platform adapters (BLE, Keystore, notifications, speech…) | ✅ code complete |
| 2a | Real-device BLE round-trip + proto wire-parity | ✅ verified on a Pixel 9a (GrapheneOS) + Momentum Flipper |
| 6 | First agent shakedown on hardware (chat → tools → Flipper) | ✅ working with Anthropic `claude-opus-4-8` |

Deferred for v1 (stubs/notes, called out honestly): camera attach (CameraX), home-screen
widgets (Glance), and firmware-update-over-BLE. Voice input/TTS are present but degrade to text
when no provider exists (the GrapheneOS case).

**121 JVM tests pass** (`./gradlew :flipper:test :agent:test :app:testDebugUnitTest`), including the
ported acceptance suites — PolicyTests, PermissionTests, EngagementTests, ExecutorTests — plus safety-invariant,
TOCTOU and full-stack simulator tests. Those tests are the definition of a faithful port; they are
the thing to review.

## Architecture

Ports-and-adapters, so the safety logic is pure JVM and testable without a phone. The two core
modules are **pure JVM on purpose** (`java-library`, not `com.android.library`) — an accidental
`import android.*` fails to compile.

```
:proto    generated protobuf (protobuf-java), pure JVM
:flipper  RPC client, framing, device model, and a Transport interface. Pure JVM. (→ :proto)
:agent    tools, ToolExecutor, RiskAssessor, approval, engagement, audit, LLM providers. Pure JVM. (→ :flipper)
:app      Android app: Compose UI + the platform implementations of the interfaces above.
```

Each platform capability is an interface the pure module **defines** and `:app` **implements**:
`Transport` (BLE) → `AndroidBleTransport`, `ApprovalGate` → a press-and-hold sheet, `AuditSink` →
an append-only log, plus `SecretStore`, speech/TTS and an ongoing-activity notification.

## The safety model (ported intact)

The guardrails are the point of the app, and they are ported faithfully, not simplified:

- **Risk is computed in code from the parsed tool call**, never supplied or influenced by the model.
  Reads are low; changes ask; transmit / emulate / delete / code-exec need an explicit press-and-hold.
- **Untrusted-input fencing:** everything read from the device or the internet is cleaned and fenced
  with a per-session marker; reading tainted content re-arms approval for every change until the next
  user message.
- **Path guards, the `rpc_raw` lock, and engagement/YOLO arming** are enforced at the executor and
  fail closed. `/int`, key/secret files, `..` and recursive top-level deletes stay blocked.
- **What you approve is what runs:** generated payloads and downloads are prepared during approval
  and reused, never regenerated between approval and execution (no TOCTOU).
- **Append-only audit log** of every action and how it was authorized.

## AI providers

Bring your own API key. Pick a provider in Settings by its id; the base URL and default model fill
in, and each provider's key is only ever sent to that provider's own hosts.

| Provider id | API | Notes |
|---|---|---|
| `openrouter` (default), `ai2342`, `blackbit`, `orcarouter`, `zai`, `kimi`, `qwen`, `minimax` | OpenAI-compatible chat completions | Ported from the iOS app. |
| `anthropic` | Anthropic Messages API (official Java SDK) | Android-only addition. Default model `claude-opus-4-8`. |

- **Keys stay on the device.** A key is written to Android Keystore-backed encrypted storage, is
  write-only in the UI, is excluded from backups, and is never shown to the model (it only learns
  whether one is stored).
- **Refusals are shown, not hidden.** If Anthropic's safety classifier declines a request, the chat
  says so with the category. Nothing is silently retried on a different model.
- **Why not the newest Opus:** on hardware, `claude-opus-5-5` declined even a read-only "what
  firmware is this?" request with category `cyber`; the app's tool list (transmit, emulate, BadUSB)
  rides along on every call. `claude-opus-4-8` works. You can type any model id in Settings.
- **Known gap:** the OpenAI-shape providers refuse HTTP redirects so a key cannot be forwarded to
  another host. The Anthropic SDK's transport does not expose that switch, so that provider relies on
  host pinning and HTTPS alone.

## Protobuf

The generated `.pb.swift` in the iOS repo came from the **Momentum** protobuf fork, not upstream
`flipperdevices/flipperzero-protobuf`. We pin
[`Next-Flip/flipperzero-protobuf@ea4f185f`](https://github.com/Next-Flip/flipperzero-protobuf) and
verified field-number parity against the committed Swift; see
[`proto/PROTO_SOURCE.txt`](proto/PROTO_SOURCE.txt). Byte-level wire parity is confirmed against real
hardware in phase 2a.

## Build & test

Needs a JDK 17+ (the Android Studio JBR works) and the Android SDK. The pure core modules build and
test with no emulator:

```sh
./gradlew :flipper:test :agent:test :app:testDebugUnitTest
```

Build and sideload the app (USB debugging on; set `sdk.dir` in `local.properties` or `ANDROID_HOME`):

```sh
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The two instrumented tests need real hardware or network and are not part of the JVM suite:
`BleSmokeTest` (a Flipper in range, PIN confirmed by hand) and `AnthropicSdkSmokeTest` (network only;
it uses a deliberately invalid key).

## Credits & license

A port of [flipper-hero/ios-app](https://github.com/flipper-hero/ios-app) by Franz Bettag and the
FlipperHero contributors. Protobuf definitions from
[Next-Flip/flipperzero-protobuf](https://github.com/Next-Flip/flipperzero-protobuf). BLE protocol
details follow the Momentum firmware and the official Flipper apps.

MIT licensed — see [LICENSE](LICENSE). Use FlipperHero with your own devices and systems you are
authorized to test. Flipper Zero is a trademark of Flipper Devices Inc.; this project is not
affiliated with them.
