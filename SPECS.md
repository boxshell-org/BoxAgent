# BoxAgent — Product & Engineering Specification

Version: 0.1 (draft)
Status: Specification
Last updated: 2026-10-03

---

## 1. Overview

**BoxAgent** is an Android application that turns the user's own phone into a device an LLM can operate. The user grants elevated capabilities through official mechanisms (Developer Mode → Wireless Debugging/ADB, Accessibility), configures their own LLM API key, and then issues natural-language tasks. The app's agent loop translates those tasks into a sequence of **atomic utility functions** — shell-level operations (uid 2000, `shell`) and accessibility-level UI operations — executed locally on-device.

The product is a **Rust core + Kotlin shell** architecture:

- **Rust core** (`boxagent-core`) — the privileged daemon, agent engine, LLM client, tool registry, and wire protocol.
- **Kotlin shell** (`app`) — Jetpack Compose UI, Android services (foreground, accessibility), permission onboarding, and the JNI bridge.

**Design language:** Apple's minimalist black-and-white aesthetic (see `DESIGN.md`, adapted from web to Android). Monochrome palette, hairline separators, pill-shaped primary actions, tight display typography, zero decorative chrome.

### Design principles

1. **No root, no exploits.** Every capability comes from documented or AOSP-sanctioned surfaces (Wireless Debugging ADB, AccessibilityService, standard intents).
2. **Local-first.** API keys, chat history, and audit logs never leave the device except to the user's chosen LLM endpoint.
3. **Atomic tools, composed by the LLM.** The app exposes a flat catalog of small, well-typed functions; the model decides sequencing.
4. **Human in the loop.** Destructive operations require explicit user confirmation; every tool call is logged.
5. **Survive the OEM.** Keep-alive is engineered against aggressive battery management, with honest degradation when the system wins.

---

## 2. Goals and non-goals

### Goals

- G1. Acquire and maintain a local `shell` (uid 2000) execution context via Wireless Debugging ADB, entirely from on-device UI (no PC required on Android 11+).
- G2. Acquire Accessibility permission and expose a complete UI-operation toolkit (read tree, tap, swipe, type, gesture, screenshot).
- G3. Encapsulate all capabilities as atomic, schema-typed tool functions callable by an LLM.
- G4. Let users bring their own OpenAI-compatible LLM endpoint (base URL + API key + model) and run a multi-turn tool-calling agent loop on-device.
- G5. Keep the agent and daemon alive in the background as robustly as Android permits.
- G6. Ship a complete, beautiful, Apple-style monochrome UI covering onboarding, chat, status, tools, logs, and settings.

### Non-goals

- Root access or privilege escalation beyond `shell` uid.
- Remote control from another device (all control is local).
- Hosting our own LLM or proxying keys (user supplies endpoint).
- Screen recording/streaming server.
- Play Store policy-safe distribution is a target, but sideloading is acceptable if a permission (e.g. battery exemption) trips review.

---

## 3. Terminology

| Term | Meaning |
|---|---|
| **Daemon / `boxagentd`** | Rust binary pushed to `/data/local/tmp` and executed via ADB; runs as `shell` uid 2000. |
| **Shell context** | Execution environment of `boxagentd`. Can run `pm`, `am`, `input`, `settings`, `screencap`, `dumpsys`, file ops under shell permissions. |
| **Core / `boxagent-core`** | Rust library compiled as `libboxagent.so`, loaded via JNI into the app process. Contains agent loop, LLM client, tool schemas, protocol codec. |
| **A11y service** | `BoxAgentAccessibilityService` — Android AccessibilityService providing UI tree access and gesture dispatch. |
| **Agent runtime** | Kotlin `ForegroundService` + Rust agent loop that drives tool calls. |
| **Tool** | One atomic function in the catalog (§5.4). JSON-schema typed. |

---

## 4. Architecture

### 4.1 Module map

