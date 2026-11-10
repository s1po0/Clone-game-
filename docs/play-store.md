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
  -keystore mangascribe-upload.jks \
  -alias mangascribe \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -storetype JKS
```

It asks for a store password and a key password — **write both down**. Use 25+ characters;
this is the one password that can never be reset. `-validity 10000` is ~27 years, which is the
Android maximum people actually use; Play requires the key to outlive your update schedule.

Then make the base64 form that GitHub can hold:

```bash
# macOS
base64 -i mangascribe-upload.jks | tr -d '\n' | pbcopy
# Linux
base64 -w0 mangascribe-upload.jks
# Windows (PowerShell)
[Convert]::ToBase64String([IO.File]::ReadAllBytes("mangascribe-upload.jks"))
```

## 2. Keep the key safe

Copy `mangascribe-upload.jks` and the two passwords into two places you will still have in five
years (a password manager and an offline copy). It is not a secret from *you* — it is a secret
from everyone else, and losing it means Play will never accept an update for this app under that
key again. Play App Signing can reset an *upload* key, but only while you still control the
Play account and the app; offline copies remove the whole question.

**Never** commit the `.jks` to git. `.gitignore` already refuses `*.jks` and `*.keystore`.

## 3. Put the four secrets in GitHub

Repository → **Settings → Secrets and variables → Actions → New repository secret**:

| Secret | What to paste |
|---|---|
| `MANGASCRIBE_PLAY_KEYSTORE_BASE64` | the long base64 string from step 1 |
| `MANGASCRIBE_PLAY_STORE_PASSWORD` | the store password you chose |
| `MANGASCRIBE_PLAY_KEY_ALIAS` | `mangascribe` (or whatever alias you used) |
| `MANGASCRIBE_PLAY_KEY_PASSWORD` | the key password (often the same as the store password) |

From then on every push builds `app-release.aab` signed with **your** key and attaches it to the
workflow run as `mangascribe-play-bundle`. The build log also prints your certificate's SHA-256 —
that is the fingerprint to compare against Play Console.

If the secrets are absent the bundle job skips itself (it does not fail), and the sideload APKs
keep being signed with `build-support/mangascribe-release.p12` so people who installed from
GitHub releases keep getting updates.

## 4. Which key signs what

| Build | Signed with | Who uses it |
|---|---|---|
| Release APK on GitHub Releases (`apk-<n>`) | `build-support/mangascribe-release.p12` | anyone sideloading; updates install over each other |
| Play upload `.aab` | **your upload key**, from the secrets | Play Console |
| The installed app on Play devices | Play App Signing key (Google holds it) | end users |

Two identities, on purpose: the sideload APKs must keep updating for the people who already have
them, and the Play key must be yours alone. They never have to be the same key.

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
