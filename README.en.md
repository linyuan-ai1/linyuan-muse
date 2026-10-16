# linyuan-muse

The open-source edition of **lin渊** ("lin-yuan"): a native Android AI assistant that lives on your phone — not a web page, and no server of your own required. Once installed it is a resident AI that can listen, speak, read the screen, operate the phone, go online, and convene a whole room of experts for a discussion — plus a "proactive assistant" that schedules its own wake-ups to get work done.

> Why the name: the early version was called "Voice Companion", but the app had long outgrown voice chat, and that name made anyone picking up the project file it away as a "voice-companion app". It was renamed **lin渊** on 2026-10-06.
> The public release uses the name **linyuan-muse**; the app's display name on the phone stays **lin渊**.
> The project folder, project name, source package (`com.linyuan.app`) and display name are all renamed. Only `applicationId` and the database/preferences file names keep their historical values, for **data compatibility** — see "Internal identifiers" below.

Current version: `0.1.0` (minSdk 26 / targetSdk 35, ~74k lines of Java, **no androidx**).

[中文说明 → README.md](README.md)

## Screenshots

<table>
<tr>
<td><img src="screenshots/01-main-chat.jpg" width="240"></td>
<td><img src="screenshots/02-group-templates.jpg" width="240"></td>
<td><img src="screenshots/03-group-members.jpg" width="240"></td>
</tr>
<tr>
<td><img src="screenshots/04-group-discussion.jpg" width="240"></td>
<td><img src="screenshots/05-group-list.jpg" width="240"></td>
<td><img src="screenshots/06-group-plans.jpg" width="240"></td>
</tr>
<tr>
<td><img src="screenshots/07-docs.jpg" width="240"></td>
<td><img src="screenshots/08-settings.jpg" width="240"></td>
<td><img src="screenshots/09-image-gen.jpg" width="240"></td>
</tr>
<tr>
<td><img src="screenshots/10-image-generating.jpg" width="240"></td>
<td><img src="screenshots/11-web-search.jpg" width="240"></td>
<td><img src="screenshots/12-history.jpg" width="240"></td>
</tr>
<tr>
<td><img src="screenshots/13-input-panel.jpg" width="240"></td>
<td><img src="screenshots/14-context-usage.jpg" width="240"></td>
<td><img src="screenshots/15-thinking-level.jpg" width="240"></td>
</tr>
<tr>
<td><img src="screenshots/16-image-gen-dark.jpg" width="240"></td>
<td><img src="screenshots/17-group-usage.jpg" width="240"></td>
<td></td>
</tr>
</table>

> Real-device screenshots (OPPO phone; light and dark themes). All files are in [`screenshots/`](screenshots/).

## What it does

### Main chat

