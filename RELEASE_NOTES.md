# AI Builder 0.2.1

Kept up to date with BlockDesigner 0.4.27: built and tested against its plugin API. Nothing changes in how it works.

**Needs BlockDesigner 0.4.24 or later** (plugin API 6). Older BlockDesigners keep 0.1.1 until BlockDesigner itself is updated.

## Changed
- Built against the BlockDesigner 0.4.27 plugin API.

---

# AI Builder 0.2.0

AI Builder's settings move to BlockDesigner's Settings window, and the Assistant and Models pages are simpler.

**Needs BlockDesigner 0.4.24 or later** (plugin API 6). Older BlockDesigners keep 0.1.1 until BlockDesigner itself is updated.

## New
- **A page in the Settings window** with the settings you set once: where answers come from and the connection details for a local server, Claude or OpenAI; the engine kind, starting with BlockDesigner and stopping when unused; the **Default style**; and, under Advanced, the context size and the most blocks per answer, which you couldn't change before. Your settings move there by themselves.
- **A status dot** on the Assistant page's button while it answers or starts the model, and when an answer failed.

## Changed
- **Assistant page:** what to work on at the top (it no longer squeezes a style box next to it; the default style is in Settings), the chat in the middle, and errors, the message box, Attach, Stop and Send at the bottom. **Other models…** now opens the Models page.
- **Models page:** shows only what the chosen source needs: the built-in models, engine and background model; the local server; or the API key. A key is saved when you press Enter, with no Save button. The status of the chosen source is at the bottom. Deleting a model asks in BlockDesigner's own dialog.
- **Commands** under each answer open in a small section in a monospaced font.

## Fixed
- **Pages that were closed** no longer keep listening for changes.

---

# AI Builder 0.1.1

Kept up to date with BlockDesigner 0.4.23: built and tested against its plugin API. Nothing changes in how it works.

**Needs BlockDesigner 0.4.17 or later** (plugin API 5).

## Changed
- Built against the BlockDesigner 0.4.23 plugin API.

---

# AI Builder 0.1.0

The first release: build and edit structures by chatting ("build a small castle", "add a tower on the east side") or from a reference picture, with a model that runs on your own PC, your own local server, or your own Claude or OpenAI key.

**Needs BlockDesigner 0.4.17 or later** (plugin API 5).

**Install:** download `ai-builder-0.1.0.jar` below, then in BlockDesigner open **Plugins (puzzle icon) › Manage plugins… › Install…** and pick it. The **Assistant** and **Models** pages appear in the plugin's tab on the right. Later versions install themselves if **Update plugins automatically** is on.

## New

### Assistant
- **Chat to build:** ask for a build or a change and the reply streams in, a short plan and then the commands it ran, each with a tick or a cross under the answer.
- **What it works on:** the selection, everything visible, or a new layer of its own for a new build. The line under the controls says which.
- **One undo step per answer:** Ctrl+Z or **Undo this** takes it back. **Stop** ends an answer at once without changing anything.
- **Tries before it changes anything:** commands run on a copy of the scene first, and failed lines go back to the model to fix, up to three times.
- **Styles:** medieval, rustic, desert, nordic, fantasy and modern palettes, or let the assistant pick.
- **Reference pictures:** attach or drop a picture. Models that see pictures get it; every model gets its main colours matched to building blocks.

### Models
- **Built-in models on your PC:** Gemma 4 E4B (recommended, 5.2 GB), Qwen2.5-VL 3B, Qwen3-VL 8B and Gemma 3 12B, with their size, memory needs and licence. Downloads show speed and time left and resume where they stopped. Any GGUF model from Hugging Face can be added too.
- **Engine:** llama.cpp, downloaded the first time, for your graphics card, CPU only or NVIDIA CUDA, falling back to the CPU build if the graphics card one won't start.
- **Background model:** Stopped, Starting or Ready, with Start and Stop, *start when BlockDesigner starts* and *stop when unused*. It stops when the plugin is turned off or BlockDesigner closes.
- **Your own AI instead:** a local server (Ollama, LM Studio or any OpenAI-compatible address), or your own Claude or OpenAI API key, stored encrypted for your Windows account.

### Builder commands for BlockEdit
- **`/setblock` and `/fill`** as in Minecraft, with block mixes such as `70%stone_bricks,30%mossy_stone_bricks`.
- **`/tower`, `/roof`, `/battlements`, `/windows`, `/door`, `/gatehouse` and `/floors`** lay whole parts with stairs and slabs facing the right way. They work in the command bar (T or /) as well as for the assistant.

## Good to know
- This is a first release, so expect rough edges; small local models make simpler builds than big ones.
- The built-in model is a one-time **5.2 GB download** (plus the engine), offered the first time you open the Assistant page. With it, or with your own local server, nothing leaves your PC.
- **Claude and OpenAI need your own API key** and are billed to your account. With a key, the scene summary, your messages and any attached picture go to that company.

---
