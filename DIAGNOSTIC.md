# Gear360: diagnostic du controle Bluetooth

Mise a jour: 2026-09-14. **Application non validee fonctionnelle.**

## Resultat reel

Les essais du 10/11 septembre sur Galaxy A05 SM-A055F, Android 15/API 35,
avec la SM-R210 ont atteint:

1. Connexion RFCOMM entrante de la camera.
2. Peer Description 2.1 et reponse acceptee.
3. Authentification WSM confirmee par la camera.
4. Activation du CRC de transport.
5. Creation du service `/System/Reserved/ServiceCapabilityDiscovery`,
   acceptee avec statut 0 et session 1.
6. Requetes CAPEX de la camera, dont `/system/DI_360_2D`.

**Aucun canal 204, JSON Gear360, config-info, PHOTO ou VIDEO n'a encore ete
valide physiquement.** Le dernier blocage observe est la creation du service
de controle apres CAPEX, pas le pairing Android.

## Causes et corrections

- WSM: protocole selectionne avant initialisation, bonnes tailles de paquets
  et bon sens du code de retour. Le chemin logiciel a fonctionne sur l'A05;
  le daemon TrustZone explore auparavant n'est pas necessaire a cet essai.
- Peer Description 2.1: paquets device bruts 5/6, pas session SAP 320.
- CRC: active apres authentification lorsque negocie. La camera a accepte
  les trames produites et ses reponses ont passe la verification CRC.
- CAPEX 2.1: ouverture du service reserve sur le canal 255 avant les requetes;
  la session reservee 1020 appartient aux versions de protocole plus recentes.
- Annonce du service: version applicative 1.0 (`0x0100`), pas version du
  transport 2.1. Reponses MATCHING/SYNC avec checksum; ALL sans checksum.
- **Role corrige: provider=0, consumer=1.** Verification dans
  `SAServiceDescriptionParser`; notre annonce native avait inverse le role.
- Connexion active native: parsing borne de la reponse CAPEX, selection d'un
  consumer Gear360 unique, demande avec son componentId reel. Une seule
  demande est emise. Le code officiel du Manager confirme ce sens.
- Le mux n'ouvre les canaux qu'apres acceptation correspondant exactement au
  profil, aux identifiants et aux sessions demandes. Une reponse tardive apres
  timeout/deconnexion ne peut pas remettre READY.
- Timeout de negociation de 30 secondes, precisant la phase atteinte.
- La socket entrante n'accepte que l'adresse de la camera selectionnee.
- Une perte RFCOMM ferme la session et interdit les envois suivants.

## Validation de cette reprise

Le 14 septembre, `testDebugUnitTest assembleDebug` a reussi avec **53 tests,
0 echec, 0 erreur**, apres les corrections du role et le premier chemin de
demande sortante native.

Les gardes de session, le timeout, le filtrage du pair entrant et les quatre
tests `NativeServiceNegotiationTest` ont ete ajoutes ensuite. **Leur compilation
et leur execution finale restent a faire**: trois tentatives d'execution ont
ete refusees par le mecanisme d'autorisation de l'environnement, avec
`Selected model is at capacity`. Ce n'est pas une erreur Gradle diagnostiquee.

Au dernier controle du 14 septembre, `adb devices -l` ne listait aucun appareil.
La nouvelle version n'a donc pas ete installee ni testee sur la camera.

Les anciens APK du dossier ne constituent PAS une livraison finale de ces
sources. L'archive source de cette reprise est explicitement non validee.

## Etat par couche

| Couche | Implementation / validation |
| --- | --- |
| RFCOMM | Echanges reels observes sur A05; nouvelle garde d'adresse a retester |
| Framing et CRC | Frames reelles CAPEX decodees et reponses acceptees |
| WSM | Confirmation reelle observee, depend de binaires Samsung embarques |
| CAPEX | Service accepte et requetes recues; nouvelle requete Sync a retester |
| SAP controle | Demande sortante codee; acceptation camera non observee |
| Canal 204 | Non valide physiquement |
| Gear360 JSON | Code existant preserve; aucun RX 204 valide observe |
| PHOTO / VIDEO | Non valides; boutons non forces |
| Wi-Fi automatique | Reste desactive pour le diagnostic controle |
| UPnP / galerie / RVF | Non traites dans cette reprise |

## Dependances et limites restantes

Les JAR de `app/sdk` sont des clients, pas le framework `com.samsung.accessory`.
Le backend legacy exige ce package separement installe. Son installation
existante sur l'A05 etait 3.1.93.90325; elle ne prouve pas une session de controle.

Le backend natif utilise encore `libwsm2`/JNI Samsung, presents pour ARM64.
Il ne constitue donc pas une reimplementation independante et universelle.
L'identite Bluetooth locale necessaire a WSM a ete fournie explicitement lors
des essais ADB; sa disponibilite automatique depend d'Android et du constructeur.

Les probes TrustZone et leurs bibliotheques de diagnostic deja presents dans
le depot ne font pas partie du chemin ayant reussi WSM. Aucun firmware camera,
formatage, suppression de media ou reset du telephone n'a ete effectue.

## Reprendre le test

Depuis la racine Gradle:

```powershell
.\gradlew.bat clean testDebugUnitTest assembleDebug lintDebug
.\diagnose-gear360.bat
```

Brancher/deverrouiller le telephone, autoriser ADB, choisir `Connect to Android`
sur la camera. Le script installe l'APK et conserve des logs dans `logs/`.
Les logs complets peuvent contenir des donnees personnelles; ne pas les publier
automatiquement dans Git.

La prochaine preuve requise est une reponse d'acceptation du service Gear360,
puis `CHANNEL 204 OPEN`, RX JSON et `config-info`. Seulement ensuite tester
une photo et une courte video suivie de STOP. Ne pas confondre `SAP READY`
(transport) avec la disponibilite camera confirmee par `config-info`.

## Fichiers modifies dans la reprise du 14 septembre

- `transport/nativegear360/Gear360ClassicBluetoothServerLink.kt`
- `transport/nativegear360/NativeGear360Transport.kt`
- `transport/nativegear360/sap/NativeSapSession.kt`
- `transport/nativegear360/sap/SapCapabilityExchange.kt`
- `transport/nativegear360/sap/SapCapabilityResponse.kt` (nouveau)
- `transport/nativegear360/sap/SapChannelMux.kt`
- Tests: `SapFrameEncoderTest.kt`, `SapCapabilityResponseTest.kt`,
  `NativeServiceNegotiationTest.kt`
- Documentation: `DIAGNOSTIC.md`, `PROTOCOL.md`, `THIRD_PARTY_NOTICES.md`,
  `docs/protocol/a05-control-20260911.md`

Les chemins Kotlin commencent sous
`app/src/main/java/io/github/teccheck/gear360app/`; les tests sous `app/src/test/java/`.
Aucun push Git effectue pendant cette reprise.
