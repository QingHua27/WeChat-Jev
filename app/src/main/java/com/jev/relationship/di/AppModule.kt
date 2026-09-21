package com.jev.relationship.di

import android.content.Context
import androidx.room.Room
import com.jev.relationship.data.fake.FakeJevAnalyzer
import com.jev.relationship.data.fake.FakeReplyGenerator
import com.jev.relationship.data.local.JevDatabase
import com.jev.relationship.data.local.RoomHistoryRepository
import com.jev.relationship.data.local.RoomContactMemoryRepository
import com.jev.relationship.data.remote.ConfigurableJevAnalyzer
import com.jev.relationship.data.remote.ConfigurableReplyGenerator
import com.jev.relationship.data.remote.OpenAiCompatibleClient
import com.jev.relationship.data.remote.RemoteReplyGenerator
import com.jev.relationship.data.remote.TypeSafeJevAnalyzer
import com.jev.relationship.data.remote.TypeSafeJevClient
import com.jev.relationship.data.remote.TypeSafeJevGateway
import com.jev.relationship.data.settings.DataStoreSettingsRepository
import com.jev.relationship.data.settings.DataStoreRealtimeAssistantRepository
import com.jev.relationship.data.settings.AndroidKeyStoreSecretProtector
import com.jev.relationship.data.settings.AndroidDatabasePassphraseProvider
import com.jev.relationship.data.settings.SettingsRepository
import com.jev.relationship.data.settings.SecretProtector
import com.jev.relationship.data.settings.DataStoreXposedIntegrationRepository
import com.jev.relationship.data.settings.XposedIntegrationRepository
import com.jev.relationship.data.settings.RealtimeAssistantSettingsRepository
import com.jev.relationship.data.settings.settingsDataStore
import com.jev.relationship.domain.AnalysisConversationUseCase
import com.jev.relationship.domain.realtime.ConversationAnalyzer
import com.jev.relationship.domain.realtime.RealtimeAnalysisCoordinator
import com.jev.relationship.domain.surface.AssistantSurfaceCoordinator
import com.jev.relationship.domain.HistoryRepository
import com.jev.relationship.domain.ContactMemoryRepository
import com.jev.relationship.domain.JevAnalyzer
import com.jev.relationship.domain.ReplyGenerator
import com.jev.relationship.ipc.MessageCaptureCoordinator
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import net.sqlcipher.database.SupportFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
        passphraseProvider: AndroidDatabasePassphraseProvider,
    ): JevDatabase = Room.databaseBuilder(context, JevDatabase::class.java, "jev.db")
        .openHelperFactory(SupportFactory(passphraseProvider.passphrase()))
        .addMigrations(JEV_DATABASE_MIGRATION_1_2)
        .build()

    @Provides
    @Singleton
    fun provideHistoryRepository(database: JevDatabase): HistoryRepository =
        RoomHistoryRepository(database.analysisDao())

    @Provides
    @Singleton
    fun provideContactMemoryRepository(database: JevDatabase): ContactMemoryRepository =
        RoomContactMemoryRepository(database.contactMemoryDao())

    @Provides
    @Singleton
    fun provideSecretProtector(): SecretProtector = AndroidKeyStoreSecretProtector()

    @Provides
    @Singleton
    fun provideDatabasePassphraseProvider(
        @ApplicationContext context: Context,
        secretProtector: SecretProtector,
    ): AndroidDatabasePassphraseProvider = AndroidDatabasePassphraseProvider(context, secretProtector)

    @Provides
    @Singleton
    fun provideSettingsRepository(
        @ApplicationContext context: Context,
        secretProtector: SecretProtector,
    ): SettingsRepository = DataStoreSettingsRepository(context, secretProtector)

    @Provides
    @Singleton
    fun provideXposedIntegrationRepository(
        @ApplicationContext context: Context,
        secretProtector: SecretProtector,
    ): XposedIntegrationRepository = DataStoreXposedIntegrationRepository(context, secretProtector)

    @Provides
    @Singleton
    fun provideRealtimeAssistantSettingsRepository(
        @ApplicationContext context: Context,
    ): RealtimeAssistantSettingsRepository = DataStoreRealtimeAssistantRepository(context.settingsDataStore)

    @Provides
    @Singleton
    fun provideOpenAiCompatibleClient(): OpenAiCompatibleClient = OpenAiCompatibleClient()

    @Provides
    @Singleton
    fun provideTypeSafeJevClient(): TypeSafeJevGateway = TypeSafeJevClient()

    @Provides
    @Singleton
    fun provideJevAnalyzer(
        settingsRepository: SettingsRepository,
        client: TypeSafeJevGateway,
    ): JevAnalyzer = ConfigurableJevAnalyzer(
        settingsRepository = settingsRepository,
        remote = TypeSafeJevAnalyzer(client, settingsRepository),
        fallback = FakeJevAnalyzer(),
    )

    @Provides
    @Singleton
    fun provideReplyGenerator(
        settingsRepository: SettingsRepository,
        client: OpenAiCompatibleClient,
    ): ReplyGenerator = ConfigurableReplyGenerator(
        settingsRepository = settingsRepository,
        remote = RemoteReplyGenerator(client, settingsRepository),
        fallback = FakeReplyGenerator(),
    )

    @Provides
    @Singleton
    fun provideMessageCaptureCoordinator(): MessageCaptureCoordinator = MessageCaptureCoordinator()

    @Provides
    @Singleton
    fun provideConversationAnalyzer(useCase: AnalysisConversationUseCase): ConversationAnalyzer = useCase

    @Provides
    @Singleton
    fun provideAssistantSurfaceCoordinator(): AssistantSurfaceCoordinator = AssistantSurfaceCoordinator()

    @Provides
    @Singleton
    fun provideRealtimeAnalysisCoordinator(
        captureCoordinator: MessageCaptureCoordinator,
        analyzer: ConversationAnalyzer,
        historyRepository: HistoryRepository,
        surfaceCoordinator: AssistantSurfaceCoordinator,
        settingsRepository: RealtimeAssistantSettingsRepository,
        @ApplicationScope scope: CoroutineScope,
    ): RealtimeAnalysisCoordinator = RealtimeAnalysisCoordinator(
        captureCoordinator = captureCoordinator,
        analyzer = analyzer,
        historyRepository = historyRepository,
        surfaceCoordinator = surfaceCoordinator,
        settingsRepository = settingsRepository,
        scope = scope,
    )

    private val JEV_DATABASE_MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
        override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS contacts (id TEXT NOT NULL PRIMARY KEY, displayName TEXT NOT NULL, updatedAt INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS memory_observations (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, contactId TEXT NOT NULL, kind TEXT NOT NULL, text TEXT NOT NULL, confidence REAL NOT NULL, updatedAt INTEGER NOT NULL, FOREIGN KEY(contactId) REFERENCES contacts(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_observations_contactId ON memory_observations(contactId)")
        }
    }
}
