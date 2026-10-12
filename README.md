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

Port in progress, built and reviewed phase by phase. The safety-critical core is done and tested;
the UI and Android platform layer are next.

| Phase | What | State |
|---|---|---|
| 0 | Read & understand the iOS sources | ✅ |
| 1 | Protobuf regenerated for Kotlin (protobuf-java) | ✅ |
| 2b | `:flipper` RPC/framing/device model + simulator, green on the JVM | ✅ |
| 3 | `:agent` safety core (risk, approval, engagement, fencing, audit, LLM) | ✅ |
| 4 | Compose UI (Chat / Device / Files / Remote / Settings) | ✅ code complete |
| 5 | Android platform adapters (BLE, Keystore, notifications, speech…) | ✅ code complete |
| 2a | Real-device BLE round-trip + proto wire-parity | ✅ verified on a Pixel 9a (GrapheneOS) + Momentum Flipper |

Deferred for v1 (stubs/notes, called out honestly): camera attach (CameraX), home-screen
widgets (Glance), and firmware-update-over-BLE. Voice input/TTS are present but degrade to text
when no provider exists (the GrapheneOS case).

**111 JVM tests pass** (`./gradlew :flipper:test :agent:test`), including the ported acceptance
suites — PolicyTests, PermissionTests, EngagementTests, ExecutorTests — plus safety-invariant,
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
:app      Android app: Compose UI + the platform implementations of the interfaces above. (not yet built)
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
./gradlew :flipper:test :agent:test
```

## Credits & license

A port of [flipper-hero/ios-app](https://github.com/flipper-hero/ios-app) by Franz Bettag and the
FlipperHero contributors. Protobuf definitions from
[Next-Flip/flipperzero-protobuf](https://github.com/Next-Flip/flipperzero-protobuf). BLE protocol
details follow the Momentum firmware and the official Flipper apps.

MIT licensed — see [LICENSE](LICENSE). Use FlipperHero with your own devices and systems you are
authorized to test. Flipper Zero is a trademark of Flipper Devices Inc.; this project is not
affiliated with them.
