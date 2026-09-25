package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.local.dao.VoiceDao
import com.example.data.local.model.CustomVoiceRule
import com.example.data.local.model.VoiceCommandLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Database(
    entities = [VoiceCommandLog::class, CustomVoiceRule::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun voiceDao(): VoiceDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "zinaida_voice.db"
                ).addCallback(object : Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        super.onCreate(db)
                        // Prepopulate with a few helpful offline custom rules
                        CoroutineScope(Dispatchers.IO).launch {
                            val dao = getInstance(context).voiceDao()
                            dao.insertRule(
                                CustomVoiceRule(
                                    triggerPhrase = "кто тебя создал",
                                    spokenResponse = "Я голосовой ассистент Зинаида, создана для работы автономно и без интернета."
                                )
                            )
                            dao.insertRule(
                                CustomVoiceRule(
                                    triggerPhrase = "где мои данные",
                                    spokenResponse = "Все ваши данные хранятся только в памяти этого устройства и никуда не передаются."
                                )
                            )
                        }
                    }
                }).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
