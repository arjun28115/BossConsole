package ai.rever.boss.plugin.loader

import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Dependency-free classes owned by this test, in a package that is not in
 * [PluginClassLoader.defaultSharedPackages] so they take the child-first path.
 * There are several because the race being covered is per class name: each one
 * gives the threads a fresh, not-yet-defined name to contend over.
 */
class Race00

class Race01

class Race02

class Race03

class Race04

class Race05

class Race06

class Race07

class Race08

class Race09

class Race10

class Race11

/**
 * Cover for BossConsole#59: [PluginClassLoader] overrode `loadClass` such that
 * the child-first path called `findClass` directly, outside
 * `getClassLoadingLock`, and never registered as parallel capable.
 *
 * `ClassLoader.loadClass` holds the per-name lock across its whole
 * find-then-define sequence. Bypassing it let two threads both miss
 * `findLoadedClass` and both reach `defineClass`, where the loser gets
 * `LinkageError: attempted duplicate class definition for name: ...`. Plugins
 * are multi-threaded by construction, so this was reachable in the field, and it
 * would have read as a regression from the unload-fallback work rather than the
 * older, separate defect it is.
 */
class ParallelCapableClassLoadingTest {
    private val tempJars = mutableListOf<File>()
    private val loaders = mutableListOf<PluginClassLoader>()

    private val hostLoader: ClassLoader = ParallelCapableClassLoadingTest::class.java.classLoader

    @AfterTest
    fun cleanup() {
        loaders.forEach { runCatching { it.close() } }
        tempJars.forEach { it.delete() }
    }

    private fun jarContaining(classes: List<Class<*>>): File {
        val jar = File.createTempFile("plugin-cl-parallel-test", ".jar")
        jar.deleteOnExit()
        tempJars.add(jar)
        JarOutputStream(jar.outputStream()).use { out ->
            for (cls in classes) {
                val path = cls.name.replace('.', '/') + ".class"
                val bytes =
                    requireNotNull(hostLoader.getResourceAsStream(path)) {
                        "test class $path missing from the test classpath"
                    }.use { it.readBytes() }
                out.putNextEntry(JarEntry(path))
                out.write(bytes)
                out.closeEntry()
            }
        }
        return jar
    }

    private fun loaderOver(classes: List<Class<*>>): PluginClassLoader =
        PluginClassLoader(
            pluginId = "ai.rever.boss.plugin.test.parallel",
            urls = arrayOf(jarContaining(classes).toURI().toURL()),
            parent = hostLoader,
        ).also { loaders.add(it) }

    private val raceClasses: List<Class<*>> =
        listOf(
            Race00::class.java,
            Race01::class.java,
            Race02::class.java,
            Race03::class.java,
            Race04::class.java,
            Race05::class.java,
            Race06::class.java,
            Race07::class.java,
            Race08::class.java,
            Race09::class.java,
            Race10::class.java,
            Race11::class.java,
        )

    @Test
    fun `the loader hands out a lock per class name`() {
        // The observable proof that registerAsParallelCapable() took effect. An
        // unregistered loader returns `this` from getClassLoadingLock for every
        // name, which serialises all loading through one monitor and, more to the
        // point here, means the registration silently did not happen. Nothing else
        // about the loader would look different.
        val loader = loaderOver(listOf(Race00::class.java))

        val lockA = loader.classLoadingLockFor("com.example.A")
        val lockB = loader.classLoadingLockFor("com.example.B")

        assertNotSame(loader, lockA, "an unregistered loader locks on itself")
        assertNotSame(lockA, lockB, "locks must be per class name, not global")
    }

    @Test
    fun `the same name always returns the same lock`() {
        // The other half: per-name locks are only useful if two threads asking for
        // one name agree on which monitor to take.
        val loader = loaderOver(listOf(Race00::class.java))
        assertSame(
            loader.classLoadingLockFor("com.example.A"),
            loader.classLoadingLockFor("com.example.A"),
        )
    }

    @Test
    fun `concurrent first loads of one name never define it twice`() {
        // The failure itself. Every thread is released onto the same fresh class
        // name at once, and reading the bytes out of the jar keeps the
        // find-then-define window wide enough to lose. Repeated over several
        // names because each name can only be raced once per loader.
        val loader = loaderOver(raceClasses)
        val threads = 8
        val pool = Executors.newFixedThreadPool(threads)
        val failures = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()

        try {
            for (cls in raceClasses) {
                val barrier = CyclicBarrier(threads)
                val tasks =
                    (0 until threads).map {
                        Callable {
                            barrier.await(30, TimeUnit.SECONDS)
                            runCatching { loader.loadClass(cls.name) }
                                .onFailure { failures.add(it) }
                                .getOrNull()
                        }
                    }
                val results = pool.invokeAll(tasks).map { it.get(30, TimeUnit.SECONDS) }

                // One winner, and everyone else must see the winner's class. Two
                // distinct Class objects for one name in one loader is the
                // duplicate definition, whether or not the JVM reported it.
                val distinct = results.filterNotNull().distinct()
                assertEquals(
                    1,
                    distinct.size,
                    "one name must resolve to one class; got ${distinct.size} for ${cls.name}",
                )
            }
        } finally {
            pool.shutdownNow()
        }

        assertEquals(
            emptyList(),
            failures.map { it.toString() },
            "no thread may fail; a duplicate definition surfaces here as LinkageError",
        )
    }

    @Test
    fun `a concurrent load still resolves to the plugin copy, not the host copy`() {
        // Locking must not have quietly changed which loader wins. These classes
        // exist in the test classpath as well as in the jar, so a child-first miss
        // would hand back the parent's copy and the test would still see one
        // consistent answer.
        val loader = loaderOver(listOf(Race00::class.java))
        val loaded = loader.loadClass(Race00::class.java.name)

        assertSame(loader, loaded.classLoader, "child-first must still win for plugin-owned classes")
        assertTrue(loaded !== Race00::class.java, "the plugin copy is a distinct Class from the host copy")
    }
}
