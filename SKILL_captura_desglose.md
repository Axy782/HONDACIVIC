---
name: skill-captura-desglose
description: >
  Cómo se genera correctamente la IMAGEN (GUARDAR IMG) y el PDF (GUARDAR PDF) de un DESGLOSE
  (correderas TRADICIONAL, P-65, P-92 y PUERTA COMERCIAL) para que NO salga con espacio en
  blanco arriba ni cortado a la derecha, tanto en el APK Android como en PC (Chrome).
  Usa este skill cuando el usuario reporte que la imagen o el PDF de un desglose sale:
  con espacio en blanco arriba, cortado/incompleto a la derecha, deforme, o con la tabla
  de medidas recortada. También al tocar printDesglose, .__hoja, .__cap, html2canvas o
  html2pdf dentro del flujo de impresión de desgloses.
---

# SKILL — CAPTURA CORRECTA DE DESGLOSE (IMG / PDF)

## El problema que resuelve

El desglose de correderas tiene una **tabla de medidas muy ancha** (columnas: CUADRO DEL MARCO,
CUADRO DE LA HOJA, JAMBAS, CORTE CRISTALES, VIDRIO). Al generar imagen o PDF salían dos defectos:

1. **Espacio en blanco arriba** — la hoja tenía `margin` superior (heredado de `.__hoja{margin:22px auto}`
   de `_escribirDocConToolbar`), y ese margen se capturaba como espacio vacío.
2. **Cortado a la derecha** — en móvil el `.__hoja` tenía `width:100%` + `overflow-x:auto` y la
   tabla con `min-width:900px`. html2canvas capturaba solo el ancho visible del `.__hoja`
   (ej. 380px), dejando la tabla (900px+) cortada a la derecha.

PUERTA COMERCIAL además dejaba espacio en blanco lateral por tener un `min-width:1180px` fijo
(pensado para TRADICIONAL, que tiene más columnas).

## La solución: la clase `.__cap` (modo captura)

El desglose (`printDesglose`, ~línea 81079) genera su HTML con el contenido envuelto en
`<div class="__hoja">...</div>`. Los estilos definen una clase especial `.__cap` que se aplica
al `<body>` SOLO durante la captura, y luego se quita:

```css
/* MODO CAPTURA (PDF / IMG en Android y PC) */
body.__cap{padding:0 !important;margin:0 !important;background:#fff !important}
body.__cap .__hoja{
  width:max-content !important;   /* la hoja crece hasta contener la tabla COMPLETA */
  min-width:0 !important;
  max-width:none !important;
  margin:0 !important;            /* sin espacio en blanco arriba */
  padding:16px 18px !important;
  box-shadow:none !important;
  border-radius:0 !important;
  overflow:visible !important;    /* la tabla no se recorta */
}
body.__cap .tabla-wrap{overflow:visible !important}
body.__cap .tabla-medidas, body.__cap table{width:auto !important;min-width:0 !important}
```

**Clave:** `width:max-content` en el `.__hoja` hace que la hoja se expanda hasta abarcar la tabla
completa (por ancha que sea), y `overflow:visible` evita que se recorte. `margin:0` elimina el
espacio superior.

## Dónde se activa `.__cap`

En las 3 funciones de captura dentro de `_escribirDocConToolbar` (~línea 22915), cada una hace
`document.body.classList.add('__cap')` antes de capturar y `.remove('__cap')` al terminar
(dentro de su función `restaurar`):

| Función | Qué genera | Línea aprox |
|---------|-----------|-------------|
| `__descargarComoPDF(btn)` | PDF con html2pdf | ~23451 |
| `__copiarComoImagen(btn)` | Imagen JPG al portapapeles / Web Share | ~23257 |
| `__enviarWhatsApp(btn)` | Imagen para WhatsApp | ~23342 |

Patrón en cada una:
```javascript
var restaurar = function(){
  document.body.classList.remove('__cap');  // ← quitar al terminar
  if(tb) tb.style.display = '';
  if(btn){ btn.innerHTML=txtOrig; btn.disabled=false; }
};
var ejecutar = function(){
  if(tb) tb.style.display = 'none';
  document.body.classList.add('__cap');      // ← agregar antes de capturar
  var objetivo = document.querySelector('.hoja-doc') || document.querySelector('.__hoja') || document.body;
  // ... html2canvas / html2pdf sobre 'objetivo'
};
```

## En el APK Android (barra nativa GUARDAR IMG / GUARDAR PDF)

El APK tiene su propia barra nativa (MainActivity.java, `abrirVentanaHija` ~línea 313) con botones
IMPRIMIR / GUARDAR PDF / GUARDAR IMG / CERRAR. El botón GUARDAR IMG llama a `capturarImagenWebView`
(~línea 423) que inyecta JS con html2canvas buscando `.hoja-doc || .__hoja || body`.

**IMPORTANTE:** para que el APK también use el modo captura, el JS que inyecta el Java debería
agregar `document.body.classList.add('__cap')` antes de llamar html2canvas. Si se toca el Java,
agregar esa línea en `capturarImagenWebView` y en `guardarComoPDF` antes de la captura, y
quitarla después. Si NO se recompila el APK, el `.__hoja` del desglose ya trae reglas base que
mitigan el problema, pero la clase `.__cap` es la solución completa.

## Requisito imprescindible: el `<div class="__hoja">`

El documento del desglose DEBE envolver TODO su contenido en `<div class="__hoja">...</div>`
(justo después de `<body>` y cerrado antes de `</body>`). Sin ese div, ni el APK ni html2canvas
encuentran qué capturar y la imagen sale del `body` completo (con toda la basura). Esto se agregó
en printDesglose: `<body class="${bodyClass}"><div class="__hoja">` ... `</div><\/script></body>`.

## Checklist al tocar la captura de desgloses

1. ¿El HTML del desglose envuelve el contenido en `<div class="__hoja">`? (obligatorio)
2. ¿Existe el bloque CSS `body.__cap { ... }` con `width:max-content` y `margin:0`?
3. ¿Las 3 funciones (`__descargarComoPDF`, `__copiarComoImagen`, `__enviarWhatsApp`) hacen
   `add('__cap')` antes y `remove('__cap')` en `restaurar`?
4. Validar el script más grande: `python3 max(scripts,key=len) → node --check`
5. Probar en COMERCIAL (pocas columnas) y TRADICIONAL 3V (muchas columnas): ambos deben salir
   completos, sin espacio arriba ni corte a la derecha.

## Aplica a TODOS los desgloses

`printDesglose` es genérico — sirve para TRADICIONAL, P-65, P-92 y COMERCIAL. La clase `.__cap`
y el `.__hoja` funcionan igual para todos porque comparten la misma función de impresión.
