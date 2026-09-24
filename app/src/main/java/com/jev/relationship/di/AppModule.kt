package com.jev.relationship.di

import android.content.Context
import androidx.room.Room
import com.jev.relationship.data.fake.FakeJevAnalyzer
import com.jev.relationship.data.fake.FakeReplyGenerator
import com.jev.relationship.data.local.JevDatabase
import com.jev.relationship.data.local.JevDatabaseMigrations
import com.jev.relationship.data.local.RoomHistoryRepository
import com.jev.relationship.data.local.RoomContactMemoryRepository
import com.jev.relationship.data.local.RoomChatAssistantStore
import com.jev.relationship.data.remote.ConfigurableJevAnalyzer
import com.jev.relationship.data.remote.ConfigurableReplyGenerator
import com.jev.relationship.data.remote.DetailedAnalysisEnricher
import com.jev.relationship.data.remote.OpenAiCompatibleClient
import com.jev.relationship.data.remote.ChatAssistantCompletion
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
import com.jev.relationship.domain.inline.InlineAnalysisCardPublisher
import com.jev.relationship.domain.inline.InlineCardStore
import com.jev.relationship.domain.inline.InlineWindowSnapshotStore
import com.jev.relationship.domain.realtime.ConversationAnalyzer
import com.jev.relationship.domain.realtime.RealtimeAnalysisCoordinator
import com.jev.relationship.domain.surface.AssistantSurfaceCoordinator
import com.jev.relationship.domain.HistoryRepository
import com.jev.relationship.domain.ContactMemoryRepository
import com.jev.relationship.domain.chatassistant.ChatAssistantCoordinator
import com.jev.relationship.domain.chatassistant.ChatAssistantStore
import com.jev.relationship.domain.JevAnalyzer
import com.jev.relationship.domain.ReplyGenerator
import com.jev.relationship.ipc.MessageCaptureCoordinator
import com.jev.relationship.ipc.IpcAnalysisResultBroadcaster
import com.jev.relationship.ipc.IpcAnalysisResultSink
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
    fun provideApplicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
        passphraseProvider: AndroidDatabasePassphraseProvider,
    ): JevDatabase = Room.databaseBuilder(context, JevDatabase::class.java, "jev.db")
        .openHelperFactory(SupportFactory(passphraseProvider.passphrase()))
        .addMigrations(JEV_DATABASE_MIGRATION_1_2)
        .addMigrations(JEV_DATABASE_MIGRATION_2_3)
        .addMigrations(JevDatabaseMigrations.VERSION_3_TO_4)
        .addMigrations(JevDatabaseMigrations.VERSION_4_TO_5)
        .build()

    @Provides
    @Singleton
    fun provideHistoryRepository(database: JevDatabase): HistoryRepository =
        RoomHistoryRepository(database.analysisDao())

    @Provides
    @Singleton
    fun provideAnalysisResultCache(database: JevDatabase): com.jev.relationship.domain.realtime.AnalysisResultCache =
        com.jev.relationship.data.local.RoomAnalysisResultCache(database.analysisResultCacheDao())

    @Provides
    @Singleton
    fun provideContactMemoryRepository(database: JevDatabase): ContactMemoryRepository =
        RoomContactMemoryRepository(database.contactMemoryDao())

    @Provides
    @Singleton
    fun provideChatAssistantStore(database: JevDatabase): ChatAssistantStore =
        RoomChatAssistantStore(database)

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
    fun provideOpenAiCompatibleClient(usage: com.jev.relationship.data.usage.TokenUsageStore): OpenAiCompatibleClient =
        OpenAiCompatibleClient(usage::record)

    @Provides
    @Singleton
    fun provideChatAssistantCompletion(client: OpenAiCompatibleClient): ChatAssistantCompletion = client

    @Provides
    @Singleton
    fun provideChatAssistantCoordinator(
        localHistoryBroker: com.jev.relationship.ipc.LocalHistoryBroker,
        store: ChatAssistantStore,
        settingsRepository: SettingsRepository,
        completion: ChatAssistantCompletion,
    ): ChatAssistantCoordinator = ChatAssistantCoordinator(
        localHistory = localHistoryBroker,
        store = store,
        settingsRepository = settingsRepository,
        completion = completion,
    )

    @Provides
    @Singleton
    fun provideTypeSafeJevClient(usage: com.jev.relationship.data.usage.TokenUsageStore): TypeSafeJevGateway =
        TypeSafeJevClient(usage::record)

    @Provides
    @Singleton
    fun provideJevAnalyzer(
        settingsRepository: SettingsRepository,
        client: TypeSafeJevGateway,
        openAiClient: OpenAiCompatibleClient,
    ): JevAnalyzer {
        val base = ConfigurableJevAnalyzer(
            settingsRepository = settingsRepository,
            remote = TypeSafeJevAnalyzer(client, settingsRepository),
            fallback = FakeJevAnalyzer(),
        )
        return DetailedAnalysisEnricher(
            base = base,
            settingsRepository = settingsRepository,
            complete = openAiClient::complete,
        )
    }

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
    fun provideLocalHistoryBroker(): com.jev.relationship.ipc.LocalHistoryBroker = com.jev.relationship.ipc.LocalHistoryBroker()

    @Provides
    @Singleton
    fun provideIpcAnalysisResultBroadcaster(): IpcAnalysisResultBroadcaster =
        IpcAnalysisResultBroadcaster()

    @Provides
    @Singleton
    fun provideIpcAnalysisResultSink(
        broadcaster: IpcAnalysisResultBroadcaster,
    ): IpcAnalysisResultSink = broadcaster

    @Provides
    @Singleton
    fun provideConversationAnalyzer(useCase: AnalysisConversationUseCase): ConversationAnalyzer = useCase

    @Provides
    @Singleton
    fun provideAssistantSurfaceCoordinator(): AssistantSurfaceCoordinator = AssistantSurfaceCoordinator()

    @Provides
    @Singleton
    fun provideInlineCardStore(): InlineCardStore = InlineCardStore()

    @Provides
    @Singleton
    fun provideInlineWindowSnapshotStore(): InlineWindowSnapshotStore = InlineWindowSnapshotStore()

    @Provides
    @Singleton
    fun provideInlineAnalysisCardPublisher(store: InlineCardStore): InlineAnalysisCardPublisher =
        InlineAnalysisCardPublisher(store)

    @Provides
    @Singleton
    fun provideRealtimeAnalysisCoordinator(
        captureCoordinator: MessageCaptureCoordinator,
        analyzer: ConversationAnalyzer,
        historyRepository: HistoryRepository,
        surfaceCoordinator: AssistantSurfaceCoordinator,
        settingsRepository: RealtimeAssistantSettingsRepository,
        @ApplicationScope scope: CoroutineScope,
        inlineCardPublisher: InlineAnalysisCardPublisher,
        analysisResultSink: IpcAnalysisResultSink,
        analysisResultCache: com.jev.relationship.domain.realtime.AnalysisResultCache,
        localHistoryBroker: com.jev.relationship.ipc.LocalHistoryBroker,
    ): RealtimeAnalysisCoordinator = RealtimeAnalysisCoordinator(
        captureCoordinator = captureCoordinator,
        analyzer = analyzer,
        historyRepository = historyRepository,
        surfaceCoordinator = surfaceCoordinator,
        settingsRepository = settingsRepository,
        scope = scope,
        inlineCardPublisher = inlineCardPublisher,
        analysisResultSink = analysisResultSink,
        analysisResultCache = analysisResultCache,
        localHistory = localHistoryBroker,
    )

    private val JEV_DATABASE_MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
        override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS contacts (id TEXT NOT NULL PRIMARY KEY, displayName TEXT NOT NULL, updatedAt INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS memory_observations (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, contactId TEXT NOT NULL, kind TEXT NOT NULL, text TEXT NOT NULL, confidence REAL NOT NULL, updatedAt INTEGER NOT NULL, FOREIGN KEY(contactId) REFERENCES contacts(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_observations_contactId ON memory_observations(contactId)")
        }
    }

    private val JEV_DATABASE_MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
        override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS message_analysis_cache (localMessageId INTEGER NOT NULL PRIMARY KEY, resultJson TEXT NOT NULL, cachedAt INTEGER NOT NULL)")
        }
    }

}
