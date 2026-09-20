# POSTMORTEM: mygps/SP Build Failures

> Every time a deploy fails, log it here with root cause + prevention. Read this **before** the next CI change.

**Stats (2026-09-18 → 2026-09-20):** 30 workflow runs, 9 success, 21 failure. ~70% failure rate.

---

## Failure Categories

### 🔴 Category 1: Android SDK setup in CI (8 failures in initial bootstrap)

**What broke:**
- `android-actions/setup-android@v3` rejected `android-version` + `components` inputs (v3 deprecated them; only accepts `packages`)
- `cmdline-tools 11.0` returns HTTP 404 on Google's CDN (no longer published)
- `packages` input parsed as a single string when YAML multi-line — needs comma-separated single line
- `sdkmanager` not on PATH even though the action set `ANDROID_HOME` env var

**Lesson:** When using a GitHub Action you haven't used before, check the README for current inputs **and** which versions are still published. v3 of an action often has totally different input schema than v2.

**Prevention:**
- Pin `android-actions/setup-android@v3` + verify with a known-good config OR
- Skip the action entirely — Ubuntu 24.04 runners ship with cmdline-tools already at `/usr/local/lib/android/sdk/cmdline-tools/latest`; just install packages via `sdkmanager` directly and add it to PATH
- Template `.github/workflows/build-apk.yml` lines 13-22 (proven good)

---

### 🔴 Category 2: Kotlin compile errors (5 failures)

**What broke:**
| Commit | Error |
|---|---|
| `33b02ef` | Two `companion object` in `MockLocationEngine.kt` (illegal) |
| `33b02ef` | `LocationManager.AVAILABLE` not found — should be `LocationProvider.AVAILABLE` |
| `bcec0b4` | `setTestProviderStatus(provider, status, extras, null)` — null doesn't fit `Long` overload; needed `@Suppress("DEPRECATION")` and an explicit Long |
| `7384f43` | `elapsedRealtimeNanos = X + Random.nextFloat() * N` — field is `Long`, needed `Random.nextLong(...)` |
| `ae7b0a6` | `Log.w("MainActivity", ...)` — `Log` not imported (used fully-qualified elsewhere inconsistently) |

**Lesson:** Sandbox has no Kotlin compiler → I write Kotlin without compile feedback. Guessing at API shapes (e.g. "is `AVAILABLE` on `LocationManager` or `LocationProvider`?") burns 2-3 build cycles each.

**Prevention:**
- **Run `./gradlew compileDebugKotlin` before pushing**, even locally. The sandbox can't do this — but if the user has the project, ask them to run this one command and paste the error if it fails
- When in doubt about an Android API, check the actual source: `androidxref.com` or `cs.android.com/android/platform`
- **Special trick**: use SDK 34 source jars (`android-34/android.jar`) and disassemble with `javap` to verify method signatures — works in JVM sandbox
- For deprecation overloads: always pass an explicit value (no `null`), even if you have to compute a dummy one

---

### 🔴 Category 3: Unit test failures due to Android stubs (1 failure)

**What broke:** `org.json.JSONObject` calls returned NPE in unit tests because Android stub jar returns `null/0/false` for everything unless Robolectric is used.

**Lesson:** Any time you use `org.json.*` in code that's tested via `testDebugUnitTest`, you need either:
- `org.json:json` standalone artifact as `testImplementation`, OR
- Robolectric `@RunWith(RobolectricTestRunner::class)`

**Prevention:**
- Add `testImplementation("org.json:json:20240303")` to `app/build.gradle.kts` from day one if you use `JSONObject` in shared code
- Or: extract JSON-only logic into a class that doesn't depend on Android (use `kotlinx.serialization`)

---

### 🔴 Category 4: osmdroid API guessing (3 failures)

**What broke:**
| Commit | Error |
|---|---|
| `734ec28` | `Configuration.getInstance().tileFileSystemCache` — doesn't exist |
| `734ec28` | `tileCacheMaxBytes` / `tileCacheTrimBytes` — don't exist either |
| `7b84264` | `BoundingBox.getTileBox(zoom)` — doesn't exist |

**Lesson:** I was guessing osmdroid 6.1.18 API names. Without local compile, every guess is a coin flip.

