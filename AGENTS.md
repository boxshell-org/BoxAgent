# BoxAgent — build & dev notes

## Toolchain

Everything lives under `/www/boxagent-toolchain` (the home partition is small):

    JAVA_HOME=/www/boxagent-toolchain/jdk
    ANDROID_HOME=/www/boxagent-toolchain/android-sdk
    ANDROID_SDK_ROOT=/www/boxagent-toolchain/android-sdk
    ANDROID_NDK_HOME=$ANDROID_HOME/ndk/27.0.12077973
    CARGO_HOME=/www/boxagent-toolchain/cargo
    RUSTUP_HOME=/www/boxagent-toolchain/rustup
    PATH=$CARGO_HOME/bin:/home/lemon/.cargo/bin:$PATH   # cargo + cargo-ndk
    GRADLE=/www/boxagent-toolchain/gradle-dist/gradle/bin/gradle

Rust targets installed: `aarch64-linux-android`, `x86_64-linux-android`,
`armv7-linux-androideabi`, host `x86_64-unknown-linux-gnu`.

`core/target` is a symlink to `/www/boxagent-target/core` — it holds all
build profiles (host debug/test, android release). Do NOT `cargo clean`
or delete it; a cold dep rebuild costs ~4 min while an incremental
`cargo test` is ~1 s. Gradle cache/parallel are already on in
gradle.properties.

## Build

    # Rust workspace check + tests (host; includes a daemon integration test
    # over real loopback TCP + the legacy abstract socket, SPAKE2 known-answer
    # vectors from BoringSSL, and agent-loop tests against a mock LLM)
    cd core && cargo test --workspace

    # Kotlin JVM unit tests (screen model, skills codec, markdown, settings
    # migration, Room migration SQL) + Paparazzi UI renders
    $GRADLE :app:testDebugUnitTest -PskipRustBuild=true

    # Re-record UI screenshots after UI changes, then review the PNGs in
    # app/src/test/snapshots/images/ (light + dark, Pixel 6)
    $GRADLE :app:recordPaparazziDebug -PskipRustBuild=true

    # Native libs -> app/src/main/jniLibs/{arm64-v8a,x86_64}/lib{boxagent,boxagentd}.so
    ./scripts/build-rust.sh "$PWD/app/src/main/jniLibs"

    # Debug APK (runs cargoNdkBuild first unless -PskipRustBuild=true)
    $GRADLE :app:assembleDebug -PskipRustBuild=true
    # -> app/build/outputs/apk/debug/app-debug.apk

## Layout

- `core/boxagent-proto` — length-prefixed JSON frames shared app<->daemon
- `core/boxagentd` — pushed to /data/local/tmp, runs as shell uid 2000,
  127.0.0.1 TCP (`--port`) + token auth, streamed exec / fs / screencap.
  Never an abstract/unix socket for the app: SELinux denies
  `untrusted_app → shell connectto` on user builds (redroid is permissive,
  so it only *looks* fine there)
- `core/adb-tls` — wireless-debugging pairing (TLS client-cert, SPAKE2 with
  EKM-bound password, AES-128-GCM PeerInfo) + AOSP adb pubkey encoding.
  M/N are BoringSSL's points (not RFC 9382's) — pinned by tests
- `vscreen/` — shell-uid `app_process` host owning the virtual display
  (loopback TCP like the daemon); `app/vscreen/` — manager, client, the
  backdrop activity that keeps the display from mirroring the real screen
- `app/daemon/PairingService` — pairing code typed into an inline-reply
  notification so Settings' pairing dialog stays open
- `core/boxagent-core` — JNI lib: tool registry (capability-filtered tool
  set), OpenAI-compatible SSE LLM client, agent loop (`act` batching),
  `context.rs` (screen dedup/elision, images, repeat hints, usage),
  `prompt.rs` (built-in operating guide), `skills.rs` (skill index,
  `{{param}}` / `{{param|url}}` expansion, zero-LLM skill runs), adb ops via
  adb_client crate
- `app/skills/` — skill model + codec (validation, import/export, recording
  refs → text selectors), built-in library, Room-backed `SkillRepository`
  (DB v2; `MIGRATION_1_2` must mirror Room's generated SQL — a unit test
  checks it)
- `app/screen/ScreenModel.kt` — pure-Kotlin compact screen (`[ref] role:
  label (state) @x,y`) with stable refs; A11yService snapshots into it
- `app/` — Kotlin shell: Compose UI (Apple B/W, see DESIGN.md), A11yService,
  AgentService (FGS), DaemonManager/Client, ToolRunner (risk + confirm gate),
  Room audit log, EncryptedSharedPreferences secrets

## Conventions

- Every tool call round-trips through `ToolRunner` (confirmation policy +
  audit); never call daemon/a11y directly from the agent. `act` steps are
  expanded in the core and each step goes through `ToolRunner` too.
- Screen-changing a11y tools return `screen` (after settling) unless called
  with `observe:false`; the core keeps only the newest screen in context.
- Skills from the agent (`save_skill`) or imports are stored as drafts; the
  agent only sees approved, enabled skills. Skill steps run through
  `ToolRunner` like any call.
- UI screens are stateless `*Content` composables plus a thin wrapper that
  wires `BoxAgentApp`; keep it that way so Paparazzi can render them.
  Text drawn on the background relies on `LocalContentColor` from
  `BoxAgentTheme` (don't hardcode black/white).
- Secrets (LLM key, adb PEM, daemon token) only in `Secrets` — never audit-log them.
- `git -c user.name=Devin -c user.email=devin@cognition.ai commit` — repo has
  no user config.
