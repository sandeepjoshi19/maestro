package maestro.cli.command

import kotlinx.coroutines.*
import maestro.cli.App
import maestro.cli.CliError
import maestro.cli.DisableAnsiMixin
import maestro.cli.device.DeviceService
import maestro.cli.report.ReportFormat
import maestro.cli.report.ReporterFactory
import maestro.cli.report.TestDebugReporter
import maestro.cli.runner.TestRunner
import maestro.cli.runner.TestSuiteInteractor
import maestro.cli.runner.resultview.AnsiResultView
import maestro.cli.runner.resultview.PlainTextResultView
import maestro.cli.session.MaestroSessionManager
import maestro.cli.util.PrintUtils
import maestro.orchestra.error.ValidationError
import maestro.orchestra.util.Env.withInjectedShellEnvVars
import maestro.orchestra.workspace.WorkspaceExecutionPlanner
import okio.buffer
import okio.sink
import picocli.CommandLine
import java.io.File
import java.util.*
import java.util.concurrent.Callable
import kotlin.collections.ArrayList
import kotlin.collections.HashSet
import kotlin.io.path.absolutePathString

@CommandLine.Command(
    name = "parallelTest",
    description = [
        "Parallel Test of Flow or set of Flows on a local iOS Simulator or Android Emulator"
    ]
)
class ParallelTestCommand: Callable<Int> {

    private var connectedDevices: HashSet<String> = HashSet()
    @CommandLine.Mixin
    var disableANSIMixin: DisableAnsiMixin? = null

    @CommandLine.ParentCommand
    private val parent: App? = null

    private var flowFile: ArrayList<File> = ArrayList()

    @CommandLine.Option(names = ["-c", "--continuous"])
    private var continuous: Boolean = false

    @CommandLine.Option(names = ["-e", "--env"])
    private var env: Map<String, String> = emptyMap()

    @CommandLine.Option(
        names = ["--format"],
        description = ["Test report format (default=\${DEFAULT-VALUE}): \${COMPLETION-CANDIDATES}"],
    )
    private var format: ReportFormat = ReportFormat.NOOP

    @CommandLine.Option(
        names = ["--test-suite-name"],
        description = ["Test suite name"],
    )
    private var testSuiteName: String? = null

    @CommandLine.Option(names = ["--output"])
    private var output: File? = null

    @CommandLine.Option(
        names = ["--debug-output"],
        description = ["Configures the debug output in this path, instead of default"]
    )
    private var debugOutput: String? = null

    @CommandLine.Option(
        names = ["--include-tags"],
        description = ["List of tags that will remove the Flows that does not have the provided tags"],
        split = ",",
    )
    private var includeTags: List<String> = emptyList()

    @CommandLine.Option(
        names = ["--exclude-tags"],
        description = ["List of tags that will remove the Flows containing the provided tags"],
        split = ",",
    )
    private var excludeTags: List<String> = emptyList()

    @CommandLine.Spec
    lateinit var commandSpec: CommandLine.Model.CommandSpec

    init {
        flowFile.add(File("/Users/sandeep.kmr/Code/Ui-Automation-Jarvis/android/test/2_checkout/4_show_price_details.yaml"))
        flowFile.add(File("/Users/sandeep.kmr/Code/Ui-Automation-Jarvis/android/test/2_checkout/2_empty_cart_flow.yaml"))
        Collections.synchronizedSet(connectedDevices)
    }

    private fun isWebFlow(): Boolean {
//        if (!flowFile.isDirectory) {
//            val config = YamlCommandReader.readConfig(flowFile.toPath())
//            return Regex("http(s?)://").containsMatchIn(config.appId)
//        }

        return false
    }
    override fun call(): Int {


        val size = DeviceService.listConnectedDevices().size - 1;
        runBlocking {
            val startTime = System.currentTimeMillis()
            println("Start Time: $startTime")
            doParallelTesting(size)
            val endTime = System.currentTimeMillis()
            println("End Time: $endTime")
            println("Time Taken: ${(endTime - startTime)/1000} seconds")
        }
        return 0
    }

    private suspend fun doParallelTesting(size: Int) = coroutineScope{
        println("Inside coroutine scope")
        var tasks: ArrayList<Deferred<Any>> = ArrayList()
        for (i in 0..size){
            println("Inside for loop $i")
            var task = async(Dispatchers.Default) {
                println("Inside for loop async $i" + Thread.currentThread().name)
                val executionPlan = try {
                    WorkspaceExecutionPlanner.plan(flowFile[i].toPath().toAbsolutePath(), includeTags, excludeTags)
                } catch (e: ValidationError) {
                    throw CliError(e.message)
                }

                val deviceId =
                    if (isWebFlow()) "chromium".also { PrintUtils.warn("Web support is an experimental feature and may be removed in future versions.\n") }
                    else parent?.deviceId

                env = env.withInjectedShellEnvVars()

                TestDebugReporter.install(debugOutputPathAsString = debugOutput)
                val debugOutputPath = TestDebugReporter.getDebugOutputPath()
                println("Inside for loop Pre maestro Creation $i")
                MaestroSessionManager.newSession(parent?.host, parent?.port, deviceId, connectedDevice = connectedDevices) { session ->
                    val maestro = session.maestro
                    val device = session.device

                    if (flowFile[i].isDirectory || format != ReportFormat.NOOP) {
                        if (continuous) {
                            throw CommandLine.ParameterException(
                                commandSpec.commandLine(),
                                "Continuous mode is not supported for directories. $flowFile is a directory",
                            )
                        }
                        val suiteResult = TestSuiteInteractor(
                            maestro = maestro,
                            device = device,
                            reporter = ReporterFactory.buildReporter(format, testSuiteName),
                        ).runTestSuite(
                            executionPlan = executionPlan,
                            env = env,
                            reportOut = format.fileExtension
                                ?.let { extension ->
                                    (output ?: File("report$extension"))
                                        .sink()
                                        .buffer()
                                },
                            debugOutputPath = debugOutputPath
                        )

                        TestDebugReporter.deleteOldFiles()
                        if (suiteResult.passed) {
                            0
                        } else {
                            printExitDebugMessage()
                            1
                        }
                    } else {
                        if (continuous) {
                            TestDebugReporter.deleteOldFiles()
                            TestRunner.runContinuous(maestro, device, flowFile[i], env)
                        } else {
                            val resultView =
                                if (DisableAnsiMixin.ansiEnabled) AnsiResultView() else PlainTextResultView()
                            val resultSingle =
                                TestRunner.runSingle(maestro, device, flowFile[i], env, resultView, debugOutputPath)
                            if (resultSingle == 1) {
                                printExitDebugMessage()
                            }
                            TestDebugReporter.deleteOldFiles()
                        }
                    }
                }
            }
            tasks.add(task)
        }
        tasks.awaitAll()

    }

    private fun printExitDebugMessage() {
        println()
        println("==== Debug output (logs & screenshots) ====")
        PrintUtils.message(TestDebugReporter.getDebugOutputPath().absolutePathString())
    }
}