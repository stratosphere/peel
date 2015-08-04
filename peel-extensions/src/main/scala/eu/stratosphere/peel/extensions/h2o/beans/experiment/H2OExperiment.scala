package eu.stratosphere.peel.extensions.h2o.beans.experiment

import java.lang.{System => Sys}
import java.io.FileWriter
import java.nio.file.{Paths, Files}

import com.typesafe.config.Config
import eu.stratosphere.peel.core.beans.data.{ExperimentOutput, DataSet}
import eu.stratosphere.peel.core.beans.experiment.Experiment
import eu.stratosphere.peel.core.util.shell
import eu.stratosphere.peel.extensions.h2o.beans.system.H2O
import spray.json._

/** An [[eu.stratosphere.peel.core.beans.experiment.Experiment Experiment]] implementation which handles the execution
  * of a single H2O job.
  *
  * Note:
  * H2O currently doesn't support submitting a java/scala job in jar file.
  * There are 2 ways to submit a h2o job:
  * 1. through interactive web interface, only the pre-implemented algorithms are supported.
  * 2. execute a python/R script. precondition: install the h2o python/R package
  *
  * So if you don't want to install the h2o python/R package, you have to write a java program which simulate all
  * the actions on the web interface.
  */
class H2OExperiment(
    command: String,
    runner : H2O,
    runs   : Int,
    inputs : Set[DataSet],
    outputs: Set[ExperimentOutput],
    name   : String,
    config : Config) extends Experiment(command, runner, runs, inputs, outputs, name, config) {

  def this(
    runs   : Int,
    runner : H2O,
    input  : DataSet,
    output : ExperimentOutput,
    command: String,
    name   : String,
    config : Config) = this(command, runner, runs, Set(input), Set(output), name, config)

  def this(
    runs   : Int,
    runner : H2O,
    inputs : Set[DataSet],
    output : ExperimentOutput,
    command: String,
    name   : String,
    config : Config) = this(command, runner, runs, inputs, Set(output), name, config)

  override def run(id: Int, force: Boolean): Experiment.Run[H2O] = new H2OExperiment.SingleJobRun(id, this, force)

  def copy(name: String = name, config: Config = config) = new H2OExperiment(command, runner, runs, inputs, outputs, name, config)

}

object H2OExperiment {

  case class State(
    name: String,
    suiteName: String,
    command: String,
    runnerID: String,
    runnerName: String,
    runnerVersion: String,
    var runExitCode: Option[Int] = None,
    var runTime: Long = 0) extends Experiment.RunState {}

  object StateProtocol extends DefaultJsonProtocol with NullOptions {
    implicit val stateFormat = jsonFormat8(State)
  }

  /** A private inner class encapsulating the logic of single run. */
  class SingleJobRun(val id: Int, val exp: H2OExperiment, val force: Boolean) extends Experiment.SingleJobRun[H2O, State] {

    import eu.stratosphere.peel.extensions.h2o.beans.experiment.H2OExperiment.StateProtocol._

    val runnerLogPath = s"${exp.config.getString("system.h2o.config.cli.tmp.dir")}/h2ologs"

    override def isSuccessful = state.runExitCode.getOrElse(-1) == 0

    override protected def logFilePatterns = List(s"$runnerLogPath/h2o_*-3-info.log")

    val pollingNode = exp.config.getStringList("system.h2o.config.slaves").get(0)
    val user = exp.config.getString("system.h2o.user")
    val dataPort = exp.config.getInt("system.h2o.config.cli.dataport")

    override protected def loadState(): State = {
      if (Files.isRegularFile(Paths.get(s"$home/state.json"))) {
        try {
          io.Source.fromFile(s"$home/state.json").mkString.parseJson.convertTo[State]
        } catch {
          case e: Throwable => State(name, Sys.getProperty("app.suite.name"), command, exp.runner.beanName, exp.runner.name, exp.runner.version)
        }
      } else {
        State(name, Sys.getProperty("app.suite.name"), command, exp.runner.beanName, exp.runner.name, exp.runner.version)
      }
    }

    override protected def writeState() = {
      val fw = new FileWriter(s"$home/state.json")
      fw.write(state.toJson.prettyPrint)
      fw.close()
    }

    override protected def runJob() = {
      // try to execute the experiment
      val (runExit, t) = Experiment.time(this ! (command, s"$home/run.out", s"$home/run.err"))
      state.runTime = t
      state.runExitCode = Some(runExit)
    }

    /** Before the run, collect runner log files and their current line counts */
    override protected def beforeRun() = {
      val logFiles = for (pattern <- logFilePatterns; f <- (shell !! s""" ssh $user@$pollingNode "ls $pattern" """).split(Sys.lineSeparator).map(_.trim)) yield f
      logFileCounts = Map((for (f <- logFiles) yield f -> (shell !! s""" ssh $user@$pollingNode "wc -l $f | cut -d' ' -f1" """).trim.toLong): _*)
    }

    /** After the run, copy logs */
    override protected def afterRun(): Unit = {
      shell ! s"rm -Rf $home/logs/*"
      for ((file, count) <- logFileCounts) shell ! s""" ssh $user@$pollingNode "tail -n +${count + 1} $file" > $home/logs/${Paths.get(file).getFileName}"""
    }

    override def cancelJob() = {
      //firstly, retrieve the jobid of running job
      val s = shell !! s"wget -qO- $user@$pollingNode:$dataPort/3/Jobs"
      var jobid = ""
      for (job <- s.parseJson.asJsObject.fields.get("jobs").get.asInstanceOf[JsArray].elements) {
        val fields = job.asJsObject.fields
        if (fields.getOrElse("status", "").toString == "\"RUNNING\"" || fields.getOrElse("status", "").toString == "\"CREATED\"") {
          val sid = fields.get("key").get.asInstanceOf[JsObject].fields.get("name").get.toString()
          jobid = sid.substring(1, jobid.length - 1)
        }
      }

      //send cancel request to REST API
      shell !! s""" wget -qO- $user@$pollingNode:$dataPort/3/Jobs/${jobid.replace("$", "\\$")}/cancel --post-data="job_id=${jobid}" """

      //check if the job has been successfully cancelled
      var isCancelled = false
      while (!isCancelled) {
        Thread.sleep(exp.config.getInt("system.h2o.startup.polling.interval") * 2)
        val status = this getStatus(jobid)
        isCancelled = status == "CANCELLED"
      }
      state.runTime = exp.config.getLong("experiment.timeout") * 1000
      state.runExitCode = Some(-1)
    }

    private def !(command: String, outFile: String, errFile: String) = {
      shell ! s"$command > $outFile 2> $errFile"
    }

    private def getStatus(jobid: String): String = {
      (shell !! s""" wget -qO- $user@$pollingNode:$dataPort/3/Jobs/${jobid.replace("$", "\\$")} | grep -Eo '"status":"[A-Z]+"' | grep -Eo [A-Z]+ """).stripLineEnd
    }

  }

}