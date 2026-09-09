# Gear360 Android Diagnostic

Date: 2026-09-09

## Cause du blocage observe

La version precedente pouvait appairer le telephone et parfois connecter le Wi-Fi de la Gear 360, mais elle ne creait pas une vraie session de controle camera.

Le blocage etait sous le protocole JSON Gear360:

- `SapFrameDecoder.append()` ne decodait aucune frame utile et retournait `UnknownFraming`.
- `SapFrameEncoder.encode()` levait `UnsupportedOperationException`.
- `SapHandshake` indiquait que le handshake etait inconnu.
- `NativeGear360Transport.send()` retournait toujours `false`.

Donc PHOTO / RECORD / RECORD_STOP ne pouvaient pas physiquement sortir sur le canal logique Samsung Accessory 204.

## Corrections appliquees

- Ajout d'un parseur incremental SAP longueur/payload, avec support CRC optionnel.
- Ajout d'un encodeur SAP qui produit des frames longueur + payload SAP.
- Ajout d'un CRC Samsung observe: CRC-16/IBM, init 0, octets wire en big-endian.
- Ajout d'une session native `NativeSapSession`.
- Ajout d'un multiplexeur channel logique -> session SAP, necessaire pour router le canal 204.
- Ajout d'une reponse CAPEX legacy minimale pour annoncer `/system/DI_360_2D`.
- Correction de la reponse CAPEX legacy: elle suit maintenant la structure observee dans `SACapexFrameUtils` (`messageType`, compteur 32 bits, uuid, friendly name, agent count, component id, profile id, ASP version, role).
- Correction critique du codage `profileId`: `/system/DI_360_2D` est une chaine Samsung terminee par `;`; seuls les identifiants commencant par `=` utilisent le champ fixe de 17 octets.
- Correction de la requete CAPEX Sync: retrait du checksum absent de ce message et ajout du terminateur `;` du profile.
- Ajout de l'acceptation d'une requete de service connection Gear360 et ouverture logique des channels negocies.
- Correction de la reponse service connection: le `profileId` accepte reprend le format variable termine par `;` attendu pour `/system/DI_360_2D`.
- Ajout de l'ecriture RFCOMM thread-safe.
- Ajout d'un listener RFCOMM serveur en plus du client sortant, car Samsung Accessory expose les deux chemins.
- Correction du scanner: l'ecran de detection lance maintenant une decouverte Bluetooth Classic (`BluetoothAdapter.startDiscovery()`) en plus du scan BLE. La SM-R210/SAP ne doit pas dependre d'un scan BLE uniquement.
- Stabilisation du cycle de vie du scanner: la decouverte Classic n'est plus annulee immediatement par `onPause()` pendant la transition `StartActivity -> ScanActivity`.
- Ajout d'une relance automatique de la decouverte Classic tant que l'ecran de scan est actif.
- Changement du mode `AUTO`: si le framework externe `com.samsung.accessory` est installe, l'application choisit `SAMSUNG LEGACY`; sinon elle choisit le backend natif experimental.
- Alignement du backend Legacy sur l'APK Gear 360 1.5.00.1: `SamAccessoryManager.connect(address, TRANSPORT_BT, ASSISTMODE_DEFAULT)` est appele directement, sans pre-scan SDP impose par notre application.
- Alignement de la connexion de service SAP sur l'APK officiel: apres `PEER_AGENT_FOUND`, le Provider appelle `requestServiceConnection(peer)`; le chemin entrant `onServiceConnectionRequested()` reste accepte en parallele.
- Ajout d'un watchdog borne sur la connexion de service SAP afin qu'une reponse `CONNECTION_DUPLICATE_REQUEST` ou une absence de callback ne laisse plus l'application bloquee indefiniment.
- Separation entre fermeture du `SASocket` et liberation de l'agent Samsung: une deconnexion utilisateur n'empeche plus une reconnexion dans la meme execution du service.
- Conservation du protocole JSON existant `BTMessage`, `MessageSender`, `MessageHandler`.
- Conservation du verrou UI: PHOTO/VIDEO restent bloques tant que `config-info` n'est pas recu.
- Projet Gradle remis a plat: module Android dans `app/`, module Gradle `:app`, nom du projet `Gear360-Android`.
- Correction des gardes API pour Android 5-10 (`BluetoothDevice.alias`, `WifiNetworkSpecifier`).
- Les JAR Samsung requis par le backend legacy sont inclus dans le depot afin qu'un clone neuf puisse compiler.

## Etat actuel

Build: OK (`testDebugUnitTest assembleDebug`)

Tests unitaires: OK, dont la sequence PD -> CAPEX Sync et les codecs SAP.

Android lint: OK (0 erreur bloquante)

APK debug: `app/build/outputs/apk/debug/app-debug.apk`

La capture physique de reference du 2026-09-03 utilisait `SM_G950F` / `dreamlte` / `/e/OS`.

Le telephone de test actuel est un Galaxy A05 `SM-A055F`, Android 15/API 35. Lors de la derniere session ADB valide, `com.samsung.accessory` version `3.1.93.90325` etait installe avec les permissions framework/Bluetooth requises. Le telephone n'etait plus visible dans `adb devices` au moment de la build du 2026-09-09; la nouvelle APK n'a donc pas encore ete reinstallee ni validee physiquement.

Installation APK debug: OK.

Samsung Accessory Service sur le S8: present, version `3.1.96.61111 / 319661111`.

