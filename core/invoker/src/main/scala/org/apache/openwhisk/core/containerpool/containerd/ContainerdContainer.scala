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

package org.apache.openwhisk.core.containerpool.containerd
import java.io.File
import java.util.concurrent.TimeoutException

import akka.actor.ActorSystem
import akka.stream._
import akka.stream.scaladsl.Framing.FramingException
import akka.stream.scaladsl.{Framing, Source}
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import akka.util.ByteString
import org.apache.openwhisk.common.{Logging, TransactionId}
import org.apache.openwhisk.core.entity.size._
import org.apache.openwhisk.core.containerpool._
import org.apache.openwhisk.core.entity.ByteSize
import org.apache.openwhisk.core.entity.ExecManifest.ImageName
import org.apache.openwhisk.http.Messages

import scala.concurrent.duration._
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}

object ContainerdContainer {

  private val byteStringSentinel = ByteString(Container.ACTIVATION_LOG_SENTINEL)

  /**
   * Creates a container running on a docker daemon.
   *
   * @param transid transaction creating the container
   * @param image either a user provided (Left) or OpenWhisk provided (Right) image
   * @param memory memorylimit of the container
   * @param environment environment variables to set on the container
   * @param name optional name for the container
   * @return a Future which either completes with a ContainerdContainer or one of two specific failures
   */
  def create(transid: TransactionId,
             image: Either[ImageName, String],
             memory: ByteSize = 256.MB,
             environment: Map[String, String] = Map.empty,
             name: Option[String] = None)(implicit containerdClient: ContainerdClient,
                                          as: ActorSystem,
                                          ec: ExecutionContext,
                                          log: Logging): Future[ContainerdContainer] = {
    implicit val tid: TransactionId = transid

    /*val environmentArgs = environment.flatMap {
      case (key, value) => Seq("-e", s"$key=$value")
    }*/

    val imageToUse = image.fold(_.publicImageName, identity)

    //TODO consider pull handling for blackbox image support

    containerdClient.createAndRun(imageToUse, name.getOrElse("s")).map { c =>
      new ContainerdContainer(ContainerId(c.name), ContainerAddress("localhost"))
    }
  }
}

class ContainerdContainer(protected val id: ContainerId, protected val addr: ContainerAddress)(
  implicit containerdClient: ContainerdClient,
  override protected val as: ActorSystem,
  protected val ec: ExecutionContext,
  protected val logging: Logging)
    extends Container {

  /** Stops the container from consuming CPU cycles. NOT thread-safe - caller must synchronize. */
  override def suspend()(implicit transid: TransactionId): Future[Unit] =
    super.suspend()

  /** Dual of halt. NOT thread-safe - caller must synchronize.*/
  override def resume()(implicit transid: TransactionId): Future[Unit] = super.resume()

  /** Completely destroys this instance of the container. */
  override def destroy()(implicit transid: TransactionId): Future[Unit] = {
    super.destroy()
    containerdClient.delete(id).map(_ => ())
  }

  /** Obtains logs up to a given threshold from the container. Optionally waits for a sentinel to appear. */
  override def logs(limit: ByteSize, waitForSentinel: Boolean)(implicit transid: TransactionId): Source[ByteString, Any] = {
    //TODO get path to logfile from ContainerdContainer
    containerdClient
      .rawContainerLogs((new File("/tmp/s.log")).toPath, 0L, if (waitForSentinel) Some(filePollInterval) else None)
      // This stage only throws 'FramingException' so we cannot decide whether we got truncated due to a size
      // constraint (like StreamLimitReachedException below) or due to the file being truncated itself.
      .via(Framing.delimiter(delimiter, limit.toBytes.toInt))
      .limitWeighted(limit.toBytes) { obj =>
        // Adding + 1 since we know there's a newline byte being read
        val size = obj.size + 1
        size
      }
      .via(new CompleteAfterOccurrences(_.containsSlice(ContainerdContainer.byteStringSentinel), 2, waitForSentinel))
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

  /** Delimiter used to split log-lines as written by the json-log-driver. */
  private val delimiter = ByteString("\n")
  protected val waitForLogs: FiniteDuration = 2.seconds
  protected val filePollInterval: FiniteDuration = 5.milliseconds

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
