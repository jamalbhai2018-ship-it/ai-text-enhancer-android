# AI Text Enhancer — Android App

Chrome extension ka Android version. Kisi bhi app (WhatsApp, Gmail, Chrome, etc.) me
text field pe focus karne se ek floating bubble dikhta hai — usse tap karke text ko
Improve / Fix Grammar / Professional / Friendly / Shorten / Expand / Custom prompt se
enhance kar sakte hain, ya AI se chat kar sakte hain (extension ke side-panel jaisa).

## APK kaise banayein (bina Android Studio install kiye — RECOMMENDED)

Is sandbox me Android build tools available nahi hain, is liye main khud APK generate
nahi kar saka. Lekin GitHub Actions se automatically ban jayega:

1. GitHub par ek naya **empty** repository banayein (public ya private, dono chalega).
2. Ye poora `AiTextEnhancer` folder us repo me push kar dein:
   ```
   cd AiTextEnhancer
   git init
   git add .
   git commit -m "AI Text Enhancer Android app"
   git branch -M main
   git remote add origin <apni-repo-ka-URL>
   git push -u origin main
   ```
3. GitHub par repo ke **Actions** tab me jayein — "Build Debug APK" workflow khud chal
   jayega (2-4 minute lagte hain).
4. Workflow complete hone par usi run ke neeche **Artifacts** section me
   `AiTextEnhancer-debug-apk` milega — download kar ke zip se APK nikal lein.
5. Wo `.apk` file apne phone me bhej kar install kar lein (Settings me "install
   unknown apps" allow karna padega — Android khud bata dega jab install karenge).

Agar koi step me atkein ya screenshot bhej dein, main foran guide kar dunga.

## Ya phir Android Studio se (agar already installed hai / karna chahte hain)

1. **Android Studio** (latest, Ladybug ya newer) install karein.
2. Ye poora `AiTextEnhancer` folder open karein: **File → Open**.
3. Gradle sync khud ho jayega (internet chahiye).
4. Menu se **Build → Build Bundle(s) / APK(s) → Build APK(s)** dabayein.
5. APK `app/build/outputs/apk/debug/app-debug.apk` par mil jayega.

## App use karne ka tareeqa

1. App kholein → **"Set API Key"** se apni free Gemini API key dalein
   (`aistudio.google.com/apikey` se milti hai).
2. **"Enable Karein"** se Accessibility settings khulengi → "AI Text Enhancer" ko ON karein.
3. Ab kisi bhi app me kisi text field pe tap karein — chhota ✨ bubble dikhega.
   Usse tap karke enhance mode ya Chat choose karein.

## Important limitations (honestly batana zaroori hai)

- **Text auto-replace har jaga guaranteed nahi.** Android me `ACTION_SET_TEXT` zyada tar
  native EditText fields (WhatsApp, Gmail app, SMS, notes apps) me kaam karta hai.
  Kuch WebView-based fields (jaise browser ke andar wali website forms) is action ko
  support nahi karte — waha app automatically clipboard-paste fallback try karta hai,
  aur wo bhi fail ho to result clipboard me copy kar deta hai taake aap khud paste kar sakein.
  Extension me ye limitation nahi thi kyunki wo seedha webpage ka DOM edit karta tha —
  Android me kisi app ke andar seedha DOM/view edit karne ki aisi permission nahi milti.
- **Accessibility Service** Google Play par publish karne ke liye strict review/privacy-policy
  requirement hoti hai (kyunke ye sensitive permission hai). Agar Play Store pe daalna hai
  to ek Privacy Policy page aur "declared use" form lagega — bataiyega to wo bhi bana dunga.
- API key device pe `EncryptedSharedPreferences` me store hoti hai (local hi rehti hai,
  kahin bheji nahi jati siwaye seedha Google Gemini API ko).

## Files

- `TextEnhancerAccessibilityService.kt` — focused field detect + text read/write (extension ka content.js)
- `OverlayManager.kt` — floating bubble, toolbar, custom prompt, chat window (extension ka popup + sidepanel)
- `GeminiClient.kt` — Gemini streaming API calls (extension ka background.js)
- `MainActivity.kt` / `SettingsActivity.kt` — onboarding aur API key settings