Permissions corrigees pour le test ADB:

- `io.github.teccheck.gear360app`: `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, `BLUETOOTH_ADVERTISE`, `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `NEARBY_WIFI_DEVICES`.
- `com.samsung.accessory`: `BLUETOOTH_CONNECT`, `BLUETOOTH_ADVERTISE`.

Logcat valide:

```text
G360-BT: BLE scan started
G360-BT: Classic discovery start requested result=true
G360-BT: Classic Bluetooth discovery started
G360-CONNECTION: Samsung framework status=PRESENT FRAMEWORK_BT_CONNECT=GRANTED version=3.1.96.61111 / 319661111
G360-CONNECTION: Control backend selected: SAMSUNG LEGACY
LEGACY-SAM: framework package present YES ... permission=APP_ACCESSORY=GRANTED FRAMEWORK_BT_CONNECT=GRANTED
LEGACY-SAM: bind manager SUCCESS
LEGACY-SAP: BTMProviderService created profile=name=G_360_APP id=/system/DI_360_2D channels=[204,222,230]
LEGACY-SAP: SA agent available
G360-BT: Classic Bluetooth discovery finished
```

La Gear 360 n'etait pas visible pendant cette capture: `BluetoothAdapter.bondedDevices` ne contenait pas `Gear 360 (7F29)` et la decouverte Classic n'a pas remonte de device Gear. Aucun handshake SAP/canal 204 ne peut donc encore etre valide physiquement dans ce log.

## Statut par couche

Bluetooth discovery: OK logiciel et ADB. BLE + Classic sont lances; la selection filtre les noms Gear 360. Validation camera necessaire.

Samsung Legacy framework: OK logiciel. Package et binder verifies sur le S8; package `3.1.93.90325` verifie sur le Galaxy A05. La nouvelle chaine complete reste a revalider avec la camera en mode `Connect to Android`.

RFCOMM: PARTIEL, code client + serveur compile. Validation physique SM-R210 necessaire.

Framing SAP: OK logiciel pour longueur, header SAP, fragmentation basique, profile-id fixe 17 octets et CRC transport.

CRC: OK logiciel, test vector `123456789` -> `0xbb3d`.

SAP session: PARTIEL. CAPEX legacy structure Samsung et service connection Gear360 sont implementes; le CAPEX moderne reste bloque volontairement avec erreur explicite.

CAPEX: PARTIEL. Legacy implemente avec record `/system/DI_360_2D`. Normal CAPEX a analyser depuis trace HCI/JADX avant reponse.

Channel 204: PARTIEL. Le mux l'ouvre seulement si la camera envoie une service connection contenant 204.

Gear360 JSON: PRESERVE. Les messages existants sont reutilises apres decapsulation channel 204.

PHOTO: NON VALIDE physiquement. Le bouton restera inactif jusqu'a `config-info`.

VIDEO: NON VALIDE physiquement. Meme condition que PHOTO.

Wi-Fi: DESACTIVE pour ce diagnostic controle.

UPnP/Gallery/RVF: NON TRAITE dans cette iteration.

## Logs attendus au test physique

Filtre conseille:

```text
Gear360|G360|AndroidRuntime|FATAL|Bluetooth|RFCOMM|SAP
```

Sequence recherchee:

```text
G360-RFCOMM: RFCOMM-SERVER CLASSIC_LISTENING ...
G360-RFCOMM: RFCOMM-CLIENT CLASSIC_CONNECTING ...
G360-RFCOMM: ... CLASSIC_CONNECTED
G360-SAP: phase=PROTOCOL_INIT
G360-SAP-FRAME: RX session=1020 ...
G360-SAP: legacy CAPEX query ...
G360-SAP: legacy CAPEX response sent profile=/system/DI_360_2D
G360-SAP-FRAME: RX session=1023 ...
G360-SAP: service request profile=/system/DI_360_2D ... channels=204->...
G360-SAP: SAP READY: channel 204 open
G360-CH204: CHANNEL 204 OPEN
G360-CH204: RX 204 len=...
G360-PROTOCOL: RX ch=204 ...
G360-CONNECTION: READY config-info received
```

## Premier point d'echec restant a identifier

Il faut tester la nouvelle APK avec la vraie SM-R210 allumee en mode `Connect to Android` pendant que l'ecran de scan est ouvert.

Si `G360-BT: Gear 360 discovered via classic: Gear 360 (...)` n'apparait pas, le blocage est au niveau visibilite/pairing Bluetooth Android.

Si la camera apparait et que l'ecran simple s'ouvre, le prochain point a observer est:

```text
LEGACY-SAM: Accessory connected
LEGACY-SAP: PEER_AGENT_FOUND; requesting service connection as the original Gear 360 Manager does
LEGACY-SAP: OUTGOING SERVICE CONNECTION REQUEST
LEGACY-SAP: INCOMING SERVICE CONNECTION REQUEST
LEGACY-SAP: acceptServiceConnectionRequest
LEGACY-SAP: SASocket connected
LEGACY-RX/G360-CH204: RX channel=204
```

Si aucun `RX_RAW` n'arrive, le blocage est RFCOMM/role/UUID.

Si `Unknown SAP framing` apparait, le prefixe/framing bas niveau doit etre ajuste a partir de la trace HCI.

Si `normal CAPEX query received` apparait, il faut completer le format CAPEX moderne.

Si le canal 204 ouvre mais aucun JSON n'arrive, le handshake/service connection reste incomplet.
