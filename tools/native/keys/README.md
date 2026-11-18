# Public package-catalog verification keys

Public OpenPGP keys from `termux/termux-packages`, commit
`6ec3ffd97c11005ed390a16218d8616a9c284064`, `packages/termux-keyring/*.gpg`.
These are public upstream signing keys, not application signing credentials.
`native-bundle.py lock` uses gpgv to verify InRelease before trusting package
hashes. Normal builds use the reviewed, checked-in SHA256 lock instead.