```
boxagent/
├── app/                      # Kotlin Android module (Gradle)
│   ├── ui/                   # Compose screens + Apple-B/W theme
│   ├── bridge/               # JNI wrappers (Core.kt)
│   ├── service/
│   │   ├── AgentService.kt           # foreground service, keep-alive
│   │   └── A11yService.kt            # accessibility service
│   ├── adb/                  # pairing/session orchestration (thin layer over Rust)
│   └── data/                 # DataStore prefs, Keystore-backed secrets
│
├── core/                     # Rust workspace (cargo)
│   ├── boxagent-core/        # cdylib → libboxagent.so (JNI)
│   │   ├── src/agent.rs      # agent loop, step cap, cancellation
│   │   ├── src/llm.rs        # OpenAI-compatible client (SSE streaming, tools)
│   │   ├── src/tools/        # tool registry + JSON schemas
│   │   ├── src/proto.rs      # length-prefixed JSON wire codec
│   │   └── src/jni.rs        # JNI surface
│   ├── boxagentd/            # bin → daemon executable (runs as shell uid)
│   │   ├── src/main.rs       # socket server, auth handshake
│   │   └── src/exec.rs       # command/file/screen ops
│   └── adb-tls/              # ADB-over-TLS client (pairing + connect + sync push)
│
├── DESIGN.md                 # Apple design system (source of truth for UI)
└── SPECS.md                  # this file
```

### 4.2 Privilege model

| Capability | Mechanism | UID / scope |
|---|---|---|
| Shell commands | `boxagentd` spawned via Wireless Debugging ADB | `shell` (2000) |
| UI reading + gestures | AccessibilityService | app uid, a11y APIs |
| Foreground persistence | ForegroundService + notification | app uid |
| Secrets | Android Keystore + EncryptedSharedPreferences | app uid |
| LLM network | HTTPS from app process (Rust `reqwest`+rustls or OkHttp path — decided §13) | app uid |

### 4.3 ADB bootstrap (Android 11+)

Self-contained flow, modeled on how Shizuku/LADB work but implemented in our Rust `adb-tls` crate:

1. User enables **Developer Options → Wireless Debugging**.
2. App discovers the pairing service via mDNS (`_adb-tls-pairing._tcp`) — or the user taps "Pair with pairing code" and types the port + 6-digit code manually.
3. `adb-tls` performs SPAKE2-based TLS pairing, registers our ADB keypair (stored in Keystore-wrapped storage).
4. App rediscovers `_adb-tls-connect._tcp`, opens a TLS ADB session (`CNXN/AUTH/OPEN/WRTE`), then:
   - `sync:` pushes `boxagentd` (shipped in `jniLibs` as `libboxagentd.so`, copied to files dir at install) to `/data/local/tmp/boxagentd`,
   - `shell:` runs `chmod 755` + exec with args: `--socket @boxagentd.<rand> --token <otp>`,
   - daemon binds an abstract Unix socket; app connects, authenticates with the one-time token (rotated each spawn), daemon discards the arg token after first auth and pins the app by uid.

**Fallback paths:** manual `adb shell` command from a PC (app displays the exact command), and optional **Shizuku backend** if Shizuku is already installed — both selectable in Settings → Privilege backend.

### 4.4 IPC

- Transport: abstract-namespace Unix domain socket.
- Codec: length-prefixed JSON frames (4-byte BE length + JSON), defined once in `proto.rs` and mirrored in Kotlin via kotlinx-serialization.
- Message types: `Exec`, `ExecResult(streaming)`, `FileRead`, `FileWrite`, `Screencap`, `Ping`, `Auth`, `Err`. Requests carry `id`; responses correlate by `id`.

### 4.5 Agent loop

Implemented in `boxagent-core`, driven by `AgentService`:

```
user prompt ──► assemble messages (system + history + task)
       ──► LLM chat.completions (streamed, tools catalog attached)
       ──► tool_call? ──► dispatch ─┬─► daemon socket (shell ops)
                                   └─► A11yService bridge (UI ops)
             result (truncated per limits) appended ──► loop
       ──► no tool_call ──► final answer ──► UI + history
```

