# MangaScribe — key register

One page, so nothing about the app's identity lives only in a conversation.

## The upload key (the app's permanent identity)

Held **outside this repository**, at `/home/user/keys/` on the build machine, and mirrored in
`keys/mangascribe-upload-key-backup.tar.gz`. It is deliberately not committed: `*.p12`, `*.jks`
and `*.keystore` are in `.gitignore`.

| | |
|---|---|
| Alias | `mangascribe` |
| Password | same 28-character value for store and key — stored beside the keystore, never here |
| Key | RSA 4096, self-signed, SHA-256, PKCS12, valid until 2054-03-28 |
| Certificate SHA-256 | `8A:34:73:1C:C9:F4:67:15:01:EE:CE:C6:12:66:05:92:DB:1D:90:B9:BE:9C:02:A4:91:E2:F1:3E:75:30:89:CB` |
| Keystore SHA-256 | `72eaa9eb6e10d3219198f37bf23ef7606f9efaa34b49f4e573fc84147db1020e` |

To verify a copy of the keystore:

```bash
sha256sum mangascribe-upload.p12     # must match the keystore hash above
openssl pkcs12 -in mangascribe-upload.p12 -nokeys -passin file:password.txt \
  | openssl x509 -noout -fingerprint -sha256
```

### Making the build use it

Four repository secrets (Settings → Secrets and variables → Actions):

| Secret | Value |
|---|---|
| `MANGASCRIBE_UPLOAD_KEYSTORE_BASE64` | `base64 -w0 mangascribe-upload.p12` |
| `MANGASCRIBE_UPLOAD_STORE_PASSWORD` | the password |
| `MANGASCRIBE_UPLOAD_KEY_ALIAS` | `mangascribe` |
| `MANGASCRIBE_UPLOAD_KEY_PASSWORD` | the password |

While they are absent, builds fall back to the project key below and still update each other.

## The project key (fallback, in the repository)

`build-support/mangascribe-release.p12`, alias `mangascribe`, password `mangascribe`.
Certificate SHA-256:

```
5f:5f:ee:f4:59:c4:76:f0:21:89:5e:5a:77:25:28:66:ef:c3:6f:c7:9d:69:b5:65:75:ab:16:bb:76:7c:6c:f4
```

Public by definition — every build before the upload key existed used it. Personal use only;
never for Play.

## The rule that matters

Android installs an update only when the signer matches the installed app **and** the
`versionCode` is higher. The workflow stamps `versionCode` from the run number, which only ever
increases, so the only thing that can break updates is changing the key. Therefore:

- keep both keys, forever;
- change a key only deliberately, and expect one export → uninstall → install → restore cycle on
  every device that had the old one;
- never regenerate a key to "fix" a signing error.
