# Galahad-X Agent

<div dir="rtl">

## معرفی (فارسی)

**گالاهاد-ایکس ایجنت** یک دستیار هوشمند کامل برای اندروید است که مستقیماً روی گوشی اجرا می‌شود — بدون روت، بدون Termux.

### چه کارهایی می‌کند؟
- 🎛 **کنترل گوشی**: باز کردن اپ و تنظیمات، خواندن صفحه، کلیک، تایپ، اسکرول (از طریق Accessibility Service)
- 🤖 **ایجنت واقعی**: مدل LLM ابزارها را خودش انتخاب و اجرا می‌کند؛ خطاها را خودش تحلیل و رفع می‌کند (حلقه Perceive → Act)
- 🐍 **پایتون ۳.۱۴ + pip + numpy + pillow**: از PyPI نصب می‌کند — یک‌بار با ابزار install_tool
- 🎬 **ffmpeg**: ساخت و ادیت ویدیو/صوت روی گوشی
- 🌐 **وب**: جستجوی وب، خواندن صفحات، باز کردن لینک در مرورگر
- 👁 **دیدن عکس**: ارسال عکس به مدل بینا و دریافت توضیح
- 💾 **مهارت‌ها و حافظه**: روال‌های تکراری را به‌صورت Skill ذخیره می‌کند و حقایق پایدار را در حافظه نگه می‌دارد (الگوی Hermes Agent)
- 🖥 **وب‌سرور لوکال**: ساخت وبسایت با write_file + preview در مرورگر گوشی
- ⚙️ **چند پروایدر**: هر API سازگار با OpenAI (OpenRouter، OpenAI، و...) + انتخاب مدل

### نصب
1. از صفحه **Releases** آخرین فایل `Galahad-X-Agent-vX.Y.Z.apk` را دانلود کنید
2. روی گوشی نصب کنید (اجازه نصب از منابع ناشناس لازم است)
3. وارد **Settings** شوید → API Base URL و کلید و مدل را اضافه کنید
4. اگر از قابلیت‌های کنترل صفحه استفاده کردید: Accessibility Service را فعال کنید
   - اندروید ۱۳+: برای اپ‌های نصب‌شده خارج از Play Store باید «اجازه تنظیمات محدود» را فعال کنید (راهنمای داخل اپ)

### ساخت از سورس
```bash
# Android SDK 33 + JDK 17 لازم است
# local.properties (commit نشده) باید امضا را بدهد:
#   keystore.file / keystore.password / keystore.alias / keystore.keyPassword
./gradlew assembleRelease
# APK: app/build/outputs/apk/release/app-release.apk
```
> فایل‌های assets پایتون و ffmpeg با **Git LFS** نگهداری می‌شوند — قبل از clone `git lfs install` را اجرا کنید.

### اجازه‌ها
- **Accessibility Service**: کنترل صفحه (کلیک/تایپ/اسکرول)
- **Internet**: ارتباط با API مدل‌ها
- **Foreground Service**: ادامه کار ایجنت هنگامی که اپ در پس‌زمینه است
- هر داده‌ای روی گوشی می‌ماند؛ تنها ترافیک خروجی، درخواست‌های API خودِ شماست

</div>

---

## Overview (English)

**Galahad-X Agent** is a full on-device AI agent for Android — no root, no Termux. It runs entirely on the phone and acts through real tools chosen by the LLM.

### Features
- 🎛 **Phone control**: open apps & settings, read the screen, tap, type, scroll (via Accessibility Service)
- 🤖 **Real agent loop**: the LLM picks and executes tools, reads observations, and self-heals errors (Perceive → Act)
- 🐍 **Python 3.14 + pip + numpy + pillow** bundled — installs from PyPI via `install_tool`
- 🎬 **ffmpeg on-device**: build and edit video/audio
- 🌐 **Web**: search, fetch pages, open URLs in the browser
- 👁 **Vision**: send images to a vision model
- 💾 **Skills & memory**: repeatable routines saved as SKILL.md; durable facts in user/memory stores (Hermes-style)
- 🖥 **Local web server**: generate sites with write_file and preview in the phone browser
- ⚙️ **Multi-provider**: any OpenAI-compatible API (OpenRouter, OpenAI, ...) with model picker

### Install
1. Download the latest `Galahad-X-Agent-vX.Y.Z.apk` from **Releases**
2. Install it (allow "install unknown apps")
3. Open **Settings** → add your API base URL, key and model
4. Enable the Accessibility Service if you want screen control
   - Android 13+: sideloaded apps need "Allow restricted settings" (in-app guide covers it)

### Build from source
Requires JDK 17 + Android SDK 33. Signing config is read from `local.properties` (not committed): `keystore.file`, `keystore.password`, `keystore.alias`, `keystore.keyPassword`.
```bash
git lfs install
git clone https://github.com/galahad-mamad/galahad-x-agent.git
cd galahad-x-agent
./gradlew assembleRelease
# APK: app/build/outputs/apk/release/app-release.apk
```
> Bundled python/ffmpeg assets are stored with **Git LFS**.

### Permissions
- **Accessibility Service**: screen control (tap/type/scroll)
- **Internet**: your own LLM API traffic
- **Foreground Service**: keep the agent running while the app is backgrounded
- All data stays on-device; the only outbound traffic is your own API calls

## License
MIT
