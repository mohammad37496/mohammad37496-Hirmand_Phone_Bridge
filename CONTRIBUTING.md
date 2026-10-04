# توسعه Hirmand Phone Bridge

## اجرای محلی

پروژه را در Android Studio باز کنید و اجازه دهید Gradle dependencyها را دریافت کند.

```bash
gradle check
gradle lintDebug
gradle assembleDebug
```

## Branchها

تغییرات جدید را روی branch جداگانه انجام دهید و با Pull Request وارد `main` کنید.

## CI

هر Push و Pull Request روی `main` بیلد، lint و check را اجرا می‌کند و APK debug را به‌عنوان Artifact ذخیره می‌کند.

Tagهایی مثل `v0.1.0` workflow انتشار را اجرا می‌کنند و APK release بدون امضا را در GitHub Release قرار می‌دهند.

## نکته امنیتی

Token و credentialها نباید در سورس یا فایل‌های workflow قرار بگیرند. از GitHub Secrets یا تنظیمات امن محیط استفاده کنید.
