# Reparto Pisos

App Android para repartidores: escribes los nombres de los paquetes, haces una foto de los
timbres y la app te dice **en qué piso vive cada destinatario**.

## Cómo se usa

1. Escribe un nombre por línea. Un número delante (`2 Juan Pérez`) es la cantidad de paquetes y se ignora al buscar.
2. Haz una foto del timbre (o elige varias de la galería).
3. Pulsa **BUSCAR PISOS**. Sale una lista agrupada por piso, con los nombres tal como los escribiste.

Si hay varias personas con el mismo apellido, se usa la inicial/nombre que aparece en el timbre
para elegir la correcta. Si no se puede decidir, el nombre sale en **REVISAR** con todos los pisos posibles.
Todo lo dudoso lleva un aviso ⚠.

## Guardar edificios

Después de leer un timbre aparece **Guardar este edificio**: le pones un identificador (por ejemplo `Hauptstr. 12`)
y se guarda en el móvil con todos los nombres y pisos. La próxima vez eliges el edificio en la lista y buscas
directamente: **no hace falta foto, ni internet, ni clave de API**. «Releer con foto» actualiza un edificio guardado
y «Borrar edificio» lo elimina.

## Dos formas de leer el timbre

- **Con IA (recomendado):** en *Ajustes* pega tu clave de API de Anthropic. La foto y los nombres se envían
  a la API y Claude lee el timbre entendiendo su disposición (rótulos EG/1. OG, filas, separadores).
- **Sin IA:** lectura de texto en el propio móvil (ML Kit). Funciona sin internet, pero depende de que la foto
  tenga rótulos de piso legibles; si no los hay, cuenta las filas y lo avisa («piso estimado»).
  Si la IA falla (sin internet, sin saldo…), la app cae automáticamente a este modo.

La clave se guarda solo en el móvil (no se incluye en el repositorio ni en copias de seguridad).

## Instalar

Cada cambio en `main` compila el APK con GitHub Actions y lo publica en
**Releases → «Última versión» → `RepartoPisos.apk`**. Descárgalo desde el móvil e instálalo
(Android pedirá permitir «instalar apps de esta fuente»). Las actualizaciones se instalan encima
sin perder datos porque el APK va firmado siempre con la misma clave (`app/debug.keystore`,
clave de depuración pública, solo para uso personal).

## Desarrollo

```
./gradlew testDebugUnitTest assembleDebug
```

- `Matcher.kt`: cantidades, normalización (ü/ue/u, ß/ss, acentos), tolerancia a errores de OCR y desempate por inicial.
- `PanelParser.kt`: de las líneas del OCR a «nombre + piso» (rótulos, cabeceras, columna de números, filas).
- `AiClient.kt`: modo IA. `Ocr.kt`: ML Kit. `MainActivity.kt`: interfaz (Compose).
