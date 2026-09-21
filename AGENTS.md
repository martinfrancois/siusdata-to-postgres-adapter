# Working on this repository

## Build and test

`./gradlew build` (`gradlew.bat build` on Windows) compiles for Java 21, runs the unit, property and integration tests and packs the shadow jar. The integration tests start PostgreSQL and Toxiproxy through Testcontainers and need Docker or a compatible engine; `-PskipIntegrationTests=true` leaves them out. `-PtestJavaVersion=25` runs the tests on another installed JDK, which is what CI does for every current Java LTS from 21 upward.

## After every CI run

Read the logs of every job, not only the pass or fail result. Warnings and errors count as findings even when the job is green. Take the logs with `gh run view <run> --log` and look at compiler notes and lint output, Gradle deprecation messages, GraalVM native-image warnings, Testcontainers and JDBC warnings, `curl` and `jq` output in the `lts` job, and anything the workflow prints with `::warning::` or `::error::`.

Then handle each finding in one of three ways:

- A justified warning or error is a defect. Fix it.
- A warning that is not justified stays visible in the log, so write down why it is not justified in a place that lasts: in [docs/ci-warnings.md](docs/ci-warnings.md), or in a comment next to the cause when that is where a reader will look.
- A justified finding that cannot be fixed right away gets a GitHub issue that says when, why and how it can be addressed. The note in the durable place links the issue.

`docs/ci-warnings.md` lists the warnings that are known and accepted, with the reason for each. Keep it current: remove an entry when its cause is gone, and add an entry before you accept a new warning.

## Code comments

A comment in the code explains a why that a reader cannot tell from the code and that stays true past the next dependency update. The history of a change, the warning that prompted it and reasoning tied to a tool version go into the commit message or the pull request, never into the code.

## Commit messages

Conventional Commits, one commit per cohesive change, subject in lowercase, body in plain sentences that say why. No attribution trailers.
