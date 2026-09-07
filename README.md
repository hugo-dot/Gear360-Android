# Gear360 Android

Application Android expérimentale pour contrôler une Samsung Gear 360 2017 (SM-R210)
sur Android 11 et les versions modernes d'Android.

## État du projet

Le projet compile avec `compileSdk 36`, cible Android 16 (`targetSdk 36`) et conserve
la compatibilité de code à partir d'Android 5 (`minSdk 21`). Le téléphone de test est
un Galaxy S8 sous /e/OS.

Fonctions déjà présentes :

- découverte Bluetooth Classic et BLE, avec détection des appareils déjà appairés ;
- backend Samsung Accessory historique et diagnostic détaillé de son framework externe ;
- protocole JSON Gear 360 existant sur le canal logique 204 ;
- commandes PHOTO, VIDEO START et VIDEO STOP, activées uniquement après réception de
  la configuration réelle de la caméra ;
- base expérimentale du transport RFCOMM/SAP natif ;
- connexion Wi-Fi moderne, désactivée pendant le diagnostic du contrôle Bluetooth.

La connexion physique complète `SAP -> canal 204 -> config-info -> READY` doit encore
être validée avec une vraie SM-R210. Le projet ne simule jamais l'état READY.

## Compilation

Prérequis : Android Studio avec son JDK intégré, Android SDK 36 et une connexion Internet
pour le premier téléchargement des dépendances Gradle.

```powershell
.\gradlew.bat clean testDebugUnitTest assembleDebug lintDebug
```

APK généré : `app/build/outputs/apk/debug/app-debug.apk`.

Les trois JAR nécessaires au backend historique sont conservés dans `app/sdk/` et sont
embarqués dans l'APK. Ils fournissent l'API cliente Samsung Accessory. Le service système
externe `com.samsung.accessory` n'est pas intégré à l'application et reste nécessaire pour
le backend `SAMSUNG LEGACY`; le backend natif est encore expérimental.

## Test sur la caméra

1. Mettre la Gear 360 en mode **Connect to Android**.
2. Autoriser les permissions Bluetooth demandées.
3. Sélectionner `Gear 360 (...)` dans le scanner.
4. Vérifier les logs `G360-*` et `LEGACY-*` jusqu'à `config-info` puis `READY`.
5. Tester PHOTO, VIDEO START et VIDEO STOP uniquement lorsque l'interface indique READY.

Le script `diagnose-s8.bat` collecte les informations ADB utiles sans modifier ni effacer
les données du téléphone.

## Documentation

- [Diagnostic Android et caméra](DIAGNOSTIC.md)
- [Protocole Gear 360](PROTOCOL.md)
- [Framing Samsung Accessory](docs/protocol/samsung-accessory-framing.md)
- [Diagnostic SAP](docs/gear360-sap-diagnostics.md)

Les APK Samsung de référence et leurs sources décompilées restent locaux dans
`reverse-engineering/` et ne sont pas destinés au dépôt Git ni à l'APK final.
Voir aussi [les informations sur les composants tiers](THIRD_PARTY_NOTICES.md).
