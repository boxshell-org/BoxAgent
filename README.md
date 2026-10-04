# BoxAgent

An on-device LLM agent for Android — Rust core, Kotlin shell.

BoxAgent pairs with your phone's **Wireless Debugging** (Developer Options),
pushes a small Rust daemon that runs with **shell (uid 2000)** privileges, and
uses **Accessibility** to see and touch the screen. A user-supplied
OpenAI-compatible LLM then drives the phone through atomic tools
(`screen`, `tap`, `type_text`, `scroll`, `app_launch`, `shell_exec`, `act`, …)
with a risk-based confirmation policy.

The agent is built to be cheap per step: the screen is a compact numbered
element list (`[4] switch: Wi-Fi (on) @980,600`, ~5× smaller than a JSON
tree) that the model acts on by ref; actions return the settled screen
(no extra read round-trip); only the newest screen stays in context; the
tool set adapts to what's available; and `act` batches predictable steps.
Optional vision mode sends a downscaled screenshot with element refs drawn
on it.

**Skills** make repeated work cheap. A skill is instructions plus optional
deterministic steps (`{{param}}` placeholders, text selectors). The agent
sees a one-line index and loads or runs skills on demand; running a
step-based skill from the Skills tab spends **zero** LLM tokens, handing
over to the model only if a step stops matching the screen. Skills come
built in (search, links, maps, alarms, timers, settings pages, …), from
"Save as skill" on any finished run (steps recorded automatically), from the
agent when you ask it to remember something, or as shared JSON — the last
two arrive as drafts you review first.

The UI is Jetpack Compose in a monochrome
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
