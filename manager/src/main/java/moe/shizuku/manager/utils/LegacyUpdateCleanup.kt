package moe.shizuku.manager.utils

import android.content.Context
import android.os.UserManager
import androidx.work.WorkManager

/** Cancel old periodic checks after upgrade, including processes created during Direct Boot. */
object LegacyUpdateCleanup {
    fun cancel(context: Context) {
        if (context.getSystemService(UserManager::class.java)?.isUserUnlocked != true) return
        runCatching { WorkManager.getInstance(context).cancelUniqueWork("shevery_auto_update_check") }
            .onFailure { android.util.Log.w("LegacyUpdateCleanup", "Could not cancel retired update work", it) }
    }
}