- Step cap (default 40), wall-clock timeout (default 10 min), per-call timeout (default 30 s), user cancel at any point.
- Confirmation gate: tools flagged `requires_confirmation` pause the loop and surface an approval card in chat (with "approve once / always allow this tool" options).
- Context hygiene: screenshots downscaled (max 1280px, JPEG 70), node trees pruned (depth ≤ 30, cap ~16k chars), shell output truncated to 8k chars, oldest tool results summarized when history exceeds model context budget.

---

## 5. Feature requirements

### F1 — Privilege onboarding & lifecycle

- Guided 3-step onboarding: (a) Developer Options + Wireless Debugging, (b) pairing (auto mDNS or manual code), (c) Accessibility enablement + battery exemption. Each step has detection (poll state), instructions with screenshots-free line-art visuals, and a skip-for-later path.
- Status model: `SHELL_OFFLINE | PAIRING | SHELL_ONLINE`, `A11Y_OFF | A11Y_ON`, `DAEMON_DOWN | STARTING | UP`.
- Daemon auto-respawn on socket drop (when ADB session can be re-established); reboot → notification "Tap to restore BoxAgent" (Wireless Debugging resets on reboot on many devices).
- Health card on Home showing all capability states.

### F2 — Background keep-alive

- `AgentService`: foreground service, `specialUse` type (Android 14+), persistent notification "BoxAgent is active — N steps running", `START_STICKY`.
- Daemon watchdog: heartbeat ping every 15 s; reconnect with exponential backoff (1→30 s); respawn via live ADB session when possible.
- WorkManager: 15-min periodic `DaemonHealthWorker` to re-check daemon + service.
- `RECEIVE_BOOT_COMPLETED` receiver → restart FGS, prompt re-pair if needed.
- Battery: `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` prompt in onboarding; in-app doc for OEM killers (MIUI/ColorOS app battery settings) with per-OEM deep links where known.
- Wake strategy: `PARTIAL_WAKE_LOCK` only while a task is actively executing; released on task end.
- Honest limits: if the OEM kills the process tree, state recovery is best-effort; the spec requires graceful detection + restore prompt, not guaranteed survival.

### F3 — Accessibility UI toolkit

`BoxAgentAccessibilityService` with config: `canRetrieveWindowContent`, `canPerformGestures`, `canTakeScreenshot` (API 30+), `accessibilityEventTypes` = typeAll (configurable to reduce overhead), all packages, `FLAG_RETRIEVE_INTERACTIVE_WINDOWS`.

Exposed internally as coroutine API; bridged into the tool registry.

### F4 — Atomic tool catalog

Every tool has: `name`, `description`, JSON-schema `parameters`, `risk: readonly|moderate|destructive`, `requires_confirmation` flag, result schema. The catalog is defined in Rust (`tools/registry.rs`) and rendered both to the LLM and to the in-app Tools browser.

#### Shell tools (via `boxagentd`, shell uid)

| Tool | Risk | Description |
|---|---|---|
| `shell_exec` | destructive* | Run shell command; args: `cmd`, `timeout_ms`; returns stdout/stderr/exit. *gated by confirmation policy |
| `app_list` | readonly | `pm list packages` + installed-app metadata (name, version, enabled). |
| `app_launch` | moderate | `am start`/`monkey` by package or component. |
| `app_stop` | moderate | `am force-stop` / `am kill`. |
| `app_install` | destructive | `pm install` from device path (push via `file_write` first). |
| `app_uninstall` | destructive | `pm uninstall`. |
| `app_clear_data` | destructive | `pm clear`. |
| `screen_capture` | readonly | `screencap -p` → PNG/JPEG bytes (downscaled per §4.5). |
| `screen_info` | readonly | Resolution, density, orientation (`wm size`/`dumpsys display`). |
| `device_info` | readonly | Model, SDK, build props, battery, network summary. |
| `settings_get` / `settings_put` | readonly / destructive | Read/write `settings` namespace entries. |
| `file_read` / `file_write` | readonly / destructive | Read/write file at path (shell perms). |
| `file_list` | readonly | `ls -la` JSON-ified. |
| `process_list` | readonly | `ps -A` parsed. |
| `clipboard_get` / `clipboard_set` | readonly / moderate | `cmd clipboard`. |
| `input_tap` / `input_swipe` / `input_text` / `input_key` | moderate | `input` CLI fallbacks when a11y unavailable. |

