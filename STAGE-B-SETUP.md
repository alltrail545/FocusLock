# Stage B — Device Owner সেটআপ (বিস্তারিত গাইড)

FocusLock-কে ফোনের **Device Owner** বানানোর পুরো প্রক্রিয়া, ধাপে ধাপে। একবারই করতে হয়। সময় লাগবে আনুমানিক 20–30 মিনিট।

> **আগে জেনে নাও:** Device Owner বসানো অবস্থায় FocusLock আনইনস্টল করা যায় না, Safe Mode / Settings থেকে Factory Reset / সময় বদলানো — সব বন্ধ হয়ে যায়। বের হওয়ার একমাত্র পথ: অ্যাপের ভেতরের Emergency Exit (PIN + 24 ঘণ্টা, নিচে ধাপ 7) — নইলে Recovery Mode থেকে ফ্যাক্টরি রিসেট (সব ডেটা মুছবে)। PIN ভুলে গেলে অ্যাপের ভেতর থেকে উদ্ধারের কোনো পথ নেই।

---

## ধাপ 0 — যা যা লাগবে

- [ ] **কমান্ড চালানোর একটা পথ** — দুটোর যেকোনো একটা:
  - **PC ছাড়াই (সুপারিশকৃত):** ফোনে **Shizuku** + **Rish** (দুটোই Play Store-এ ফ্রি; তোমার ফোনে Shizuku ইতিমধ্যে authorized অবস্থায় চলছে — PhoneLink ওটা দিয়েই কাজ করে, তাই নতুন pairing লাগার কথা নয়)
  - **PC দিয়ে:** একটা PC (Windows / Mac / Linux) + USB ডেটা কেবল (শুধু চার্জের কেবল নয়) + PC-তে [Android Platform Tools](https://developer.android.com/tools/releases/platform-tools) ডাউনলোড ও আনজিপ করা (`adb` ওখানেই থাকে)
- [ ] ফোনে FocusLock-এর **সর্বশেষ সংস্করণ** ইনস্টল করা থাকবে (ধাপ 1)
- [ ] Google অ্যাকাউন্টের **password মনে আছে** / হাতের কাছে আছে (ধাপ 3-এর পরে আবার লগইন করতে হবে; 2-step verification লাগতে পারে)
- [ ] ফোনের চার্জ অন্তত 50%
- [ ] জরুরি ফাইল/ছবির ব্যাকআপ (সতর্কতা হিসেবে — স্বাভাবিক প্রক্রিয়ায় কিছু মোছে না)

## ধাপ 1 — ফোনে FocusLock প্রস্তুত করো

1. পুরোনো সংস্করণ থাকলে: অ্যাপের ভেতর সেটিংস → **সেটিংস ব্যাকআপ** থেকে backup ফাইল সেভ করো → পুরোনোটা **আনইনস্টল** → **সর্বশেষ APK** ইনস্টল করো (CI সাইনিং প্রতিবার আলাদা, তাই uninstall-first বাধ্যতামূলক)
2. অ্যাপ খুলে ব্যাকআপ **রিস্টোর** করো (শিডিউল/লিমিট/প্রিসেট ফিরে আসবে)
3. **PIN সেট করো** — এমন PIN যেটা তুমি কখনো ভুলবে না
4. সেটিংস ট্যাবের **পারমিশন সেটিংস** কার্ড থেকে সবগুলো দাও:
   - Accessibility ✅
   - Usage Access ✅
   - Overlay ✅
   - Do Not Disturb অ্যাক্সেস ✅
   - **Device Admin ✅** (এই ধাপটা জরুরি — Owner হওয়ার আগের শর্ত)
5. একই কার্ডে দেখে নাও: "Device Owner (Stage B)" — এখনো ❌ দেখাবে, সেটাই স্বাভাবিক

## ধাপ 2 — USB Debugging চালু করো

1. Settings → About phone → **Build number**-এ পরপর 7 বার ট্যাপ করো ("You are now a developer" দেখাবে; ফোনের PIN দিতে হতে পারে)
2. Settings → System / Additional settings → **Developer options** → **USB debugging** ON করো
3. (Infinix XOS-এ কোনো কোনো মডেলে "USB debugging (Security settings)" অপশনও থাকে — থাকলে সেটাও ON করো)

> নোট: v1.5.2 থেকে সিদ্ধান্ত বদলেছে — Owner বসার পরেও **USB/Wireless Debugging চালুই থাকবে** (DISALLOW_DEBUGGING_FEATURES ইচ্ছা করে বসানো হয়নি, যাতে Shizuku / Termux / PhoneLink আগের মতো চলে)। এতে Owner-এর শক্তি কমে না — non-testOnly Owner `dpm remove-active-admin` দিয়ে সরানো যায় না, সেটা আগের মতোই।

## ধাপ 3 — সব অ্যাকাউন্ট সাময়িকভাবে Remove করো

Device Owner কমান্ড **ফোনে কোনো অ্যাকাউন্ট থাকলে reject করে** — তাই সাময়িকভাবে সব সরাতে হবে। কোনো ডেটা মোছে না; ধাপ 6-এ আবার লগইন করলেই সব ফিরে আসবে।

1. Settings → **Passwords & accounts** (XOS-এ Settings → Accounts ও হতে পারে)
2. তালিকার **প্রতিটা** অ্যাকাউন্টে ট্যাপ করে **Remove account** করো — Google, Facebook, Messenger, Telegram, WhatsApp, যা-ই থাকুক
3. তালিকা একদম খালি হয়েছে কিনা ভালো করে দেখো (Dual Apps / App Clone চালু থাকলে ক্লোন-প্রোফাইলের অ্যাকাউন্টও অনেক সময় গোনা হয় — ক্লোন থাকলে সেটাও খালি করো)

> সতর্কতা: এই অবস্থায় ফোন **রিস্টার্ট দিও না** যতক্ষণ না ধাপ 4 শেষ হচ্ছে — শুধু সাবধানে এগিয়ে যাও।

## ধাপ 4 — Device Owner কমান্ড (দুটো পথের একটা)

### পথ ক — Shizuku + Rish (PC ছাড়াই) ✅ সুপারিশকৃত

তোমার ফোনে Shizuku ইতিমধ্যে authorized অবস্থায় চলছে (PhoneLink ওটা দিয়েই কাজ করে), তাই নতুন করে pairing লাগার কথা নয়।

1. Play Store থেকে **Rish** ইনস্টল করো (Shizuku-এর অফিসিয়াল shell — "Rish" লিখে সার্চ দিলেই পাবে)
2. **Rish** খোলো — Shizuku চলমান থাকলে সরাসরি shell পাবে
3. এই কমান্ডটা হুবহু চালাও:

```
dpm set-device-owner com.focuslock.app/.AdminReceiver
```

4. সফল হলে আউটপুট:

```
Success: Device owner set to package com.focuslock.app
```

5. এরর এলে নিচের টেবিল দেখো (PC পথের মতোই একই এরর আসে)

> Rish না চাইলে Termux-এর ভেতরেও `dpm` চালানো যায় — তবে Rish-ই সবচেয়ে সোজা, কারণ ওটা Shizuku-র permission সরাসরি পায়।

### পথ খ — PC থেকে (Shizuku না থাকলে)

1. USB কেবল দিয়ে ফোন PC-তে লাগাও; ফোনে USB মোড **File Transfer** বেছে নাও (শুধু Charging নয়)
2. PC-তে Platform Tools ফোল্ডারে টার্মিনাল/Command Prompt খুলে চালাও:

```
adb devices
```

3. ফোনের স্ক্রিনে "Allow USB debugging?" ডায়ালগ আসবে — **Always allow** টিক দিয়ে **Allow** করো। PC-তে ফোনের সিরিয়ালের পাশে `device` লেখা থাকতে হবে (`unauthorized` থাকলে ডায়ালগটা আবার দেখো)
4. এবার আসল কমান্ড:

```
adb shell dpm set-device-owner com.focuslock.app/.AdminReceiver
```

5. সফল হলে আউটপুট:

```
Success: Device owner set to package com.focuslock.app
```

### এরর হলে

| এরর | মানে | সমাধান |
|---|---|---|
| `Not allowed to set the device owner because there are already several users on the device` | ফোনে Guest / দ্বিতীয় user আছে | Settings → Users থেকে Guest/অন্য user মুছে আবার চেষ্টা করো |
| `...already some accounts on the device` (বা একই ধরনের) | কোনো অ্যাকাউন্ট রয়ে গেছে | ধাপ 3 আবার ভালো করে দেখো — বিশেষ করে Dual Apps/ক্লোন প্রোফাইল |
| `Trying to set device owner but device is already provisioned` | এই ফার্মওয়্যারে অ্যাকাউন্ট-সরানো পথে হচ্ছে না | নিচের "Plan B: Factory Reset পথ" অনুসরণ করো |
| `adb: no devices/emulators found` | PC ফোন দেখছে না | কেবল বদলাও, USB মোড File Transfer দাও, Windows হলে USB driver লাগতে পারে; `adb kill-server` দিয়ে আবার `adb devices` |
| `unauthorized` থেকে যাচ্ছে | ফোনে Allow ডায়ালগ আসেনি/মিস হয়েছে | Developer options → "Revoke USB debugging authorizations" → কেবল খুলে আবার লাগাও |

### Plan B: Factory Reset পথ (শুধু উপরেরটা ব্যর্থ হলে)

> ⚠️ এতে ফোনের **সব ডেটা মুছবে** — আগে সম্পূর্ণ ব্যাকআপ নাও (ছবি, ফাইল, WhatsApp ব্যাকআপ, MessMate-এর ডেটা Firestore-এ থাকে বলে লগইনে ফিরবে)।

1. Settings → Factory data reset করে ফোন রিসেট করো
2. নতুন সেটআপে **Wi-Fi ও Google অ্যাকাউন্ট যোগ করা Skip** করো (কোনো অ্যাকাউন্ট ছাড়াই হোম স্ক্রিনে যাও)
3. ধাপ 2-এর মতো USB Debugging চালু করো
4. FocusLock-এর সর্বশেষ APK PC থেকে বসাও: `adb install <apk-ফাইলের-নাম>` (অথবা কেবল দিয়ে কপি করে ফোনে ট্যাপ করে ইনস্টল)
5. ফোনে FocusLock খুলে **Device Admin** চালু করো (ধাপ 1-এর 4 নম্বর)
6. ধাপ 4-এর `set-device-owner` কমান্ড চালাও
7. সফল হলে তারপর Google অ্যাকাউন্ট যোগ করো ও বাকি সব পুনরায় সেটআপ করো

## ধাপ 5 — যাচাই (সবগুলো একে একে দেখো)

1. FocusLock → সেটিংস → পারমিশন সেটিংস: **Device Owner (Stage B) ✅** এবং সুরক্ষা কার্ডে "Device Owner (Stage B): চালু আছে"
2. **আনইনস্টল টেস্ট:** FocusLock আনইনস্টল করতে যাও — সিস্টেম আটকে দেবে ✅
3. **Suspend টেস্ট:** একটা অ্যাপের লিমিট কমিয়ে (যেমন 1 মিনিট) লিমিট পার করো — অ্যাপের আইকন ধূসর হয়ে খোলা বন্ধ হয়ে যাবে ✅ (টেস্ট শেষে লিমিট আগের জায়গায় নিতে 24 ঘণ্টার নিয়ম প্রযোজ্য হতে পারে — টেস্টের জন্য এমন অ্যাপ বেছে নাও যেটা একদিন না চললেও সমস্যা নেই)
4. **Kiosk টেস্ট:** 5 মিনিটের ফোকাস শুরু করো → Home/Recents চাপো — কোথাও যাওয়া যাবে না; notification shade টানো — খুলবে না ✅
5. **সময় টেস্ট:** Settings-এ তারিখ/সময় বদলাতে যাও — আটকে দেবে / auto-time বাধ্যতামূলক থাকবে ✅

## ধাপ 6 — অ্যাকাউন্ট ফেরত আনো

1. Settings → Passwords & accounts → **Add account** → Google দিয়ে শুরু করে একে একে সব অ্যাকাউন্ট আবার লগইন করো
2. Play Store / Gmail / WhatsApp খুলে সব ঠিকমতো চলছে কিনা দেখো
3. FocusLock-এ একটা ছোট ফোকাস সেশন চালিয়ে চূড়ান্ত যাচাই করো

## ধাপ 7 — জরুরি বের-হওয়ার পথ (Emergency Exit)

কোনোদিন সত্যিই Owner সরাতে হলে:

1. FocusLock → সেটিংস → সুরক্ষা → **"Device Owner সরানোর আবেদন"** → PIN দাও
2. আবেদন **24 ঘণ্টা পরে** কার্যকর হয় (মাঝে মন বদলালে Pending বাতিল করা যায়, PIN দিয়ে)
3. কার্যকর হওয়ার সময় অ্যাপ নিজে এই ক্রমে সব ছাড়ে — যাতে কোনো অ্যাপ আটকে না থাকে:
   - Status bar ফেরত + LockTask whitelist খালি
   - সব user restriction তোলা (Safe boot / Factory reset / সময় / Add user — Debugging v1.5.2 থেকে আর বসানোই হয় না, তাই তোলারও কিছু নেই)
   - নিজের uninstall-block তোলা
   - লিমিট-দেওয়া সব অ্যাপ **unsuspend + unhide**
   - সবশেষে Device Owner + Device Admin সরানো
4. এরপর অ্যাপটা সাধারণভাবে আনইনস্টল করা যাবে

> মনে রেখো: `adb shell dpm remove-active-admin` দিয়ে (non-testOnly) Device Owner সরানো যায় না — সেটা ইচ্ছা করেই এমন। একমাত্র বিকল্প পথ Recovery Mode থেকে ফ্যাক্টরি রিসেট, যাতে সব ডেটা মুছবে এবং FRP-র কারণে একই Google অ্যাকাউন্ট দিয়ে আবার লগইন করতে হবে।

---

*কোনো ধাপে আটকে গেলে এরর মেসেজের screenshot নিয়ে রাখো — ওটা দেখলেই পরের সমাধান বলা যাবে।*
