# Warnings that CI prints and why they stay

AGENTS.md asks for every warning in a CI log to be fixed, or listed here with the reason it is not a defect. This is that list. Remove an entry when its cause is gone.

## Test JVM: "Sharing is only supported for boot loader classes because bootstrap classpath has been appended"

Printed once per test JVM on every leg of the `build` matrix. Mockito's inline mock maker attaches the Byte Buddy agent at startup, and that agent appends to the bootstrap class path. The JVM then says it cannot use its class data sharing archive for application classes. Nothing is wrong: sharing is a startup-time optimisation, and the tests do not depend on it. Turning it off with `-Xshare:off` would hide the line without changing anything the tests do.

## GraalVM native-image: "Option 'DynamicProxyConfigurationResources' is deprecated"

Printed by `nativeCompile` in the `windows-native` job. The application carries `META-INF/native-image/proxy-config.json` for the `java.sql.Connection` proxy that its database connection needs. Native Image still consumes that metadata and the job proves the resulting executable by running it against PostgreSQL. The replacement reflection-metadata format would describe the same proxy; the warning is advance notice that GraalVM may remove the old format in a future release, not a defect in the current executable. Keep this entry until the metadata is migrated before that removal.

## actions/checkout: "hint: Using 'master' as the name for the initial branch"

Printed by `git init` inside `actions/checkout` on every job. Git prints it whenever `init.defaultBranch` is unset on the machine, and the runner image is not ours to configure. The checkout fetches the requested commit into that repository, so the initial branch name plays no part.