- **Streaming output**: while generating, the reply is split into three live sections — *thinking* / *tool calls* / *answer* — which collapse when the turn ends (long answers can be collapsed manually).
- **Six thinking levels** (off / light / medium / high / extreme / Ultra) on a hand-drawn slider. The level is mapped step-wise onto request parameters, with **parameter negotiation**: if a model does not accept a parameter, the request is automatically retried at a lower setting instead of failing.
- **Model card in the top bar**: tap the model name to switch models (it lists the ones available on the current provider) and tap the level chip to change thinking strength. Providers are switched from the "＋" panel on the input bar.
- **Input bar**: tapping "＋" rotates it into "×" and opens a two-page swipeable panel (page 2 holds the **gallery** and **scheduled tasks**). The 📷 button carries three attachments in one (camera / image / file); attachments can be previewed and withdrawn.
- **Context-usage sheet**: it measures a **real request** along the actual send path (system prompt / memory / tools / conversation, in tokens) — the card and skeleton appear first, then the measured numbers.
- Long conversations are compacted automatically; repeated-output degeneration is detected; reply latency and token usage are tracked.
- Conversation history: height-capped list (buttons can't be pushed off-screen), editable note badges, and an image gallery.

### Group discussion

- **Role library**: experts / engineering roles / famous-minds / your own templates — pick any set to form a room. The discussion can search a **material pool** and a **user archive**; subagents can be pulled in to do work.
- **Member management**: an admin (the AI side) can propose adding / removing members or changing their configuration; changes apply after you confirm.
- **Four phases**: explore → deepen → converge → finalize, advanced automatically by the confirmation ratio of members.
- **Budget guard**: default 1,000,000-token context budget; at 95% the discussion is forced to wrap up (it suggests exporting the plan and opening a new room).
- **Goal mode**: run unlimited rounds (cap 9999) until every member confirms.
- Questions that need your call are collected in one place; the whole discussion can be **exported as a plan document** into the app's document library.

### Tools (the AI calls them as needed)

**90 tools** in total, handed out per scenario rather than all at once:

| Scenario | Tools | Categories |
| --- | --- | --- |
| Main chat | 56 | system control 16, in-app browser 8, Amap 9, communications 5, files/photos/calendar 5, tasks 3, image generation 2, memory & history 4, group chat 2, web 2 |
| Group chat · admin | 22 | expert library, member proposals, discussion / plans / usage / progress, error retries, archives, web |
| Group chat · member | up to 13 | questions, agreements & convergence, material pool, user archive, reports, pulling in experts |
| Assistant panel / subagents | up to 61 | the whole main-chat set filtered by a grant list (59 by default) + 2 subagent tools |
| Unattended runs | 6–8 | scheduling, memory writing, perception, history lookups (no hands-on tools) |

Covered: flashlight / volume / brightness / DND / alarms / media, launching and stopping apps, accessibility automation (tap, swipe, back, read screen, clipboard, typing), tap-script recording and replay, screenshots, contacts CRUD, phone calls, SMS send/read, call log, file browsing and editing, photos, calendar read/write, the in-app browser (open / search / click / type / read article / list links / back / close), Amap (find places / routes / navigation / locate / traffic & satellite layers / weather), image generation, web search and page fetching, memory and history search.

### Memory and knowledge

- **SQLite long-term memory**, retrieved with a hybrid ranking of keyword score + vector cosine; the embedding model defaults to `Qwen/Qwen3-Embedding-0.6B` (address and model configurable).
- Memory is no longer extracted by keywords on the fly: **after 30 seconds idle, or when you switch conversations, a dedicated memory model consolidates it** (add / rewrite / merge + entity extraction). That model can have its own endpoint, model name and key.
- **Two notebooks**: user memory and the AI's own `self_note` are stored separately; the assistant panel keeps yet another set (instructions / episodes / judgements / self-description), isolated from user memory.
- When something is stored, a small banner appears at the top ("remembered N items") and fades after 2.6 seconds.
- **Document library**: imported documents are chunked at 500 characters (up to 800 chunks) and retrieved by cosine similarity (top 5). Text-based PDF and plain-text files are supported; **docx and scanned PDFs are not** (they are rejected as unreadable).
- **Backup / restore**: one tap exports a JSON (via the system save dialog, defaulting to Download) that you can drop into WeChat Favorites; restoring imports memories, knowledge base, conversations, documents and the perception archive.

### Perception archive (a phone dossier tree)

- "Perception records" store long-lived facts about your phone as a **knowledge-graph-style tree**, shown on a zoomable, drag-and-pan **mind-map canvas** (curved branches between parent and child nodes).
- When injected into context it carries **only the title skeleton** (`#id` plus indentation per line); the AI fetches the body by id with the `perception` tool — cheap in tokens, and irrelevant content never pollutes the conversation.
- A built-in "Perception Archive Universal Framework" node seeds the skeleton.

### Voice

- **Call**: tap "📞 Call" at the bottom for a **hands-free continuous call** (no push-to-talk) with automatic voice-activity segmentation; tap again to hang up. In a call the AI **speaks its reply automatically** (streamed), and you can **interrupt** it simply by talking.
- Replies outside a call are **not synthesized automatically**: tap the play button on a bubble to synthesize; the audio is cached, so a second tap costs nothing.
- **Speech recognition**: microphone audio goes to the speech service you configure (an Aliyun Bailian / DashScope ASR channel). The Volcano/Doubao ASR branches remain in the code but are currently **disabled** (off by default, entry hidden).
- **Multi-channel TTS fallback**: Doubao bidirectional streaming → Tencent Cloud → Aliyun model pool → Aliyun key pool → single-account → generic; any level failing drops to the next, so a dead channel doesn't mean silence.
- **On-device voiceprint** (off by default): sherpa-onnx + CAM++ (`app/src/main/assets/voiceprint.onnx`), fully local, audio never uploaded. When enabled, a call first checks "is this the owner's voice" and ignores other segments (if the model or analysis errors out, audio is let through — it will never lock you out).
- **Free quota pools**: CosyVoice 10k characters/month, Sambert 30k characters/month, restored automatically across months; the key pool rotates and disables a failing key for 30 minutes; dates are network-time-verified (so changing the phone clock can't farm quota).

### Proactive assistant and automation

- **Proactive assistant**: the top entry on the settings page, a panel built like the main chat but with its own model, thinking level, role prompt and memory (stored under the `assistant_panel` preferences, isolated from the main chat). It has a "📊 usage" button (token report) and three real attachment actions (camera / gallery / file).
- **Scheduled self-activation**: the assistant can schedule its own wake-ups (`schedule_self`; minimum 5 minutes, maximum 15 days, at most one every 5 minutes). Unattended runs only get scheduling, memory, perception and history tools — no hands-on tools.
- **Subagents**: create named subagents, each with its own granted tools, context and thinking level; the main chat or assistant dispatches work with `call_subagent` and the subagent reports back. Built-in subagents cannot be modified.
- **Scheduled tasks**: AlarmManager exact alarms (falling back automatically if the exact-alarm permission is denied), with edit / pause / resume / delete; results are written back into the conversation and a notification is posted if enabled.
- **WeChat auto-reply**: input bar "＋" panel page 2 → ⏰ scheduled tasks → "📱 WeChat auto-reply". Supports daily or weekday time windows (crossing midnight, e.g. Mon & Wed 23:00–03:00, with a live preview in the form), a master switch and per-window pause. It injects user memory / AI self-notes / entities / knowledge-base snippets (not the assistant's memory set). The prompt does one thing only — reply to WeChat messages: anything uncertain or sensitive (money, promises, privacy, arguments) is left unanswered, and it never makes commitments on your behalf.
- **Accessibility automation**: the AI can simulate taps, swipes, back / home / notifications / recents, read the screen, write the clipboard, type text and take screenshots. "Tap script" lets you tap coordinates on a full-screen recording overlay, drag and undo, then have `tap_script` replay it.
- **Phone-awareness tools**: location, weather, step count, battery and system status, notifications, usage time.

### Phone integrations you don't often see

- **Typing channel (a built-in IME)**: `LinYuanImeService` is a **headless input method** that types character by character into any input field via `InputConnection.commitText()` (typewriter effect). It works even in WeChat, whose input fields are invisible to accessibility services, and it **can type Chinese** (which `adb shell input text` cannot). Sending is done with `performEditorAction()` instead of you pressing the send key, and whether the message actually went out is **read back and verified** rather than blindly reported as success. Voice commands "switch to your IME / switch back" are executed by the AI via `app_control(ime_on / ime_off)`. A manual switch **sticks** (the AI won't switch back after typing) so you can have it fill several fields in a row — note this stickiness is runtime state and is lost if the service restarts. Switching back uses `InputMethodService.switchInputMethod()` and needs no permission.
- **Desktop island**: `SystemIslandService` + `BrowserIslandView` draw around the punch-hole camera. The app **hides it while in the foreground and keeps it on screen once backgrounded**; **double-tap** it to toggle a call (talk to the AI like a walkie-talkie without opening the app, double-tap again to hang up). The petal width has three automatic steps: standby `18dp` → call standby `34dp` → working / user speaking `58dp`. Text scrolls right-to-left with adaptive speed and stays in sync with the spoken audio.
- **In-app browser**: open pages, search, click, type (**password fields are refused**), read the article text, list clickable links, go back, close — without leaving the app.
- **Built-in maps**: the Amap SDK — find places, routes (including transit), turn-by-turn driving navigation, locate, traffic / satellite layers, weather.

## Building the APK

You need JDK 17 (Android Studio's bundled JBR is fine) and Gradle 8.7 (the repo ships no gradle wrapper; use your locally installed one):

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME = '<your Android SDK path>'
& "<path to Gradle 8.7>\bin\gradle.bat" assembleDebug
```

> Before the first build, create `local.properties` in the repo root containing `sdk.dir=<your Android SDK path>` (already git-ignored).

The APK lands in `app/build/outputs/apk/debug/app-debug.apk`.

Install on a connected phone:

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

**Dependencies.** The repo ships `sherpa-onnx-1.13.6-slim.aar` (Apache-2.0). The Amap (Gaode) navigation SDK (`amap-navi-map-loc-search-11.3.100.jar`, ~106 MB) is **proprietary and its licence does not allow redistribution**, so download it yourself from the [Amap open platform](https://lbs.amap.com/) and put it in `app/libs/` (see `app/build.gradle` for the exact file name and version). The Amap key is configured at runtime in the app's settings, never in source.

## Configuring the AI

**Two ways: fill it in on the settings page, or just tell the AI.**

Settings-page UI (under *Settings → ② Model & API*): speech synthesis (Doubao / Tencent Cloud, with a key pool and voice preview), Embedding, image recognition (Vision), built-in maps (Amap, including the signing SHA1). Under *Settings → ① Basic switches → Web search* you'll find the search endpoint, key, key pool and rate limits.

**Core items such as the chat model, ASR and image generation deliberately have no UI** — just tell the AI "set the image endpoint and key to xxx" and it writes them through `app_control(set_setting)`; `get_settings` reads them back for verification. Writable keys include: LLM (`api_key` / `model` / `base_url` / `system_prompt` / `max_tokens` / `temperature` / `top_p`), toggles (thinking / memory / web), TTS, ASR, Embedding, search (including `search_key_pool`), image generation (`image_base_url` / `image_model` / `image_key_pool` / `image_concurrency`) and the Amap key. **Deletions are impossible for the AI** — conversations, memories and settings can only be deleted by you, by hand.

You need at least one chat model (Aliyun Bailian / Volcano Ark / any OpenAI-compatible service):

- Aliyun Bailian: base `https://dashscope.aliyuncs.com/compatible-mode/v1`, model e.g. `qwen-plus`
- Volcano Ark: base `https://ark.cn-beijing.volces.com/api/v3`, model = the endpoint ID you created in the console
- OpenAI-compatible: the service's compatible base URL, model name and API key

Everything else is optional and simply unavailable when left blank: vector search needs an Embedding key (the address and model already default to the public SiliconFlow + `Qwen/Qwen3-Embedding-0.6B`, but you must supply the key); memory consolidation can use its own model (blank = the main chat model); call transcription uses Aliyun Bailian ASR; search and image generation each need an "endpoint + key" pool.

API keys are stored only in the app's private preferences file (inside the app sandbox, **with no extra encryption**) and never written into source; the phone data collected by "perception records" is additionally encrypted at rest with a system key (AndroidKeyStore, AES-GCM). **The public build ships with the built-in search / image key pools emptied** — supply your own endpoint and key; without a key the relevant tool simply reports "not configured" and nothing else breaks.

## Internal identifiers (what was renamed, and what was **deliberately** kept)

Renamed: project folder (`lin渊-android`), project name (`settings.gradle`; the open-source copy uses ASCII `linyuan`), **source package `com.linyuan.app`**, display name, accessibility/notification service names, all user-visible strings and README instructions.

The following keep the historical name `voicecompanion`, because changing them would cause **real damage** (not merely cosmetic inconsistency):

| Identifier | Location | What changing it would break |
|---|---|---|
| `applicationId com.voicecompanion.app` | `build.gradle` | It would become **a different app**: existing users lose all data on upgrade and must re-grant runtime permissions, accessibility and notification access |
| Database name `voice_companion.db` | `AppDatabase` | The old database is not found — memories, conversations and documents all gone |
| SharedPreferences `voice_companion` | several places | All settings (including API keys) lost |

> The source package (namespace) and the applicationId are two different things: the former only organizes source code and can be changed safely; the latter is the app's identity in the system, and changing it means a new app. Only the former was changed here.

New images are saved to `Pictures/lin渊/`; images in the old `Pictures/VoiceCompanion/` directory are still there and neither is deleted.

## Current limits

- **Not WebRTC full-duplex audio.** A call is turn-based: voice-activity segmentation → recognition → streamed answer → streamed speech. Latency is not that of Doubao/ChatGPT-style continuous audio. Reaching that would mean integrating a vendor's realtime voice SDK or a WebSocket/WebRTC interface.
- **No keys are bundled.** Chat, search, image generation, speech, embeddings and maps all need your own configuration; anything unconfigured reports itself as unavailable rather than pretending to work.
- **Speech recognition depends on the service you configure**; the Volcano/Doubao ASR branches are currently disabled, and call transcription goes through the DashScope channel.
- **The document library only reads text-based PDFs and plain text** — no docx, no scanned documents, no text-in-images.
- **Android only** (minSdk 26, developed on ColorOS), no iOS or desktop version.
- **Token-hungry features deserve supervision**: group discussions, the proactive assistant and unattended tasks keep spending quota. Group chat has a 95% budget stop; the others have no hard cap.
- **No unit tests**: verification means a successful build plus real-device testing.

## Development conventions

- **Framework-only project**: `android.useAndroidX=false`; androidx dependencies are not allowed (Markdown is span-ified by hand with commonmark, and FileProvider is home-grown).
- **Dependencies are local jars/aars**: Amap SDK and sherpa-onnx, not from Maven (the Amap jar must be downloaded as described above).
- **No unit tests**: verification = successful build + real-device testing.
- **Development logs stay out of the repo**: internal development logs and troubleshooting handbooks are the author's private documents and are not distributed; code changes are documented by the commit history.

Two hard UI conventions (read before touching buttons or dialogs):

- **Button depth** is drawn by the button's own background: a two-layer `layer-list` — a base layer one shade darker plus a face layer with `android:bottom="2dp"`, leaving ~2dp of the darker band visible. `LinYuanButton` in `themes.xml` kills the system shadow at theme level (`stateListAnimator=@null` + `elevation=0dp`). Change appearance via the four drawables `btn_secondary` / `btn_primary` / `btn_ghost` / `btn_danger`; the depth colours live in `colors.xml` as `btn*Depth`.
- **Settings back bar**: the "‹ back" button is the small square (`btn_ghost_square`, radius 6dp, height 28dp), **not** the 999dp pill `btn_ghost`. The bar uses a `FrameLayout` — back button `gravity=start`, title `gravity=center` (a `LinearLayout` with weights makes the title drift with the button's width).

## Credits

Most of the built-in group-chat role prompts (engineering roles, famous-minds, engineer personas) were **collected from open-source prompt projects on GitHub, or distilled/adapted from them with AI assistance — they are not original work of this project**. Known sources and their licences, plus an honest note about the parts whose origin could not be traced, are in **[CREDITS.en.md](CREDITS.en.md)** (Chinese original: [CREDITS.md](CREDITS.md)). If you are an original author and want attribution added or content removed, please open an issue.

## License

[GNU AGPL-3.0](LICENSE) (GNU Affero General Public License v3).

- Use, modification and distribution are free, but **derivative works must also be released under AGPL-3.0** — no closing it up;
- The key difference from GPL (section 13): **even if you only run it as a network service for others without distributing a single byte, you must offer the complete source to your users**;
- Commercial use is not forbidden, but commercial users are bound by the same "must stay open" rule.

> Third-party components shipped in this repo: sherpa-onnx (Apache-2.0), commonmark (BSD-2-Clause), kotlin-stdlib (Apache-2.0), and the on-device voiceprint model voiceprint.onnx (3D-Speaker CAM++, distributed via sherpa-onnx, Apache-2.0; see [CREDITS.md](CREDITS.md)).
> The Amap SDK is proprietary and is not distributed with this repo (download it yourself; it is additionally governed by Amap's own terms).
