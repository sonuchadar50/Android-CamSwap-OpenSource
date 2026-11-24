package io.github.zensu357.camswap;

import android.graphics.SurfaceTexture;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.InputConfiguration;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;

import java.util.ArrayList;
import java.io.File;
import java.util.LinkedHashSet;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;
import java.util.Map;
import android.media.ImageWriter;
import android.media.Image;
import android.media.ImageReader;
import android.media.ImageReader.OnImageAvailableListener;
import android.media.MediaMetadataRetriever;
import android.os.SystemClock;
import android.graphics.Bitmap;
import android.graphics.Rect;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;

import io.github.zensu357.camswap.utils.LogUtil;
import io.github.zensu357.camswap.utils.VideoManager;

import io.github.zensu357.camswap.api101.Api101Runtime;

public final class Camera2SessionHook {
    private static final int WHATSAPP_YUV_BRIDGE_MAX_IMAGES = 8;

    private static final long YUV_CACHE_REFRESH_GL_MS = 66L;
    private static final long YUV_CACHE_REFRESH_GL_HIRES_MS = 133L;
    private static final long YUV_CACHE_REFRESH_FALLBACK_MS = 200L;
    private static final long YUV_CACHE_REFRESH_CODEC_MS = 33L;
    private static final int YUV_HIRES_PIXEL_THRESHOLD = 1280 * 720;

    private final MediaPlayerManager playerManager;
    private volatile String currentPackageName;
    private volatile String currentActivityClassName;
    private volatile CameraCaptureSession activeCaptureSession;

    Surface previewSurface;
    Surface previewSurface1;
    Surface readerSurface;
    Surface readerSurface1;

    public final Set<Surface> trackedReaderSurfaces = Collections
            .newSetFromMap(new ConcurrentHashMap<Surface, Boolean>());
    public final Set<Long> trackedReaderNativeHandles = Collections
            .newSetFromMap(new ConcurrentHashMap<Long, Boolean>());
    public final Map<Surface, ImageWriter> imageWriterMap = new ConcurrentHashMap<>();
    public final Map<Surface, Integer> surfaceFormatMap = new ConcurrentHashMap<>();
    public final Map<Long, Integer> surfaceNativeFormatMap = new ConcurrentHashMap<>();
    public final Map<Surface, int[]> surfaceSizeMap = new ConcurrentHashMap<>();
    public final Map<Long, int[]> surfaceNativeSizeMap = new ConcurrentHashMap<>();
    private final Map<Surface, CachedYuvFrame> cachedYuvFrameMap = new ConcurrentHashMap<>();
    private final Map<Surface, FakeYuvBridge> fakeYuvBridgeMap = new ConcurrentHashMap<>();
    private final Set<Surface> internalFakeYuvReaderSurfaces = Collections
            .newSetFromMap(new ConcurrentHashMap<Surface, Boolean>());
    public final Set<Surface> pendingJpegSurfaces = Collections
            .newSetFromMap(new ConcurrentHashMap<Surface, Boolean>());
    private final Set<String> hookedStateCallbackClasses = Collections
            .newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private final Set<String> hookedDeviceClasses = Collections
            .newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private final Set<String> hookedSessionCallbackClasses = Collections
            .newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private volatile long lastWhatsAppYuvPlaceholderLogMs = 0L;
    private volatile long lastNoGlRendererLogMs = 0L;
    private volatile long lastGlBlackFallbackLogMs = 0L;
    private volatile long lastGlNullFallbackLogMs = 0L;
    private volatile long lastYuvKeepLogMs = 0L;
    private volatile boolean lastYuvFrameWasFallback = true;
    private volatile boolean lastYuvFrameWasCodec = false;
    private volatile int yuvFrameCount = 0;
    private volatile long yuvFpsWindowStartMs = 0L;
    private final Map<ImageReader, YuvCallbackPump> whatsappYuvPumpMap = new ConcurrentHashMap<>();
    private HandlerThread whatsappYuvPumpThread;
    private Handler whatsappYuvPumpHandler;

    private final Set<Surface> sessionKeptYuvSurfaces = Collections
            .newSetFromMap(new ConcurrentHashMap<Surface, Boolean>());

    public volatile Surface pendingPhotoSurface;
    private volatile boolean bypassCurrentSession = false;
    private volatile boolean isReleasing = false;

    volatile boolean pendingPlayback = false;
    private volatile boolean yuvBridgeSessionReady = false;
    public volatile MediaCodecYuvDecoder yuvDecoder;

    private Surface lastInitReader, lastInitReader1, lastInitPreview, lastInitPreview1;

