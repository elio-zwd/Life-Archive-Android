package com.elio.lifearchive

import androidx.lifecycle.*
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VisibilityLifecycleOwnerTest {
    @Test fun hiddenBusinessStopsUiCollectionWithoutDestroyingItsOwner() {
        val parent = object : LifecycleOwner {
            val registry = LifecycleRegistry(this)
            override val lifecycle: Lifecycle get() = registry
        }
        parent.registry.currentState = Lifecycle.State.RESUMED
        val child = VisibilityLifecycleOwner(parent.lifecycle)
        parent.lifecycle.addObserver(child)
        child.setActive(true)
        assertEquals(Lifecycle.State.RESUMED, child.lifecycle.currentState)
        child.setActive(false)
        assertEquals(Lifecycle.State.CREATED, child.lifecycle.currentState)
        parent.registry.currentState = Lifecycle.State.STARTED
        assertEquals(Lifecycle.State.CREATED, child.lifecycle.currentState)
        child.setActive(true)
        assertEquals(Lifecycle.State.STARTED, child.lifecycle.currentState)
        parent.registry.currentState = Lifecycle.State.DESTROYED
        assertEquals(Lifecycle.State.DESTROYED, child.lifecycle.currentState)
    }
}
