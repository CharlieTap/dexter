# AGENTS.md

## Build & Test

- **Fast dev loop:** run `./gradlew test`. This runs the JVM tests, which
  cover the shared code in `commonTest` plus the JVM-only checks.
- **Full check:** run `./gradlew build`. This adds the Android host tests
  (`testAndroidHostTest`), linting and the ABI check.
- The project is Kotlin Multiplatform with two targets, JVM and Android.
  The Android target needs an Android SDK (`ANDROID_HOME` or `sdk.dir` in
  `local.properties`).
- The library has no runtime dependencies beyond the Kotlin standard
  library. Keep it that way; test-only dependencies are fine.

## Formatting & ABI

- Run `./gradlew fmt` before committing to keep formatting consistent.
- If you’ve changed a public ABI, first check it with `./gradlew
  checkKotlinAbi`. If it reports changes, record them with `./gradlew
  updateKotlinAbi`.

## Commit Messages

- Write subjects as `<area>: <imperative outcome>`, where the area names the
  affected subsystem, such as `code`, `write`, `build`, or `ci`.
- Keep the subject concise and specific. Prefer `code: relax branches that
  cross a switch payload` over generic summaries such as `update code`
  or category prefixes such as `refactor(code)`.
- A small, self-explanatory commit may use only a subject. For substantive
  changes, add a body that explains:
  1. the previous behavior, bottleneck, or failure and why it mattered;
  2. the implementation approach and any important invariants, fallbacks, or
     compatibility guarantees.
- Describe the resulting behavior rather than narrating the editing process.
- Do not add routine verification such as `./gradlew test`, formatting, linting,
  or standard CI checks to a commit body. These checks are expected for every
  change and do not help explain the commit.
- Include verification evidence only when it adds information needed to
  understand a claim or risk. Performance claims must include the benchmark
  workload, baseline, relevant conditions, and measured result. Compatibility
  or migration claims should likewise include non-obvious validation details
  when they matter.
- Wrap prose at roughly 72 characters and separate distinct ideas into short
  paragraphs.

For example:

```text
write: compute the header checksum in eight lanes on Android

The DEX header checksum is an Adler-32 over the whole file. Summing one
byte at a time makes every addition wait on the previous one.

Sum eight independent lanes per 8-byte block and recombine them into the
two Adler-32 sums at the end of each 3,800-byte chunk, which keeps the
sum of sums below 2^31. The checksum itself is unchanged.
```
