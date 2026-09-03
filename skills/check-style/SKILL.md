---
description: Review a code change for majo coding conventions: seam trios, loud errors, single-source constants.
---
# Check style

Review code against the harness conventions:

- capabilities are Service + Provider/Strategy seam + tool consumer plugins;
- plugins return Disposables; no ctx.effect leaking across fibers;
- constants keep a single source of truth; errors fail loudly with context.
Report each finding as `file: finding` and end with a verdict line `Verdict: pass|fail`.
