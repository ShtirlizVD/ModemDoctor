# 📱 Modem Doctor

Root приложение для диагностики и исправления проблем с модемом Samsung Exynos на Google Pixel 6 / 6A.

## ⚠️ Проблема

Pixel 6A (и некоторые Pixel 6) с модемом Samsung Exynos S5123/S5300 имеют дефект HSI2C I2C шины на адресе 0x43. Это приводит к крашам модема при попытке доступа к 3G радиостеку.

**Симптомы:**
- Потеря сети в метро, лифтах, зданиях
- Модем крашится при переключении на 3G
- Телефон не может совершать/принимать звонки
- Требуется перезагрузка для восстановления

## ✅ Решение

**ULTRA DISABLE 3G** - полностью запрещает WCDMA/3G на уровне radio properties, заставляя модем работать только в LTE режиме.

### Методы отключения 3G:

1. **CS Fallback отключен** - звонки не упадут в 3G
2. **WCDMA capability отключена** - стек считает, что 3G нет
3. **WCDMA scanning отключен** - модем не ищет 3G сети
4. **LTE only форсирован** - через `service call phone 73`
5. **IMS/VoLTE зафиксирован** - все звонки через VoLTE
6. **wcdma_supported = 0** - САМЫЙ ЖЁСТКИЙ метод!

## 🚀 Функции

- **Network Monitoring** - мониторинг состояния сети в фоне
- **Auto Log Upload** - автоматическая загрузка логов на GitHub Gist при потере сети
- **Disable 5G** - отключение 5G для стабильности
- **ULTRA Disable 3G** - полное отключение WCDMA для проблемных модемов
- **Enable VoLTE** - принудительное включение VoLTE
- **Log Collection** - сбор диагностических логов

## 📦 Сборка

```bash
# Клонировать репозиторий
git clone <repo-url>
cd ModemDoctor

# Собрать APK
./gradlew assembleDebug

# APK будет в app/build/outputs/apk/debug/
```

## 🔧 Требования

- Google Pixel 6 / 6A с root доступом (Magisk)
- Android 12+

## 📋 Использование

1. Установите APK
2. Предоставьте root доступ
3. Настройте GitHub токен (опционально) для авто-загрузки логов
4. Нажмите **ULTRA DISABLE** для отключения 3G
5. **Перезагрузите телефон** для применения изменений

## 🔑 GitHub Token

Настройте GitHub токен (с gist scope) через UI приложения.

Логи автоматически загружаются в приватные Gists.

## 📱 Скриншоты

Приложение содержит:
- Статус root доступа
- Мониторинг сети
- Сбор логов
- Быстрые действия для проблем
- Настройки 5G/3G/VoLTE

## ⚡ Важно

- После нажатия ULTRA DISABLE **требуется перезагрузка**
- Если运营商 требует 3G для звонков, используйте VoLTE
- Все изменения сохраняются в persist properties

## 🛠️ Технические детали

Команды для отключения 3G:

```bash
# CS Fallback
resetprop -p persist.vendor.radio.disable_csfb 1

# WCDMA disabled
resetprop -p persist.vendor.radio.wcdma_disabled 1

# WCDMA not supported (САМЫЙ ЖЁСТКИЙ)
resetprop -p persist.vendor.radio.wcdma_supported 0

# LTE only
service call phone 73 i32 0 i32 11
```

## 📄 Лицензия

MIT

---

Создано для владельцев Pixel 6/6A с проблемным модемом Exynos.
