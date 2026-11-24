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

/**
- Handles Camera2 session interception: replaces real camera surfaces with
- a virtual surface, then starts video playback via {@link MediaPlayerManager}.
 */
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

    // Camera2 surfaces
    Surface previewSurface;
    Surface previewSurface1;
    Surface readerSurface;
    Surface readerSurface1;

    // Tracker for Photo Fake
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

        // onOpened
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

        // onClosed
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

        // onError
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

        // onDisconnected
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
