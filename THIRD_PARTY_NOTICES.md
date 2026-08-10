# Third-party notices

## MediaPipe

- Component: MediaPipe Tasks Vision, Gesture Recognizer model, and Face Landmarker model
- Copyright: Google LLC
- License: Apache License 2.0 for code; model terms are provided by Google AI Edge
- Source: https://developers.google.com/edge/mediapipe/solutions/vision/gesture_recognizer
- Face Landmarker source: https://ai.google.dev/edge/mediapipe/solutions/vision/face_landmarker/android

## AndroidX and Jetpack Compose

- Components: CameraX, Compose, Navigation, Lifecycle, DataStore, Benchmark
- Copyright: The Android Open Source Project
- License: Apache License 2.0
- Source: https://developer.android.com/jetpack

## Kotlin and kotlinx.coroutines

- Components: Kotlin standard library and kotlinx.coroutines
- Copyright: JetBrains s.r.o. and Kotlin contributors
- License: Apache License 2.0
- Source: https://github.com/JetBrains/kotlin and https://github.com/Kotlin/kotlinx.coroutines

## LiteRT

- Component: Google AI Edge LiteRT Android runtime
- Copyright: Google LLC
- License: Apache License 2.0
- Source: https://github.com/google-ai-edge/LiteRT

## Filament

- Components: Filament Android, gltfio, and utils
- Copyright: Google LLC
- License: Apache License 2.0
- Source: https://github.com/google/filament

## Face experience visual assets

- Smiley Sans v2.0.1 (得意黑), Copyright atelierAnchor, SIL Open Font License 1.1. Source: https://github.com/atelier-anchor/smiley-sans
- Roboto Condensed, Copyright Google LLC, SIL Open Font License 1.1. Source: https://github.com/google/fonts/tree/main/ofl/robotocondensed
- VRM1 Constraint Twist Sample avatar: copyright 2022 pixiv Inc., used under the VRM Public License 1.0 and reduced to a head-only continuous face-mocap asset with 24 selected source facial morph targets. Source: https://github.com/vrm-c/vrm-specification/tree/master/samples/VRM1_Constraint_Twist_Sample

## Qualcomm AI Runtime

- Components: QAIRT/QNN API headers, QNN/HTP runtime libraries, LiteRT Qualcomm plugins, and QNN-generated model library
- Copyright: Qualcomm Technologies, Inc. and/or its subsidiaries
- License: proprietary Qualcomm SDK terms; QNN headers in the internal build explicitly state "Confidential and Proprietary"
- Source: https://softwarecenter.qualcomm.com/catalog/item/Qualcomm_AI_Runtime_Community
- Distribution: not included in the public source directory. Developers must obtain the SDK and redistributable runtime through Qualcomm and comply with the applicable agreement.

## Training and evaluation datasets

- HaGRIDv2: Creative Commons Attribution-ShareAlike 4.0 International; source https://github.com/hukenovs/hagrid
- COCO 2017: dataset terms and per-image licenses; source https://cocodataset.org
- Places365: dataset-specific terms; source http://places2.csail.mit.edu
- IPN Hand: dataset terms supplied by the dataset authors; source https://gibranbenitez.github.io/IPN_Hand/
- Distribution: no dataset media is included in the public source directory.

## Ultralytics training stack

- Component: Ultralytics YOLO training and export tooling used to produce the hand model
- Source: https://github.com/ultralytics/ultralytics
- License: AGPL-3.0 or a separate Ultralytics Enterprise license, depending on the license obtained by the model producer
- Distribution: Ultralytics code is not vendored. The final trained model is withheld from the public source candidate until its redistribution basis is documented.

No public dataset media, Qualcomm SDK file, APK, or Android signing material is redistributed with the public source directory. Dataset licenses and citations must accompany generated evaluation reports. An official signed APK may be distributed separately through GitHub Releases only after the model and bundled runtime redistribution terms have been verified; such an APK is not licensed solely under the repository's Apache License 2.0.
