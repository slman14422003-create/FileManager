# skills — FileManager

## آخر مهمة
- إصلاح فشل البناء (assembleRelease) في ApkDeepScan.java السطر 302.
- السبب: الكلاس ProviderInfo لا يحتوي الحقل permission (موجود فقط في ServiceInfo وActivityInfo).
- الحل: استخدام readPermission وwritePermission فقط للـ providers.

## قواعد المشروع
- لا ملفات README جديدة.
- الأكواد بدون نصوص عربية داخلها، والنصوص في ملفات strings.
- حزمة التطبيق: com.fileman.app (جافا).
- البناء عبر GitHub Actions، الإصدار 1.0.53 وكود 63.

## ملاحظات للمتابعة
- عند أي خطأ cannot find symbol راجع الحقول المتاحة في كلاس android.content.pm المعني.
