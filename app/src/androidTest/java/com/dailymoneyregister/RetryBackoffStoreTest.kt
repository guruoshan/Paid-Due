package com.dailymoneyregister

import androidx.test.core.app.ApplicationProvider
import com.dailymoneyregister.core.cloud.sync.RetryBackoffStore
import org.junit.Assert.assertEquals
import org.junit.Test

class RetryBackoffStoreTest {
    @Test fun retryTimestampSurvivesNewStoreInstance() {
        val prefs = ApplicationProvider.getApplicationContext<android.content.Context>()
            .getSharedPreferences("retry-backoff-test", android.content.Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        RetryBackoffStore(prefs).save("operation-1", 123456L)
        assertEquals(123456L, RetryBackoffStore(prefs).nextRetryAt("operation-1"))
        prefs.edit().clear().commit()
    }
}
