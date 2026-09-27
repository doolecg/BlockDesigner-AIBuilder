# AI Builder plugin: plan

> **Status (2026-09-27):** phases 1–5 are built and unit tested (78 tests, `./gradlew jar test`). Phase 6, trying it in the app and
> releasing, is next and is the user's. Nothing is committed. See [the checklist](#status-checklist) at the end.

> **0.2.0 (UI overhaul, API 6, needs BlockDesigner 0.4.24):** the set-once settings moved to BlockDesigner's Settings
> window (`AiOptions`: provider and connection details, engine kind, start at launch, idle stop, Default style,
> context size and most blocks per answer; `AiSettings.migratedToApp` moves an older settings file over once). The
> Assistant and Models pages are built with the UI kit: controls at the top, the chat or model cards in the middle,
> status and errors at the bottom; the Models page shows only what the chosen source needs, and API keys save on Enter.
> Page status dots while answering, listeners removed on dispose. 82 tests.

## Context

The user wants a BlockDesigner plugin that builds 3D Minecraft structures from a text prompt or a reference image,
and edits them by chat, much like the Claude "LEGO" demo. Example requests:
- "build a castle"
- "replace all stone with stone or mossy cobble"
- "add a castle tower peak on the side"
- a picture of a building turned into a 3D build

There is no pixel art and no generated images. Placement goes through **BlockDesigner's own WorldEdit** (full block
states: facing, half, shape…).

The AI runs **locally by default**: Google's **Gemma 4 E4B** (Apache-2.0, sees images), downloaded by the plugin from a
nice Models page and run in the background. Advanced users can pick other local models, their own local server
(Ollama, LM Studio, any OpenAI-compatible URL), or their own **Claude** or **OpenAI** API key.

The plugin is named **AI Builder** (id `ai-builder`) and lives in its own repo, `F:/PROGRAMMING/REPOS/BlockDesigner-AIBuilder`,
per the plugins-separate rule. The "no AI in the app" rule is kept, because this is an opt-in plugin the user asked for.

## Verified facts

- **llama.cpp** publishes Windows builds on every release (tags like `b11205`):
  - `llama-bNNNN-bin-win-vulkan-x64.zip` (33 MB, any GPU), `-cpu-x64.zip` (19 MB), `-cuda-12.4/13.4-x64.zip` (+ cudart)
  - `llama-server.exe` inside them serves an OpenAI-compatible `/v1/chat/completions` with image input when given `--mmproj`
- **Hugging Face** (ungated, direct `resolve/main` URLs):
  - `ggml-org/gemma-4-E4B-it-GGUF`: `gemma-4-E4B-it-Q4_0.gguf` 4.6 GB + `mmproj-gemma-4-E4B-it-Q8_0.gguf` 0.56 GB (default)
  - `ggml-org/Qwen2.5-VL-3B-Instruct-GGUF`: Q4_K_M 1.9 GB + mmproj Q8_0 0.85 GB (light)
  - `unsloth/Qwen3-VL-8B-Instruct-GGUF`: Q4_K_M 5.0 GB (+ mmproj) (quality)
  - `ggml-org/gemma-3-12b-it-GGUF`: Q4_K_M 7.3 GB + mmproj 0.85 GB (big)
- **Core `io.blockdesigner.core.worldedit.WorldEdit`** is public in the core jar:
  - `new WorldEdit().run(line, new Context(world, look, aim, hand, resolve))` runs `//pos1 x y z`, `//set`, `//replace`,
    `//walls`, `//faces`, `//line`, `//cyl`, `//hcyl`, `//sphere`, `//pyramid`, `//stack`, `//copy/paste/rotate/flip`,
    `//hollow`, `//overlay` and `//fixshapes`, with patterns like `70%stone_bricks,30%mossy_stone_bricks` and full states.
  - `PluginContext.registerCommand` registers into the same `WorldEdit.register` registry
    (`app/.../plugins/PluginManager.java:740`), so the plugin's own generator commands work for the AI **and** in the
    app's command bar.
- **Undo:** `PluginContext.editWorld(label, world -> …)` is one undo step, and `addLayer` makes a new layer active.
- Java has `java.net.http` in the app runtime, and Jackson is provided (compileOnly, like Resource Tracker).

## Architecture (packages under `io.blockdesigner.aibuilder`)

