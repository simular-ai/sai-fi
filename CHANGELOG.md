# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project uses [0.x](https://semver.org/) versioning until the public
voice-profile and FSM contract are stable.

## [Unreleased]

## [0.1.0] — 2026-08-21

First public release.

A standalone Android app that puts the Sai voice concierge on Meta Ray-Ban glasses. The phone runs
a Gemini Live audio session with your own API key and owns the conversation state machine (queue,
interrupt, resolve). It talks to the Sai API only to reach your agent. The glasses are the
microphone, speaker and camera.

See the [README](README.md) to build and run it.

### Notes

- App version is `0.1.0` (`versionCode` 1). 0.x means the bundled voice profile and FSM rules may
  still change.
- Default `sai_api_url` is production (`https://api.sai.simular.ai`). An empty value fails at
  runtime with `no sai_api_url` rather than a mysterious network error.

[Unreleased]: https://github.com/simular-ai/sai-fi/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/simular-ai/sai-fi/releases/tag/v0.1.0