    private final List<Surface> virtualSurfaces = new ArrayList<>();
    private final List<SurfaceTexture> virtualTextures = new ArrayList<>();
    private final Map<Surface, Surface> originalToVirtualMap = new ConcurrentHashMap<>();
    private HandlerThread virtualSurfaceDrainThread;
    private Handler virtualSurfaceDrainHandler;
    private boolean needRecreate;
    private final ThreadLocal<Integer> fakeYuvAcquireDepth = new ThreadLocal<Integer>() {
        @Override
        protected Integer initialValue() {
            return 0;
        }
    };
    private final ThreadLocal<Integer> internalBridgeCreationDepth = new ThreadLocal<Integer>() {
        @Override
        protected Integer initialValue() {
            return 0;
        }
    };

    public synchronized boolean isVirtualSurface(Surface surface) {
        return surface != null && virtualSurfaces.contains(surface);
    }

    public Surface getVirtualSurface() {
        return createVirtualSurface(0);
    }

    public final Map<Long, Surface> originalNativeToVirtualMap = new ConcurrentHashMap<>();

    public Surface getVirtualSurfaceFor(Surface originalSurface) {
        if (originalSurface != null) {
            Surface vs = originalToVirtualMap.get(originalSurface);
            if (vs != null && vs.isValid()) {
                return vs;
            }
            long handle = getSurfaceNativeHandle(originalSurface);
            if (handle != 0L) {
                vs = originalNativeToVirtualMap.get(handle);
                if (vs != null && vs.isValid()) {
                    return vs;
                }
            }
            if (!originalToVirtualMap.isEmpty() || !originalNativeToVirtualMap.isEmpty()) {
                return null;
            }
            if (isSurfaceTextureSurface(originalSurface)) {
                return getVirtualSurface();
            }
        }
        return null;
    }

    public synchronized void invalidateVirtualSurface() {
        needRecreate = true;
    }

    public Surface ensureVirtualSurface() {
        return createVirtualSurface(0);
    }

    CaptureRequest.Builder captureBuilder;
    SessionConfiguration fakeSessionConfig;
    SessionConfiguration realSessionConfig;
    OutputConfiguration outputConfig;
    boolean isFirstHookBuild = true;

    public Camera2SessionHook(MediaPlayerManager playerManager) {
        this.playerManager = playerManager;
    }

    public void setCurrentPackageName(String packageName) {
        currentPackageName = packageName;
        playerManager.setPackageName(packageName);
    }

    public void setCurrentActivityClassName(String activityClassName) {
        currentActivityClassName = activityClassName;
    }

    public static boolean isWhatsAppPackage(String packageName) {
        return packageName != null && packageName.toLowerCase(Locale.ROOT).contains("whatsapp");
    }

    public static boolean isLinePackage(String packageName) {
        if (packageName == null || packageName.isEmpty()) {
            return false;
        }
        String normalized = packageName.toLowerCase(Locale.ROOT);
        return normalized.equals("jp.naver.line.android")
                || normalized.startsWith("jp.naver.line.android:");
    }

    public static boolean shouldUseFakeYuvBridgeForPackage(String packageName) {
        return isWhatsAppPackage(packageName) || isLinePackage(packageName);
    }

    private boolean isVoipActivity() {
        if (currentActivityClassName == null) {
            return false;
        }
        String lower = currentActivityClassName.toLowerCase(Locale.ROOT);
        return lower.contains("voipserviceactivity")
                || lower.contains("freecall")
                || lower.contains("groupcall")
                || lower.contains("voip2");
    }

    private boolean isLineScannerActivity() {
        return currentActivityClassName != null
                && currentActivityClassName.contains("CameraScannerActivity");
    }

    private boolean shouldUseYuvPumpForSurface(Surface surface) {
        if (surface == null || !isYuvReaderSurface(surface)) {
            return false;
        }
        if (shouldUseFakeYuvBridgeForPackage(getCurrentPackageName())) {
            return true;
        }
        return isCurrentSessionYuvReaderSurface(surface);
    }

    public String getCurrentPackageName() {
        if (currentPackageName != null && !currentPackageName.isEmpty()) {
            return currentPackageName;
        }
        return HookMain.toast_content != null ? HookMain.toast_content.getPackageName() : null;
    }

    public boolean isCurrentSessionBypassed() {
        return bypassCurrentSession;
    }

    private void setBypassCurrentSession(boolean bypass) {
        bypassCurrentSession = bypass;
    }

