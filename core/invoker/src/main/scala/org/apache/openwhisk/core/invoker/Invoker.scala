/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.openwhisk.core.invoker

import java.util.concurrent.TimeoutException

import akka.{Done, NotUsed}
import akka.actor.{ActorSystem, CoordinatedShutdown, Terminated}
import akka.stream._
import akka.stream.alpakka.file.scaladsl.FileTailSource
import akka.stream.scaladsl.{FileIO, Flow, Framing, Sink}
import akka.stream.scaladsl.Framing.FramingException
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import akka.util.ByteString
import com.typesafe.config.ConfigValueFactory
import kamon.Kamon
import pureconfig.loadConfigOrThrow

import scala.concurrent.ExecutionContext
// import org.apache.openwhisk.common.Https.HttpsConfig
import org.apache.openwhisk.common._
import org.apache.openwhisk.core.{ConfigKeys, WhiskConfig}
import org.apache.openwhisk.core.WhiskConfig._
// import org.apache.openwhisk.core.connector.{MessagingProvider, PingMessage}
import org.apache.openwhisk.core.containerpool.ContainerPoolConfig
import org.apache.openwhisk.core.entity.{ExecManifest, InvokerInstanceId}
// import org.apache.openwhisk.core.entity.ActivationEntityLimit
import org.apache.openwhisk.core.entity.size._
import org.apache.openwhisk.core.entity.ByteSize
// import org.apache.openwhisk.http.{BasicHttpService, BasicRasService}
// import org.apache.openwhisk.spi.SpiLoader
import org.apache.openwhisk.utils.ExecutionContextFactory

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.util.{Failure, Success, Try}

//import akka.http.scaladsl.Http
//import akka.http.scaladsl.model._
import java.io.File
import java.nio.file.Path
import akka.stream.scaladsl.{FileIO, Source}
import org.apache.openwhisk.http.Messages
import org.apache.openwhisk.core.entity.ActivationLogs
import org.apache.openwhisk.core.containerpool.containerd.ContainerdContainerFactoryProvider

case class CmdLineArgs(uniqueName: Option[String] = None, id: Option[Int] = None, displayedName: Option[String] = None)

object Invoker {

  protected val protocol = loadConfigOrThrow[String]("whisk.invoker.protocol")

  /**
   * An object which records the environment variables required for this component to run.
   */
  def requiredProperties =
    Map(servicePort -> 8080.toString, runtimesRegistry -> "") ++
      ExecManifest.requiredProperties ++
      kafkaHosts ++
      zookeeperHosts ++
      wskApiHost

  def initKamon(instance: Int): Unit = {
    // Replace the hostname of the invoker to the assigned id of the invoker.
    val newKamonConfig = Kamon.config
      .withValue("kamon.environment.host", ConfigValueFactory.fromAnyRef(s"invoker$instance"))
    Kamon.reconfigure(newKamonConfig)
  }

