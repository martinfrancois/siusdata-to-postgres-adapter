# Warnings that CI prints and why they stay

AGENTS.md asks for every warning in a CI log to be fixed, or listed here with the reason it is not a defect. This is that list. Remove an entry when its cause is gone.

## Test JVM: "Sharing is only supported for boot loader classes because bootstrap classpath has been appended"

Printed once per test JVM on every leg of the `build` matrix. Mockito's inline mock maker attaches the Byte Buddy agent at startup, and that agent appends to the bootstrap class path. The JVM then says it cannot use its class data sharing archive for application classes. Nothing is wrong: sharing is a startup-time optimisation, and the tests do not depend on it. Turning it off with `-Xshare:off` would hide the line without changing anything the tests do.

## GraalVM native-image: "Found typeReached condition in JSON configuration files. The typeReached condition is not supported by this version of GraalVM"

Printed by `nativeCompile` in the `windows-native` job. The build takes reachability metadata from GraalVM's shared repository (`metadataRepository` in `build.gradle.kts`), and newer entries there use the `typeReached` condition, which GraalVM for JDK 21 does not know. It treats the condition as always true, so at worst a little more metadata is included than needed; the executable is not affected. The condition becomes known once the native build moves to a newer GraalVM, which is tied to the Java version the README promises.

## actions/checkout: "hint: Using 'master' as the name for the initial branch"

Printed by `git init` inside `actions/checkout` on every job. Git prints it whenever `init.defaultBranch` is unset on the machine, and the runner image is not ours to configure. The checkout fetches the requested commit into that repository, so the initial branch name plays no part.
