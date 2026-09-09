# Samsung Accessory Framing

Source d'analyse: APK Samsung Gear 360 1.5.00.1 et APK embarque `SAccessoryService_3.1.93.90325_User.apk`.

Les classes importantes observees:

```text
com.samsung.accessory.connectivity.bt.SABtRfConnection
com.samsung.accessory.connectivity.bt.SABtServerListener
com.samsung.accessory.utils.SAProtocolFrameUtils
com.samsung.accessory.utils.SACapexFrameUtils
com.samsung.accessory.utils.SAServiceFrameUtils
com.samsung.accessory.protocol.SAProtocolHeaderConstants
com.samsung.accessory.protocol.SAProtocolMessageConstants
```

## Transport RFCOMM

`SABtRfConnection.connect()` reference directement:

```text
a49eb41e-cb06-495c-9f4f-bb80a90cdf00
```

`SABtServerListener.listenForIncomingConnections()` reference notamment:

```text
a49eb41e-cb06-495c-9f4f-bb80a90cdf4a
a49eb41e-cb06-495c-9f4f-aa80a90cdf4a
```

La build actuelle ouvre donc:

- un client RFCOMM sortant vers l'UUID choisi par SDP ou fallback Gear360;
- un serveur RFCOMM entrant sur les deux UUIDs Accessory observes.

## Longueur et CRC

Le framing de transport utilise un prefixe longueur 16 bits big-endian.

Quand le CRC est actif:

- 2 octets CRC protegent les 2 octets de longueur;
- 2 octets CRC protegent le payload SAP;
- CRC-16/IBM, init 0, polynome reflechi `0xa001`;
- l'ordre wire du CRC est big-endian.

La build actuelle detecte le mode CRC par validation du CRC de longueur.

## Payload SAP

Le payload SAP commence par un header 2 octets.

Session id:

```text
sessionId = ((byte0 & 0x0f) << 6) | ((byte1 & 0xfc) >> 2)
```

Frame type:

```text
0 = data
1 = control
```

Fragmentation:

```text
byte1 & 0x03
```

La reliability/sequence number n'est pas encore activee dans cette implementation, car les channels Gear360 connus semblent utiliser le payload JSON simple sur 204. Toute trace indiquant un sequence number devra etre ajoutee avant validation complete.

## Service connection

La session reservee:

```text
1023
```

sert aux messages service connection.

La build parse:

- creation request;
- creation response;
- profile id variable termine par `;` pour `/system/DI_360_2D`, ou profile id fixe 17 octets lorsqu'il commence par `=`;
- liste des session ids;
- liste des channel ids;
- QoS triplets;
- payload types.

Elle accepte uniquement:

```text
/system/DI_360_2D
```

Puis elle ouvre les channels annonces par la camera.

## CAPEX legacy

`SACapexFrameUtils.composeCapabilityDiscoveryLegacyResponseMessage()` construit une reponse plus riche que `messageType + profileId`.

Structure implementee:

```text
uint8  messageType = 6
uint32 recordCount
uint16 uuid
utf8   friendlyName, termine par ';'
uint16 agentCount
uint16 componentId
bytes  profileId UTF-8 termine par ';'
uint16 aspVersion
uint8  role << 6
```

## CAPEX Sync

La requete initiale utilise la session reservee `1020` pour les accessoires anterieurs a ASP 3.3:

```text
uint8 messageType = 1
uint8 queryType = 3
uint8 profileCount
bytes profileIds termines par ';'
```

Cette variante Sync ne contient pas le checksum 32 bits utilise par d'autres types de requete CAPEX.

## Limites connues

- Reponse CAPEX moderne non implementee; la requete Sync initiale est implementee.
- ACK/ABORT/control frame observes mais non interpretes completement.
- Sequence number non implemente.
- Fragmentation applicative non reassemblee au-dela du transport length-prefix.
- Validation physique SM-R210 necessaire pour confirmer le sens RFCOMM utilise et la reponse CAPEX exacte.
