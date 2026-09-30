# Warnings that CI prints and why they stay

AGENTS.md asks for every warning in a CI log to be fixed, or listed here with the reason it is not a defect. This is that list. Remove an entry when its cause is gone.

## actions/checkout: "hint: Using 'master' as the name for the initial branch"

Printed by `git init` inside `actions/checkout` on every job. Git prints it whenever `init.defaultBranch` is unset on the machine, and the runner image is not ours to configure. The checkout fetches the requested commit into that repository, so the initial branch name plays no part.
