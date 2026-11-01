Save this phone's live-listening speech-to-text settings: an HTTPS transcription URL, a model id, and an API key. Use it when the user gives you those values, pastes them as one block, or asks you to set up 直播监听. Call with no arguments to read the current settings.

preset=openrouter sets https://openrouter.ai/api/v1/audio/transcriptions and model openai/whisper-large-v3. preset=openai sets https://api.openai.com/v1/audio/transcriptions and model whisper-1. preset=custom keeps the URL and model you pass. A key prefix sk-or- means OpenRouter; any other sk- key means OpenAI. Passing only the key selects that preset when the saved URL and model are still a known preset. If the key and the URL belong to different providers, the result includes warning. Do not call the transcription API while warning is set unless the user explicitly keeps that URL.

Put a combined paste in paste, and a bare key only in key or inside paste. Never copy the key into endpoint, model, reason, or your reply. Never quote the key, its middle, or the raw paste. You may say whether keySet is true and repeat keyHint (a short prefix and the last four characters only).

This does not start capture or grant Android permission. After a successful save, tell the user to open 设置 → 干活时 → 直播监听 and tap 开始监听. microphone true or false changes the capture switch; omit it to leave the switch unchanged.
