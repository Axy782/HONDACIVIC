---
name: fracciones-sugeridas
description: >
  Cómo funciona el sistema de FRACCIONES SUGERIDAS (chips que aparecen debajo de un
  input de medida cuando el usuario escribe "N M", ej "56 1" → 1/2 1/4 1/8 1/16) en el
  Sistema Desglose y Facturación de Euro Aluminio. Usa este skill siempre que agregues un
  input NUEVO de medida en pulgadas (ancho, alto, largo de hueco/cristal) en cualquier
  módulo (Correderas, Puerta Comercial, Optimizador de Vidrio, Baño, Abisagrado, Plafón,
  o cualquier módulo futuro) y quieras que muestre las fracciones sugeridas igual que en
  el resto del sistema. Cubre la función central _sugFracciones, cómo cablear un input,
  el div de sugerencias, la navegación al aplicar, y las reglas de layout (fila horizontal
  + scroll automático) implementadas en v109. Consultar siempre que se toque _sugFracciones,
  _aplicarFraccion, _ocultarSugFrac, la clase CSS .sug-fracciones, o cualquier input de medida.
---

# SKILL — FRACCIONES SUGERIDAS

## Qué hace

Cuando el usuario escribe una medida en pulgadas y quiere agregar una fracción, en vez de
teclear "56 1/2" a mano, escribe `56` + espacio + el numerador (`1`), y aparecen chips
tappables con las fracciones que empiezan con ese numerador:

```
56 1  →  [1/2] [1/4] [1/8] [1/16]
56 3  →  [3/4] [3/8] [3/16]
56 5  →  [5/8] [5/16]
```

Un tap completa la medida (`56 1/2`) y navega automáticamente al siguiente campo.

Esto es **solo para móvil** (en PC se ocultan vía CSS `@media (min-width: 641px)`), porque
en PC es más rápido teclear la fracción directa.

---

## Las 3 funciones centrales (NO duplicar — ya existen)

Todas están cerca de la línea ~71545 en `sistema.html`. Son globales y las comparten TODOS
los módulos. Para aplicar fracciones a un input nuevo, **NO** se crea código nuevo: solo se
cablea el input a estas funciones existentes.

### 1. `_sugFracciones(inputId)`
Se llama en el `oninput` y `onfocus` del input. Lee el valor, y si coincide con el patrón
`N M` (entero + espacio + numerador), rellena el div `sug-<inputId>` con los chips.

- Patrón: `/^(\d+(?:\.\d+)?)\s+(\d+)$/`
- Fuente de fracciones: `const _TODAS_FRACCIONES = ['1/2','1/4','3/4','1/8',...]`
- **v109**: fuerza `flexWrap:wrap` + `flexDirection:row` (fila horizontal) y hace
  `sugDiv.scrollIntoView({behavior:'smooth', block:'center'})` tras 30ms para que el
  recuadro SIEMPRE quede visible sin girar/subir la pantalla.

### 2. `_aplicarFraccion(inputId, wholePart, frac)`
Se llama al tocar un chip. Pone `wholePart + ' ' + frac` en el input, actualiza el `state`
correspondiente al `inputId`, oculta las sugerencias y **navega** al siguiente campo.

⚠️ IMPORTANTE: esta función tiene un bloque `if/else if` que mapea cada `inputId` a su
propiedad de `state`, y otro que define la navegación (a qué campo saltar o qué función
llamar). **Si agregas un input nuevo, tienes que añadir su caso en AMBOS bloques.**

### 3. `_ocultarSugFrac(inputId)`
Oculta el div. Se llama en el `onblur` del input (con un `setTimeout` de ~150-200ms para
dar tiempo al click del chip antes de ocultar).

---

## Cómo cablear un input NUEVO (checklist)

Para que un input de medida muestre fracciones sugeridas:

### Paso 1 — El input
Agrega las 3 llamadas en los eventos:
```html
<input id="MI-INPUT-ID" type="text" value="${...}"
  oninput="...tu_state_aqui...;_sugFracciones('MI-INPUT-ID')"
  onfocus="this.select();_sugFracciones('MI-INPUT-ID')"
  onblur="setTimeout(()=>_ocultarSugFrac('MI-INPUT-ID'),200)"
  ...>
```
- Debe ser `type="text"` (NO `type="number"` — number no acepta el espacio ni la fracción).
- Las medidas deben estar en **pulgadas** (las fracciones son en 16avos de pulgada). NO
  aplicar a campos en cm (ej. el pedido de puertas usa "Ancho (cm)" type=number → NO lleva).

