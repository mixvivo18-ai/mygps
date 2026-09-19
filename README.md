# SP — Fake GPS (Mock Location) บน Android

แอป Android สำหรับ **ปลอมตำแหน่ง GPS** ของเครื่อง ให้แอปอื่นๆ คิดว่ามือถืออยู่ ณ ตำแหน่งที่คุณเลือก โดยใช้ **Mock Location Provider API** ของ Android อย่างเป็นทางการ

UI อ้างอิงจากแอป Fake GPS ดังนี้:

- หน้าจอหลักเป็น **แผนที่ (osmdroid / OpenStreetMap)** เต็มจอ
- Toolbar: hamburger | "SP" | play | stop | filter | more
- **ปุ่ม play กลมใหญ่ มุมล่างขวา** สำหรับ start/stop
- แถบ overlay แสดง Latitude / Longitude ด้านล่าง
- Drawer menu: Change View / Search / Share / Rate Us / Go Pro / Privacy Policy / Detect mock locations / Development Settings / Help!

## วิธีติดตั้ง

### 1) เปิด Developer Options บนเครื่อง Android

ไปที่ **Settings → About phone → กด Build number 7 ครัศ**

### 2) ตั้งค่า SP เป็น mock location app

ไปที่ **Settings → System → Developer Options → Select mock location app → SP**

(ชื่อเมนูใน Android เวอร์ชันต่างๆ อาจต่างกันเล็กน้อย เช่น "Mock location app" หรือ "Set mock location app")

### 3) Build APK

```bash
cd mygps
./gradlew assembleDebug
```

APK จะออกที่ `app/build/outputs/apk/debug/app-debug.apk` — เอาไปติดตั้งบนเครื่อง Android

หรือเปิดโฟลเดอร์ด้วย Android Studio แล้วกด Run

## วิธีใช้

1. เปิดแอป SP → จะเห็นแผนที่ ตำแหน่งเริ่มต้นอยู่ที่กรุงเทพฯ
2. **กดแผนที่ค้าง (long press)** ตรงไหนก็ได้ → ปักหมุดเลือกตำแหน่งนั้น
3. หรือเปิด **hamburger menu → Search** แล้วพิมพ์ชื่อสถานที่ (เช่น "Tokyo") แล้วเลือกจากผลลัพธ์
4. กด **ปุ่ม play มุมล่าบขวา** (หรือ play ใน toolbar) → เริ่ม mock GPS
5. เปิด Google Maps / Grab / LINE ฯลฯ → จะเห็นว่าตำแหน่งเป็นพิกัดที่คุณเลือก
6. กด stop เมื่อใช้เสร็จ

## Save locations

กด filter (icon funnel ใน toolbar) เพื่อเปิด sheet ด้านล่าง:

- กรอกชื่อ → กด **Save** เพื่อบันทึกตำแหน่งปัจจุบันเป็น preset
- แต่ละ row มีปุ่ม **Load** (กระโดดไปตำแหน่งนั้น) และ **Delete**

ข้อมูลจะถูกเก็บใน SharedPreferences — อยู่ถาวรจนกว่าจะ uninstall แอป

## โครงสร้างโปรเจกต์

```
mygps/
├── app/
│   ├── build.gradle.kts
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── java/com/example/mygps/
│       │   │   ├── MainActivity.kt          # UI หลัก + drawer + map
│       │   │   ├── MockLocationService.kt   # Foreground service - ค้ำ mock location
│       │   │   ├── MockLocationEngine.kt    # LocationManager test-provider (helper)
│       │   │   ├── SavedLocations.kt        # SharedPreferences JSON store
│       │   │   └── NominatimService.kt      # OpenStreetMap geocoding
│       │   └── res/
│       │       ├── layout/                  # activity_main, dialogs, items
│       │       ├── menu/                    # toolbar + drawer
│       │       ├── drawable/                # vector icons + backgrounds
│       │       ├── mipmap-anydpi-v26/       # adaptive launcher icon
│       │       └── values/                  # strings, colors, themes
│       └── test/java/com/example/mygps/     # JVM unit tests
└── build.gradle.kts / settings.gradle.kts
```

## Dependencies

- **osmdroid 6.1.18** — แผนที่ OpenStreetMap (ไม่ต้อง API key)
- **Material Components 1.11.0** — UI หลัก
- **AndroidX** — AppCompat, Activity, Lifecycle, RecyclerView
- **Kotlin Coroutines 1.7.3** — สำหรับ async search + location ticker
- **JUnit 4** — unit tests

## Build & Test

> **Note:** the Gradle wrapper JAR (`gradle/wrapper/gradle-wrapper.jar`) is not
> committed to this repo (it's binary). Android Studio generates it automatically
> the first time you open the project. If you want to use `./gradlew` from the
> command line, run `gradle wrapper` once on a machine with Gradle installed.

```bash
# Build debug APK (after wrapper jar is generated)
./gradlew assembleDebug

# Run unit tests
./gradlew testDebugUnitTest

# Lint
./gradlew lintDebug
```

If you just want to verify the pure-JVM validator logic without Android Studio,
run the Python smoke test:

```bash
python3 standalone_test.py
```

## ข้อจำกัดที่ควรรู้

- **Android 6.0+ (API 23)**: ต้องขอ runtime permission `ACCESS_FINE_LOCATION` (mygps จัดการให้)
- **Android 7.0+ (API 24)**: ต้องเรียก `setTestProviderStatus(AVAILABLE)` ก่อน location fix จะถูกยอมรับ
- **Android 10+ (API 29)**: background location access ต้องขอเพิ่ม — mygps ไม่ต้องการเพราะทำงานเฉพาะตอนแอปอยู่ foreground
- **บางแอปตรวจ mock ได้**: Snapchat, Pokémon GO, แอปธนาคารบางตัว ใช้ `Location.isFromMockProvider()` หรือ Play Integrity API — mygps ไม่สามารถ bypass การตรวจเหล่านี้ได้
- **Battery/Performance**: การ mock ต่อเนื่องจะกิน battery เล็กน้อย กด stop เมื่อใช้เสร็จ

## License

MIT — ใช้งาน / fork / แก้ไขได้ตามสะดวก