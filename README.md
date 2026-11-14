# Hushtype for Android

Dictate into any app with a floating bubble. Tap it, talk, tap again, and polished text lands
where you're typing. The Android sibling of [Hushtype for Mac](https://github.com/chiranjeebhub/hushtype)
and [Hushtype Keyboard](https://github.com/chiranjeebhub/HushType-Keyboard) for iPhone.

## How it works

1. **Bubble.** An accessibility service shows the bubble when a text field has focus (or always,
   if you prefer) and keeps it above the keyboard. Tap to start and stop, hold to talk, drag to
   move, drop it on ✕ to hide it for an hour.
2. **Speech to text.** Android's `SpeechRecognizer`, on the phone when it can be
   (`createOnDeviceSpeechRecognizer`), with your dictionary and the names near the cursor passed as
   biasing hints. Android stops the recognizer after a pause, so Hushtype restarts it and joins the
   takes into one dictation until you tap again.
3. **Polish.** The same prompt as the Mac app, with the app's writing style and the text around
   the cursor. Pick one:
   - **On this phone:** Gemini Nano through ML Kit's Prompt API (Pixel 9 and newer, recent Galaxy S).
   - **Claude:** Claude Haiku with your own Anthropic key, streamed, cut off after 2 s without a first word.
   - **Off:** type exactly what was heard.
   A failed or slow polish never loses the dictation: the transcript is typed as heard.
4. **Typing.** The text is set into the focused field at the cursor (falling back to paste). With
   no field focused it's copied, and every dictation is in History.
5. **Learning.** For 90 seconds after typing, Hushtype watches the field. Names you respell join
   the dictionary (same rules as the Mac).

The microphone is used only while you dictate. A foreground service runs during dictation and for
two minutes after, then stops, so there's no permanent notification or mic indicator.

## Dictionary, snippets and sync

Stored in one JSON file behind `VocabularyRepository`. Every entry carries its edit time and
removals stay as tombstones, so devices merge entry by entry (`VocabularyEdits.merge`) and a
removal sticks.

**Settings → Sync** pairs the phone with Hushtype on the Mac, iPhone and other phones, with no
account: scan (or type) another device's `HT1-…` code, or start a group here and show its QR code.
Everything is end-to-end encrypted (HKDF-SHA256 keys, AES-256-GCM); the server only stores a blob it
can't read. The protocol is specified in hushtype's `sync-server/README.md`, and `SyncTest` checks
the reference test vectors from `sync-server/reference.mjs`.

## Build

Needs JDK 17+ and the Android SDK (platform 36).

```sh
./gradlew testDebugUnitTest assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

After installing: open Hushtype, allow the microphone, then turn on **Accessibility → Hushtype bubble**.
Android 13+ blocks that switch for apps installed outside the Play Store until you open
**App info → ⋮ → Allow restricted settings**.

## License

MIT