### Paso 2 — El div de sugerencias
Justo DESPUÉS del input, agrega un div con id `sug-<mismo-id>` y clase `sug-fracciones`:
```html
<div id="sug-MI-INPUT-ID" class="sug-fracciones" style="display:none"></div>
```
- Si el input está en una celda estrecha (ej. fila de tabla editable), el div puede ser
  flotante con `position:absolute`. El CSS `.sug-fracciones[style*="absolute"]` ya le da
  `min-width:200px; width:max-content` para que las fracciones quepan en fila.

### Paso 3 — El state en `_aplicarFraccion`
Añade el caso de tu input en el `if/else if` que actualiza el state (~línea 71607):
```javascript
else if(inputId === 'MI-INPUT-ID'){ state.miModulo.miCampo = nuevoValor; }
```

### Paso 4 — La navegación en `_aplicarFraccion`
Añade el caso de navegación al final de la función (~línea 71640): a qué campo saltar
después de aplicar la fracción (o qué función llamar, ej. agregar la fila):
```javascript
if(inputId === 'MI-INPUT-ID'){
  const sig = document.getElementById('MI-SIGUIENTE-CAMPO');
  if(sig){ sig.focus(); sig.select(); }
  return;
}
```

---

## Inputs YA cableados (a jul 2026, v109)

| Módulo | Input(s) |
|--------|----------|
| Correderas / Puerta Comercial (agregar) | `inp-new-ancho`, `inp-new-alto` |
| Correderas / Puerta Comercial (editar) | `${idAncho}`, `${idAlto}` (editAncho_/editAlto_) |
| Optimizador de Vidrio (nuevo pedido) | `vidAncho`, `vidAlto` |
| Optimizador de Vidrio (fila editable) | `vrow-ancho-${_rid}`, `vrow-alto-${_rid}` |
| Baño (slider) | `banio-ancho-input` |
| Baño Abisagrado | `abis-ancho-input` |
| Plafón (espacios) | `espAncho`, `espLargo` |

---

## CSS: `.sug-fracciones`

Está cerca de la línea ~471 de `sistema.html`:
```css
.sug-fracciones{ display:none; flex-wrap:wrap; flex-direction:row; gap:5px; margin-top:6px; align-items:center; }
.sug-fracciones[style*="absolute"]{ min-width:200px; width:max-content; max-width:90vw; right:auto !important; }
.sug-fracciones button{ ... botones azules grandes ... }
@media (min-width: 641px){ .sug-fracciones{ display:none !important; } }  /* solo móvil */
```

---

## Reglas aprendidas / errores a evitar

1. **NO poner fracciones en campos de cm ni en `type="number"`.** Solo pulgadas, `type="text"`.
2. **Siempre añadir el caso en los DOS bloques de `_aplicarFraccion`** (state + navegación).
   Si falta el de state, la fracción se ve pero no se guarda; si falta el de navegación, no
   salta al siguiente campo.
3. **Layout horizontal**: desde v109 las fracciones van en fila (`flex-direction:row` +
   `flex-wrap:wrap`), NO en columna. Si un div se ve vertical, es porque su ancho está
   limitado — usar el selector `[style*="absolute"]` o quitar el `right:0`.
4. **Scroll automático**: v109 hace `scrollIntoView({block:'center'})` para que se vea sin
   girar la pantalla. Va dentro de un `setTimeout(...,30)` para esperar a que el div se pinte.
5. **Cuidado al editar `_sugFracciones` con str_replace**: es fácil borrar sin querer la
   llave `}` de cierre de la función. SIEMPRE validar el archivo completo después:
   extraer TODOS los bloques `<script>` (hay ~5) y correr `node --check` sobre el más grande,
   no solo el primero.

---

## Validación obligatoria tras tocar cualquier cosa de este skill

```bash
cd /home/claude && python3 -c "
import re
c=open('sistema.html').read()
scripts=re.findall(r'<script>(.*?)</script>', c, re.DOTALL)
main=max(scripts, key=len)
open('/tmp/main.js','w').write(main)
print('Script principal:', len(main), 'bytes')
"
node --check /tmp/main.js
```
Si `node --check` no imprime error, la sintaxis está bien.
