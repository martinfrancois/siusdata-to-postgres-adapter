# Warnings that CI prints and why they stay

AGENTS.md asks for every warning in a CI log to be fixed, or listed here with the reason it is not a defect. This is that list. Remove an entry when its cause is gone.

## GraalVM native-image: "Option 'DynamicProxyConfigurationResources' is deprecated"

Printed by `nativeCompile` in the `windows-native` job. The application carries `META-INF/native-image/proxy-config.json` for the `java.sql.Connection` proxy that its database connection needs. Native Image still consumes that metadata and the job proves the resulting executable by running it against PostgreSQL. The replacement reflection-metadata format would describe the same proxy; the warning is advance notice that GraalVM may remove the old format in a future release, not a defect in the current executable. Keep this entry until the metadata is migrated before that removal.

## actions/checkout: "hint: Using 'master' as the name for the initial branch"

Printed by `git init` inside `actions/checkout` on every job. Git prints it whenever `init.defaultBranch` is unset on the machine, and the runner image is not ours to configure. The checkout fetches the requested commit into that repository, so the initial branch name plays no part.
