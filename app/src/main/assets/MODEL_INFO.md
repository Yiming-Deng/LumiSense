# Model assets

## Final pose-gesture LiteRT model

- Asset: `gesture_pose_final_fullqat_w8a16_640.tflite`
- Source: the same final 1,024-step QAT model used by the QNN release backend
- Quantization: W8A16 with portable LiteRT boundaries
- Runtime: `com.google.ai.edge.litert:litert:2.1.6`, NPU first with GPU/CPU fallback
- Input: RGB NCHW `[1,3,640,640]`, gray-114 letterbox, values in `[0,1]`
- Output: raw pose-gesture tensor `[1,101,8400]`; Android performs the shared top-K and NMS post-processing
- Classes: 33 HaGRIDv2 gesture classes plus `no_gesture`
- Model SHA-256: `8cad88ede2d05039c888bdc097148b1427bf8db349b67246c587aeec4e9a9ed5`
- Role: MediaTek NPU path and the only fallback model for QNN initialization or execution failures

Delivery builds fail when this asset is absent or when its checksum matches the retired early model.

## Final pose-gesture QNN release

- Library: `libgesture_pose_final_qat_w8a16.so`
- Model SHA-256: `97724208662d099b1ca26c881d4d154e43222788b62b7ec5c54794c12b4886b4`
- Model version: `gesture-pose-final-fullqat1024-w8a16-qnn`
- Quantization: W8A16
- Runtime: QAIRT 2.47, QNN online compose, Qualcomm HTP V73/V75/V79
- Release performance mode: sustained
- Input: RGB NCHW `[1,3,640,640]`, gray-114 letterbox, values quantized from `[0,1]`
- Output: compact FP32 rows containing `xyxy + score + classId + 21*(x,y,confidence)`
- Role: release V2 gesture backend; one inference provides boxes, keypoints, and static classes to ProductFSM

The release APK selects QNN first on Qualcomm SoCs through `BuildConfig.GESTURE_RUNTIME=qnn_htp`.
QNN and LiteRT use the same final QAT-trained model rather than different model generations.

## Gesture Recognizer

- Asset: `gesture_recognizer.task`
- Source: Google MediaPipe Gesture Recognizer, float16 version 1
- URL: https://storage.googleapis.com/mediapipe-models/gesture_recognizer/gesture_recognizer/float16/1/gesture_recognizer.task
- Runtime: `com.google.mediapipe:tasks-vision:0.10.29`
- Role: retained V1 implementation; not the default gesture backend
- Enabled V1 canned labels: Closed_Fist, Open_Palm, Pointing_Up, Thumb_Down, Thumb_Up, Victory
- Disabled V1 canned labels: ILoveYou, None/Unknown

The SHA-256 checksum is recorded in `MODEL_SHA256.txt` after download.

## Face Landmarker

- Asset: `face_landmarker.task`
- Source: Google MediaPipe Face Landmarker, float16 version 1
- URL: https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task
- Runtime: `com.google.mediapipe:tasks-vision:0.10.29`
- Configuration: one face, live stream, face Blendshapes and facial transformation matrices enabled
- Product expressions: Smile, Mouth Open, Brow Raise, Mouth Pucker, Left Wink, Right Wink, Eyes Wide,
  plus explicit None rejection; simultaneous eye closure is rejected as None
- Product head outputs: Center/Left/Right state and Turn Left/Turn Right/Nod/Shake events

The model emits 478 normalized landmarks, 52 Blendshape scores, and a row-major 4x4 facial transformation matrix. Product labels are deterministic post-processing outputs and must not be interpreted as inferred emotions.

## VRM avatar face mocap

- Asset: `v3_avatar_head_only_mocap.glb`
- Source: pixiv `VRM1_Constraint_Twist_Sample`, VRM Public License 1.0
- Asset SHA-256: `25fcc5a44226e8a428a833decba40a1897c6ffb4b9c1beb4897ea461ee78eb5e`
- Mobile asset: head-only standard GLB, 3.51 MiB, 24 ordered facial morph targets
- Driver: continuous MediaPipe Face Landmarker Blendshape scores with independent attack/release smoothing
- Coverage: independent blinks, brow states, eye expressions, smile/frown, jaw open, lip size/direction, and A/I/U/E/O mouth shapes
- Build script: `tools/v3/build_vrm_avatar_head.py`

The avatar mocap path does not consume `ExpressionId` or expression event sequence numbers. Product expression recognition and avatar deformation are separate consumers of the same Face Landmarker frame.
