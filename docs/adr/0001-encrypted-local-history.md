# ADR 0001: Encrypt Local Conversation History

## Status

Accepted

## Context

Conversation history contains sensitive personal messages. API-key encryption alone does not protect the transcript and analysis rows stored locally. The product promise is local-first storage with explicit consent before remote transmission.

## Decision

Use SQLCipher-backed Room for conversation history. Generate a per-install database passphrase, protect that passphrase with an Android Keystore AES-GCM key, and store only the encrypted passphrase in app-private preferences. Provider API keys use the same Keystore-backed protection boundary.

The domain and repository contracts remain independent of SQLCipher so the storage mechanism can be replaced without changing the UI or analysis use case.

## Consequences

- A copied database file is not readable without the device Keystore key.
- Uninstalling the app invalidates the Keystore key and local history, which is acceptable for local-only MVP data.
- Database initialization must happen after the passphrase is available and must fail closed if the encrypted passphrase cannot be decrypted.
- Instrumentation coverage is required for first-run key creation, reopen stability, and migration behavior.

