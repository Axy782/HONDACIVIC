---
name: skill-formato-de-imprimir
description: >
  Formato COMPLETO de impresión, exportación PDF, guardar imagen y enviar por WhatsApp de
  TODOS los documentos del Sistema de Desglose (desgloses de correderas TRADICIONAL, P-65,
  P-92, PUERTA COMERCIAL; recibos, facturas, cotizaciones, estados de cuenta, reportes,
  órdenes, etc.). Explica qué botones aparecen y cuáles se ocultan en PC vs Android, cómo se
  descarga el PDF, cómo se guarda la imagen, y la barra de herramientas. Usa este skill
  siempre que se toque: impresión de cualquier documento, exportar/descargar PDF, guardar o
  copiar imagen, enviar por WhatsApp, la función _escribirDocConToolbar, la clase .__hoja,
  .__doctb, .__cap, .__es-apk, html2canvas, jsPDF, window.print(), o cuando un documento
  salga cortado, con espacio en blanco, deforme, o con barra visible donde no debe.
---

# SKILL — FORMATO DE IMPRIMIR (todo el sistema)

## Regla de oro: TODO pasa por `_escribirDocConToolbar`

En este sistema, CUALQUIER documento que se imprime/exporta (desglose, recibo, factura,
cotización, estado de cuenta, reporte, orden…) se genera con la función central
**`_escribirDocConToolbar(win, htmlContent, opciones)`** (~línea 22929). Hay ~30 funciones que
la usan. Por eso, cualquier mejora al formato de impresión se hace UNA vez ahí y aplica a TODO
el sistema automáticamente. NO hay que tocar cada documento por separado.

Si creas un documento nuevo para imprimir, hazlo con `_escribirDocConToolbar` y hereda todo esto
gratis.

## La barra de herramientas (`.__doctb`)

Cada documento abre en una ventana nueva (`window.open`) con una barra arriba que tiene:
- **🖨️ Imprimir** — `window.print()` (en Android el APK lo intercepta)
- **📄 Descargar PDF** — `__descargarComoPDF()`
- **📋 Copiar imagen** — `__copiarComoImagen()` (solo PC)
- **💬 Enviar por WhatsApp** — `__enviarWhatsApp()` (solo si `opciones.whatsapp`)
- **✕ Cerrar** — `__cerrarVentana()`

### Qué se muestra y qué se oculta (MUY IMPORTANTE)

- **En PC:** la barra `.__doctb` se muestra completa con todos los botones.
- **En Android (APK):** la barra HTML `.__doctb` se OCULTA SIEMPRE (vertical Y horizontal),
  porque el APK tiene su propia barra NATIVA arriba (IMPRIMIR / GUARDAR PDF / GUARDAR IMG /
  CERRAR). Tener dos barras confunde.

Cómo se detecta el APK para ocultar la barra (v111s):
```javascript
// Detecta por window.AndroidApp O por user agent de WebView (/Android/ + / wv/)
function esAPK(){
  if(window.AndroidApp) return true;
  var ua = navigator.userAgent || '';
  if(/Android/.test(ua) && / wv/.test(ua)) return true;
  return false;
}
// Marca la clase __es-apk en <html> (SIEMPRE existe) y en <body> cuando esté listo.
// Con reintentos (setInterval) por si el bridge AndroidApp se inyecta tarde.
```
```css
html.__es-apk .__doctb { display: none !important; }
body.__es-apk .__doctb { display: none !important; }
```
**Lección clave:** el script de detección NO puede ir solo en `<head>` marcando `document.body`
(ahí body es null). Marcar en `document.documentElement` (<html>) que siempre existe, y reintentar.
El `@media (max-width:700px)` NO basta: en móvil horizontal el ancho pasa de 700px y la barra
reaparecía.

Los botones que son solo de PC llevan la clase `.__solo-pc`.

## Descargar PDF — el método que SÍ funciona (v111s)

**Problema histórico:** el desglose (sobre todo COMERCIAL y P-92) es una tabla MÁS ANCHA que una
hoja carta. Los métodos que fallaron:
- `html2pdf().from(elem).save()` con `format:'letter'` fijo → **corta** la tabla a la derecha.
- `windowWidth` grande en html2canvas → **deforma** y descentra.
- Depender del bundle `html2pdf.bundle.min.js` → **NO expone** `window.html2canvas` global,
  daba "PDF: la librería cargó pero html2canvas no está disponible".

**Método correcto (el que funciona):**
1. Activar la clase `.__cap` en el body (hoja crece a `fit-content`, `margin:0`, sin sombra).
2. Cargar **html2canvas Y jsPDF POR SEPARADO** desde sus CDN propios (NO el bundle):
   - `https://cdnjs.cloudflare.com/ajax/libs/html2canvas/1.4.1/html2canvas.min.js`
   - `https://cdnjs.cloudflare.com/ajax/libs/jspdf/2.5.1/jspdf.umd.min.js`
