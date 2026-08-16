# Reproductor JFV

Reproductor de audio para el carro (estilo Poweramp) — Euro Aluminio JFV.

## Compilar el APK automatico
Cada vez que subes cambios, GitHub compila el APK solo.
Para descargarlo:
1. Entra a la pestana **Actions** (arriba).
2. Abre la ejecucion mas reciente (la de arriba, con el check verde).
3. Baja hasta **Artifacts** y descarga **app-debug**.
4. Adentro esta `app-debug.apk` — pasalo al telefono e instalalo.

## Actualizar el reproductor
Solo se reemplaza el archivo:
`app/src/main/assets/reproductor.html`
y GitHub vuelve a compilar el APK solo.
