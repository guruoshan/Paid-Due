package com.dailymoneyregister

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dailymoneyregister.core.device.DeviceIdentityProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class DeviceIdentityProviderTest {
    private lateinit var context: Context
    private lateinit var base: Context
    private lateinit var preferenceName: String

    @Before
    fun setUp() {
        base = ApplicationProvider.getApplicationContext()
        preferenceName = "device_identity_test_${UUID.randomUUID()}"
        context = object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                base.getSharedPreferences(preferenceName, mode)
        }
    }

    @After
    fun tearDown() {
        base.deleteSharedPreferences(preferenceName)
    }

    @Test
    fun generatesAndPersistsOneRandomUuid() {
        val provider = DeviceIdentityProvider(context)
        val id = provider.deviceId
        assertEquals(4, UUID.fromString(id).version())
        assertEquals(id, provider.deviceId)
        assertEquals(id, context.getSharedPreferences("dmr_device_identity", Context.MODE_PRIVATE)
            .getString("device_id", null))
    }

    @Test
    fun recreatingProviderReusesSavedIdentity() {
        val id = DeviceIdentityProvider(context).deviceId
        assertEquals(id, DeviceIdentityProvider(context).deviceId)
    }

    @Test
    fun preservesAnExistingIdentity() {
        val saved = UUID.randomUUID().toString()
        context.getSharedPreferences("dmr_device_identity", Context.MODE_PRIVATE)
            .edit().putString("device_id", saved).commit()
        assertEquals(saved, DeviceIdentityProvider(context).deviceId)
    }

    @Test
    fun concurrentProvidersShareOneIdentity() {
        val executor = Executors.newFixedThreadPool(8)
        try {
            val results = executor.invokeAll((1..32).map {
                Callable { DeviceIdentityProvider(context).deviceId }
            }, 10, TimeUnit.SECONDS).map { it.get() }
            assertEquals(1, results.toSet().size)
            assertEquals(results.first(), DeviceIdentityProvider(context).deviceId)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun clearingInstallationStorageCreatesANewIdentity() {
        val previous = DeviceIdentityProvider(context).deviceId
        context.getSharedPreferences("dmr_device_identity", Context.MODE_PRIVATE).edit().clear().commit()
        assertNotEquals(previous, DeviceIdentityProvider(context).deviceId)
    }
}