- **`models`**
  - `ModelCatalog`: the built-in entries (id, name, blurb, files and sizes, vision, RAM guide, licence, recommended),
    plus custom entries (HF repo/file or URL, optional mmproj).
  - `Downloader`: resumable HTTP (Range into `.part`), speed and ETA, cancel, size check, background executor.
  - `ModelStore`: files in `dataFolder()/models/<id>/`; installed, delete, and disk use.
  - `Engine`: finds the newest llama.cpp release with a `win-<variant>-x64` asset through the GitHub API, then downloads
    and unzips it into `dataFolder()/engine/<tag>-<variant>/`. Variants: GPU (Vulkan, default), CPU, NVIDIA (CUDA +
    cudart). If a GPU start fails, it falls back to CPU.
  - `LocalServer`:
    - Starts `llama-server.exe -m … [--mmproj …] --jinja -c 16384 -ngl 99 --host 127.0.0.1 --port <free>` in the
      background, hidden, logging to a file.
    - Polls `/health` until ready.
    - Starts on first use, or at launch if that option is on; stops on `disable()` and a JVM shutdown hook, and after
      an optional idle timeout.
- **`llm`**
  - `ChatClient`: `chat(messages, onText) → reply`, streamed.
  - `OpenAiCompatibleClient` serves the built-in server, Ollama (`/v1`), LM Studio, OpenAI and custom URLs (e.g.
    OpenCode Zen).
  - `AnthropicClient` serves Claude through the Messages API, with image blocks.
  - `Secrets`: API keys encrypted with Windows DPAPI (through PowerShell `ConvertFrom-SecureString`), stored in
    `dataFolder()/keys/`.
- **`build`**
  - `BuildRunner` executes a command script against a `WorldEdit.World`: one WorldEdit session, lines run in order,
    per-line ok/error results, and a block limit.
  - Vanilla-style commands are added on top: `/setblock x y z <state>` and `/fill x1 y1 z1 x2 y2 z2 <state> [hollow|outline|replace <from>]`.
  - `Generators` are registered with `ctx.registerCommand`, so they also work in the command bar. Each lays blocks with
    correct stair/slab states:
    - `/tower <radius> <height> [cone|flat|battlements] [wall] [roof]` on pos1
    - `/roof <gable|hip|pyramid|flat> [material] [dir]` over the region
    - `/battlements [material]`
    - `/windows [glass] [spacing] [height]` in the region's walls
    - `/door [material] [dir]`
    - `/gatehouse`
    - `/floors <n> [material]`
  - `Styles`: named palettes (medieval, rustic, desert, nordic, fantasy, modern) mapping roles (wall, trim, roof, floor,
    glass, accent) to blocks. Prompts use `@wall` / `@roof` placeholders, resolved per style.
- **`agent`**
  - `Assistant` runs one conversation. The system prompt holds:
    - the command reference (generated from the registry plus our additions)
    - coordinate conventions (y up; build at the anchor; north is −z)
    - style tips
    - the **scene summary**
  - The model answers with a short plan and a fenced block of commands. That is used rather than tool calling, because
    small local models do it more reliably and it's the same for every provider.
  - The commands are run. Up to 3 repair rounds pass the errors back to the model.
  - `SceneSummary` lists the visible layers with their world bounds, block counts and top blocks; the selection's box
    and palette; and a coarse height/outline sketch of the selection or the whole build (for "add a tower on the side").
  - **Image reference:** the image is attached for vision models. Its dominant colours, mapped to the nearest building
    blocks with `BlockCatalog.averageColor`, go into the prompt for all models.
- **`ui`**
  - The **Models page** has:
    - cards per model: a vision badge, size, RAM guide, a status chip (Not installed / Downloading 43% · 12 MB/s · 2 min
      left / Installed / Active), Download · Cancel · Use · Delete
    - an **Engine** card: GPU/CPU/CUDA, version, update
    - **Background model** status (Stopped / Starting / Ready) with Start/Stop
    - an **Advanced** section: custom model, local server URL + model, and **API keys** for Claude and OpenAI with
      provider and model fields
  - The **Assistant page**:
    - a chat transcript
    - an input with Send, Stop and **Attach image**
    - a scope line ("Editing: selection 12×9×20" or "New build at …")
    - per AI turn, the commands that ran (collapsible) and **Undo this**
  - The first run shows a friendly "Download Gemma 4 E4B (5.2 GB)" call to action.
- **Applying changes:** each AI turn runs inside one `ctx.editWorld("AI: <prompt>", …)`, so it is one undo step.
  - A new build on an empty scene or with nothing selected first gets its own layer (`addLayer("AI: castle", new Structure())`).
  - Edits work on the selection, or on everything visible when nothing is selected.

## Phases

1. **Scaffold:** repo from the Pixel Art Generator template, 0.4.21 API jars, manifest (`api: 5`), logo, `docs/plan.md`.
2. **Build engine:** `BuildRunner`, `/setblock` `/fill`, the generators and styles, and tests. No AI needed; they run
   against an in-memory World.
3. **Models:** the catalog, downloader, engine, local server and the Models page. Tests for resume and catalog parsing.
4. **Assistant:** the clients (built-in, OpenAI-compatible, Anthropic), secrets, scene summary, the agent loop and the
   chat page.
