package io.github.zensu357.camswap;

public class SwitchablePreview {
    public static boolean useOriginalCamera() {
        try {
            ConfigManager cm = HookMain.configManager;
            if (cm != null) {
                cm.reload();
                return cm.getBoolean(ConfigManager.KEY_USE_ORIGINAL_CAMERA, true);
            }
        } catch (Throwable ignored) {
        }
        return true;
    }
}