  def main(args: Array[String]): Unit = {
    Kamon.loadReportersFromConfig()
    implicit val ec = ExecutionContextFactory.makeCachedThreadPoolExecutionContext()
    implicit val actorSystem: ActorSystem =
      ActorSystem(name = "invoker-actor-system", defaultExecutionContext = Some(ec))
    implicit val logger = new AkkaLogging(akka.event.Logging.getLogger(actorSystem, this))
    val poolConfig: ContainerPoolConfig = loadConfigOrThrow[ContainerPoolConfig](ConfigKeys.containerPool)

    // Prepare Kamon shutdown
    CoordinatedShutdown(actorSystem).addTask(CoordinatedShutdown.PhaseActorSystemTerminate, "shutdownKamon") { () =>
      logger.info(this, s"Shutting down Kamon with coordinated shutdown")
      Kamon.stopAllReporters().map(_ => Done)
    }

    // load values for the required properties from the environment
    implicit val config = new WhiskConfig(requiredProperties)

    def abort(message: String) = {
      logger.error(this, message)(TransactionId.invoker)
      actorSystem.terminate()
      Await.result(actorSystem.whenTerminated, 30.seconds)
      sys.exit(1)
    }

    if (!config.isValid) {
      abort("Bad configuration, cannot start.")
    }

    val execManifest = ExecManifest.initialize(config)
    if (execManifest.isFailure) {
      logger.error(this, s"Invalid runtimes manifest: ${execManifest.failed.get}")
      abort("Bad configuration, cannot start.")
    }

    /** Returns Some(s) if the string is not empty with trimmed whitespace, None otherwise. */
    def nonEmptyString(s: String): Option[String] = {
      val trimmed = s.trim
      if (trimmed.nonEmpty) Some(trimmed) else None
    }

    // process command line arguments
    // We accept the command line grammar of:
    // Usage: invoker [options] [<proposedInvokerId>]
    //    --uniqueName <value>   a unique name to dynamically assign Kafka topics from Zookeeper
    //    --displayedName <value> a name to identify this invoker via invoker health protocol
    //    --id <value>     proposed invokerId
    def parse(ls: List[String], c: CmdLineArgs): CmdLineArgs = {
      ls match {
        case "--uniqueName" :: uniqueName :: tail =>
          parse(tail, c.copy(uniqueName = nonEmptyString(uniqueName)))
        case "--displayedName" :: displayedName :: tail =>
          parse(tail, c.copy(displayedName = nonEmptyString(displayedName)))
        case "--id" :: id :: tail if Try(id.toInt).isSuccess =>
          parse(tail, c.copy(id = Some(id.toInt)))
        case Nil => c
        case _   => abort(s"Error processing command line arguments $ls")
      }
    }
    val cmdLineArgs = parse(args.toList, CmdLineArgs())
    logger.info(this, "Command line arguments parsed to yield " + cmdLineArgs)

    val assignedInvokerId = cmdLineArgs match {
      // --id is defined with a valid value, use this id directly.
      case CmdLineArgs(_, Some(id), _) =>
        logger.info(this, s"invokerReg: using proposedInvokerId $id")
        id

      // --uniqueName is defined with a valid value, id is empty, assign an id via zookeeper
      case CmdLineArgs(Some(unique), None, _) =>
        if (config.zookeeperHosts.startsWith(":") || config.zookeeperHosts.endsWith(":")) {
          abort(s"Must provide valid zookeeper host and port to use dynamicId assignment (${config.zookeeperHosts})")
        }
        new InstanceIdAssigner(config.zookeeperHosts).getId(unique)

      case _ => abort(s"Either --id or --uniqueName must be configured with correct values")
    }

    initKamon(assignedInvokerId)

// CONTAINERD: not required for first prototype
//    val topicBaseName = "invoker"
//    val topicName = topicBaseName + assignedInvokerId

// CONTAINERD: not required for first prototype
//    val maxMessageBytes = Some(ActivationEntityLimit.MAX_ACTIVATION_LIMIT)
    val invokerInstance =
      InvokerInstanceId(assignedInvokerId, cmdLineArgs.uniqueName, cmdLineArgs.displayedName, poolConfig.userMemory)

// CONTAINERD: not required for first prototype
//    val msgProvider = SpiLoader.get[MessagingProvider]
//    if (msgProvider
//          .ensureTopic(config, topic = topicName, topicConfig = topicBaseName, maxMessageBytes = maxMessageBytes)
//          .isFailure) {
//      abort(s"failure during msgProvider.ensureTopic for topic $topicName")
//    }
//    val producer = msgProvider.getProducer(config, Some(ActivationEntityLimit.MAX_ACTIVATION_LIMIT))
//    val invoker = try {
//      new InvokerReactive(config, invokerInstance, producer, poolConfig)
//    } catch {
//      case e: Exception => abort(s"Failed to initialize reactive invoker: ${e.getMessage}")
//    }

// CONTAINERD: not required for first prototype
//    Scheduler.scheduleWaitAtMost(1.seconds)(() => {
//      producer.send("health", PingMessage(invokerInstance)).andThen {
//        case Failure(t) => logger.error(this, s"failed to ping the controller: $t")
//      }
//    })

// CONTAINERD: not required for first prototype
//    val port = config.servicePort.toInt
//    val httpsConfig =
//      if (Invoker.protocol == "https") Some(loadConfigOrThrow[HttpsConfig]("whisk.invoker.https")) else None
//
//    BasicHttpService.startHttpService(new BasicRasService {}.route, port, httpsConfig)(
//      actorSystem,
//      ActorMaterializer.create(actorSystem))

    // CONTAINERD: HTTP POST / DELETE request to containerd-bridge
    implicit val materializer = ActorMaterializer()

    logger.info(this, "Creating container.")

    val cf = ContainerdContainerFactoryProvider.instance(
      actorSystem,
      logger,
      config,
      invokerInstance,
      Map.empty[String, Set[String]])

    cf.init()

    val cContainerFuture =
      cf.createContainer(TransactionId.testing, "s", ExecManifest.ImageName("myimage"), false, 128.MB, 1)

    val cContainer = Await.result(cContainerFuture, 5.seconds)

    logger.info(this, s"$cContainer")

    /* val createFuture: Future[HttpResponse] =
      Http().singleRequest(HttpRequest(uri = "http://127.0.0.1:8080/container/s", method = HttpMethods.POST))

    createFuture.andThen {
      case Success(res) => logger.info(this, s"HTTP result: $res")
      case Failure(t)   => logger.error(this, s"HTTP request failed: $t")
    }
    Await.result(createFuture, 5.seconds)
     */
    val logs: Future[ActivationLogs] = collectLogs(TransactionId.testing, false)
    logs.andThen {
      case Success(al) => logger.info(this, s"ActivationLogs: $al")
      case Failure(LogCollectingException(l)) =>
        logger.error(this, s"LogCollectionException: $l")
      case Failure(t) => logger.error(this, s"Log collection failed: $t")
    }
    Await.result(logs, 5.seconds)

    logger.info(this, "Deleting container.")

    /** val deleteFuture: Future[HttpResponse] =
      Http().singleRequest(HttpRequest(uri = "http://127.0.0.1:8080/container/s", method = HttpMethods.DELETE))

    deleteFuture.andThen {
      case Success(res) => logger.info(this, s"HTTP result: $res")
      case Failure(t)   => logger.error(this, s"HTTP request failed: $t")
    } **/
    Await.result(cContainer.destroy()(TransactionId.testing), 5.seconds)

    // CONTAINERD: shutdown required
    logger.info(this, "Shutting down Kamon.")
    val kamonStop = Kamon.stopAllReporters().andThen {
      case u =>
        logger.info(this, "Kamon shutdown completed.")
        u
    }

    Await.result(kamonStop, 5.seconds)

    logger.info(this, "Shutting down actor system.")

    val termination = actorSystem.terminate()
    logger.info(this, "After actorSystem.terminate().")

    val result: Terminated = Await.result(actorSystem.whenTerminated, 5.seconds)

    println(s"Actor system shutdown complete: '$result'.")

    sys.exit()
  }