    public void hookStateCallback(Class<?> hookedClass) {
        if (hookedClass == null) {
            return;
        }
        if (!hookedStateCallbackClasses.add(hookedClass.getName())) {
            return;
        }

        try {
            Method m = resolveMethodOnClass(hookedClass, "onOpened", CameraDevice.class);
            Api101Runtime.requireModule().hook(m).intercept(chain -> {
                Object[] args = toArgs(chain.getArgs());
                try {
                    isReleasing = false;
                    needRecreate = true;
                    createVirtualSurface();
                    playerManager.releaseCamera2Resources();
                    releaseImageWriters(false);
                    previewSurface1 = null;
                    readerSurface1 = null;
                    readerSurface = null;
                    previewSurface = null;
                    pendingPlayback = false;
                    captureBuilder = null;
                    setBypassCurrentSession(false);
                    yuvBridgeSessionReady = false;
                    isFirstHookBuild = true;
                    lastInitReader = null;
                    lastInitReader1 = null;
                    lastInitPreview = null;
                    lastInitPreview1 = null;
                    LogUtil.log("【CS】打开相机C2");

                    File file = HookGuards.getCurrentVideoFile();
                    if (!file.exists() && !VideoManager.isUsingProviderBackedVideo()) {
                        VideoManager.checkProviderAvailability();
                        if (VideoManager.isProviderAvailable()) {
                            VideoManager.getConfig().forceReload();
                            VideoManager.updateVideoPath(false);
                            LogUtil.log("【CS】onOpened 延迟获取 Provider 成功");
                        }
                    }

                    hookAllCreateSessionVariants(args[0].getClass());
                } catch (Throwable t) {
                    LogUtil.log("【CS】onOpened before 异常: " + t);
                }
                return chain.proceed(args);
            });
        } catch (Throwable t) {
            LogUtil.log("【CS】Hook onOpened 失败: " + t);
        }

        try {
            Method m = resolveMethodOnClass(hookedClass, "onClosed", CameraDevice.class);
            Api101Runtime.requireModule().hook(m).intercept(chain -> {
                Object[] args = toArgs(chain.getArgs());
                try {
                    LogUtil.log("【CS】相机关闭 onClosed，释放播放器资源");
                    activeCaptureSession = null;
                    setBypassCurrentSession(false);
                    yuvBridgeSessionReady = false;
                    stopAllWhatsAppYuvPumps();
                    playerManager.releaseCamera2Resources();
                    releaseImageWriters();
                } catch (Throwable t) {
                    LogUtil.log("【CS】onClosed before 异常: " + t);
                }
                return chain.proceed(args);
            });
        } catch (Throwable t) {
            LogUtil.log("【CS】Hook onClosed 失败: " + t);
        }

        try {
            Method m = resolveMethodOnClass(hookedClass, "onError", CameraDevice.class, int.class);
            Api101Runtime.requireModule().hook(m).intercept(chain -> {
                Object[] args = toArgs(chain.getArgs());
                try {
                    LogUtil.log("【CS】相机错误onerror：" + (int) args[1]);
                } catch (Throwable t) {
                    LogUtil.log("【CS】onError before 异常: " + t);
                }
                return chain.proceed(args);
            });
        } catch (Throwable t) {
            LogUtil.log("【CS】Hook onError 失败: " + t);
        }

        try {
            Method m = resolveMethodOnClass(hookedClass, "onDisconnected", CameraDevice.class);
            Api101Runtime.requireModule().hook(m).intercept(chain -> {
                Object[] args = toArgs(chain.getArgs());
                try {
                    LogUtil.log("【CS】相机断开 onDisconnected，释放播放器资源");
                    activeCaptureSession = null;
                    setBypassCurrentSession(false);
                    yuvBridgeSessionReady = false;
                    stopAllWhatsAppYuvPumps();
                    playerManager.releaseCamera2Resources();
                    releaseImageWriters();
                } catch (Throwable t) {
                    LogUtil.log("【CS】onDisconnected before 异常: " + t);
                }
                return chain.proceed(args);
            });
        } catch (Throwable t) {
            LogUtil.log("【CS】Hook onDisconnected 失败: " + t);
        }
    }

