# التوقيع

التطبيق يُبنى على GitHub Actions. بدون إعداد إضافي يُوقَّع الـ APK بمفتاح تجريبي: يُثبَّت عادة،
لكن كل بناء بمفتاح مختلف لا يتحدّث فوق السابق (يلزم حذف القديم أولًا).

## للحصول على تحديثات سلسة (مرة واحدة)
1. أنشئ مفتاحًا دائمًا:
   - من GitHub: **Actions ← Generate signing key ← Run workflow** (المستودع يجب أن يكون **Private**)، ثم نزّل الـ Artifact `signing-key`.
   - أو على جهازك: `./tools/make-keystore.sh`
2. أضف أسرار المستودع (Settings ← Secrets and variables ← Actions):
   - `KEYSTORE_BASE64` — ناتج `base64 -w0 release.jks`
   - `KEYSTORE_PASSWORD`
   - `KEY_ALIAS` (الافتراضي `fileman`)
   - `KEY_PASSWORD` (اختياري إن كانت مثل كلمة مرور الملف)
3. احتفظ بنسخة احتياطية من `release.jks` وكلمة المرور خارج GitHub. فقدانه = لا تحديثات بعد اليوم.
