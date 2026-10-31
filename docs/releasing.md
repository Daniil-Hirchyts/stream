# Releasing Stream

Releases are published on
[GitHub Releases](https://github.com/Daniil-Hirchyts/stream/releases) by the
`Release` workflow (`.github/workflows/release.yml`). Each release carries one
signed APK per processor type, a universal APK and `SHA256SUMS.txt`:

| File | For |
| --- | --- |
| `stream-vX.Y.Z-arm64-v8a.apk` | Most current TVs and boxes (64-bit ARM) |
| `stream-vX.Y.Z-armeabi-v7a.apk` | 32-bit sticks and older TVs, such as the first Fire TV Stick |
| `stream-vX.Y.Z-x86_64.apk` | x86 emulators and boxes |
| `stream-vX.Y.Z-universal.apk` | Any of the above, larger download |

## One-time setup: the signing key

Every release must be signed with the same key, or Android refuses to update an
installed copy. Create it once and keep it (and its passwords) somewhere safe
outside the repository:

```sh
keytool -genkeypair -v -keystore stream-release.jks -alias stream \
  -keyalg RSA -keysize 4096 -validity 10000
```

Then add four repository secrets (**Settings → Secrets and variables →
Actions**):

| Secret | Value |
| --- | --- |
| `RELEASE_KEYSTORE_BASE64` | `base64 -i stream-release.jks` |
| `RELEASE_KEYSTORE_PASSWORD` | The keystore password |
| `RELEASE_KEY_ALIAS` | `stream` (or the alias you chose) |
| `RELEASE_KEY_PASSWORD` | The key password |

Without them the workflow stops before building, so an unsigned APK is never
published.

## Publishing a release

1. Raise `versionCode` and set `versionName` in `android/app/build.gradle`
   (for example `0.2.0`), and commit.
2. Tag that commit with the same version and push the tag:

   ```sh
   git tag v0.2.0 && git push origin v0.2.0
   ```

The workflow checks that the tag matches `versionName`, builds the release APKs
(`./gradlew :app:assembleRelease -PabiSplits=true`), aligns them to 16 KiB,
signs and verifies each with `apksigner`, writes the checksums and creates the
release with generated notes. To rebuild an existing tag's files, run the
workflow by hand (**Actions → Release → Run workflow**) with that tag.

The release build is the one the device tests exercise through
`./simulator.sh release-test`; run it before tagging (see
[testing](testing.md)). Release APKs are not validated on physical TVs by the
workflow.