    public void startPlayback() {
        if (readerSurface == null && readerSurface1 == null
                && previewSurface == null && previewSurface1 == null) {
            LogUtil.w("【CS】【SessionHook】【延迟播放】所有播放目标 Surface 均为 null，等待 addTarget 分发..."
                    + " [preview=" + previewSurface + ", preview1=" + previewSurface1
                    + ", reader=" + readerSurface + ", reader1=" + readerSurface1 + "]");
            pendingPlayback = true;
            return;
        }
        if (readerSurface == lastInitReader && readerSurface1 == lastInitReader1
                && previewSurface == lastInitPreview && previewSurface1 == lastInitPreview1) {
            LogUtil.log("【CS】【SessionHook】跳过重复 startPlayback：所有 Surface 均未发生改变");
            pendingPlayback = false;
            return;
        }

        LogUtil.log("【CS】【SessionHook】[触发播放] Camera2 播放器装载 ->"
                + " preview: " + previewSurface + " (isValid=" + (previewSurface != null && previewSurface.isValid()) + ")"
                + ", preview1: " + previewSurface1 + " (isValid=" + (previewSurface1 != null && previewSurface1.isValid()) + ")"
                + ", reader: " + readerSurface + " (isValid=" + (readerSurface != null && readerSurface.isValid()) + ")"
                + ", reader1: " + readerSurface1 + " (isValid=" + (readerSurface1 != null && readerSurface1.isValid()) + ")");

        lastInitReader = readerSurface;
        lastInitReader1 = readerSurface1;
        lastInitPreview = previewSurface;
        lastInitPreview1 = previewSurface1;
        pendingPlayback = false;
        playerManager.initCamera2Players(readerSurface, readerSurface1,
                previewSurface, previewSurface1);
        markYuvBridgeSessionReadyIfPossible();
        startContinuousYuvPumper();

        int userRotation = VideoManager.getConfig().getInt(ConfigManager.KEY_VIDEO_ROTATION_OFFSET, 0);
        MediaPlayerManager pm = HookMain.playerManager;
        if (pm.c2_renderer != null) pm.c2_renderer.setRotation(userRotation);
        if (pm.c2_renderer_1 != null) pm.c2_renderer_1.setRotation(userRotation);
        LogUtil.log("【CS】【SessionHook】渲染器旋转角度已应用: " + userRotation + "°");
    }

    public static long getSurfaceNativeHandle(Surface surface) {
        if (surface == null) return 0L;
        try {
            java.lang.reflect.Field field = Surface.class.getDeclaredField("mNativeObject");
            field.setAccessible(true);
            return field.getLong(surface);
        } catch (Throwable t) {
            try {
                String str = surface.toString();
                int idx = str.indexOf("mNativeObject=");
                if (idx != -1) {
                    int end = str.indexOf(")", idx);
                    if (end != -1) {
                        return Long.parseLong(str.substring(idx + 14, end).trim());
                    }
                }
            } catch (Throwable ignored) {}
        }
        return 0L;
    }

    public void registerImageReaderSurface(Surface surface, int format, int width, int height) {
        if (surface == null) {
            return;
        }
        trackedReaderSurfaces.add(surface);
        surfaceFormatMap.put(surface, format);
        surfaceSizeMap.put(surface, new int[] { width, height });

        long handle = getSurfaceNativeHandle(surface);
        if (handle != 0L) {
            trackedReaderNativeHandles.add(handle);
            surfaceNativeFormatMap.put(handle, format);
            surfaceNativeSizeMap.put(handle, new int[] { width, height });
        }
    }

    public boolean isTrackedReaderSurface(Surface surface) {
        if (surface == null) return false;
        if (trackedReaderSurfaces.contains(surface)) return true;
        long handle = getSurfaceNativeHandle(surface);
        return handle != 0L && trackedReaderNativeHandles.contains(handle);
    }

    public boolean isJpegReaderSurface(Surface surface) {
        if (surface == null) return false;
        Integer format = surfaceFormatMap.get(surface);
        if (format == null) {
            long handle = getSurfaceNativeHandle(surface);
            if (handle != 0L) format = surfaceNativeFormatMap.get(handle);
        }
        return format != null && format == ImageFormat.JPEG;
    }

    public boolean isYuvReaderSurface(Surface surface) {
        if (surface == null) return false;
        Integer format = surfaceFormatMap.get(surface);
        if (format == null) {
            long handle = getSurfaceNativeHandle(surface);
            if (handle != 0L) format = surfaceNativeFormatMap.get(handle);
        }
        return format != null && format == ImageFormat.YUV_420_888;
    }

    public boolean shouldKeepRealReaderSurface(Surface surface) {
        return isJpegReaderSurface(surface);
    }

    public boolean shouldKeepRealReaderSurfaceForPackage(Surface surface, String packageName) {
        if (!isTrackedReaderSurface(surface)) {
            return false;
        }
        return isJpegReaderSurface(surface);
    }

