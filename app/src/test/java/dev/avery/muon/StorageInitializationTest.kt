package dev.avery.muon

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class StorageInitializationTest {
    @Test fun simultaneousCallersSharePreparationAndBindingAndPublishOnlyReadyValue()=runTest {
        val main=StandardTestDispatcher(testScheduler,"main")
        val io=StandardTestDispatcher(testScheduler,"io")
        val scope=CoroutineScope(SupervisorJob()+main)
        var preparations=0; var bindings=0
        val result=Any(); val prepared=Any()
        val owner=StorageInitialization(scope,main,io,{ preparations++; prepared },{ actual:Any ->
            assertSame(prepared,actual); bindings++; result
        })
        try {
            assertEquals(StorageAvailability.NotStarted,owner.availability.value)
            val a=async { owner.await() }; val b=async { owner.await() }
            runCurrent()
            assertSame(result,a.await()); assertSame(result,b.await()); assertSame(result,owner.await())
            assertEquals(1,preparations); assertEquals(1,bindings)
            assertEquals(StorageAvailability.Ready,owner.availability.value)
        } finally { scope.cancel() }
    }
    @Test fun cancellingFirstScreenWhileNativePreparationRunsLeavesOneLiveProcessAttempt()=runTest {
        val main=StandardTestDispatcher(testScheduler)
        val executor=java.util.concurrent.Executors.newSingleThreadExecutor()
        val io=executor.asCoroutineDispatcher()
        val scope=CoroutineScope(SupervisorJob()+main)
        val entered=java.util.concurrent.CountDownLatch(1)
        val release=java.util.concurrent.CountDownLatch(1)
        var prepared=0; var bound=0; var uiTurns=0
        val owner=StorageInitialization(scope,main,io,{
            prepared++; entered.countDown()
            check(release.await(5,java.util.concurrent.TimeUnit.SECONDS))
            Any()
        },{ value:Any -> bound++; value })
        try {
            val first=launch { owner.await() }; runCurrent()
            assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(StorageAvailability.Preparing,owner.availability.value)
            launch { uiTurns++ }; runCurrent(); assertEquals(1,uiTurns)
            first.cancelAndJoin()
            val second=async { owner.await() }; runCurrent()
            assertEquals(1,prepared); assertEquals(0,bound)
            release.countDown(); second.await()
            assertEquals(1,prepared); assertEquals(1,bound)
            assertEquals(StorageAvailability.Ready,owner.availability.value)
        } finally { release.countDown(); scope.cancel(); io.close() }
    }
    @Test fun preparationFailureIsStickyAndNeverBindsOrRetries()=runTest {
        val dispatcher=StandardTestDispatcher(testScheduler)
        val scope=CoroutineScope(SupervisorJob()+dispatcher)
        var prepares=0; var binds=0
        val failure=IOException("Injected preparation failure")
        val owner=StorageInitialization<Any,Any>(scope,dispatcher,dispatcher,{ prepares++; throw failure },{ binds++; it })
        try {
            repeat(2) {
                try { owner.await(); fail("Must fail") } catch(actual:IOException) { assertSame(failure,actual) }
            }
            assertEquals(1,prepares); assertEquals(0,binds)
            assertEquals(StorageAvailability.Failed,owner.availability.value)
            assertTrue(owner.unavailable().message!!.contains("kept"))
        } finally { scope.cancel() }
    }
    @Test fun bindingFailureRetainsPreparedAttemptAndCannotSelectAnotherBackend()=runTest {
        val dispatcher=StandardTestDispatcher(testScheduler)
        val scope=CoroutineScope(SupervisorJob()+dispatcher)
        var prepares=0; var binds=0
        val resource=Any(); val failure=IOException("Injected binding failure")
        val owner=StorageInitialization<Any,Any>(scope,dispatcher,dispatcher,{ prepares++; resource },{
            assertSame(resource,it); binds++; throw failure
        })
        try {
            repeat(2) {
                try { owner.await(); fail("Must fail") } catch(actual:IOException) { assertSame(failure,actual) }
            }
            assertEquals(1,prepares); assertEquals(1,binds)
            assertEquals(StorageAvailability.Failed,owner.availability.value)
        } finally { scope.cancel() }
    }
}
