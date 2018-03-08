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

package whisk.core.containerpool.containerd

import java.time.Instant

import akka.actor.ActorSystem

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import whisk.common.Logging
import whisk.common.TransactionId
import whisk.core.containerpool._
import whisk.core.entity.ActivationResponse.{ConnectionError, MemoryExhausted}
import whisk.core.entity.{ActivationEntityLimit, ByteSize}
import whisk.core.entity.size._
import akka.stream.scaladsl.Source
import akka.util.ByteString
import spray.json._
import whisk.core.containerpool.logging.LogLine
import whisk.http.Messages

object ContainerdContainer {

  /**
   * Creates a container running on a docker daemon.
   *
   * @param transid transaction creating the container
   * @param image image to create the container from
   * @param userProvidedImage whether the image is provided by the user
   *     or is an OpenWhisk provided image
   * @param memory memorylimit of the container
   * @param cpuShares sharefactor for the container
   * @param environment environment variables to set on the container
   * @param network network to launch the container in
   * @param dnsServers list of dns servers to use in the container
   * @param name optional name for the container
   * @param useRunc use docker-runc to pause/unpause container?
   * @return a Future which either completes with a DockerContainer or one of two specific failures
   */
  def create(transid: TransactionId,
             image: String,
             userProvidedImage: Boolean = false,
             memory: ByteSize = 256.MB,
             cpuShares: Int = 0,
             environment: Map[String, String] = Map.empty,
             network: String = "bridge",
             dnsServers: Seq[String] = Seq.empty,
             name: Option[String] = None,
             useRunc: Boolean = true,
             dockerRunParameters: Map[String, Set[String]])(implicit containerd: ContainerdApi,
                                                            cni: CniApi,
                                                            as: ActorSystem,
                                                            ec: ExecutionContext,
                                                            log: Logging): Future[ContainerdContainer] = {
    implicit val tid: TransactionId = transid

    val environmentArgs = environment.flatMap {
      case (key, value) => Seq("-e", s"$key=$value")
    }.toSeq

    for {
      cname <- name
        .map(n => Future.successful(n))
        .getOrElse(Future.failed(WhiskContainerStartupError("No name specified")))
      _ <- if (userProvidedImage) {
        containerd.pull(image).recoverWith {
          case _ => Future.failed(BlackboxStartupError(Messages.imagePullError(image)))
        }
      } else Future.successful(())
      ip <- cni.addNetwork(cname).recoverWith {
        case _ => Future.failed(WhiskContainerStartupError("Failed to set up container network"))
      }
      id <- containerd.run(image, cname, environmentArgs).recoverWith {
        case _ =>
          cni.deleteNetwork(cname)
          Future.failed(WhiskContainerStartupError(Messages.resourceProvisionError))
      }
    } yield new ContainerdContainer(id, ip)
  }
}

/**
 * Represents a container as run by containerd.
 *
 * @constructor
 * @param id the id of the container
 * @param addr the ip of the container
 */
class ContainerdContainer(protected val id: ContainerId, protected val addr: ContainerAddress)(
  implicit containerd: ContainerdApi,
  cni: CniApi,
  as: ActorSystem,
  protected val ec: ExecutionContext,
  protected val logging: Logging)
    extends Container {

  def suspend()(implicit transid: TransactionId): Future[Unit] = containerd.pause(id)
  def resume()(implicit transid: TransactionId): Future[Unit] = containerd.resume(id)
  override def destroy()(implicit transid: TransactionId): Future[Unit] = {
    super.destroy()
    // * containerd.rm() must only be executed if containerd.kill() was successful
    // * cni.deleteNetwork() must be executed after the containerd.kill() /
    //   containerd.rm() sequence - regardless of the sequence's result
    // * If any containerd.kill() / containerd.rm() / cni.deleteNetwork()
    //   fail, return a failed Future with the first failure,
    //   otherwise return a successful Future
    containerd
      .kill(id)
      .flatMap(_ => containerd.rm(id))
      .recoverWith {
        // Either kill() or rm() failed: deleteNetwork(), but return original failure
        case t =>
          cni.deleteNetwork(id.asString).recoverWith { case _ => Future.failed(t) }.flatMap(_ => Future.failed(t))
      }
      .flatMap(_ => cni.deleteNetwork(id.asString))
  }

  /**
   * Was the container killed due to memory exhaustion?
   *
   * Retries because as all docker state-relevant operations, they won't
   * be reflected by the respective commands immediately but will take
   * some time to be propagated.
   *
   * @param retries number of retries to make
   * @return a Future indicating a memory exhaustion situation
   */
  private def isOomKilled(retries: Int = 0)(implicit transid: TransactionId): Future[Boolean] = Future.successful(false)

  override protected def callContainer(path: String, body: JsObject, timeout: FiniteDuration, retry: Boolean = false)(
    implicit transid: TransactionId): Future[RunResult] = {
    val started = Instant.now()
    val http = httpConnection.getOrElse {
      val conn = new HttpUtils(s"${addr.host}:${addr.port}", timeout, ActivationEntityLimit.MAX_ACTIVATION_ENTITY_LIMIT)
      httpConnection = Some(conn)
      conn
    }
    Future {
      http.post(path, body, retry)
    }.flatMap { response =>
      val finished = Instant.now()

      response.left
        .map {
          // Only check for memory exhaustion if there was a
          // terminal connection error.
          case error: ConnectionError =>
            isOomKilled().map {
              case true  => MemoryExhausted()
              case false => error
            }
          case other => Future.successful(other)
        }
        .fold(_.map(Left(_)), right => Future.successful(Right(right)))
        .map(res => RunResult(Interval(started, finished), res))
    }
  }

  /**
   * Obtains the container's stdout and stderr output and converts it to our own JSON format.
   * At the moment, this is done by reading the internal Docker log file for the container.
   * Said file is written by Docker's JSON log driver and has a "well-known" location and name.
   *
   * For warm containers, the container log file already holds output from
   * previous activations that have to be skipped. For this reason, a starting position
   * is kept and updated upon each invocation.
   *
   * If asked, check for sentinel markers - but exclude the identified markers from
   * the result returned from this method.
   *
   * Only parses and returns as much logs as fit in the passed log limit.
   *
   * @param limit the limit to apply to the log size
   * @param waitForSentinel determines if the processor should wait for a sentinel to appear
   *
   * @return a vector of Strings with log lines in our own JSON format
   */
  def logs(limit: ByteSize, waitForSentinel: Boolean)(implicit transid: TransactionId): Source[ByteString, Any] = {
    Source.single(ByteString(LogLine(Instant.now.toString, "stdout", Messages.logFailure).toJson.compactPrint))
  }
}
