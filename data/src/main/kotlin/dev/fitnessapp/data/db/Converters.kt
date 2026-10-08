package dev.fitnessapp.data.db

import androidx.room.TypeConverter

/** Room converters: `text[]` / `uuid[]` columns are stored as JSON arrays (ANDROID.md 8.2). */
class Converters {
    @TypeConverter
    fun fromList(list: List<String>): String = StringListJson.encode(list)

    @TypeConverter
    fun toList(text: String): List<String> = StringListJson.decode(text)
}
