package io.github.zensu357.camswap;

public class SwitchablePreview {
    private static volatile boolean sessionInitialized = false;

    public static boolean useOriginalCamera() {
        try {
            ConfigManager cm = HookMain.getConfig();
            if (cm != null) {
                cm.forceReload();
                return cm.getBoolean(ConfigManager.KEY_USE_ORIGINAL_CAMERA, true);
            }
        } catch (Throwable ignored) {
        }
        return true;
    }
}