    public boolean shouldKeepRealReaderSurfaceForCurrentPackage(Surface surface) {
        return shouldKeepRealReaderSurfaceForPackage(surface, getCurrentPackageName());
    }

    private boolean shouldKeepYuvReaderSurfaceForPackage(Surface surface, String packageName) {
        if (!shouldUseFakeYuvBridgeForPackage(packageName)) {
            return false;
        }
        return isYuvReaderSurface(surface)
                && !isInternalFakeYuvReaderSurface(surface);
    }

    private boolean isCurrentSessionYuvReaderSurface(Surface surface) {
        if (surface == null) {
            return false;
        }
        return surface.equals(readerSurface) || surface.equals(readerSurface1)
                || sessionKeptYuvSurfaces.contains(surface);
    }

    private boolean shouldDriveYuvBridgeNow() {
        return !isReleasing && yuvBridgeSessionReady;
    }

    public boolean shouldKeepYuvReaderSurfaceForCurrentPackage(Surface surface) {
        boolean keep = shouldKeepYuvReaderSurfaceForPackage(surface, getCurrentPackageName());
        if (surface != null && isYuvReaderSurface(surface)) {
            maybeLogYuvKeepDecision(surface, keep);
        }
        return keep;
    }

    private void maybeLogYuvKeepDecision(Surface surface, boolean keep) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastYuvKeepLogMs < 5000L) {
            return;
        }
        lastYuvKeepLogMs = now;
        LogUtil.log("【CS】YUV keep: " + keep + " surface=" + surface);
    }

    public boolean shouldSkipImageReaderTracking() {
        return internalBridgeCreationDepth.get() > 0;
    }

    public boolean shouldBypassYuvAcquireHook(Surface surface) {
        return fakeYuvAcquireDepth.get() > 0 || isInternalFakeYuvReaderSurface(surface);
    }

    public boolean isInternalFakeYuvReaderSurface(Surface surface) {
        return surface != null && internalFakeYuvReaderSurfaces.contains(surface);
    }

    private void enterInternalBridgeCreation() {
        internalBridgeCreationDepth.set(internalBridgeCreationDepth.get() + 1);
    }

    private void exitInternalBridgeCreation() {
        int depth = internalBridgeCreationDepth.get();
        if (depth <= 1) {
            internalBridgeCreationDepth.remove();
        } else {
            internalBridgeCreationDepth.set(depth - 1);
        }
    }

    private void enterFakeYuvAcquire() {
        fakeYuvAcquireDepth.set(fakeYuvAcquireDepth.get() + 1);
    }

    private void exitFakeYuvAcquire() {
        int depth = fakeYuvAcquireDepth.get();
        if (depth <= 1) {
            fakeYuvAcquireDepth.remove();
        } else {
            fakeYuvAcquireDepth.set(depth - 1);
        }
    }

    public boolean shouldUseReaderPlaybackSurfaceForPackage(String packageName) {
        return !shouldUseFakeYuvBridgeForPackage(packageName);
    }

    private Surface getOutputSurface(Object output) {
        if (output instanceof Surface) {
            return (Surface) output;
        }
        if (output instanceof OutputConfiguration) {
            return ((OutputConfiguration) output).getSurface();
        }
        return null;
    }

    private boolean shouldBypassWhatsAppYuvSession(List<?> outputs, String packageName) {
        return false;
    }

    private void enableSessionBypass(String reason) {
        if (!bypassCurrentSession) {
            LogUtil.log("【CS】启用 YUV 会话旁路: " + reason);
        }
        setBypassCurrentSession(true);
        pendingPlayback = false;
        previewSurface = null;
        previewSurface1 = null;
        readerSurface = null;
        readerSurface1 = null;
        sessionKeptYuvSurfaces.clear();
        playerManager.releaseCamera2Resources();
    }

    private void disableSessionBypass() {
        setBypassCurrentSession(false);
    }

    public void rememberPreviewSurface(Surface surface) {
        if (surface == null || isTrackedReaderSurface(surface)) {
            return;
        }
        boolean isNew = false;
        if (previewSurface == null) {
            previewSurface = surface;
            isNew = true;
        } else if (!previewSurface.equals(surface) && previewSurface1 == null) {
            previewSurface1 = surface;
            isNew = true;
        }
        if (isNew || pendingPlayback) {
            LogUtil.log("【CS】addTarget 触发播放 (preview: " + surface + ")");
            startPlayback();
        }
    }

    public void rememberReaderPlaybackSurface(Surface surface) {
        if (!shouldUseReaderPlaybackSurfaceForPackage(getCurrentPackageName())) {
            return;
        }
        if (surface == null || shouldKeepRealReaderSurface(surface)) {
            return;
        }
        if (isYuvReaderSurface(surface) && !shouldUseFakeYuvBridgeForPackage(getCurrentPackageName())) {
            LogUtil.log("【CS】跳过 YUV reader surface 作为播放目标 (无 YUV 兼容): " + surface);
            return;
        }
        boolean isNew = false;
        if (readerSurface == null) {
            readerSurface = surface;
            isNew = true;
        } else if (!readerSurface.equals(surface) && readerSurface1 == null) {
            readerSurface1 = surface;
            isNew = true;
        }
        if (isNew || pendingPlayback) {
            LogUtil.log("【CS】addTarget 触发播放 (reader: " + surface + ")");
            startPlayback();
        }
    }

    public void onTargetRemoved(Surface surface) {
        if (surface == null) {
            return;
        }
        pendingJpegSurfaces.remove(surface);
        if (surface.equals(previewSurface)) {
            previewSurface = null;
        }
        if (surface.equals(previewSurface1)) {
            previewSurface1 = null;
        }
        if (surface.equals(readerSurface)) {
            readerSurface = null;
        }
        if (surface.equals(readerSurface1)) {
            readerSurface1 = null;
        }
    }

    public void markPendingJpegCapture(Surface surface) {
        if (shouldKeepRealReaderSurface(surface)) {
            pendingJpegSurfaces.add(surface);
            pendingPhotoSurface = surface;
        }
    }

    private synchronized void ensureVirtualDrainThread() {
        if (virtualSurfaceDrainThread == null || !virtualSurfaceDrainThread.isAlive()) {
            virtualSurfaceDrainThread = new HandlerThread("CS-VirtualDrain");
            virtualSurfaceDrainThread.start();
            virtualSurfaceDrainHandler = new Handler(virtualSurfaceDrainThread.getLooper());
        }
    }

    private synchronized void releaseVirtualSurfaces() {
        for (Surface vs : virtualSurfaces) {
            if (vs != null) {
                try {
                    vs.release();
                } catch (Exception ignored) {
                }
            }
        }
        virtualSurfaces.clear();
        for (SurfaceTexture vt : virtualTextures) {
            if (vt != null) {
                try {
                    vt.release();
                } catch (Exception ignored) {
                }
            }
        }
        virtualTextures.clear();
        originalToVirtualMap.clear();
        originalNativeToVirtualMap.clear();
    }

    private synchronized Surface createVirtualSurface(int index) {
        if (needRecreate) {
            releaseVirtualSurfaces();
            needRecreate = false;
        }
        ensureVirtualDrainThread();
        while (virtualSurfaces.size() <= index) {
            int texIndex = 15 + virtualSurfaces.size();
            SurfaceTexture vt = new SurfaceTexture(texIndex);
            vt.setDefaultBufferSize(1920, 1080);
            vt.setOnFrameAvailableListener(st -> {
                try {
                    st.updateTexImage();
                } catch (Exception ignored) {
                }
            }, virtualSurfaceDrainHandler);
            Surface vs = new Surface(vt);
            virtualTextures.add(vt);
            virtualSurfaces.add(vs);
            LogUtil.log("【CS】创建虚拟Surface[" + (virtualSurfaces.size() - 1) + "]: " + vs);
        }
        return virtualSurfaces.get(index);
    }

    private Surface createVirtualSurface() {
        return createVirtualSurface(0);
    }

    private List<Surface> rewriteSessionSurfaces(List<?> outputs) {
        return rewriteSessionSurfaces(outputs, getCurrentPackageName());
    }

    private List<Surface> rewriteSessionSurfaces(List<?> outputs, String packageName) {
        LinkedHashSet<Surface> rewritten = new LinkedHashSet<>();
        sessionKeptYuvSurfaces.clear();
        int virtualIndex = 0;
        if (outputs != null) {
            for (Object output : outputs) {
                if (output instanceof Surface) {
                    Surface surface = (Surface) output;
                    boolean isJpeg = isJpegReaderSurface(surface);
                    boolean isYuv = isYuvReaderSurface(surface) || shouldKeepYuvReaderSurfaceForPackage(surface, packageName);
                    boolean isPreview = (virtualIndex == 0 && !isJpeg);

                    if (isPreview) {
                        Surface vSurface = createVirtualSurface(virtualIndex++);
                        originalToVirtualMap.put(surface, vSurface);
                        long handle = getSurfaceNativeHandle(surface);
                        if (handle != 0L) originalNativeToVirtualMap.put(handle, vSurface);
                        rememberPreviewSurface(surface);
                        rewritten.add(vSurface);
                        LogUtil.log("【CS】SessionSurfaces 替换主预览 Surface -> virtual[0]: " + surface);
                    } else if (isYuv) {
                        Surface vSurface = createVirtualSurface(virtualIndex++);
                        originalToVirtualMap.put(surface, vSurface);
                        long handle = getSurfaceNativeHandle(surface);
                        if (handle != 0L) originalNativeToVirtualMap.put(handle, vSurface);
                        rememberReaderPlaybackSurface(surface);
                        sessionKeptYuvSurfaces.add(surface);
                        rewritten.add(vSurface);
                        LogUtil.log("【CS】SessionSurfaces [方案一替身置换] 替换 YUV Reader Surface -> virtual[" + (virtualIndex - 1) + "]: " + surface);
                    } else {
                        rewritten.add(surface);
                        LogUtil.log("【CS】SessionSurfaces 保留真实辅助/JPEG Surface: " + surface);
                    }
                }
            }
        }
        if (rewritten.isEmpty()) {
            rewritten.add(createVirtualSurface(0));
        }
        return new ArrayList<>(rewritten);
    }

    public boolean isSurfaceTextureSurface(Surface surface) {
        if (surface == null) return false;
        String s = surface.toString();
        return s.contains("SurfaceTexture") || s.contains("SurfaceView") || s.contains("SurfaceHolder");
    }

    private List<OutputConfiguration> rewriteOutputConfigurations(List<?> outputs) {
        return rewriteOutputConfigurations(outputs, getCurrentPackageName());
    }

    private List<OutputConfiguration> rewriteOutputConfigurations(List<?> outputs, String packageName) {
        List<OutputConfiguration> rewritten = new ArrayList<>();
        sessionKeptYuvSurfaces.clear();
        int virtualIndex = 0;
        if (outputs != null) {
            for (Object output : outputs) {
                if (!(output instanceof OutputConfiguration)) {
                    continue;
                }
                OutputConfiguration config = (OutputConfiguration) output;
                Surface originalSurface = config.getSurface();
                boolean isJpeg = isJpegReaderSurface(originalSurface);
                boolean isYuv = isYuvReaderSurface(originalSurface) || shouldKeepYuvReaderSurfaceForPackage(originalSurface, packageName);
                boolean isPreview = (virtualIndex == 0 && !isJpeg);

                if (isPreview) {
                    Surface vSurface = createVirtualSurface(virtualIndex++);
                    if (originalSurface != null) {
                        originalToVirtualMap.put(originalSurface, vSurface);
                        long handle = getSurfaceNativeHandle(originalSurface);
                        if (handle != 0L) originalNativeToVirtualMap.put(handle, vSurface);
                        rememberPreviewSurface(originalSurface);
                    }
                    rewritten.add(createOutputConfiguration(config, vSurface, packageName));
                    LogUtil.log("【CS】OutputConfig 替换主预览 Surface -> virtual[0]: " + originalSurface);
                } else if (isYuv) {
                    Surface vSurface = createVirtualSurface(virtualIndex++);
                    if (originalSurface != null) {
                        originalToVirtualMap.put(originalSurface, vSurface);
                        long handle = getSurfaceNativeHandle(originalSurface);
                        if (handle != 0L) originalNativeToVirtualMap.put(handle, vSurface);
                        rememberReaderPlaybackSurface(originalSurface);
                        sessionKeptYuvSurfaces.add(originalSurface);
                    }
                    rewritten.add(createOutputConfiguration(config, vSurface, packageName));
                    LogUtil.log("【CS】OutputConfig [方案一替身置换] 替换 YUV Reader Surface -> virtual[" + (virtualIndex - 1) + "]: " + originalSurface);
                } else {
                    rewritten.add(createOutputConfiguration(config, originalSurface, packageName));
                    LogUtil.log("【CS】OutputConfig 保留真实辅助/JPEG Surface: " + originalSurface);
                }
            }
        }
        if (rewritten.isEmpty()) {
            rewritten.add(new OutputConfiguration(createVirtualSurface(0)));
        }
        return rewritten;
    }

    private OutputConfiguration createOutputConfiguration(OutputConfiguration original, Surface surface,
            String packageName) {
        OutputConfiguration rewritten = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                rewritten = new OutputConfiguration(original.getSurfaceGroupId(), surface);
            } catch (Throwable ignored) {
            }
        }
        if (rewritten == null) {
            rewritten = new OutputConfiguration(surface);
        }
        copyOutputConfigurationMetadata(original, rewritten);
        copySharedSurfaces(original, rewritten, surface, packageName);
        return rewritten;
    }

    private void copyOutputConfigurationMetadata(OutputConfiguration original,
            OutputConfiguration rewritten) {
        copyOutputConfigurationProperty(original, rewritten,
                "getPhysicalCameraId", "setPhysicalCameraId");
        copyOutputConfigurationProperty(original, rewritten,
                "getStreamUseCase", "setStreamUseCase");
        copyOutputConfigurationProperty(original, rewritten,
                "getDynamicRangeProfile", "setDynamicRangeProfile");
        copyOutputConfigurationProperty(original, rewritten,
                "getTimestampBase", "setTimestampBase");
        copyOutputConfigurationProperty(original, rewritten,
                "getMirrorMode", "setMirrorMode");
        copyOutputConfigurationProperty(original, rewritten,
                "getReadoutTimestampEnabled", "setReadoutTimestampEnabled");
        copyOutputConfigurationProperty(original, rewritten,
                "getColorSpace", "setColorSpace");
        copyOutputConfigurationSensorPixelModes(original, rewritten);
    }

    private void copyOutputConfigurationProperty(OutputConfiguration original,
            OutputConfiguration rewritten, String getterName, String setterName) {
        try {
            Object value = invokeNoArgMethod(original, getterName);
            if (value != null) {
                invokeCompatibleSingleArgMethod(rewritten, setterName, value);
            }
        } catch (Throwable ignored) {
        }
    }

    private void copyOutputConfigurationSensorPixelModes(OutputConfiguration original,
            OutputConfiguration rewritten) {
        try {
            Object value = invokeNoArgMethod(original, "getSensorPixelModesUsed");
            if (value instanceof int[]) {
                for (int pixelMode : (int[]) value) {
                    invokeCompatibleSingleArgMethod(rewritten, "addSensorPixelModeUsed", pixelMode);
                }
            } else if (value instanceof Iterable) {
                for (Object pixelMode : (Iterable<?>) value) {
                    if (pixelMode != null) {
                        invokeCompatibleSingleArgMethod(rewritten, "addSensorPixelModeUsed", pixelMode);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private void copySharedSurfaces(OutputConfiguration original, OutputConfiguration rewritten,
            Surface rewrittenSurface, String packageName) {
        try {
            @SuppressWarnings("unchecked")
            List<Surface> originalSurfaces = (List<Surface>) invokeNoArgMethod(original, "getSurfaces");
            if (originalSurfaces == null || originalSurfaces.size() <= 1) {
                return;
            }
            invokeNoArgMethod(rewritten, "enableSurfaceSharing");
            Surface originalPrimarySurface = original.getSurface();
            boolean keptOriginalPrimary = rewrittenSurface != null && rewrittenSurface.equals(originalPrimarySurface);
            for (Surface extraSurface : originalSurfaces) {
                if (extraSurface == null || extraSurface.equals(originalPrimarySurface)) {
                    continue;
                }
                boolean keepExtraSurface = shouldKeepRealReaderSurfaceForPackage(extraSurface, packageName)
                        || shouldKeepYuvReaderSurfaceForPackage(extraSurface, packageName);
                if (!keepExtraSurface && !keptOriginalPrimary) {
                    continue;
                }
                invokeCompatibleSingleArgMethod(rewritten, "addSurface", extraSurface);
                if (shouldKeepYuvReaderSurfaceForPackage(extraSurface, packageName)) {
                    sessionKeptYuvSurfaces.add(extraSurface);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private SessionConfiguration rewriteSessionConfiguration(SessionConfiguration sessionConfiguration) {
        String packageName = getCurrentPackageName();
        List<OutputConfiguration> outputs = rewriteOutputConfigurations(sessionConfiguration.getOutputConfigurations(),
                packageName);
        SessionConfiguration rewritten = new SessionConfiguration(
                sessionConfiguration.getSessionType(),
                outputs,
                sessionConfiguration.getExecutor(),
                sessionConfiguration.getStateCallback());
        try {
            InputConfiguration inputConfiguration = sessionConfiguration.getInputConfiguration();
            if (inputConfiguration != null) {
                rewritten.setInputConfiguration(inputConfiguration);
            }
        } catch (Throwable ignored) {
        }
        try {
            CaptureRequest sessionParameters = sessionConfiguration.getSessionParameters();
            if (sessionParameters != null) {
                rewritten.setSessionParameters(sessionParameters);
            }
        } catch (Throwable ignored) {
        }
        return rewritten;
    }
