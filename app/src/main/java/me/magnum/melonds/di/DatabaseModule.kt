package me.magnum.melonds.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import me.magnum.melonds.database.MelonDatabase
import me.magnum.melonds.database.callback.CustomCheatCreationCallback
import me.magnum.melonds.database.daos.RetroAchievementsDao
import me.magnum.melonds.database.migrations.Migration1to2
import me.magnum.melonds.database.migrations.Migration4to5
import me.magnum.melonds.database.migrations.Migration5to6
import me.magnum.melonds.database.migrations.Migration7to8
import me.magnum.melonds.impl.retroachievements.NoCacheRetroAchievementsDao
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @IntoSet
    fun provideCustomCheatDatabaseCreationCallback(): RoomDatabase.Callback {
        return CustomCheatCreationCallback()
    }

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context, callbacks: Set<@JvmSuppressWildcards RoomDatabase.Callback>): MelonDatabase {
        seedRetroAchievementsDatabase(context)
        return Room.databaseBuilder(context, MelonDatabase::class.java, "melon-database")
            .apply {
                callbacks.forEach {
                    addCallback(it)
                }
            }
            .addMigrations(Migration1to2(), Migration4to5(), Migration5to6(), Migration7to8())
            .build()
    }

    @Provides
    fun provideRAAchievementsDao(database: MelonDatabase): RetroAchievementsDao {
        return NoCacheRetroAchievementsDao(database.achievementsDao())
    }

    /**
     * 方案B：首次启动（或成就库缺失时）把 assets 内置的成就库部署到 Room 数据目录，
     * 并冻结 hash 库刷新时间戳，防止启动联网刷新清掉汉化 hash 行。
     */
    @JvmStatic
    fun seedRetroAchievementsDatabase(context: Context) {
        val dbPath = context.getDatabasePath("melon-database")
        try {
            if (!dbPath.exists()) {
                dbPath.parentFile?.mkdirs()
                context.assets.open("melon_ra.db").use { input ->
                    dbPath.outputStream().use { output -> input.copyTo(output) }
                }
                context.getDatabasePath("melon-database-wal").delete()
                context.getDatabasePath("melon-database-shm").delete()
                android.util.Log.i("MelonDB", "内置成就库已部署: ${dbPath.absolutePath}")
            }
        } catch (e: Exception) {
            android.util.Log.w("MelonDB", "内置成就库部署失败（将走官方空库）", e)
        }
        // 无条件冻结 hash 库刷新时间戳（幂等；只要已过期就写未来 1 年）
        try {
            val prefs = android.preference.PreferenceManager.getDefaultSharedPreferences(context)
            val key = "ra_hash_library_last_updated"
            val now = System.currentTimeMillis()
            if (prefs.getLong(key, 0) < now) {
                prefs.edit().putLong(key, now + 365L * 24 * 3600 * 1000).apply()
                android.util.Log.i("MelonDB", "hash 库刷新时间戳已冻结")
            }
        } catch (e: Exception) {
            android.util.Log.w("MelonDB", "hash 库刷新时间戳冻结失败", e)
        }
        // 冻结成就集/用户数据刷新时间戳（官方 7 天/1 天过期会联网覆盖本地数据，
        // 方案B 要求纯离线走本地库：把 DB 内 ra_game_set_metadata 三个时间戳写为未来 1 年）
        try {
            val future = System.currentTimeMillis() + 365L * 24 * 3600 * 1000
            val sq = android.database.sqlite.SQLiteDatabase.openDatabase(
                dbPath.absolutePath, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE,
            )
            try {
                sq.execSQL(
                    "UPDATE ra_game_set_metadata SET last_achievement_set_updated = ?, last_user_data_updated = ?, last_hardcore_user_data_updated = ?",
                    arrayOf(future, future, future),
                )
            } finally {
                sq.close()
            }
            android.util.Log.i("MelonDB", "成就集元数据刷新时间戳已冻结")
        } catch (e: Exception) {
            android.util.Log.w("MelonDB", "成就集元数据冻结失败（表缺失则忽略）", e)
        }
    }
}