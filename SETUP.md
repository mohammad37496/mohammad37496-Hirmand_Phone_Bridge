# راه‌اندازی Hirmand Phone Bridge

در Environment Variables سرویس Liara سایت این کلید را تنظیم کنید:

    HIRMAND_PHONE_BRIDGE_TOKEN=<یک کلید تصادفی طولانی>

Endpoint سایت:

    https://www.hirmandrealestate.ir/api/device-sync/v1

برای کار بدون اینترنت عمومی، سرور باید در همان LAN یا Hotspot گوشی باشد. نمونه:

    http://192.168.1.10:3000/api/device-sync/v1

هر Push/PR روی main با GitHub Actions شامل Gradle check، Android lint، assembleDebug و Node syntax check می‌شود و APK به‌عنوان Artifact ذخیره می‌شود.

Tagهایی مثل v0.1.0 نیز workflow انتشار را اجرا می‌کنند و APK release بدون امضا را در GitHub Release قرار می‌دهند.

برای گیرندهٔ محلی:

    cd server
    npm start

متغیرهای نمونه:

    HOST=0.0.0.0
    PORT=3000
    DEVICE_SYNC_TOKEN=<token>
    DATA_DIR=./data

Token را داخل Git یا APK عمومی قرار ندهید.
