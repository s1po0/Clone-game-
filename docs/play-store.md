# Your own key, and the updates it unlocks

You do not have to put this app on Google Play. The key below is useful **even if you never
open Play Console**: it is the identity of your app. Whoever holds it can publish an update
that installs over the old build; nobody else can.

The plan, in one sentence: **create one upload key on your own machine, keep it forever, put it
in four GitHub secrets, and never put it in the repository.**

---

## 1. Create your key (once, on your computer)

You need a JDK (`keytool` ships with it). In a terminal, on a machine you trust:

```bash
keytool -genkeypair -v \
  -keystore mangascribe-upload.p12 \
  -alias mangascribe \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -storetype PKCS12
```

(`keytool` from JDK 9 or newer writes PKCS12 with this command; older JDKs need
`-storetype JKS`, and this build reads either.)

It asks for a store password and a key password — **write both down**. Use 25+ characters;
this is the one password that can never be reset. `-validity 10000` is ~27 years, which is the
Android maximum people actually use; Play requires the key to outlive your update schedule.

Then make the base64 form that GitHub can hold:

```bash
# macOS
base64 -i mangascribe-upload.p12 | tr -d '\n' | pbcopy
# Linux
base64 -w0 mangascribe-upload.p12
# Windows (PowerShell)
[Convert]::ToBase64String([IO.File]::ReadAllBytes("mangascribe-upload.p12"))
```

## 2. Keep the key safe

Copy `mangascribe-upload.p12` and the two passwords into two places you will still have in five
years (a password manager and an offline copy). It is not a secret from *you* — it is a secret
from everyone else, and losing it means Play will never accept an update for this app under that
key again. Play App Signing can reset an *upload* key, but only while you still control the
Play account and the app; offline copies remove the whole question.

**Never** commit the keystore to git. `.gitignore` already refuses `*.jks`, `*.keystore` and
`*.p12`, and this build never writes it into the source tree — it is decoded into the build
directory for the length of one run.

## 3. Put the four secrets in GitHub

Repository → **Settings → Secrets and variables → Actions → New repository secret**:

| Secret | What to paste |
|---|---|
| `MANGASCRIBE_UPLOAD_KEYSTORE_BASE64` | the long base64 string from step 1 |
| `MANGASCRIBE_UPLOAD_STORE_PASSWORD` | the store password you chose |
| `MANGASCRIBE_UPLOAD_KEY_ALIAS` | `mangascribe` (or whatever alias you used) |
| `MANGASCRIBE_UPLOAD_KEY_PASSWORD` | the key password (often the same as the store password) |

From then on every push signs **both** the release APKs and `app-release.aab` with your key, and
attaches the bundle to the workflow run as `mangascribe-play-bundle`. The build log prints the
certificate's SHA-256 for whichever artifact it just checked — that is the fingerprint to compare
with Play Console and with an APK you already have installed.

If the secrets are absent the bundle job skips itself (it does not fail) and the APKs are signed
with the project key instead, so a fork or a local build still produces something installable.

## 4. Which key signs what

| Build | Signed with | Who uses it |
|---|---|---|
| Release APK on GitHub Releases (`apk-<n>`) | **your upload key** | anyone sideloading; every later build updates it |
| Play upload `.aab` | **your upload key** | Play Console |
| The installed app on Play devices | Play App Signing key (Google holds it) | end users |

One identity for everything you ship, which is the point: an install from GitHub and an install
from Play are the same app, so a future build updates either one without losing the library.

If the four secrets are missing, the APKs fall back to the project key
`build-support/mangascribe-release.p12`. Those installs can only be updated by builds made with
that project key, so if you add the secrets later, expect one backup-and-restore step for anyone
who installed before.

## 4b. Turning the key on: what happens to installs made before

Adding the secrets changes the app's signer. Android only accepts an update from the *same*
signer, so the first build made with your key cannot install over an APK signed with the project
key. That is a one-time step, and it costs nothing if you do it in this order:

1. Add the four secrets.
2. On the phone, **Settings → Backup and moving to a new build → Export backup** and save the
   zip somewhere off the phone (or share it to yourself).
3. Uninstall the old app.
4. Install the new APK (`apk-<n>` from the same workflow run — it is now signed with your key).
5. **Settings → Restore from a backup**, pick the zip. Library, translations and pages return;
   API keys are device-bound and must be re-entered (the app says so).

From then on no one ever has to do that again: every build, from GitHub or from Play, carries
the same key and installs in place.

## 5. Publishing, if and when you want to

1. Play Console → **Create app** → name, default language, app, free.
2. **Testing → Internal testing → Create new release**, upload the `.aab` from the workflow run.
3. First upload only: accept **Play App Signing** (this is what keeps the app updatable even if
   you later change the upload key).
4. Fill in the declarations Play demands before anything goes live: privacy policy URL, data
   safety form, content rating, target audience, ads declaration.
5. Promote through internal → closed → production when you are ready.

Store listing assets (icon 512×512, feature graphic 1024×500, screenshots) are not in this
repository — generate them from the running app so they match what people actually get.

## 6. What Play will look at in this app

Honest list, because these are the things that get an app rejected or removed:

- **Translation is not free of policy.** The default engine uses Google's unofficial
  `translate_a/single` endpoint, which has no terms covering a published app. For Play, set the
  engine to a keyed/official one, or keep the app's distribution to GitHub releases. This is a
  product decision, not a technical bug.
- **`INTERNET` + user-supplied keys**: the data-safety form must say pages are sent to the
  translation service the user selected, and that API keys are stored encrypted on the device.
- **Copyrighted content intake.** The app translates pages the user supplies; the listing must
  not advertise reading or downloading copyrighted manga from any source.
- **Target API level** is 35, above the current Play requirement.
- **Signing**: upload key you control, `versionCode` monotonically increasing (the CI run
  number), so every future release is an update, never a new app.
