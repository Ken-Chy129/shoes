# Repository Workflow

- Develop directly on `master` unless the user explicitly requests another branch. Do not create feature branches by default.
- Keep `master` as the complete, deployable history: validate changes, commit them on `master`, and push to `origin/master`.
- Production deployment must use the server's `master` branch after it has been fast-forwarded to `origin/master`; do not deploy a feature-branch-only commit.
- Before changing or deploying code, verify that the local, remote, and server `master` revisions are aligned or deliberately reconcile them first.
