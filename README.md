# MindCraft

Minecraft Fabric 1.21.x mod embedding a CPU/ram-only LLM inference server
(llama.cpp) for NPC dialogue and grammar-constrained tool calls.

Status: Phase 0 feasibility spike complete -> **GO** (see
`spikes/jni-inference/RESULTS.md`).

- `spikes/jni-inference/` - Task 1 spike: llama.cpp CPU build + Java HTTP
  harness, measured 67-90 tok/s at 4 threads and ~590 MiB peak RSS on a 0.5B
  Q4_K_M model.
- `.deps/llama.cpp` - local llama.cpp checkout (gitignored).