3. Capturar TODO el documento como imagen con `html2canvas(elem, {scale:2, backgroundColor:'#fff'})`
   — esto captura completo, igual que GUARDAR IMG.
4. Crear el PDF con jsPDF (`window.jspdf.jsPDF`), orientación landscape si `cw>=ch`.
5. Meter la imagen **escalada proporcionalmente** para que quepa:
   `ratio = Math.min(maxW/cw, maxH/ch)` → centrada horizontal, pegada arriba.
6. Descargar con `<a download>` del blob (NO `.save()`, que fallaba en la ventana hija).
7. Quitar `.__cap` al terminar (restaurar).

Así el PDF sale COMPLETO (nada cortado: ni fecha, ni la primera columna, ni la última
RIEL/ALFEIZAL), sin deformar, sin espacio en blanco arriba. Si es muy ancho sale más pequeño
pero completo (se puede hacer zoom).

En móvil (APK): tras generar el blob, intenta `navigator.share` (Web Share con el picker de
Android: WhatsApp/Drive/Gmail) y si no, descarga directa.

## Guardar / Copiar imagen (`__copiarComoImagen`) y WhatsApp (`__enviarWhatsApp`)

Mismo principio: activan `.__cap`, capturan el `.__hoja` con html2canvas, y:
- **Copiar imagen:** copia el JPG al portapapeles (PC) o usa Web Share (móvil).
- **WhatsApp:** genera la imagen y abre `wa.me/<tel>?text=<mensaje>` con el texto ya listo.

## La clase `.__hoja` — contenedor obligatorio

Todo el contenido del documento debe estar dentro de `<div class="__hoja">...</div>`. Es lo que
html2canvas captura. **`_escribirDocConToolbar` lo agrega AUTOMÁTICAMENTE** si el documento no lo
trae ya (detecta `hoja-doc` o `class="__hoja"` para no duplicarlo):
```javascript
const _yaTieneHoja = /hoja-doc/i.test(html) || /class=["']__hoja["']/i.test(html);
const _aperturaHoja = _yaTieneHoja ? '' : '<div class="__hoja">';
```
**Lección:** si detectas SOLO `hoja-doc` y el documento trae `__hoja`, se agrega OTRO div
anidado y se ROMPE la impresión. Detectar ambos.

Estilos de `.__hoja`:
- **En pantalla:** `margin:22px auto` (centrada, tipo hoja de Word), fondo blanco, sombra.
- **En captura (`.__cap`):** `fit-content`, `margin:0`, sin sombra → para capturar completo sin
  espacio arriba.
- **En impresión (`@media print`):** `width:100%`, sin margen.

## Reglas / errores a evitar (aprendidos a la mala)

1. **NUNCA** poner `<tag>` con símbolos `< >` dentro de comentarios CSS de un documento generado
   por `document.write` / template string. El parser HTML lo interpreta como etiqueta real,
   cierra el `<style>` y VUELCA todo el CSS como texto visible en la página. Escribir los
   comentarios sin `< >` (ej. "la clase cap", no "`<body>`").
2. **NUNCA** cargar html2canvas del bundle html2pdf esperando `window.html2canvas` — no lo
   expone. Cargarlo de su CDN propio.
3. El `.__hoja` en pantalla va CENTRADO (`margin:22px auto`); el `margin:0` va SOLO en `.__cap`.
4. Validar SIEMPRE el script más grande tras editar:
   `python3 -c "import re;c=open('sistema.html').read();s=re.findall(r'<script>(.*?)</script>',c,re.DOTALL);open('/tmp/main.js','w').write(max(s,key=len))"` → `node --check /tmp/main.js`

## En el APK (MainActivity.java)

La barra nativa (`abrirVentanaHija` ~línea 313) muestra IMPRIMIR / GUARDAR PDF / GUARDAR IMG /
CERRAR en toda ventana hija. GUARDAR IMG llama `capturarImagenWebView` (html2canvas buscando
`.hoja-doc || .__hoja || body`). GUARDAR PDF llama `generarPDF`. Ambos agregan/quitan `.__cap`.
Si tocas el bridge o la barra nativa, recompilar el APK.

## Checklist al tocar cualquier impresión

1. ¿El documento usa `_escribirDocConToolbar`? (debe)
2. ¿El contenido queda dentro de `.__hoja`? (lo agrega la función central sola)
3. ¿PDF descarga completo? Probar en COMERCIAL y P-92 (los más anchos) y en TRADICIONAL 3V.
4. ¿La barra se oculta en Android (vertical Y horizontal) y se ve en PC?
5. ¿Sin espacio en blanco arriba, sin corte a la derecha, sin deformar?
6. Validar el script más grande con node --check.
