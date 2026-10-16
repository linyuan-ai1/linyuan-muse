# Credits & Attribution · 素材来源与致谢

This file explains where the app's built-in "group-chat role prompt library" comes from, its copyright status, and how it is handled.

[中文版 → CREDITS.md](CREDITS.md)

## In one sentence

**Most of the prompts used for group discussions (experts / engineering roles / famous-minds / engineer personas) are not original work of this project.** They were collected over time from open-source prompt projects on GitHub, and part of them were further distilled, rewritten and merged with AI assistance. The app's code and engineering (group-chat orchestration, memory system, voice pipeline, tool system, etc.) *is* original work of this project.

## How the role library came together

1. **Collection** — roles were gathered from open-source prompt projects on GitHub (various `xxx-skill` sets, `agency-agents` / `agency-orchestrator`, etc.);
2. **Distillation and adaptation** — some were distilled, rewritten and merged with AI assistance, to unify their structure, localize them into Chinese, and fit this app's group-chat collaboration model;
3. **Engineering integration** (original to this project) — a unified archive format, wiring into the group-chat scheduler and role assignment, per-role model and thinking-level configuration, and so on.

Because collection spanned a long period and the notes taken at the time were incomplete, **the original source of some material can no longer be traced accurately**.

## Known sources (licence texts are distributed with the app, see `app/src/main/assets/licenses/`)

### 1. Software development lifecycle roles (23)

Source projects:
- [jnMetaCode/agency-orchestrator](https://github.com/jnMetaCode/agency-orchestrator) (Apache-2.0, Chinese-language prompts)
- Upstream: [msitarzewski/agency-agents](https://github.com/msitarzewski/agency-agents) (MIT, Copyright © 2025 AgentLand Contributors)

Roles involved: product manager, trend researcher, software architect, backend architect, frontend developer, UI designer, UX architect, UX researcher, code reviewer, security engineer, SRE, technical writer, WeChat Mini Program developer, mobile app developer, API tester, reality checker, performance benchmarker, accessibility auditor, compliance auditor, legal compliance, government digitalization pre-sales, China market localization, agent orchestration.

Licence text: [`app/src/main/assets/licenses/devroles-Apache2.txt`](app/src/main/assets/licenses/devroles-Apache2.txt)

### 2. Famous-minds / engineer persona archives (16)

Source projects ([Nuwa / 花叔 (Huashu)](https://github.com/alchaincyf) ecosystem):

- `alchaincyf`: steve-jobs-skill, munger-skill, feynman-skill, elon-musk-skill, taleb-skill, karpathy-skill
- [nuwa-skills](https://github.com/nuwa-skills): buffett-skill, inamori-skill, bezos-skill, torvalds-skill, knuth-skill, turing-skill, zhangxiaolong-skill, wujun-skill, shannon-skill, vonneumann-skill

Licence: MIT (Copyright © 2026 Huashu 花叔; `torvalds-skill` is © 2026 Panmax).

Licence text: [`app/src/main/assets/licenses/personas-MIT.txt`](app/src/main/assets/licenses/personas-MIT.txt)

### 3. On-device voiceprint model (voiceprint.onnx)

- File: `app/src/main/assets/voiceprint.onnx` (~28 MB, shipped with the repository so it works out of the box)
- Model: `3dspeaker_speech_campplus_sv_zh-cn_16k-common` (3D-Speaker CAM++ Chinese voiceprint model, 192-dim embeddings, local offline inference)
- Distribution source: [k2-fsa/sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) (Apache-2.0); the upstream [3D-Speaker](https://github.com/modelscope/3D-Speaker) project is also Apache-2.0
- Purpose: on-device voiceprint verification ("is this the owner's voice?"), no network access, audio never uploaded

## Parts whose origin could not be traced (honest disclosure)

The following could **not** be traced to an original source (mostly gathered or distilled while filling out the roster, from various places):

- **17 engineering roles**: capacity & concurrency planner, destructive tester, cost analyst, creative expert, data architect, divergent-thinking expert, environment adaptation engineer, mistake-proofing designer, growth data analyst, human-behaviour designer, logic architect, open-source researcher, payments & orders expert, project coordinator, prompt engineer, risk-grading expert, threshold & capacity guardian;
- **2 famous-minds**: Socrates, Drucker.

If you recognise any of these prompts as coming from your project, please tell us — we would genuinely like to know.

## If you are an original author

- **To be credited**, or if you **do not want your content used here**: please open an Issue in the repository explaining the source and what you would like; we will add attribution or **remove the content** as quickly as we can;
- Anyone with a lead that helps complete this list is equally welcome to contribute.

## What this project *did* originate

- All app code (native Android / Java, zero androidx dependencies);
- The engineering: group-chat orchestration (role scheduling, convergence, budget guard, goal mode), the memory system, the typing channel, on-device voiceprint, the voice pipeline, 90 tools, and so on;
- The organisation of the role library and how it is wired in.

---

> Note: this repository as a whole is released under AGPL-3.0; the third-party material listed above **keeps its original licence** (MIT / Apache-2.0), and the licence texts ship with the app (`app/src/main/assets/licenses/`).
