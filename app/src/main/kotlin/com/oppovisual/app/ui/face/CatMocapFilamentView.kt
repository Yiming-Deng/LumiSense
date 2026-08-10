package com.oppovisual.app.ui.face

import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.Matrix
import android.util.AttributeSet
import android.view.Choreographer
import android.view.Surface
import android.view.TextureView
import com.google.android.filament.Box
import com.google.android.filament.Camera
import com.google.android.filament.EntityManager
import com.google.android.filament.LightManager
import com.google.android.filament.Renderer
import com.google.android.filament.SwapChain
import com.google.android.filament.SwapChainFlags
import com.google.android.filament.View
import com.google.android.filament.android.UiHelper
import com.google.android.filament.utils.ModelViewer
import com.google.android.filament.utils.Utils
import com.oppovisual.app.ui.RecognitionUiState
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.max

enum class MocapHeadgearProfile(
    val assetName: String,
    val yawFactor: Float,
    val pitchFactor: Float,
) {
    ELDER_SPRITE(
        assetName = "v3_avatar_head_only_mocap.glb",
        yawFactor = 0.72f,
        pitchFactor = 0.72f,
    ),
}

class CatMocapFilamentView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    private val profile: MocapHeadgearProfile = MocapHeadgearProfile.ELDER_SPRITE,
    private val assetName: String = profile.assetName,
    private val transparentSurface: Boolean = true,
) : TextureView(context, attrs), Choreographer.FrameCallback {
    var onRendererReady: ((Boolean) -> Unit)? = null

    @Volatile
    var renderedFrameCount: Long = 0
        private set

    @Volatile
    var hasVisibleAnchor: Boolean = false
        private set

    @Volatile
    var lastVisibleRenderableCount: Int = 0
        private set

    @Volatile
    var lastMotion: CatMocapMotion = CatMocapMotion()
        private set

    @Volatile
    var lastAppliedMotion: CatMocapMotion = CatMocapMotion()
        private set

    @Volatile
    var lastMorphWeights: FloatArray = FloatArray(0)
        private set

    @Volatile
    var lastAppliedAnchor: FaceHeadgearAnchor = FaceHeadgearAnchor()
        private set

    @Volatile
    var lastAppliedLayout: FaceHeadgearLayout? = null
        private set

    /** Screen-space geometry after the avatar has been posed and eye-corrected. */
    @Volatile
    var lastVirtualFaceLayout: FaceHeadgearLayout? = null
        private set

    var onVirtualFaceLayoutChanged: ((FaceHeadgearLayout?) -> Unit)? = null

    val assetRenderableCount: Int get() = asset.renderableEntities.size
    val sceneRenderableCount: Int get() = viewer.scene.renderableCount
    val rootHasTransform: Boolean get() = transformManager.hasComponent(asset.root)
    val rootTransformInstance: Int get() = transformManager.getInstance(asset.root)
    val rootLocalTransform: FloatArray
        get() = transformManager.getTransform(rootTransformInstance, FloatArray(16))
    val rootWorldTransform: FloatArray
        get() = transformManager.getWorldTransform(rootTransformInstance, FloatArray(16))
    val assetBoundsCenter: FloatArray get() = asset.boundingBox.center
    val assetBoundsHalfExtent: FloatArray get() = asset.boundingBox.halfExtent
    internal val avatarFaceViewportBounds: FloatArray
        get() = projectRenderableBoundsToViewport(avatarFaceEntity)
    internal val avatarEyeViewportGeometry: FloatArray
        get() = projectAvatarEyesToViewport()
    val totalPrimitiveCount: Int
        get() = asset.renderableEntities.sumOf { entity ->
            viewer.engine.renderableManager.getInstance(entity).takeIf { it != 0 }?.let {
                viewer.engine.renderableManager.getPrimitiveCount(it)
            } ?: 0
        }
    val animatedEntityCount: Int get() = entities.size + morphRenderableInstances.size
    val assetCenterNdc: FloatArray
        get() {
            val rootWorld = rootWorldTransform
            val viewProjection = FloatArray(16)
            val modelViewProjection = FloatArray(16)
            Matrix.multiplyMM(
                viewProjection,
                0,
                viewer.camera.getProjectionMatrix(DoubleArray(16)).map(Double::toFloat).toFloatArray(),
                0,
                viewer.camera.getViewMatrix(FloatArray(16)),
                0,
            )
            Matrix.multiplyMM(modelViewProjection, 0, viewProjection, 0, rootWorld, 0)
            val center = asset.boundingBox.center
            val clip = FloatArray(4)
            Matrix.multiplyMV(
                clip,
                0,
                modelViewProjection,
                0,
                floatArrayOf(center[0], center[1], center[2], 1f),
                0,
            )
            return if (abs(clip[3]) < 1e-6f) clip else floatArrayOf(
                clip[0] / clip[3],
                clip[1] / clip[3],
                clip[2] / clip[3],
                clip[3],
            )
        }

    private val viewer: ModelViewer
    private val asset get() = requireNotNull(viewer.asset)
    private val transformManager get() = viewer.engine.transformManager
    private val anchorEstimator = FaceHeadgearAnchorEstimator()
    private val motionFilter = CatMocapMotionFilter()
    private val avatarMorphFilter = VrmAvatarMorphFilter()
    private val pendingFrame = AtomicReference<RenderFrame>()
    private val baselineTransforms = mutableMapOf<String, FloatArray>()
    private val entities = mutableMapOf<String, Int>()
    private val morphRenderableInstances = mutableListOf<Int>()
    private val rootPlacement = FloatArray(16)
    private val rootResult = FloatArray(16)
    private val localTransform = FloatArray(16)
    private val animatedTransform = FloatArray(16)
    private val eyeCorrection = FloatArray(16)
    private val correctedRoot = FloatArray(16)
    private val rootUnitTransform: FloatArray
    private val lightEntities: IntArray
    private var frameScheduled = false
    private var released = false
    private var diagnosticFixedPose = false
    private var readyReported = false
    private var projectionDirty = true
    private var avatarEyeCenterNormalizedX = 0f
    private var avatarEyeCenterNormalizedY = 0f
    private var avatarEyeCenterNormalizedZ = 0f
    private var avatarEyeDistanceNormalized = 1f
    private var avatarFaceEntity = 0
    private var transparentSwapChain: SwapChain? = null
    private var transparentNativeSurface: Surface? = null
    private var transparentSurfaceTexture: SurfaceTexture? = null

    init {
        ensureInitialized()
        isOpaque = !transparentSurface
        val uiHelper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK).apply {
            isOpaque = !transparentSurface
        }
        viewer = ModelViewer(this, uiHelper = uiHelper, manipulator = null).apply {
            view.blendMode = if (transparentSurface) View.BlendMode.TRANSLUCENT else View.BlendMode.OPAQUE
            view.isPostProcessingEnabled = !transparentSurface
            view.setShadowingEnabled(false)
            view.setFrustumCullingEnabled(false)
            renderer.clearOptions = Renderer.ClearOptions().apply {
                clearColor = doubleArrayOf(0.0, 0.0, 0.0, if (transparentSurface) 0.0 else 1.0)
                clear = true
                discard = false
            }
            loadModelGlb(readAsset(assetName))
        }
        rootUnitTransform = createFaceAlignedUnitTransform()
        transformManager.setTransform(rootTransformInstance, rootUnitTransform)
        AVATAR_NODE_NAMES.forEach { name ->
            asset.getFirstEntityByName(name).takeIf { it != 0 }?.let { entity ->
                entities[name] = entity
                baselineTransforms[name] = transformManager.getTransform(
                    transformManager.getInstance(entity),
                    FloatArray(16),
                )
            }
        }
        asset.renderableEntities.forEach { entity ->
            viewer.engine.renderableManager.getInstance(entity).takeIf { it != 0 }?.let { instance ->
                viewer.engine.renderableManager.setCulling(instance, false)
                viewer.engine.renderableManager.setCastShadows(instance, false)
                viewer.engine.renderableManager.setReceiveShadows(instance, false)
                if (entity == asset.getFirstEntityByName("AvatarFace")) {
                    check(
                        viewer.engine.renderableManager.getMorphTargetCount(instance) ==
                            VrmAvatarMorphTargets.COUNT,
                    ) { "Unexpected VRM face morph target count" }
                    morphRenderableInstances += instance
                }
            }
        }
        lightEntities = intArrayOf(
            createDirectionalLight(-0.20f, -0.15f, -1.0f, 100_000f, 1.0f, 0.96f, 0.92f),
            createDirectionalLight(0.75f, 0.10f, -0.65f, 35_000f, 0.64f, 0.82f, 1.0f),
        )
        viewer.scene.addEntities(lightEntities)
        viewer.camera.apply {
            lookAt(0.0, 0.0, 5.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0)
            setExposure(14f, 1f / 100f, 100f)
        }
    }

    fun update(state: RecognitionUiState) {
        val anchor = anchorEstimator.update(
            landmarks = state.faceLandmarks,
            headPose = state.headPose,
            facePresent = state.facePresent,
            timestampMs = state.lastFaceFrameTimestampMs,
        )
        val motion = if (state.facePresent) {
            motionFilter.update(state.headPose, state.blendshapes)
        } else {
            CatMocapMotion()
        }
        val avatarMorphWeights = if (state.facePresent) {
            avatarMorphFilter.update(state.blendshapes)
        } else {
            FloatArray(VrmAvatarMorphTargets.COUNT)
        }
        lastMotion = motion
        pendingFrame.set(
            RenderFrame(
                anchor = anchor,
                motion = motion,
                avatarMorphWeights = avatarMorphWeights,
                inputWidth = state.inputWidth,
                inputHeight = state.inputHeight,
            ),
        )
    }

    fun setDiagnosticOpaqueBackground(enabled: Boolean) {
        viewer.renderer.clearOptions = Renderer.ClearOptions().apply {
            clearColor = if (enabled) {
                doubleArrayOf(42.0 / 255.0, 54.0 / 255.0, 64.0 / 255.0, 1.0)
            } else {
                doubleArrayOf(0.0, 0.0, 0.0, 0.0)
            }
            clear = true
            discard = false
        }
    }

    fun setDiagnosticFixedPose(enabled: Boolean) {
        diagnosticFixedPose = enabled
        if (enabled) {
            alpha = 1f
            hasVisibleAnchor = true
            val diagnosticScale = AVATAR_DIAGNOSTIC_SCALE
            setRootTransform(
                scaleX = diagnosticScale,
                scaleY = diagnosticScale,
                scaleZ = diagnosticScale,
            )
        }
        updateProjection(width, height)
    }

    fun captureNextFilamentFrame(callback: (Bitmap) -> Unit) {
        viewer.debugGetNextFrameCallback(callback)
    }

    fun animatedNodeTransform(name: String): FloatArray? {
        val entity = entities[name] ?: return null
        return transformManager.getTransform(transformManager.getInstance(entity), FloatArray(16))
    }

    internal fun resetTrackingForTest() {
        anchorEstimator.reset()
        motionFilter.reset()
        avatarMorphFilter.reset()
        pendingFrame.set(null)
        lastAppliedAnchor = FaceHeadgearAnchor()
        lastAppliedLayout = null
        publishVirtualFaceLayout(null)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        released = false
        projectionDirty = true
        scheduleFrame()
    }

    override fun onDetachedFromWindow() {
        Choreographer.getInstance().removeFrameCallback(this)
        frameScheduled = false
        super.onDetachedFromWindow()
        releaseTransparentSurfaceReference()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        projectionDirty = true
    }

    override fun doFrame(frameTimeNanos: Long) {
        frameScheduled = false
        if (released || !isAttachedToWindow) return
        ensureTransparentSwapChain()
        if (projectionDirty && width > 0 && height > 0) {
            updateProjection(width, height)
            projectionDirty = false
        }
        pendingFrame.get()?.let { frame ->
            if (applyFrame(frame)) {
                pendingFrame.compareAndSet(frame, null)
            }
        }
        if (viewer.render(frameTimeNanos)) {
            renderedFrameCount++
            lastVisibleRenderableCount = viewer.view.visibleRenderableCount
            if (!readyReported && lastVisibleRenderableCount > 0) {
                readyReported = true
                onRendererReady?.invoke(true)
            }
        }
        scheduleFrame()
    }

    fun release() {
        if (released) return
        released = true
        Choreographer.getInstance().removeFrameCallback(this)
        frameScheduled = false
        onRendererReady?.invoke(false)
    }

    private fun updateProjection(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val aspect = width.toDouble() / height
        viewer.camera.setProjection(Camera.Projection.ORTHO, -aspect, aspect, -1.0, 1.0, 0.1, 20.0)
    }

    private fun ensureTransparentSwapChain() {
        if (!transparentSurface || !isAvailable) return
        val currentSwapChain = viewer.swapChain ?: return
        val currentSurfaceTexture = surfaceTexture ?: return
        if (currentSwapChain === transparentSwapChain && currentSurfaceTexture === transparentSurfaceTexture) return

        releaseTransparentSurfaceReference()
        viewer.engine.destroySwapChain(currentSwapChain)
        val nativeSurface = Surface(currentSurfaceTexture)
        val replacement = viewer.engine.createSwapChain(nativeSurface, SwapChainFlags.CONFIG_TRANSPARENT)
        MODEL_VIEWER_SWAP_CHAIN_FIELD.set(viewer, replacement)
        transparentSwapChain = replacement
        transparentNativeSurface = nativeSurface
        transparentSurfaceTexture = currentSurfaceTexture
        projectionDirty = true
    }

    private fun releaseTransparentSurfaceReference() {
        transparentNativeSurface?.release()
        transparentNativeSurface = null
        transparentSwapChain = null
        transparentSurfaceTexture = null
    }

    private fun createFaceAlignedUnitTransform(): FloatArray {
        val faceEntity = asset.getFirstEntityByName("AvatarFace")
        require(faceEntity != 0) { "AvatarFace is required for face-aligned placement" }
        avatarFaceEntity = faceEntity
        val renderableInstance = viewer.engine.renderableManager.getInstance(faceEntity)
        require(renderableInstance != 0) { "AvatarFace must be renderable" }
        val faceBox = viewer.engine.renderableManager.getAxisAlignedBoundingBox(renderableInstance, Box())
        val faceTransformInstance = transformManager.getInstance(faceEntity)
        require(faceTransformInstance != 0) { "AvatarFace must have a transform" }
        val faceWorldTransform = transformManager.getWorldTransform(faceTransformInstance, FloatArray(16))
        val center = faceBox.center
        val halfExtent = faceBox.halfExtent
        val minimum = floatArrayOf(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
        val maximum = floatArrayOf(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY)
        val point = FloatArray(4)
        for (xSign in intArrayOf(-1, 1)) {
            for (ySign in intArrayOf(-1, 1)) {
                for (zSign in intArrayOf(-1, 1)) {
                    Matrix.multiplyMV(
                        point,
                        0,
                        faceWorldTransform,
                        0,
                        floatArrayOf(
                            center[0] + halfExtent[0] * xSign,
                            center[1] + halfExtent[1] * ySign,
                            center[2] + halfExtent[2] * zSign,
                            1f,
                        ),
                        0,
                    )
                    for (axis in 0..2) {
                        minimum[axis] = kotlin.math.min(minimum[axis], point[axis])
                        maximum[axis] = kotlin.math.max(maximum[axis], point[axis])
                    }
                }
            }
        }
        val faceCenter = FloatArray(3) { axis -> (minimum[axis] + maximum[axis]) * 0.5f }
        val faceHalfWidth = (maximum[0] - minimum[0]) * 0.5f
        val faceHalfHeight = (maximum[1] - minimum[1]) * 0.5f
        require(faceHalfWidth > 0f && faceHalfHeight > 0f) { "AvatarFace has invalid bounds" }
        val normalizingScale = 1f / max(faceHalfWidth, faceHalfHeight)
        val normalizationTransform = FloatArray(16).also { transform ->
            Matrix.setIdentityM(transform, 0)
            Matrix.scaleM(transform, 0, normalizingScale, normalizingScale, normalizingScale)
            Matrix.translateM(transform, 0, -faceCenter[0], -faceCenter[1], -faceCenter[2])
        }
        val leftEye = transformPoint(faceWorldTransform, AVATAR_LEFT_EYE_CENTER)
        val rightEye = transformPoint(faceWorldTransform, AVATAR_RIGHT_EYE_CENTER)
        val normalizedLeftEye = transformPoint(normalizationTransform, leftEye)
        val normalizedRightEye = transformPoint(normalizationTransform, rightEye)
        avatarEyeCenterNormalizedX = (normalizedLeftEye[0] + normalizedRightEye[0]) * 0.5f
        avatarEyeCenterNormalizedY = (normalizedLeftEye[1] + normalizedRightEye[1]) * 0.5f
        avatarEyeCenterNormalizedZ = (normalizedLeftEye[2] + normalizedRightEye[2]) * 0.5f
        avatarEyeDistanceNormalized = kotlin.math.hypot(
            normalizedRightEye[0] - normalizedLeftEye[0],
            normalizedRightEye[1] - normalizedLeftEye[1],
        )
        require(avatarEyeDistanceNormalized > 0f) { "Avatar eye distance must be positive" }
        return normalizationTransform
    }

    private fun transformPoint(transform: FloatArray, point: FloatArray): FloatArray {
        val output = FloatArray(4)
        Matrix.multiplyMV(output, 0, transform, 0, point, 0)
        return output
    }

    private fun projectAvatarEyesToViewport(): FloatArray {
        return projectAvatarPointsToViewport(AVATAR_LEFT_EYE_CENTER, AVATAR_RIGHT_EYE_CENTER)
    }

    private fun projectAvatarMouthToViewport(): FloatArray {
        return projectAvatarPointsToViewport(AVATAR_MOUTH_CENTER)
    }

    private fun projectAvatarPointsToViewport(vararg points: FloatArray): FloatArray {
        if (avatarFaceEntity == 0 || width <= 0 || height <= 0) return FloatArray(points.size * 2)
        val transformInstance = transformManager.getInstance(avatarFaceEntity)
        if (transformInstance == 0) return FloatArray(points.size * 2)
        val worldTransform = transformManager.getWorldTransform(transformInstance, FloatArray(16))
        val viewProjection = FloatArray(16)
        val modelViewProjection = FloatArray(16)
        Matrix.multiplyMM(
            viewProjection,
            0,
            viewer.camera.getProjectionMatrix(DoubleArray(16)).map(Double::toFloat).toFloatArray(),
            0,
            viewer.camera.getViewMatrix(FloatArray(16)),
            0,
        )
        Matrix.multiplyMM(modelViewProjection, 0, viewProjection, 0, worldTransform, 0)
        fun project(point: FloatArray): FloatArray {
            val clip = transformPoint(modelViewProjection, point)
            if (abs(clip[3]) < 1e-6f) return FloatArray(2)
            val ndcX = clip[0] / clip[3]
            val ndcY = clip[1] / clip[3]
            return floatArrayOf(
                (ndcX + 1f) * 0.5f * width,
                (1f - ndcY) * 0.5f * height,
            )
        }
        return points.flatMap { point -> project(point).asList() }.toFloatArray()
    }

    private fun projectRenderableBoundsToViewport(entity: Int): FloatArray {
        if (entity == 0 || width <= 0 || height <= 0) return FloatArray(4)
        val renderableInstance = viewer.engine.renderableManager.getInstance(entity)
        val transformInstance = transformManager.getInstance(entity)
        if (renderableInstance == 0 || transformInstance == 0) return FloatArray(4)
        val box = viewer.engine.renderableManager.getAxisAlignedBoundingBox(renderableInstance, Box())
        val worldTransform = transformManager.getWorldTransform(transformInstance, FloatArray(16))
        val viewProjection = FloatArray(16)
        val modelViewProjection = FloatArray(16)
        Matrix.multiplyMM(
            viewProjection,
            0,
            viewer.camera.getProjectionMatrix(DoubleArray(16)).map(Double::toFloat).toFloatArray(),
            0,
            viewer.camera.getViewMatrix(FloatArray(16)),
            0,
        )
        Matrix.multiplyMM(modelViewProjection, 0, viewProjection, 0, worldTransform, 0)
        val center = box.center
        val halfExtent = box.halfExtent
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        val clip = FloatArray(4)
        for (xSign in intArrayOf(-1, 1)) {
            for (ySign in intArrayOf(-1, 1)) {
                for (zSign in intArrayOf(-1, 1)) {
                    Matrix.multiplyMV(
                        clip,
                        0,
                        modelViewProjection,
                        0,
                        floatArrayOf(
                            center[0] + halfExtent[0] * xSign,
                            center[1] + halfExtent[1] * ySign,
                            center[2] + halfExtent[2] * zSign,
                            1f,
                        ),
                        0,
                    )
                    if (abs(clip[3]) < 1e-6f) continue
                    val ndcX = clip[0] / clip[3]
                    val ndcY = clip[1] / clip[3]
                    minX = kotlin.math.min(minX, ndcX)
                    minY = kotlin.math.min(minY, ndcY)
                    maxX = kotlin.math.max(maxX, ndcX)
                    maxY = kotlin.math.max(maxY, ndcY)
                }
            }
        }
        return floatArrayOf(
            (minX + 1f) * 0.5f * width,
            (1f - maxY) * 0.5f * height,
            (maxX + 1f) * 0.5f * width,
            (1f - minY) * 0.5f * height,
        )
    }

    private fun createDirectionalLight(
        x: Float,
        y: Float,
        z: Float,
        intensity: Float,
        red: Float,
        green: Float,
        blue: Float,
    ): Int = EntityManager.get().create().also { entity ->
        LightManager.Builder(LightManager.Type.DIRECTIONAL)
            .direction(x, y, z)
            .color(red, green, blue)
            .intensity(intensity)
            .castShadows(false)
            .build(viewer.engine, entity)
    }

    private fun applyFrame(frame: RenderFrame): Boolean {
        if (width <= 0 || height <= 0 || frame.inputWidth <= 0 || frame.inputHeight <= 0) return false
        hasVisibleAnchor = diagnosticFixedPose || frame.anchor.visible
        alpha = if (diagnosticFixedPose) 1f else frame.anchor.alpha

        if (diagnosticFixedPose) {
            transformManager.openLocalTransformTransaction()
            animateParts(frame.motion, frame.avatarMorphWeights)
            transformManager.commitLocalTransformTransaction()
            publishVirtualFaceLayout(projectVirtualFaceLayout())
            lastAppliedMotion = frame.motion
            return true
        }
        if (!frame.anchor.visible) {
            setRootTransform(scaleX = 0.001f, scaleY = 0.001f, scaleZ = 0.001f)
            publishVirtualFaceLayout(null)
            return true
        }

        val layout = mapHeadgearAnchorToViewport(
            frame.anchor,
            frame.inputWidth,
            frame.inputHeight,
            width.toFloat(),
            height.toFloat(),
        )
        lastAppliedAnchor = frame.anchor
        lastAppliedLayout = layout
        val aspect = width.toFloat() / height
        val scale = layout.eyeDistancePx * AVATAR_SCALE_MULTIPLIER * 2f / height / avatarEyeDistanceNormalized
        val eyeCenterWorldX = (layout.eyeCenterX / width * 2f - 1f) * aspect
        val eyeCenterWorldY = 1f - layout.eyeCenterY / height * 2f
        // Preserve the old frontal placement. Pose is applied to the avatar
        // locally, then a screen-space correction aligns the rendered eyes.
        setRootTransform(
            translateX = eyeCenterWorldX - scale * avatarEyeCenterNormalizedX,
            translateY = eyeCenterWorldY - scale * avatarEyeCenterNormalizedY,
            scaleX = scale,
            scaleY = scale,
            scaleZ = scale,
        )
        transformManager.openLocalTransformTransaction()
        animateParts(frame.motion, frame.avatarMorphWeights)
        transformManager.commitLocalTransformTransaction()
        correctRootToTrackedEyes(layout, aspect)
        publishVirtualFaceLayout(projectVirtualFaceLayout())
        lastAppliedMotion = frame.motion
        return true
    }

    private fun publishVirtualFaceLayout(layout: FaceHeadgearLayout?) {
        lastVirtualFaceLayout = layout
        onVirtualFaceLayoutChanged?.invoke(layout)
    }

    private fun projectVirtualFaceLayout(): FaceHeadgearLayout? {
        val bounds = avatarFaceViewportBounds
        val eyes = avatarEyeViewportGeometry
        val mouth = projectAvatarMouthToViewport()
        if (bounds.size < 4 || eyes.size < 4 || mouth.size < 2) return null
        val faceWidth = bounds[2] - bounds[0]
        val faceHeight = bounds[3] - bounds[1]
        val eyeDistance = kotlin.math.hypot(eyes[2] - eyes[0], eyes[3] - eyes[1])
        if (faceWidth <= 0f || faceHeight <= 0f || eyeDistance <= 0f) return null
        return FaceHeadgearLayout(
            centerX = (bounds[0] + bounds[2]) * 0.5f,
            centerY = (bounds[1] + bounds[3]) * 0.5f,
            faceWidthPx = faceWidth,
            faceHeightPx = faceHeight,
            eyeCenterX = (eyes[0] + eyes[2]) * 0.5f,
            eyeCenterY = (eyes[1] + eyes[3]) * 0.5f,
            eyeDistancePx = eyeDistance,
            eyeRotationDegrees = Math.toDegrees(
                kotlin.math.atan2((eyes[3] - eyes[1]).toDouble(), (eyes[2] - eyes[0]).toDouble()),
            ).toFloat(),
            mouthCenterX = mouth[0],
            mouthCenterY = mouth[1],
        )
    }

    private fun animateParts(motion: CatMocapMotion, avatarMorphWeights: FloatArray) {
        animateElderSprite(motion, avatarMorphWeights)
    }

    private fun animateElderSprite(motion: CatMocapMotion, weights: FloatArray) {
        if (morphRenderableInstances.isEmpty()) return
        animateAvatarHead("AvatarFace", motion)
        animateAvatarHead("AvatarHair", motion)
        morphRenderableInstances.forEach { instance ->
            viewer.engine.renderableManager.setMorphWeights(instance, weights, 0)
        }
        lastMorphWeights = weights
    }

    private fun animateAvatarHead(name: String, motion: CatMocapMotion) {
        val entity = entities[name] ?: return
        val baseline = baselineTransforms[name] ?: return
        Matrix.setIdentityM(localTransform, 0)
        Matrix.translateM(
            localTransform,
            0,
            avatarEyeCenterNormalizedX,
            avatarEyeCenterNormalizedY,
            avatarEyeCenterNormalizedZ,
        )
        Matrix.rotateM(localTransform, 0, -motion.roll, 0f, 0f, 1f)
        // HeadPose already compensates the front-camera mirror in the recognizer;
        // applying another sign flip makes the avatar turn opposite to the user.
        Matrix.rotateM(localTransform, 0, motion.yaw * profile.yawFactor, 0f, 1f, 0f)
        Matrix.rotateM(localTransform, 0, -motion.pitch * profile.pitchFactor, 1f, 0f, 0f)
        Matrix.translateM(
            localTransform,
            0,
            -avatarEyeCenterNormalizedX,
            -avatarEyeCenterNormalizedY,
            -avatarEyeCenterNormalizedZ,
        )
        Matrix.multiplyMM(animatedTransform, 0, baseline, 0, localTransform, 0)
        transformManager.setTransform(transformManager.getInstance(entity), animatedTransform)
    }

    private fun correctRootToTrackedEyes(layout: FaceHeadgearLayout, aspect: Float) {
        val actual = projectAvatarEyesToViewport()
        if (actual.size < 4) return
        val actualLeft = viewportPointToWorld(actual[0], actual[1], aspect)
        val actualRight = viewportPointToWorld(actual[2], actual[3], aspect)
        val actualCenterX = (actualLeft[0] + actualRight[0]) * 0.5f
        val actualCenterY = (actualLeft[1] + actualRight[1]) * 0.5f
        val actualDx = actualRight[0] - actualLeft[0]
        val actualDy = actualRight[1] - actualLeft[1]
        val actualDistance = kotlin.math.hypot(actualDx, actualDy)
        if (actualDistance < 1e-4f) return

        val targetCenterX = (layout.eyeCenterX / width * 2f - 1f) * aspect
        val targetCenterY = 1f - layout.eyeCenterY / height * 2f
        val targetDx = kotlin.math.cos(Math.toRadians(-layout.eyeRotationDegrees.toDouble())).toFloat() *
            layout.eyeDistancePx * AVATAR_SCALE_MULTIPLIER * 2f / height
        val targetDy = kotlin.math.sin(Math.toRadians(-layout.eyeRotationDegrees.toDouble())).toFloat() *
            layout.eyeDistancePx * AVATAR_SCALE_MULTIPLIER * 2f / height
        val targetDistance = kotlin.math.hypot(targetDx, targetDy)
        val ratio = (targetDistance / actualDistance).coerceIn(0.75f, 1.35f)
        val actualAngle = kotlin.math.atan2(actualDy, actualDx)
        val targetAngle = kotlin.math.atan2(targetDy, targetDx)
        val correctionDegrees = Math.toDegrees((targetAngle - actualAngle).toDouble()).toFloat()
            .coerceIn(-25f, 25f)
        Matrix.setIdentityM(eyeCorrection, 0)
        Matrix.translateM(eyeCorrection, 0, targetCenterX, targetCenterY, 0f)
        Matrix.rotateM(eyeCorrection, 0, correctionDegrees, 0f, 0f, 1f)
        Matrix.scaleM(eyeCorrection, 0, ratio, ratio, 1f)
        Matrix.translateM(eyeCorrection, 0, -actualCenterX, -actualCenterY, 0f)
        Matrix.multiplyMM(correctedRoot, 0, eyeCorrection, 0, rootResult, 0)
        transformManager.setTransform(rootTransformInstance, correctedRoot)
    }

    private fun viewportPointToWorld(x: Float, y: Float, aspect: Float): FloatArray = floatArrayOf(
        (x / width * 2f - 1f) * aspect,
        1f - y / height * 2f,
    )

    private fun setRootTransform(
        translateX: Float = 0f,
        translateY: Float = 0f,
        translateZ: Float = 0f,
        pitch: Float = 0f,
        yaw: Float = 0f,
        roll: Float = 0f,
        scaleX: Float = 1f,
        scaleY: Float = 1f,
        scaleZ: Float = 1f,
    ) {
        writeTransform(
            rootPlacement,
            translateX,
            translateY,
            translateZ,
            pitch,
            yaw,
            roll,
            scaleX,
            scaleY,
            scaleZ,
        )
        Matrix.multiplyMM(rootResult, 0, rootPlacement, 0, rootUnitTransform, 0)
        transformManager.setTransform(rootTransformInstance, rootResult)
    }

    private fun writeTransform(
        target: FloatArray,
        translateX: Float,
        translateY: Float,
        translateZ: Float,
        pitch: Float,
        yaw: Float,
        roll: Float,
        scaleX: Float,
        scaleY: Float,
        scaleZ: Float,
    ) {
        Matrix.setIdentityM(target, 0)
        Matrix.translateM(target, 0, translateX, translateY, translateZ)
        Matrix.rotateM(target, 0, roll, 0f, 0f, 1f)
        Matrix.rotateM(target, 0, yaw, 0f, 1f, 0f)
        Matrix.rotateM(target, 0, pitch, 1f, 0f, 0f)
        Matrix.scaleM(target, 0, scaleX, scaleY, scaleZ)
    }

    private fun readAsset(name: String): ByteBuffer {
        val bytes = context.assets.open(name).use { it.readBytes() }
        return ByteBuffer.allocateDirect(bytes.size).apply {
            put(bytes)
            flip()
        }
    }

    private fun scheduleFrame() {
        if (!frameScheduled && !released) {
            frameScheduled = true
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private data class RenderFrame(
        val anchor: FaceHeadgearAnchor,
        val motion: CatMocapMotion,
        val avatarMorphWeights: FloatArray,
        val inputWidth: Int,
        val inputHeight: Int,
    )

    private companion object {
        const val AVATAR_SCALE_MULTIPLIER = 1.04f
        val MODEL_VIEWER_SWAP_CHAIN_FIELD = ModelViewer::class.java.getDeclaredField("swapChain").apply {
            isAccessible = true
        }
        const val AVATAR_DIAGNOSTIC_SCALE = 0.30f
        val AVATAR_LEFT_EYE_CENTER = floatArrayOf(-0.041510336f, 1.4432187f, 0.05492556f, 1f)
        val AVATAR_RIGHT_EYE_CENTER = floatArrayOf(0.041510336f, 1.4432187f, 0.05492556f, 1f)
        // Derived from the vertices most affected by the MouthSmall morph in
        // vrm_avatar_head_mocap24.blend, converted from Blender Z-up to glTF Y-up.
        val AVATAR_MOUTH_CENTER = floatArrayOf(-0.000000118f, 1.386871576f, 0.064485043f, 1f)
        val AVATAR_NODE_NAMES = listOf("AvatarFace", "AvatarHair")
        private var initialized = false

        @Synchronized
        fun ensureInitialized() {
            if (initialized) return
            Utils.init()
            initialized = true
        }
    }

}