**Prevention:**
- Don't guess osmdroid APIs — check the actual source jar or Javadoc. The 6.1.18 Javadoc is at https://javadoc.io/doc/org.osmdroid/osmdroid-android/6.1.18/
- For tile download loops, compute x/y from lon/lat manually using the slippy map formula (it's only 4 lines). Don't depend on library helper methods.
- The `tileFileSystemCache` doesn't exist as a public API in 6.x — there's `tileSource.getTile(...)` but cache management is internal.

**Template for manual tile XY from lon/lat:**
```kotlin
private fun lonToTileX(lon: Double, z: Int): Int =
    ((lon + 180.0) / 360.0 * (1 shl z)).toInt()

private fun latToTileY(lat: Double, z: Int): Int {
    val latRad = Math.toRadians(lat)
    val n = 1 shl z
    return ((1.0 - Math.log(Math.tan(latRad) + 1 / Math.cos(latRad)) / Math.PI) / 2.0 * n).toInt()
}
```

---

### 🟡 Category 5: Dependabot auto-bumps breaking build (4 failures)

**What broke:**
- `build(deps): bump gradle-wrapper from 8.4 to 9.7.1` — Gradle 9 dropped support for AGP 8.2
- `build(deps): bump org.jetbrains.kotlinx:kotlinx-coroutines-android` — pulled in Kotlin version incompatible with our setup
- `Bump androidx.recyclerview:recyclerview from 1.3.2 to 1.4.0` — required compileSdk bump we hadn't done

**Lesson:** Dependabot doesn't understand semantic versioning constraints. It will bump to the latest even when our `compileSdk = 34` can't support a newer AndroidX.

**Prevention:**
- Disable weekly bumps; switch to monthly or only security updates
- OR add `.github/dependabot.yml` `ignore` rules for known-incompatible bumps:
  ```yaml
  ignore:
    - dependency-name: "gradle-wrapper"
    - dependency-name: "org.jetbrains.kotlinx:kotlinx-coroutines-android"
      versions: ["1.8.x", "1.9.x"]
  ```
- Always pin `gradle-wrapper.properties` to a version you've tested locally

---

### 🟡 Category 6: User experience issues requiring code changes

Not "CI failures" but they triggered rebuilds:
- Map tiles blocked (OSM strict UA) → had to switch tile sources 3 times (OSM main → Wikimedia → OSM DE)
- Auto-revert after 1 min → needed Foreground Service
- QueQ detection → added jitter (doesn't actually bypass `isFromMockProvider()`)

**Lesson:** Some bugs need 2-3 iterations to get right. Build/test cycle on real device is unavoidable for UX issues.

---

## Prevention Checklist (before next CI change)

```
□ Did I run ./gradlew compileDebugKotlin locally? (or run it in CI before merging)
□ For new Android API: did I verify the exact method signature in source/javadoc?
□ For osmdroid APIs: did I check the Javadoc, not guess?
□ For CI actions: did I check the README for current input schema (v3 vs v2)?
□ For Dependabot: is the bump compatible with our compileSdk/Kotlin/Gradle?
□ Did I use `org.json.JSONObject` in unit-tested code? (if yes, add org.json:json dep)
□ For companion objects: only ONE per class
□ For deprecated overloads: pass explicit values, not null
```

## Reference: Where I went wrong per failure

| Run | Commit | What I should have done |
|---|---|---|
| `8835306` | pin cmdline-tools 11.0 | Verified 11.0 exists on Google's CDN — it doesn't, jumped to 12.0 |
| `9447b40` | packages as comma string | Tested v3 input format locally |
| `32db3b9` | manual sdkmanager | Added to PATH before invoking |
| `33b02ef` | companion obj duplicates | Merged before commit |
| `bcec0b4` | null Long | Used `0L` or `SystemClock.elapsedRealtimeNanos()` |
| `7384f43` | nextFloat vs nextLong | Read `Location.elapsedRealtimeNanos` Javadoc |
| `35349457602` | 4 NPE in unit tests | Added `org.json:json` from day one |
| `ae7b0a6` | tileFileSystemCache | Checked osmdroid 6.1.18 Javadoc |
| `734ec28` | tileCacheMaxBytes | Same — guess, didn't check |
| `7b84264` | BoundingBox.getTileBox | Implement slippy map conversion manually |

## TL;DR

The 70% failure rate came from **three compounding causes**:
1. No local Kotlin compile feedback in the sandbox
2. Guessing Android/osmdroid API shapes instead of checking docs
3. Dependabot bumping to incompatible versions

**For next project: install Gradle + Android SDK in the sandbox (if possible) or commit to the "compile once via CI" workflow and accept 1-2 failed builds per change as the cost of iteration speed.**