#### UI tools (via AccessibilityService)

| Tool | Risk | Description |
|---|---|---|
| `ui_tree` | readonly | Dump interactive window tree: per-node `id`, `class`, `text`, `desc`, `bounds`, `clickable/scrollable/editable`, pruned per §4.5. |
| `ui_find` | readonly | Find nodes by text/content-desc/resource-id (regex allowed); returns matches + bounds. |
| `tap` | moderate | Tap at x,y or on a found node. |
| `long_press` | moderate | Long-press at point/node. |
| `swipe` | moderate | Gesture between two points with duration. |
| `pinch` | moderate | Pinch in/out at center point. |
| `scroll` | moderate | Directional scroll on a scrollable node or screen region. |
| `type_text` | moderate | Focus editable node (or current focus) and insert text via `ACTION_SET_TEXT` / IME fallback. |
| `key` | moderate | Global actions: `back`, `home`, `recents`, `notifications`, `quick_settings`, `power_dialog`. |
| `wait_for` | readonly | Wait until predicate (text/node/activity) appears or timeout. |
| `screenshot` | readonly | `AccessibilityService.takeScreenshot` (API 30+); falls back to `screen_capture`. |
| `launch_intent` | moderate | `am start` intent URI (deep links, settings screens). |

#### Agent/meta tools

| Tool | Risk | Description |
|---|---|---|
| `task_done` | readonly | Model signals completion with final summary. |
| `ask_user` | readonly | Model requests clarification; pauses loop, shows prompt card. |
| `notify` | readonly | Post a user-visible notification (used for background tasks). |

> The LLM-facing surface is intentionally the same catalog users see in the Tools tab — one source of truth, no hidden powers.

### F5 — LLM integration

- Config: `base_url` (default `https://api.openai.com/v1`), `api_key`, `model`, optional `organization`/custom headers, temperature (default 0.2), max tokens, system prompt (editable template with tool-use instructions and safety rules).
- Wire: OpenAI-compatible `POST {base_url}/chat/completions`, `tools[]` function-calling, `stream: true` SSE for assistant text; tool results sent as `role: tool` messages.
- Model presets: OpenAI, OpenRouter, DeepSeek, Moonshot, local (llama.cpp/Ollama on LAN) — preset just pre-fills base_url/model.
- Multi-key profiles; active profile switcher. Key stored in Keystore-encrypted storage, never logged (redacted in audit log).
- Agent UX: streaming assistant text, per-tool-call cards (name, args, result preview, duration, risk badge), running-step indicator, cancel button, retry-on-network-error with backoff.
- History: multi-conversation store (Room or DataStore-serialized), title auto-generated from first prompt, per-conversation model override.

### F6 — App UI

