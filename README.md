# Platform5 Terminal — настройка доверенного хранилища (КриптоПро JCP)

Ниже описана быстрая настройка хранилища сертификатов для КриптоПро JCP.

## Шаги
1. Установить криптопро JCP из каталога /JCPInstall и запустить ControlPane.exe

[//]: # (2. В КриптоПро JCP откройте CertStore и создайте &#40;или выберите&#41; хранилище. Укажите каталог и имя файла с расширением `.store`. )

[//]: # (3. Добавьте в хранилище следующие файлы &#40;при вопросах подтверждайте «Заменить»&#41;:)

[//]: # (   - `2035gost.reg.event.crt`)

[//]: # (   - `certnew.p7b`)

[//]: # (   - `Тестовый УЦ.. .crt`)
2. Доверенная цепочка берётся из PKCS#7 файла `gost.p7b`. Источник можно выбрать в `application.conf`:

```conf
platform5.server.remote.ssl {
  // ...
  gostP7b {
    fromResource = true
    filePath = ""                    // если fromResource = false, укажите путь на диске
  }
}
```

## Связанный материал
- Фото-инструкция: [photo_2025-08-22_11-48-19.jpg](./photo_2025-08-22_11-48-19.jpg)

[//]: # (Возможный вариант создания хранилища из консоли:)

[//]: # (```keytool -import -v -trustcacerts -alias Root -file D:\gostjcp.cer -keystore d:\trust\new.store -storepass 11111111 -sigalg GOST3411withGOST3410EL```)
