package watch.dependency

import com.google.common.jimfs.Jimfs
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineStart.UNDISPATCHED
import kotlinx.coroutines.launch
import org.junit.Test
import kotlin.time.seconds

class DependencyWatchTest {
	private val fs = Jimfs.newFileSystem().rootDirectory
	private val mavenRepository = FakeMavenRepository()
	private val notifier = RecordingNotifier()
	private val app = DependencyWatch(
		mavenRepository = mavenRepository,
		database = InMemoryDatabase(),
		notifier = notifier,
		checkInterval = 5.seconds,
	)

	@Test fun awaitNotifiesOncePresent() = test { context ->
		// Start undispatched to suspend on waiting for delay.
		launch(start = UNDISPATCHED) {
			app.await("com.example", "example", "1.0")
		}

		context.advanceTimeBy(5.seconds)
		context.triggerActions()
		assertThat(notifier.notifications).isEmpty()

		mavenRepository.addArtifact("com.example", "example", "1.0")
		context.advanceTimeBy(4.seconds)
		context.triggerActions()
		assertThat(notifier.notifications).isEmpty()

		context.advanceTimeBy(1.seconds)
		context.triggerActions()
		assertThat(notifier.notifications).containsExactly("com.example:example:1.0")
	}

	@Test fun monitorNotifiesOnceAvailable() = test { context ->
		val config = fs.resolve("config.yaml")
		config.writeText("""
			|coordinates:
			| - com.example:example-a
		""".trimMargin())

		// Start undispatched to immediately trigger first check.
		val monitorJob = launch(start = UNDISPATCHED) {
			app.monitor(config)
		}

		assertThat(notifier.notifications).isEmpty()

		mavenRepository.addArtifact("com.example", "example-a", "1.0")

		context.advanceTimeBy(5.seconds)
		assertThat(notifier.notifications).containsExactly(
			"com.example:example-a:1.0",
		)

		monitorJob.cancel()
	}

	@Test fun monitorReadsConfigForEachCheck() = test { context ->
		val config = fs.resolve("config.yaml")
		config.writeText("""
			|coordinates:
			| - com.example:example-a
		""".trimMargin())

		mavenRepository.addArtifact("com.example", "example-a", "1.0")

		// Start undispatched to immediately trigger first check.
		val monitorJob = launch(start = UNDISPATCHED) {
			app.monitor(config)
		}

		assertThat(notifier.notifications).containsExactly(
			"com.example:example-a:1.0",
		)

		// Add artifact to repo but not to config. Should not notify.
		mavenRepository.addArtifact("com.example", "example-b", "2.0")
		context.advanceTimeBy(5.seconds)
		assertThat(notifier.notifications).containsExactly(
			"com.example:example-a:1.0",
		)

		config.writeText("""
			|coordinates:
			| - com.example:example-a
			| - com.example:example-b
		""".trimMargin())
		context.advanceTimeBy(5.seconds)
		assertThat(notifier.notifications).containsExactly(
			"com.example:example-a:1.0",
			"com.example:example-b:2.0",
		)

		monitorJob.cancel()
	}
}
