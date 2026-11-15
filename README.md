# TransferBubble

Comparte archivos entre tus equipos por **WiFi** o **hotspot**, sin internet, sin cuentas y sin nubes.

## Que hace (version 0.1.0 - MVP)

- Aparece en el menu **Compartir** de cualquier app (WhatsApp, Galeria, Archivos...).
- **Descubre** automaticamente los equipos de la red local (no hay que escribir IPs).
- **Envia** archivos usando el protocolo **LocalSend v2.2** (compatible con equipos que tengan LocalSend instalado).
- **Recibe** archivos y los guarda en `Descargas/TransferBubble`.

## Por que el protocolo LocalSend

Es un protocolo REST publico y documentado:
https://github.com/localsend/protocol

Usarlo nos da interoperabilidad gratis: la app puede hablar con LocalSend de
Windows, Linux, macOS e iOS sin reescribir nada.

## Pruebas que respaldan el diseno

Antes de escribir esta app se probo el motor con un servidor de prueba en el
Note 9 y transferencias reales con un Galaxy A51:

| Prueba | Resultado |
|---|---|
| WiFi de casa, ambas direcciones | OK |
| Hotspot, ambas direcciones | OK |
| Video de 17 MB | integro, ~4,3 MB/s (35 Mbps) |
| Aislamiento de clientes | no existe en estos equipos |
| Servidor secuencial ante desconexion | **se colgaba** -> se uso concurrente |

Hallazgo importante: **la IP del otro equipo cambia** (DHCP), asi que la app
siempre descubre; nunca asume una IP fija.

Otro hallazgo: con la WiFi del anfitrion encendida **y** el hotspot a la vez,
el cliente tarda mas en asociarse (funcion "Wi-Fi Sharing" de Samsung alinea
canales). Para conectar rapido, apaga la WiFi del equipo que hace de hotspot.

## Compilar

El APK se compila en la nube con **GitHub Actions** (no hace falta Android SDK
en el telefono):

1. Sube el proyecto al repositorio.
2. Pestana **Actions** -> **Build APK** -> **Run workflow**.
3. Cuando termine (~4-6 min), descarga el artefacto **TransferBubble-APK**.

Localmente tambien: `./gradlew assembleRelease`

## Permisos

| Permiso | Para que |
|---|---|
| INTERNET, ACCESS_WIFI_STATE | red local |
| CHANGE_WIFI_MULTICAST_STATE | recibir el anuncio multicast |
| READ_MEDIA_IMAGES/VIDEO | explorador de archivos |

## Hoja de ruta

- [x] F1: envio/recepcion + menu Compartir
- [ ] F2: burbuja flotante (overlay) como bandeja permanente
- [ ] F3: gestor de archivos propio con **arrastrar y soltar** sobre la burbuja
- [ ] F4: favoritos, historial, PIN

## Licencia

MIT