# BoxAgent

An on-device LLM agent for Android — Rust core, Kotlin shell.

BoxAgent pairs with your phone's **Wireless Debugging** (Developer Options),
pushes a small Rust daemon that runs with **shell (uid 2000)** privileges, and
uses **Accessibility** to see and touch the screen. A user-supplied
OpenAI-compatible LLM then drives the phone through ~37 atomic tools
(`shell_exec`, `ui_tree`, `tap`, `type_text`, `screenshot`, `app_*`, …) with a
risk-based confirmation policy. The UI is Jetpack Compose in a monochrome
Apple-style design language, fully bilingual (English / 中文 — follow system
or pick in Settings).

## Architecture

```
app/          Kotlin shell — Compose UI, A11yService, AgentService (FGS),
              DaemonManager/Client, ToolRunner (confirm gate + audit), Room
core/
  boxagentd      daemon pushed to /data/local/tmp; abstract-socket JSON frames
  adb-tls        wireless-debugging pairing: TLS + SPAKE2 (EKM-bound) +
                 AES-128-GCM PeerInfo; AOSP adb pubkey encoding
  boxagent-core  JNI: tool registry, OpenAI-compatible SSE agent loop, adb ops
  boxagent-proto shared wire protocol (4-byte LE len + JSON)
```

## Build

Requires JDK 17, Android SDK (platform 35, build-tools 34), NDK
27.0.12077973, Rust stable with `aarch64-linux-android` +
`x86_64-linux-android` targets, and `cargo-ndk` ≥ 4.1.

```sh
./scripts/build-rust.sh "$PWD/app/src/main/jniLibs"
./gradlew :app:assembleDebug          # or -PskipRustBuild=true to reuse .so
# APK: app/build/outputs/apk/debug/app-debug.apk
```

See `SPECS.md` for the full design spec, `DESIGN.md` for the UI tokens, and
`AGENTS.md` for dev environment notes.

## Safety

No root exploits — all capabilities go through official surfaces (Wireless
Debugging, Accessibility, foreground service). Destructive tools require
confirmation under the default policy; every tool call is audit-logged.

## License

GPL-3.0 — see [LICENSE](LICENSE).
