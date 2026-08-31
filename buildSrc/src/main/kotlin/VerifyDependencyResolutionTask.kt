import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "Resolution itself is the verification")
abstract class VerifyDependencyResolutionTask : DefaultTask() {

    @get:Classpath
    abstract val resolvedArtifacts: ConfigurableFileCollection

    @TaskAction
    fun verify() {
        logger.lifecycle(
            "Resolved {} runtime/test artifacts without a version conflict.",
            resolvedArtifacts.files.size,
        )
    }
}
