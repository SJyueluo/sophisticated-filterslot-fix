# Building this project without the private `reliquary` dependency

The 1.21.x build resolves `net.p3pp3rf1y:reliquary` from
`https://maven.pkg.github.com/p3pp3rf1y/reliquary` with `GITHUB_ACTOR` / `GITHUB_TOKEN`
(see `build.gradle`, repository `GHPReliquary`). Without credentials for that package the build stops at
`:compileJava` with:

```
Could not resolve net.p3pp3rf1y:reliquary:[1.21.1-2.0.79,1.21.2).
  > Unable to load Maven meta-data from https://maven.pkg.github.com/p3pp3rf1y/reliquary/...
     > Username must not be null!
```

This is not a defect in the project — it is simply undocumented for outside contributors.

## Workaround used for the verification in this repository

All changes are local; nothing was pushed.

1. `build.gradle` — drop the else-branch that declares the dependency:
   ```groovy
   dependencies.implementation("net.p3pp3rf1y:reliquary:${reliquary_version}") { transitive = false }
   dependencies.testImplementation("net.p3pp3rf1y:reliquary:${reliquary_version}") { transitive = false }
   ```
   and exclude the one source that needs it:
   ```groovy
   sourceSets.main.java.exclude 'net/p3pp3rf1y/sophisticatedcore/compat/reliquary/**'
   ```
2. `init/ModCompat.java` — remove the import and the single registration line referencing `ReliquaryCompat`.
3. `gradle.properties` — bump `mod_version` to `1.5.19` so the built jar satisfies Backpacks' `[1.5.18,)`
   range while being a snapshot build.

Then:

```
./gradlew compileJava   # BUILD SUCCESSFUL
./gradlew jar           # build/libs/sophisticatedcore-1.21.1-1.5.19.-SNAPSHOT.jar
```

## Result

The jar built from `1.21.x` **with the patch from PR #518 applied** was run on a dedicated
1.21.1 / NeoForge 21.1.265 server together with Backpacks 3.26.15:

```
tier1 (duplicate owned item) : BLOCKED   gain=0 repeat5=0
negative controls            : click_gain=0 bogus_slot_threw=false bogus_delta=0
regression (normal transfer) : moved=3
```

The unpatched 1.5.18 release gives `gain=1 repeat5=5` and `bogus_slot_threw=true` for the same run.
Raw record: `docs/evidence/m-upstream-patched.json`.
