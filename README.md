# GitWatch

App Android (Kotlin + Jetpack Compose) que vigila las releases de GitHub de tus apps y de tus módulos root, te avisa cuando hay versión nueva y las instala. Inspirada en Obtainium, con extras pensados para ROMs con root.

## Qué hace

**Base (como Obtainium)**
- Vigila `owner/repo` de GitHub y compara la última release con la versión instalada.
- Comprobación periódica en segundo plano (WorkManager) con notificación.
- Elige el APK correcto según la ABI del dispositivo, con filtro regex opcional por asset y por tag.
- Pre-releases opcionales por elemento.
- Compartir una URL de GitHub desde el navegador abre GitWatch con el repo rellenado.

**Mejoras**
- **Detecta tus apps instaladas** y propone su repo de GitHub (heurística por paquete, nombre y, con token, búsqueda en código).
- **Módulos root** (KernelSU / ReSukiSU / Magisk / APatch): lee `/data/adb/modules/*/module.prop`, deduce el repo desde `updateJson` y los actualiza con `ksud` / `apd` / `magisk --install-module`.
- **Instalación silenciosa con root** (`pm install`) y, si no hay root, instalación estándar con `PackageInstaller`.
- **Auto-actualización opcional de apps** (solo con root, desactivada por defecto). Los módulos nunca se instalan solos.
- **Verificación de integridad**: SHA-256 contra el digest que publica GitHub, comprobación de que el APK/ZIP es realmente del paquete/módulo esperado y descarga solo desde `github.com`.
- **Menos consumo de API**: peticiones condicionales con ETag (un 304 no cuenta para el límite).
- Ignorar una versión concreta, ver notas de la release, exportar/importar la lista (acepta también exportaciones de Obtainium).

## Compilar

Necesitas JDK 17 y Android SDK (compileSdk 35).

```
gradle wrapper
./gradlew :app:assembleRelease
```

Sin keystore propio, `release` se firma con la clave de debug. Para firmar de verdad define `KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` y `KEY_PASSWORD`.

**En GitHub Actions** (`.github/workflows/build.yml`): cada push a `main` genera la APK como artefacto, y cada tag `v*` la publica en Releases. Para firmar con tu clave crea los secretos `KEYSTORE_B64` (el `.jks` en base64), `KEYSTORE_PASS`, `KEY_ALIAS` y `KEY_PASS` (los mismos nombres que usa OpGram). Mantén siempre la misma clave: si cambia, no podrás actualizar encima.

## Notas y límites

- Solo repos públicos de GitHub. El token es opcional y no necesita permisos.
- El token se guarda en las preferencias privadas de la app (sin cifrado adicional).
- La autodetección del repo es orientativa; confírmala antes de aceptar.
- Android solo deja actualizar un APK firmado con la misma clave que el instalado.
- `QUERY_ALL_PACKAGES` está pensado para uso fuera de Google Play.
- Las versiones de dependencias son las estables conocidas; súbelas si tu entorno lo pide.

## Estructura

```
app/src/main/java/com/kherio/gitwatch/
  Repository.kt          comprobación, alta, autodetección, instalación, export/import
  data/                  Room, cliente GitHub, ajustes
  domain/                versiones, root, instalador, apps instaladas
  work/UpdateWorker.kt   comprobación periódica + notificaciones
  ui/                    pantallas Compose (inicio, detectar, ajustes)
```
