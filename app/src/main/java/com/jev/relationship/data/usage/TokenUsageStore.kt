package com.jev.relationship.data.usage

import android.content.Context
import android.util.Log
import androidx.room.*
import com.google.gson.JsonElement
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

enum class UsageSource(val label: String) { JEV("Jev"), UNDERSTANDING("理解模型") }

@Entity(tableName = "token_usage", indices = [Index("timestampMs")])
data class TokenUsageEvent(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val timestampMs: Long = System.currentTimeMillis(),
    val source: String,
    val model: String,
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val totalTokens: Long? = null,
    val succeeded: Boolean,
)

data class ReportedTokens(val input: Long?, val output: Long?, val total: Long?) {
    companion object {
        fun parse(value: JsonElement?): ReportedTokens? {
            if (value?.isJsonObject != true) return null
            fun count(vararg names: String): Long? = names.firstNotNullOfOrNull { name ->
                runCatching { value.asJsonObject.get(name)?.asBigDecimal?.longValueExact()?.takeIf { it >= 0 } }.getOrNull()
            }
            val input = count("prompt_tokens", "input_tokens")
            val output = count("completion_tokens", "output_tokens")
            val total = count("total_tokens") ?: if (input != null && output != null) {
                runCatching { Math.addExact(input, output) }.getOrNull()
            } else null
            return if (input == null && output == null && total == null) null else ReportedTokens(input, output, total)
        }
    }
}

/** Recording must not turn a successful model response into an error. */
suspend fun recordUsageSafely(record: suspend (TokenUsageEvent) -> Unit, event: TokenUsageEvent) {
    withContext(NonCancellable + Dispatchers.IO) {
        runCatching { record(event) }.onFailure { runCatching { Log.w("JevTokenUsage", "Usage storage unavailable") } }
    }
}

@Dao
interface TokenUsageDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(event: TokenUsageEvent)

    @Query("SELECT * FROM token_usage WHERE timestampMs >= :start AND timestampMs < :end ORDER BY timestampMs")
    fun observe(start: Long, end: Long): Flow<List<TokenUsageEvent>>
}

// Separate metadata-only database: existing encrypted conversation storage is untouched.
@Database(entities = [TokenUsageEvent::class], version = 1, exportSchema = false)
abstract class TokenUsageDatabase : RoomDatabase() {
    abstract fun usageDao(): TokenUsageDao
}

@Singleton
class TokenUsageStore @Inject constructor(@ApplicationContext context: Context) {
    private val database = Room.databaseBuilder(context, TokenUsageDatabase::class.java, "token-usage.db").build()
    suspend fun record(event: TokenUsageEvent) = database.usageDao().insert(event)
    fun observe(start: Long, end: Long) = database.usageDao().observe(start, end)
}
