# NOVA for Android

A lightweight, Nova-style voice assistant for Android — powered by the free Gemini API.
Developed by **[Manik Hossain](https://manikhossain.in/)**. Free and open source.

## Install
1. On your phone open **https://github.com/procoder-manik/nova-android/releases/latest** and download **Nova.apk**.
2. Open it → allow "Install unknown apps" for your browser → Install.
3. Open Nova → Settings → paste your free Gemini key from https://aistudio.google.com/apikey
4. Settings → Permissions → allow everything you want Nova to do.

## Use
- Say **"Nova"** (works with the screen off — a small notification shows Nova is listening), or tap the orb.
- Say **"Stop"** to interrupt. "That's all / থামো" puts Nova on standby.
- Ask in Bangla or English: open apps, websites, YouTube, call people, type WhatsApp/SMS messages,
  read new notifications (WhatsApp, Messenger, SMS, missed calls), check Gmail, alarms, timers, flashlight,
  music & volume, Google Maps directions, news with sources, and Nova remembers your preferences.

## Gmail (optional, one-time setup, free)
Nova reads Gmail notifications without any setup. For full inbox reading:
1. https://console.cloud.google.com → create a project → **APIs & Services → Library → Gmail API → Enable**.
2. **OAuth consent screen** → External → app name "Nova" → add your Gmail address as a **Test user**.
3. **Credentials → Create credentials → OAuth client ID → Android**
   - Package name: `com.manikhossain.nova`
   - SHA-1: `5F:90:DE:0C:F7:C1:91:B4:D0:82:C3:AB:EB:7A:6F:97:06:FB:60:70`
4. In Nova → Settings → **Connect Gmail** → choose your account → Allow (read-only).

## Build
GitHub Actions builds `Nova.apk` on every push (see `.github/workflows/build.yml`).
The signing key in `signing/` is a dedicated key for this open-source app (so updates install over the old version).
