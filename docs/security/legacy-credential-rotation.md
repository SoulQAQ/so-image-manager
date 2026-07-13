# Legacy Credential Rotation

The rebuild no longer reads, compiles, packages, logs, or exports a shared model credential. The previously tracked credential must be revoked at its provider before any rebuilt APK is distributed because deleting it from the current tree does not remove it from Git history.

Revocation is an external owner action and cannot be inferred from repository state. Record only the provider, revocation date, and operator in the private release record; never record the credential value in this repository.