Screens (single-activity Compose, bottom-bar-free — nav via a minimal top bar + sheet, matching Apple's restraint):

1. **Onboarding** — paged, one capability per page, detection-driven.
2. **Home / Chat** — the primary surface. Message list (assistant text, tool-call cards, user bubbles), bottom input pill with send/cancel, conversation switcher in top bar.
3. **Status** — capability matrix (shell, a11y, daemon, LLM reachable), heartbeat sparkline, "repair" actions.
4. **Tools** — searchable catalog; tap a tool → schema detail + "Try it" playground (form → JSON args → run → result).
5. **Logs** — audit log: every tool call with timestamp, args (secrets redacted), result status, duration; filter by risk; export as JSON.
6. **Settings** — LLM profiles, confirmation policy, keep-alive options, privilege backend, theme (follow-system/light/dark), about.

Design execution per `DESIGN.md` (Apple) adapted to Android:

- **Color**: monochrome only — `canvas #FFFFFF`, `parchment #F5F5F7`, `hairline #E0E0E0`, `ink #1D1D1F`, `ink-muted #7A7A7A`, `surface-black #000000`. Dark theme = exact inversion (black canvas, near-white ink, `tile #272729` surfaces). Apple uses a blue accent; per brief we keep the single-accent grammar but render it in **pure black on white / white on black** — one accent, zero second colors. Semantic states use tone, not hue (error = outlined ink capsule, not red fill).
- **Type**: `Inter` (closest open SF Pro substitute) bundled locally; fallback `system-ui`/Roboto. Display 600-weight with -0.02em tracking; body 400 at 17sp-equivalent; captions 14sp. No weight-500.
- **Shape**: pill (999) for primary actions and input; 18dp for cards; 8dp utility; tiles unrounded.
- **Depth**: no shadows on chrome; elevation via surface alternation + hairlines; scrim only under modal sheets.
- **Motion**: one orchestrated moment (onboarding→home reveal); press = scale(0.95); everything else ≤200ms fades/slides that answer user action.
- **Chrome**: 44dp-equivalent thin top bar (black in dark surfaces), 56dp bottom input dock, edge-to-edge content, large air around hero/empty states.

### F7 — Security & privacy

- API key + ADB keypair in Keystore-backed encrypted storage; hardware-backed when available.
- Daemon socket: one-time token at spawn → uid pinning of the connected app; reject all other peers.
- Confirmation policy levels: `strict` (confirm all non-readonly), `balanced` (confirm destructive only, default), `autonomous` (no confirms — behind a scary toggle + warning sheet).
- Audit log is append-only within a session export; user can wipe it in Settings.
- No analytics, no crash telemetry, no third-party trackers. Network egress only to the configured LLM endpoint and ADB localhost TLS.
- `android:exported=false` on all components except the accessibility service and boot receiver (as required); a11y service refuses control from other apps.
- Manifest permissions (appendix A) kept to the minimum working set.

---

## 6. Data model & storage

| Store | Tech | Contents |
|---|---|---|
| `secrets` | EncryptedSharedPreferences (Keystore) | LLM api keys, ADB keypair, daemon token |
| `prefs` | DataStore (proto or JSON) | Settings, onboarding progress, confirmation policy |
| `conversations` | Room (3 tables: conversations, messages, tool_calls) | Chat history + tool call records |
| `audit` | Room table or append-only JSONL file | Operation log (separate retention setting) |

---

## 7. Error handling

- Typed error taxonomy in Rust (`thiserror`): `Adb`, `Daemon`, `A11y`, `Llm`, `Tool`, `Policy`. Each surfaces as a chat-card or status-pill state with a concrete repair action (re-pair, enable a11y, check key).
- Tool execution never crashes the loop: failures return structured `{error, hint}` to the model so it can self-correct.
- LLM errors mapped: 401→"check API key", 429→backoff + banner, context-length→auto-compact history, timeout→retry once.
- Daemon death mid-task → checkpointed step count, attempt respawn once, else fail task with resume option.

---

## 8. Testing plan

- **Rust**: `cargo test` — protocol codec round-trip, tool schema validity, LLM client against recorded SSE fixtures, exec parsing. `cargo clippy -D warnings`, `cargo fmt`.
- **Kotlin**: unit tests for reducers/mappers; Compose screenshot tests for theme tokens.
- **Integration** (device/emulator, API 30 & 34+): pair → spawn → `shell_exec("id")` == uid 2000; a11y `ui_tree` on Settings app; agent loop against a mock LLM server (fixture responses driving a 3-tool task).
- **Soak**: keep-alive survival over 24h on a stock-Pixel emulator image; document OEM caveats.

---

## 9. Dependency policy

"Appropriate but not large-scale" — allowlist only; anything else needs a spec change.

**Kotlin**: Jetpack Compose BOM (ui, material3, material-icons-core only), lifecycle-viewmodel/compose, navigation-compose, kotlinx-coroutines, kotlinx-serialization-json, OkHttp (LLM SSE fallback + debug), Room, DataStore, WorkManager, security-crypto. Optional: `rikka.shizuku` api only for the optional Shizuku backend.

**Rust**: `jni`, `ndk`/`ndk-context`, `tokio`, `serde`/`serde_json`, `thiserror`, `reqwest` (rustls, no native-tls), `tracing`, `base64`, `sha2`, `rand`, `mdns-sd` (or manual mDNS), `rustls` (ADB TLS), `rsa`/`ring` (ADB auth), `spake2` (pairing), `libc`.

Not allowed: UI kit libraries, analytics, ad SDKs, monolithic frameworks duplicating the above.

---

## 10. Milestones

| # | Deliverable | Exit criteria |
|---|---|---|
| M1 | Scaffold | Gradle+cargo-ndk build, JNI round-trip, Compose theme applied |
| M2 | Privilege | ADB pair → daemon spawn → `shell_exec` returns uid=shell from app UI |
| M3 | UI toolkit | a11y service live; `ui_tree`/`tap`/`type_text`/`screenshot` working via Tools playground |
| M4 | Keep-alive | FGS + watchdog + boot restore + battery exemption flow |
| M5 | Agent | LLM chat → multi-tool task completion with confirms, streaming, cancel |
| M6 | Polish | Onboarding, Logs, Settings complete; Apple B/W theme pass; audit export |
| M7 | QA | Integration suite green on API 30/34/35; soak report |

---

## 11. Risks & mitigations

| Risk | Mitigation |
|---|---|
| Wireless Debugging disabled on reboot / OEM-hidden | Persist ADB key; quick re-pair UX; PC-adb fallback path; Shizuku optional backend |
| OEM task killers (MIUI, ColorOS, Samsung) | Exemption prompts + per-OEM deep links + watchdog; document residual risk honestly |
| `takeScreenshot` needs API 30+, restricted windows (FLAG_SECURE) | Fall back to `screencap` via shell; surface "secure surface" as structured tool result |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` Play policy | Runtime prompt via intent (not auto-grant); acceptable for direct distribution |
| Context blowout from ui_tree/screenshots | Pruning/downscale budgets in §4.5, hard truncation |
| Model without tool-calling support | Preflight test call at config save; recommend presets known to support tools |

---

## 12. Open decisions

- **D1** LLM transport: Rust `reqwest` in core (single networking stack, core-owned loop) vs OkHttp in Kotlin (smaller native surface). Default: Rust `reqwest`+rustls — keeps agent loop self-contained.
- **D2** mDNS in Rust (`mdns-sd`) vs Android `NsdManager`. Default: `NsdManager` (Kotlin) for reliability, passing endpoints into Rust.
- **D3** Chat history: Room vs flat JSONL. Default: Room (queryable audit joins).
- **D4** Shizuku backend: optional, behind feature flag; core path must work without it.
- **D5** Min SDK: **30** (Accessibility `takeScreenshot`, Wireless Debugging 11+). API 29 and below = PC-adb fallback only, reduced screenshot capability.

---

## Appendix A — Manifest permissions (working set)

```
INTERNET, ACCESS_NETWORK_STATE, FOREGROUND_SERVICE,
FOREGROUND_SERVICE_SPECIAL_USE, POST_NOTIFICATIONS,
RECEIVE_BOOT_COMPLETED, REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
WAKE_LOCK, FOREGROUND_SERVICE_DATA_SYNC (if needed),
BIND_ACCESSIBILITY_SERVICE (on service decl), QUERY_ALL_PACKAGES*
```
\* `QUERY_ALL_PACKAGES` justified: `app_list` is a core tool; alternatively use PackageManager with intent queries — decided at M2.

## Appendix B — Wire frame example

```json
{ "id": 7, "type": "exec", "cmd": "pm list packages -3", "timeout_ms": 15000 }
→ { "id": 7, "type": "exec_result", "exit": 0, "stdout": "package:com.x\n...", "stderr": "", "duration_ms": 212 }
```

## Appendix C — System prompt (default template sketch)

"You are BoxAgent, an operator on the user's Android device. You act only through the provided tools. Prefer reading before acting; keep steps minimal; confirm before destructive actions unless the user pre-authorized; explain briefly what you did at the end. Screen reading: use `ui_tree` first, `screenshot` when layout/coordinates are ambiguous."
