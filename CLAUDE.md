# Token-efficient coding rules (apply to every project under this folder)

- Solve exactly what is asked. No refactors, restyling or extra features. Change only the lines needed.
- Before exploring, read `docs/repo_map.md` or `CODEBASE_INDEX.md` if present. Use a code-index MCP (code-context-engine, tokensave) first if installed; fall back to Grep/Read.
- Never scan the whole repo. Skip `node_modules`, `dist`, `build`, `.next`, lockfiles, `.git`, images and generated files unless the task is about them.
- Read only the needed line range of a file. Don't re-read a file already read this session unless it changed.
- Make small surgical edits; never rewrite a whole file for a small change.
- If the request is ambiguous (which page/element/file), ask ONE short question instead of exploring.
- Verify with the cheapest check (targeted test, type-check, affected build), not the full suite, unless asked.
- Keep command output small (`| head`, `--quiet`, `git diff --stat`).
- Replies: short. Say what changed and where (file:line). No recap, no restating the request.
- Use the `token-efficient-coding` and `karpathy-guidelines` skills when writing or revising code.
- Correctness beats saving tokens: if unsure, read the file instead of guessing.
