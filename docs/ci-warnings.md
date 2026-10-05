# Warnings that CI prints and why they stay

AGENTS.md asks for every warning in a CI log to be fixed, or listed here with the reason it is not a defect. This is that list. Remove an entry when its cause is gone.

## build (Java N) on main: "Failed to save cache entry ... ReserveCacheError: Unable to reserve cache with key gradle-..., another job may be creating this cache"

Printed by setup-gradle's post step in one of the `build` matrix legs on a push to main. setup-gradle splits the Gradle User Home into shared entries (dependencies, wrapper, transforms, instrumented jars) keyed by their content, and every leg that finishes wants to save the same keys. The first leg saves them; the others lose the race and print this line. The job summary of the losing leg still lists those keys as saved, and the repository's cache list shows them on refs/heads/main, so no leg ends up without a cache. Pull request runs only read the cache and never print it.

## windows-native: "WARNING: Waiting for service 'postgresql-...' to start..."

Printed by PowerShell's `Start-Service` while the PostgreSQL service on the Windows runner is still starting. `Start-Service` waits for the service to reach its running state before the workflow continues. The following commands then create the test database, run the native executable, and verify that it imports every expected row. A successful job therefore shows that this transient service-start warning did not hide a startup or application defect.