  private val readChunkSize = 8192 // bytes
  def rawContainerLogs(containerdLogFilePath: Path,
                       fromPos: Long,
                       pollInterval: Option[FiniteDuration]): Source[ByteString, Any] =
    try {
      // If there is no waiting interval, we can end the stream early by reading just what is there from file.
      pollInterval match {
        case Some(interval) => FileTailSource(containerdLogFilePath, readChunkSize, fromPos, interval)
        case None           => FileIO.fromPath(containerdLogFilePath, readChunkSize, fromPos)
      }
    } catch {
      case t: Throwable => Source.failed(t)
    }

  protected val waitForLogs: FiniteDuration = 2.seconds
  protected val filePollInterval: FiniteDuration = 5.milliseconds

  /** Delimiter used to split log-lines as written by the json-log-driver. */
  private val delimiter = ByteString("\n")

  val ACTIVATION_LOG_SENTINEL = "XXX_THE_END_OF_A_WHISK_ACTIVATION_XXX"

  private val byteStringSentinel = ByteString(ACTIVATION_LOG_SENTINEL)

  def logs(limit: ByteSize, waitForSentinel: Boolean)(implicit transid: TransactionId): Source[ByteString, Any] = {
    rawContainerLogs((new File("/tmp/s.log")).toPath, 0L, if (waitForSentinel) Some(filePollInterval) else None)
    // This stage only throws 'FramingException' so we cannot decide whether we got truncated due to a size
    // constraint (like StreamLimitReachedException below) or due to the file being truncated itself.
      .via(Framing.delimiter(delimiter, limit.toBytes.toInt))
      .limitWeighted(limit.toBytes) { obj =>
        // Adding + 1 since we know there's a newline byte being read
        val size = obj.size + 1
        size
      }
      .via(new CompleteAfterOccurrences(_.containsSlice(byteStringSentinel), 2, waitForSentinel))
      // As we're reading the logs after the activation has finished the invariant is that all loglines are already
      // written and we mostly await them being flushed by the docker daemon. Therefore we can timeout based on the time
      // between two loglines appear without relying on the log frequency in the action itself.
      .idleTimeout(waitForLogs)
      .recover {
        case _: StreamLimitReachedException =>
          // While the stream has already ended by failing the limitWeighted stage above, we inject a truncation
          // notice downstream, which will be processed as usual. This will be the last element of the stream.
          ByteString(Messages.truncateLogs(limit))
        case _: OccurrencesNotFoundException | _: FramingException | _: TimeoutException =>
          // Stream has already ended and we insert a notice that data might be missing from the logs. While a
          // FramingException can also mean exceeding the limits, we cannot decide which case happened so we resort
          // to the general error message. This will be the last element of the stream.
          ByteString(Messages.logFailure)
      }
  }

