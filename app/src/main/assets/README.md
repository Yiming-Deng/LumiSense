# Runtime assets

This public source candidate intentionally excludes model weights and the VRM/GLB runtime asset.

Expected filenames:

- `gesture_pose_final_fullqat_w8a16_640.tflite`
- `face_landmarker.task`
- `gesture_recognizer.task`
- `v3_avatar_head_only_mocap.glb`

See `docs/MODEL_AND_VENDOR_ASSETS.md` before obtaining or redistributing any file. A debug APK can be compiled without these assets, but recognition and avatar features will not run correctly.
