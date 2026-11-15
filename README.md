# 🎧 Audio Route Tester Pro (فاحص وموجه مسارات الصوت المتقدم لأندرويد)

![Build APK](https://img.shields.io/badge/GitHub_Actions-Auto_APK_Build-00E5A3?style=for-the-badge&logo=githubactions&logoColor=white)
![Platform](https://img.shields.io/badge/Android-SDK_26--34_(Android_8--14+)-9D65FF?style=for-the-badge&logo=android&logoColor=white)
![Language](https://img.shields.io/badge/Kotlin-Jetpack_Compose_RTL-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)

مشروع أندرويد هندسي متكامل مصمم خصيصاً لاختبار وتشخيص **فصل مسار الميكروفون عن مسار الاستماع** (استخدام **ميكروفون سماعة الجكة السلكية 3.5mm TRRS أو USB-C** للتحدث بصوت نقي، مع الاستماع للطرف الآخر عبر **سماعة البلوتوث**)، مع كشف حقيقي وصارم لقيود نظام Android أثناء المكالمات العادية ومكالمات واتساب (VoIP) **بدون أي نتائج نجاح وهمية**.

---

## 🎯 لماذا يفشل معظم التطبيقات في هذا السيناريو؟ وما الذي يميز هذا المشروع؟

في نظام Android، العديد من التطبيقات تستدعي الدالة `setPreferredDevice()` أو `setCommunicationDevice()`، وعندما تعيد الدالة القيمة المنطقية `true` تعرض للمستخدم رسالة *"نجح التوجيه!"* بينما في الحقيقة يكون نظام التشغيل (وتحديداً `AudioPolicyManager` وطبقة `Audio HAL`) قد تجاهل الطلب أو قام بدمج المسارين معاً!

### مبادئ الصدق التقني الصارمة في `Audio Route Tester Pro`:
1. **عدم الثقة بالقيمة المرجعة المبدئية (`Boolean Return`)**: التطبيق لا يعتبر قبول الطلب من الـ API نجاحاً.
2. **استجواب طبقة العتاد الفعلية (`getRoutedDevice()`)**: بعد فتح البث الصوتي الفعلي (`AudioRecord.read` و `AudioTrack.write`)، يستجوب المحرك `audioRecord.routedDevice.id` و `audioTrack.routedDevice.id` ويقارنهما بمعرفات الأجهزة التي اختارها المستخدم.
3. **قياس طاقة الإشارة الصوتية الخام (`PCM 16-bit RMS dBFS & Peak`)**: يحلل التطبيق العينات الصوتية الملتقطة لحظياً للتأكد من أن الميكروفون المختار يرسل بيانات صوتية حقيقية غير صفرية، وليس مكتوماً (`Silent Buffer`) بسبب استحواذ مكالمة أخرى على الـ `AudioFocus`.

---

## 🔬 التشريح الهندسي لقيود نظام Android أثناء المكالمات

| وضع التشغيل | سلوك الدخل (مايك الجكة) | سلوك الخرج (سماعة البلوتوث) | النتيجة الفعلية في نظام Android |
| :--- | :--- | :--- | :--- |
| **الوضع العادي (`MODE_NORMAL`)** عبر الجسر الصوتي داخل التطبيق | `AudioRecord.setPreferredDevice` يوجه لمايك الجكة (`TYPE_WIRED_HEADSET`) | `AudioTrack.setPreferredDevice` يوجه لبلوتوث الوسائط (`TYPE_BLUETOOTH_A2DP`) | **ينجح الفصل 100%** لأن البلوتوث يعمل ببروتوكول `A2DP` أحادي الاتجاه ولا يفرض تفعيل مايك البلوتوث. |
| **وضع المكالمات (`MODE_IN_COMMUNICATION`)** (مكالمات واتساب / تيليجرام) | واتساب يطلب `VOICE_COMMUNICATION` والنظام يفعل `setCommunicationDevice` | يتحول البلوتوث إجبارياً من `A2DP` إلى `HFP/SCO` ثنائي الاتجاه | **يرفض النظام الفصل** لأن `setCommunicationDevice` يربط المايك والسماعة في جهاز اتصال واحد (`Coupled Route`). |
| **المكالمات الخلوية (`MODE_IN_CALL`)** | يدار حصرياً عبر مودم الاتصال (`TelecomManager` / `Baseband`) | لا يملك أي تطبيق طرف ثالث صلاحية فصل المسارين | يتطلب صلاحية نظام `MODIFY_AUDIO_ROUTING` أو حل عتادي (Hardware Bypass). |

---

## 🛠️ الميزات والأدوات المدمجة في المشروع

1. **مختبر الجسر الصوتي المستقل (Stream-Level Split Loopback Lab)**:
   - يتيح لك اختيار جهاز الدخل (مثل `TYPE_WIRED_HEADSET`) وجهاز الخرج (مثل `TYPE_BLUETOOTH_A2DP` أو `SCO`) بشكل منفصل.
   - يعرض مؤشر `VU Meter` لحظي بـ `dBFS` مع إمكانية دمج نغمة اختبار `440Hz` للتأكد السمعي الفوري.
   - يصدر بطاقة حكم صارمة (`PASS: TRUE SPLIT` أو `FAIL: OS COUPLED` أو `FAIL: MIC MUTED`).
2. **مختبر فحص ارتباط المكالمات (`setCommunicationDevice` Coupling Probe)**:
   - يختبر سلوك هاتفك الفعلي عند تفعيل `MODE_IN_COMMUNICATION` ويكشف بدقة الجهاز الذي يفرضه الـ `Audio HAL`.
3. **حارس المكالمات التلقائي + زر الإشعارات السريع (`Call Mic Guard Service + Quick Settings Tile`)**:
   - خدمة خلفية (`Foreground Service`) مع زر سريع في شريط الإشعارات (`تثبيت مايك الجكة`) لإجبار الهاتف على التحول إلى مايك الجكة السلكية تلقائياً عند بدء مكالمة واتساب إذا كنت تفضل نقاء الصوت على حساب سماعة البلوتوث.
4. **سجل تشخيصي لحظي (`Live HAL & Hotplug Telemetry`)**:
   - يرصد توصيل وفصل الجكة أو البلوتوث لحظياً (`AudioDeviceCallback`) مع زر نسخ التقرير الهندسي الكامل للحافظة.

---

## 💡 الحلول العملية والبديلة الموصى بها (بدون وضع سماعة السلك في الأذن)

بما أن نواة Android تمنع برمجياً فصل المايك عن السماعة داخل تطبيقات المكالمات (لتلافي مشاكل إلغاء الصدى `Acoustic Echo Cancellation - AEC` بين ساعتين مختلفتين `Clock Drift`)، إليك أفضل الحلول العملية المجربة:

1. **الحل العتادي الأذكى (مضمون 100% على كل الهواتف وفي واتساب والمكالمات العادية)**:
   - استخدم **مقسم جكة `3.5mm TRRS Y-Splitter`** (يفصل مدخل الجكة في الهاتف إلى مخرجين: مدخل ميكروفون 🎤 ومخرج سماعة 🎧).
   - أوصل في مدخل الميكروفون **مايك ياقة سلكي صغير (Lavalier Mic)** علّقه على قميصك.
   - أوصل في مخرج السماعة **مرسل بلوتوث صغير (`3.5mm AUX Bluetooth Transmitter`)** مقترن بسماعة البلوتوث في أذنك.
   - **النتيجة**: يرى الهاتف أن سماعة جكة سلكية كاملة متصلة به، فيأخذ صوتك النقي من المايك السلكي، ويرسل صوت الطرف الثاني عبر مرسل البلوتوث إلى سماعة البلوتوث في أذنك في جميع المكالمات (واتساب، تيليجرام، ومكالمات الشبكة العادية)!

---

## 🚀 طريقة الرفع على GitHub وبناء ملف الـ APK تلقائياً

المشروع مجهز بالكامل بملف سير عمل GitHub Actions في المسار:
`.github/workflows/android-build.yml`

### الخطوات:
1. فك الضغط عن ملف `AudioRouteTester-Pro-Android.zip` على جهازك.
2. أنشئ مستودعاً جديداً (Repository) على حسابك في GitHub.
3. ارفع ملفات المشروع إلى المستودع عبر الأوامر التالية:
   ```bash
   git init
   git add .
   git commit -m "Initial commit: Audio Route Tester Pro"
   git branch -M main
   git remote add origin https://github.com/<USERNAME>/<REPO_NAME>.git
   git push -u origin main
   ```
4. بمجرد الرفع، توجه إلى تبويب **Actions** في مستودعك على GitHub:
   - ستجد عملية البناء **Build Audio Route Tester APK** تعمل تلقائياً.
   - بعد انتهاء البناء (خلال دقيقتين تقريباً)، اضغط على عملية البناء وحمّل ملف **`AudioRouteTester-Debug-APK`** الجاهز للتثبيت مباشرة على هاتفك.
