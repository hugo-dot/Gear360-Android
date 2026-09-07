# Samsung Gear 360 SM-R210 Protocol Notes

Ces notes documentent l'etat connu pour cette iteration. Elles ne contiennent pas de code source Samsung decompile.

## Architecture

La couche Gear360 haute niveau reste:

```text
MessageSender / MessageHandler
    |
logical SAP channel 204
    |
Gear360ControlTransport
    |
LegacySamsungAccessoryTransport ou NativeGear360Transport
```

Le backend natif doit reproduire le transport Samsung Accessory necessaire, puis exposer exactement le meme evenement:

```text
onReceive(204, payloadJson)
send(204, payloadJson)
```

## UUID observes

Dans `SAccessoryService_3.1.93.90325_User.apk`, la chaine Bluetooth expose notamment:

```text
a49eb41e-cb06-495c-9f4f-bb80a90cdf00
a49eb41e-cb06-495c-9f4f-aa80a90cdf4a
63e30bad-4206-4596-839f-e47cbf7a4b5d
797ae4e9-2e58-4fe8-b48d-b5c79599fb9b
```

Le code natif utilise maintenant:

- `a49eb41e-cb06-495c-9f4f-bb80a90cdf00` comme UUID RFCOMM principal.
- `a49eb41e-cb06-495c-9f4f-aa80a90cdf4a` comme UUID RFCOMM serveur secondaire.

Le channel `204` n'est pas un port RFCOMM. C'est un channel logique SAP.

## Framing transport

Format implemente:

```text
No CRC:
uint16 payloadLength big-endian
sapPayload

With CRC:
uint16 payloadLength big-endian
uint16 crc(payloadLengthBytes) big-endian
sapPayload
uint16 crc(sapPayload) big-endian
```

Le CRC est compatible avec le comportement observe dans `SAFrameworkUtils.computeCrc`:

```text
CRC-16/IBM reflected polynomial 0xa001
initial value 0
wire order big-endian
```

## Header SAP

Payload SAP:

```text
byte 0:
  bits 7..5 = protocol version, attendu 0
  bit 4     = frame type, 0 data, 1 control
  bits 3..0 = session id bits 9..6

byte 1:
  bits 7..2 = session id bits 5..0
  bits 1..0 = fragmentation

then:
  payload SAP applicatif
```

Reserved sessions:

```text
1020 = CAPEX
1023 = service connection
```

## Profile id

Pour le profil Gear 360:

```text
/system/DI_360_2D
```

Samsung encode ce profile id dans les trames legacy et service connection comme un champ fixe de 17 octets. Il n'est pas termine par `;`.

Les profile ids dont le premier octet vaut `=` suivent l'autre format observe: chaine terminee par `;`, jusqu'a 64 octets.

## CAPEX

Legacy CAPEX:

```text
query    = message type 5
response = message type 6
```

La reponse legacy annonce maintenant un record complet:

```text
/system/DI_360_2D
friendlyName = DI_360_2DApp
agentCount = 1
aspVersion = 0x0201
role = provider
```

Le CAPEX moderne est detecte mais pas encore repondu. S'il apparait au test physique, la build signale une erreur explicite au lieu de simuler READY.

## Service profile Gear 360

Profile:

```text
name: G_360_APP
id: /system/DI_360_2D
role: provider
transport: TRANSPORT_BT
channels: 204, 222, 230
```

La requete de service connection est parsee depuis la session reservee 1023.

Une fois acceptee, la reponse conserve le profile id fixe 17 octets et chaque channel logique est mappe vers son session id negocie:

```text
channel 204 -> sessionId camera-provided
channel 222 -> sessionId camera-provided
channel 230 -> sessionId camera-provided
```

`MessageSender` n'envoie sur 204 que lorsque ce mapping existe.

## Conditions READY

`READY` n'est pas:

- Bluetooth bonded
- Wi-Fi connected
- RFCOMM open
- SAP channel 204 open seul

`READY` reste:

```text
SAP channel 204 ouvert
+ RX JSON valide
+ config-info recu de la camera
```

## Commandes Gear360 haut niveau preservees

Le protocole JSON actuel est conserve:

```text
PHOTO -> CaptureCommand.CAPTURE
VIDEO START -> CaptureCommand.RECORD
VIDEO STOP -> CaptureCommand.RECORD_STOP
```

Ces commandes passent par:

```text
sender.send(204, json.encodeToByteArray())
```
