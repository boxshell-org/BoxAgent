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

    # Rust workspace check + tests (host)
    cd core && cargo test --workspace

    # Native libs -> app/src/main/jniLibs/{arm64-v8a,x86_64}/lib{boxagent,boxagentd}.so
    ./scripts/build-rust.sh "$PWD/app/src/main/jniLibs"

    # Debug APK (runs cargoNdkBuild first unless -PskipRustBuild=true)
    $GRADLE :app:assembleDebug -PskipRustBuild=true
    # -> app/build/outputs/apk/debug/app-debug.apk

## Layout

- `core/boxagent-proto` — length-prefixed JSON frames shared app<->daemon
- `core/boxagentd` — pushed to /data/local/tmp, runs as shell uid 2000,
  abstract Unix socket + token auth, streamed exec / fs / screencap
- `core/adb-tls` — wireless-debugging pairing (TLS client-cert, SPAKE2 with
  EKM-bound password, AES-128-GCM PeerInfo) + AOSP adb pubkey encoding
- `core/boxagent-core` — JNI lib: tool registry, OpenAI-compatible SSE LLM
  client, agent loop, adb ops via adb_client crate
- `app/` — Kotlin shell: Compose UI (Apple B/W, see DESIGN.md), A11yService,
  AgentService (FGS), DaemonManager/Client, ToolRunner (risk + confirm gate),
  Room audit log, EncryptedSharedPreferences secrets

## Conventions

- Every tool call round-trips through `ToolRunner` (confirmation policy +
  audit); never call daemon/a11y directly from the agent.
- Secrets (LLM key, adb PEM, daemon token) only in `Secrets` — never audit-log them.
- `git -c user.name=Devin -c user.email=devin@cognition.ai commit` — repo has
  no user config.