  val toFormattedString: Flow[ByteString, String, NotUsed] = {
    Flow[ByteString].map { bs =>
      val raw = bs.utf8String
      val idx = raw.indexOf('|')
      val idx2 = raw.indexOf('|', idx + 1)
      s"date-time: '${raw.substring(0, idx)}', stream: '${raw.substring(idx + 1, idx2)}', content: '${raw.substring(idx2 + 1, raw.length)}'"
    }

  }

  def collectLogs(transid: TransactionId, waitForSentinel: Boolean)(implicit ec: ExecutionContext,
                                                                    mat: Materializer): Future[ActivationLogs] = {
    val limit = 10.MB
    logs(limit, waitForSentinel)(transid)
      .via(toFormattedString)
      .runWith(Sink.seq)
      .flatMap { seq =>
        val possibleErrors = Set(Messages.logFailure, Messages.truncateLogs(limit))
        val errored = seq.lastOption.exists(last => possibleErrors.exists(last.contains))
        val logs = ActivationLogs(seq.toVector)
        if (!errored) {
          Future.successful(logs)
        } else {
          Future.failed(LogCollectingException(logs))
        }
      }
  }

  /** Indicates reading logs has failed either terminally or truncated logs */
  case class LogCollectingException(partialLogs: ActivationLogs) extends Exception("Failed to read logs")
}

/**
 * Completes the stream once the given predicate is fulfilled by N events in the stream.
 *
 * '''Emits when''' an upstream element arrives and does not fulfill the predicate
 *
 * '''Backpressures when''' downstream backpressures
 *
 * '''Completes when''' upstream completes or predicate is fulfilled N times
 *
 * '''Cancels when''' downstream cancels
 *
 * '''Errors when''' stream completes, not enough occurrences have been found and errorOnNotEnough is true
 */
class CompleteAfterOccurrences[T](isInEvent: T => Boolean, neededOccurrences: Int, errorOnNotEnough: Boolean)
    extends GraphStage[FlowShape[T, T]] {
  val in: Inlet[T] = Inlet[T]("WaitForOccurrences.in")
  val out: Outlet[T] = Outlet[T]("WaitForOccurrences.out")
  override val shape: FlowShape[T, T] = FlowShape.of(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) with InHandler with OutHandler {
      private var occurrencesFound = 0

      override def onPull(): Unit = pull(in)

      override def onPush(): Unit = {
        val element = grab(in)
        val isOccurrence = isInEvent(element)

        if (isOccurrence) occurrencesFound += 1

        if (occurrencesFound >= neededOccurrences) {
          completeStage()
        } else {
          if (isOccurrence) {
            pull(in)
          } else {
            push(out, element)
          }
        }
      }

      override def onUpstreamFinish(): Unit = {
        if (occurrencesFound >= neededOccurrences || !errorOnNotEnough) {
          completeStage()
        } else {
          failStage(OccurrencesNotFoundException(neededOccurrences, occurrencesFound))
        }
      }

      setHandlers(in, out, this)
    }
}

/** Indicates that Occurrences have not been found in the stream */
case class OccurrencesNotFoundException(neededCount: Int, actualCount: Int)
    extends RuntimeException(s"Only found $actualCount out of $neededCount occurrences.")
