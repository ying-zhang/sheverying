package moe.shizuku.manager;

import moe.shizuku.manager.utils.MultiLocaleEntity;

public class Helps {

    public static final MultiLocaleEntity ADB = new MultiLocaleEntity();
    public static final MultiLocaleEntity SUI = new MultiLocaleEntity();
    public static final MultiLocaleEntity RISH = new MultiLocaleEntity();
    public static final MultiLocaleEntity ADB_PERMISSION = new MultiLocaleEntity();

    static {
        ADB.put("zh-CN", "https://shizuku.rikka.app/zh-hans/guide/setup/");
        ADB.put("en", "https://shizuku.rikka.app/guide/setup/");

        ADB_PERMISSION.put("zh-CN", "https://shizuku.rikka.app/zh-hans/guide/setup/#%E9%80%9A%E8%BF%87%E6%97%A0%E7%BA%BF%E8%B0%83%E8%AF%95%E5%90%AF%E5%8A%A8-%E9%80%9A%E8%BF%87%E8%BF%9E%E6%8E%A5%E7%94%B5%E8%84%91%E5%90%AF%E5%8A%A8-adb-%E6%9D%83%E9%99%90%E5%8F%97%E9%99%90");
        ADB_PERMISSION.put("en", "https://shizuku.rikka.app/guide/setup/#start-via-wireless-debugging-start-by-connecting-to-a-computer-the-permission-of-adb-is-limited");

        SUI.put("en", "https://github.com/RikkaApps/Sui");

        RISH.put("en", "https://github.com/RikkaApps/Shizuku-API/tree/master/rish");
    }
}
