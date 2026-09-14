# Third-party components

This project uses AndroidX, Material Components, Gson, Moshi, ExoPlayer and JUnit under
their respective licenses.

The files in `app/sdk/` are Samsung Accessory SDK client libraries and remain subject to
Samsung's applicable SDK terms. They are included to preserve the legacy interoperability
backend. They do not contain or install the external Samsung Accessory Service package
`com.samsung.accessory`.

Reference Samsung APKs and decompiled material are intentionally excluded from this Git
repository. Only clean-room protocol findings are documented under `docs/`.

The native-control experiment also uses the Samsung WSM binary libraries in
`app/src/main/jniLibs/arm64-v8a/` and their JNI interface. The successful A05 WSM
authentication still relies on these binaries; the project is not yet an entirely
independent implementation. Their presence does not bundle the external Accessory
Service Android package. The additional TrustZone probe and supporting libraries
already in the debug tree are diagnostic experiments, not the validated WSM path.
Binary redistribution remains subject to the applicable Samsung terms; no new
license to those components is granted by this project's source license.