5. **Image reference:** attachments, dominant-colour block hints, and vision prompts.
6. **Try it in the app** (install into `%APPDATA%/BlockDesigner/plugins`), then release like the other plugins when the
   user asks.

## Verification

- **Unit tests:** command runner (setblock/fill/WorldEdit lines, errors per line); generators (tower block counts,
  stair facings on roofs, battlement pattern); fenced-block parsing; scene summary; downloader resume against a local
  HTTP server; catalog.
- **Manual:** in the app:
  1. Download Gemma, and check the engine starts in the background.
  2. Ask "build a small castle".
  3. Ask "replace stone bricks with 70% stone bricks 30% mossy stone bricks".
  4. Ask "add a tower on the east side".
  5. Attach a photo of a house.
  6. Undo each turn.

## Status checklist

### Phase 1: scaffold (done)
- [x] Repo `F:/PROGRAMMING/REPOS/BlockDesigner-AIBuilder`, `git init -b main`, no commits
- [x] Standalone Gradle build from the Pixel Art Generator template; toolchain 26, release 25; JavaFX, Jackson and the API jars `compileOnly`
- [x] `libs/blockdesigner-plugin-api-0.4.21.jar`, `libs/blockdesigner-core-0.4.21.jar`
- [x] Manifest `ai-builder`, `api: 5`, `updates` link; `ManifestTest`
- [x] Logo in the plugin logo style (drawn with the main repo's `make_plugin_icons.py` helpers from a script outside the repo; not yet
      added to that file's `LOGOS`, which has someone else's uncommitted changes)
- [x] README, RELEASE_NOTES.md, MIT LICENSE

### Phase 2: build engine (done)
- [x] `BuildRunner`: one BlockEdit session per script, per-line results, block limit, `/style`, `@role` placeholders, `/undo` refused,
      stair/pane/fence shapes rejoined at the end, a seed so retries match
- [x] `/setblock`, `/fill` (hollow, outline, keep, replace with a filter; patterns as the block; `~` relative)
- [x] Generators registered with `ctx.registerCommand`: `/tower`, `/roof` (gable, hip, pyramid, flat), `/battlements`, `/windows`, `/door`,
      `/gatehouse`, `/floors`
- [x] `Styles`: medieval, rustic, desert, nordic, fantasy, modern
- [x] Tests: runner, every generator (ring counts, stair facings, ridge slabs, gable ends, hip corners, battlement pattern, symmetric windows,
      door halves, gate arch, floors), script parsing

### Phase 3: models (done; downloads untried with real files)
- [x] `ModelCatalog` (`models.json`, sizes checked against Hugging Face on 2026-09-27) and custom entries
- [x] `Downloader` (Range resume into `.part`, size check, cancel, speed) and `DownloadJob` (several files, ETA)
- [x] `ModelStore`, `Engine` (scans the release list: llama.cpp's `bNNNN` builds are pre-releases and "latest" is a `v0.5.0` release with no
      Windows builds), `LocalServer` (free port, hidden, `/health`, shutdown hook, idle stop), `LocalModels` (engine install, CPU fallback)
- [x] Models page: provider, model cards with progress, engine card, background model card, Advanced (custom model, local server, API keys)
- [x] Tests against a local HTTP server and fake release JSON; no model or engine was downloaded

### Phase 4: assistant (done; untried against real models)
- [x] `OpenAiCompatibleClient` (built-in server, Ollama, LM Studio, OpenAI, custom), `AnthropicClient` (Messages API, streamed, image blocks,
      `claude-opus-5` default, server-side fallback on declined requests where offered)
- [x] `Secrets` (DPAPI through PowerShell, key on stdin); a real round-trip passes in the tests
- [x] `SceneSummary` (layers, bounds, counts, palette, height map), `Scope`, `SystemPrompt`
- [x] `Assistant`: fenced command block, tries on an overlay, up to 3 repair rounds, one undo step per turn, a new layer for a new build,
      Undo this, history of the last turns
- [x] Assistant page: transcript, streaming, Send / Stop / Attach image, scope line and choice, style, commands per turn, first-run download

### Phase 5: image reference (done)
- [x] `Attachment` (scaled to 1024 px, JPEG or PNG), drag and drop, file picker
- [x] `ImageHints`: k-means in OKLab, nearest block by `BlockCatalog.averageColor` (a built-in colour table before assets load)
- [x] Pictures sent to models that see them; hints go to every model

### Phase 6: in the app (to do, the user's)
- [ ] Install the jar into `%APPDATA%/BlockDesigner/plugins`, download Gemma 4 E4B and the engine, and run the manual checks above
- [ ] Tune the system prompt on real answers from the small local models
- [ ] Add the logo to `make_plugin_icons.py`, create the GitHub repo and release, when asked
