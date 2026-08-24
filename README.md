# KotlinCompilerApp — on-device Kotlin → APK build engine (scaffold)

This is a **scaffold**, not a finished app. It compiles as an Android
Studio project (or via the GitHub Actions workflow in `.github/workflows/`)
and shows the correct architecture end-to-end. Two pieces are still
deliberately stubbed and marked `TODO` / with comments:

1. `ApkSigner.signWithDebugKey` — copies the APK into a new jar without
   actually computing `MANIFEST.MF` / `CERT.SF` digests or writing the
   PKCS#7 `CERT.RSA` block. Needed for a truly installable APK.
2. `DebugKeystore.getOrCreate` — needs to generate a self-signed RSA
   keypair + certificate and write it out as PKCS12, using
   `java.security.KeyPairGenerator` + `java.security.cert` /
   `sun.security.x509` (or a small pure-Kotlin ASN.1/PKCS12 writer, since
   `sun.security` isn't guaranteed present on all ART builds — this is a
   known gotcha, budget time for it).

Also stubbed: `MainActivity`'s SAF-picked project folder is copied into
app-private storage (implemented), but there's no validation yet that the
picked folder actually looks like a valid project (has `AndroidManifest.xml`,
`src/`, `res/`) before a build is attempted.

## Compiler assets — now automated

Previously `kotlin-compiler-dex.jar`, `android.jar`, and `kotlin-stdlib.jar`
had to be hand-downloaded and dexed manually. This is now a Gradle task
(`prepareCompilerAssets` in `app/build.gradle.kts`) that runs automatically
before every build:

- Resolves `kotlin-compiler-embeddable` and `kotlin-stdlib` as normal
  Gradle dependencies (cached, retried — far more reliable than a bare
  `wget` from an unreliable network).
- Dexes the Kotlin compiler **and d8 itself** (both are pure JVM bytecode)
  using the `d8` that ships with the Android SDK build-tools already
  installed in CI. This is why `d8` no longer needs a native ARM binary —
  `D8Runner.kt` loads the dexed `d8-dex.jar` via `DexClassLoader` and runs
  it in-process on-device, the same trick used for the Kotlin compiler
  itself (see `KotlinCompilerRunner.kt`).
- Copies the SDK's own `android.jar` into assets.

A second task, `fetchNativeBuildTools`, downloads a prebuilt ARM64 `aapt2`
binary from the [AndroidIDE project's `androidide-tools`
releases](https://github.com/AndroidIDEOfficial/androidide-tools/releases)
into `jniLibs/arm64-v8a/`. **aapt2 is the one tool that genuinely needs a
native binary** — it's C++, not JVM bytecode, so it can't be dexed and run
in-process like d8/kotlinc. AndroidIDE's build was chosen deliberately
over Termux's `aapt2` package: Termux's build dynamically links against
several Termux-prefix shared libraries (`libprotobuf`, `libc++_shared`,
etc.) that won't resolve correctly from inside a *different* app's
sandbox, whereas AndroidIDE's tools are built specifically to run
standalone inside a sandboxed Android app process — exactly this
scenario. That said, **this hasn't been verified on a real device** (I
can't run it myself) — if `aapt2` fails to execute at runtime, check
`adb logcat` for a dynamic-linker error and see the fallback note below.

**Fallback** if the automated `aapt2` fetch breaks (release layout
changes, URL goes stale, etc.): pull it from a live Termux install
instead, since a working install already has all its shared-library
dependencies resolved consistently:
```bash
pkg install aapt2 -y
cp $PREFIX/bin/aapt2 <path-to-repo>/app/src/main/jniLibs/arm64-v8a/libaapt2_bin.so
# You'll also need to hunt down and copy its .so dependencies
# (readelf -d $PREFIX/bin/aapt2 | grep NEEDED) — see the caveat above
# about versioned .so filenames not matching Android's `lib*.so` rule.
```

`zipalign` no longer needs a native binary either — its job (padding
uncompressed zip entries to 4-byte alignment) is simple enough to
reimplement directly; see `ZipAligner.kt`. Also unverified on a real
device — if `pm install` rejects a built APK's alignment, compare against
AOSP's `zipalign/ZipAlign.cpp` reference implementation.

## Suggested build order for finishing this yourself

1. Push to GitHub and let the Actions workflow build a debug APK — it
   should now compile and launch without crashing.
2. Pick a real single-file Kotlin project through the app's folder picker
   and attempt a build; watch the on-screen build log for the first
   failure (most likely candidates: `aapt2` dynamic-linking issues, or
   `ZipAligner`/`ApkSigner` output rejected by the installer).
3. Implement `ApkSigner` + `DebugKeystore` properly so the output APK
   actually installs (`adb install` it as a sanity check).
4. Only then tackle multi-module / dependency resolution (see prior
   conversation — that's its own multi-week subproject).

## Module layout

```
KotlinCompilerApp/
  app/
    src/main/
      java/com/example/kotlincompiler/
        CompilerApp.kt          - app-wide tool path resolution
        engine/
          BuildModels.kt        - project/step data classes
          ProcessRunner.kt      - subprocess helper for native tools
          KotlinCompilerRunner.kt - DexClassLoader + reflection into kotlinc
          ApkSigner.kt          - (stub) v1 JAR signing
          BuildEngine.kt        - orchestrates all 6 build steps
        ui/
          MainActivity.kt       - pick a project folder, kick off a build
          BuildLogActivity.kt   - runs BuildEngine off-thread, streams log
      res/                      - standard Android resources
      assets/tools/             - put kotlin-compiler-dex.jar etc. here
      jniLibs/<abi>/            - put renamed d8/aapt2/zipalign here
```
