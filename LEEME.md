# Sistema Desglose — APK Android

APK del **Sistema de Desglose y Facturacion** con detector de actualizaciones
por WhatsApp.

---

## Como funciona

- **El APK** = el cascaron (se instala una vez)
- **El HTML** = el sistema real, actualizable por WhatsApp

Los datos NO se pierden entre actualizaciones porque el APK levanta un
servidor local en `http://127.0.0.1:47821` y el localStorage se mantiene.

---

## Compilar en GitHub (gratis)

1. Sube TODO este contenido al repo (crea uno nuevo llamado `SistemaDesglose`)
2. Pestana **Actions** → compila solo (~5 min)
3. Pestana **Releases** → descargas el APK

---

## Contenido incluido

- Sistema de Desglose y Facturacion **v032 MOVIL**
- Nombre de la app: **Sistema Desglose**
- Identificador interno: `com.sistemadesglose.app`
- Icono nuevo (laptop + engranajes)
- Llave de firma `desglose-key.jks`

---

## Actualizar en el futuro

Cuando salga v033, v034, etc:
- Recibes el HTML por WhatsApp
- Lo abres con "Abrir con → Sistema Desglose"
- La app detecta la version nueva y ofrece actualizar
- Sin recompilar el APK, sin perder datos

---

## Firma

- Archivo: `desglose-key.jks`
- Alias: `jfv` (nombre interno de la llave, no importa)
- Contrasenas: `eurojfv2026`

**No borrar.** Es lo que permite actualizar el APK sin desinstalar.
