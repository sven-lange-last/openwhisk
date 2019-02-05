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
import java.nio.file.Path

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpMethods, HttpRequest, StatusCode}
import org.apache.openwhisk.common.{Logging, TransactionId}
import org.apache.openwhisk.core.containerpool.ContainerId
import org.apache.openwhisk.core.containerpool.containerd.model.{Container, Version, VersionJsonProtocol}
import org.apache.openwhisk.core.containerpool.containerd.model.ContainerJsonProtocol.ContainerFormat
import org.apache.openwhisk.core.containerpool.containerd.model.VersionJsonProtocol.VersionFormat

import scala.collection.immutable.Seq
import scala.concurrent.{ExecutionContext, Future}
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.stream.alpakka.file.scaladsl.FileTailSource
import akka.stream.scaladsl.{FileIO, Source}
import akka.util.ByteString

import scala.concurrent.duration.FiniteDuration

// import spray.json._
import akka.http.scaladsl.unmarshalling._
// import akka.http.scaladsl.common.EntityStreamingSupport
// import akka.http.scaladsl.common.JsonEntityStreamingSupport
import akka.stream.ActorMaterializer

case class BridgeConfig(scheme: String, host: String, port: Int)

class ContainerdClient(config: BridgeConfig)(executionContext: ExecutionContext)(implicit logging: Logging,
                                                                                 actorSystem: ActorSystem)
    extends ContainerdClientAPI {

  implicit private val ec = executionContext

  private implicit val materializer = ActorMaterializer()

  def shutdown(): Future[Unit] = Future.successful(materializer.shutdown())

//  implicit val jsonStreamingSupport: JsonEntityStreamingSupport =
//    EntityStreamingSupport.json()

  def pull(imageToUse: String) = ???

  /**
   * The version number of the docker client cli
   *
   * @return The version of the docker client cli being used by the invoker
   */
  def clientVersion(): Future[Version] = {
    Http()
      .singleRequest(
        HttpRequest(
          method = HttpMethods.GET,
          uri = s"${config.scheme}://${config.host}:${config.port}/${VersionJsonProtocol.requestPath}"))
      .flatMap { response =>
        if (response.status.isSuccess) {
          Unmarshal(response.entity.withoutSizeLimit)
            .to[Version]
        } else {
          // This is important, as it drains the entity stream.
          // Otherwise the connection stays open and the pool dries up.
          response
            .discardEntityBytes()
            .future
            .flatMap(_ =>
              Future.failed(new BridgeCommunicationException(response.status, "could not retrieve client version")))
        }
      }
  }

  /**
   * Deletes the container
   * @param id the ContainerId of the container to delete
   * @return Future
   */
  def delete(id: ContainerId)(implicit transid: TransactionId): Future[Container] = {
    Http()
      .singleRequest(
        HttpRequest(
          method = HttpMethods.DELETE,
          headers = Seq(transid.toHeader),
          uri = s"${config.scheme}://${config.host}:${config.port}/container/${id.asString}"))
      .flatMap { response =>
        if (response.status.isSuccess) {
          Unmarshal(response.entity.withoutSizeLimit).to[Container]
        } else {
          response
            .discardEntityBytes()
            .future
            .flatMap(_ =>
              Future.failed(
                new BridgeCommunicationException(response.status, s"Unable to delete container: ${id.asString}")))
        }
      }
  }

  /**
   * Spawns a container in detached mode.
   *
   * @param image the image to start the container with
   * @param args arguments for the docker run command
   * @return id of the started container
   */
  def createAndRun(image: String, name: String)(implicit transid: TransactionId): Future[Container] = {
    Http()
    .singleRequest(
      HttpRequest(
        uri = s"${config.scheme}://${config.host}:${config.port}/container/${name}",
        headers = Seq(transid.toHeader),
        method = HttpMethods.POST))
    .flatMap { response =>
      if (response.status.isSuccess) {
        Unmarshal(response.entity.withoutSizeLimit).to[Container]
      } else {
        response
          .discardEntityBytes()
          .future
          .flatMap(_ =>
            Future.failed(
              new BridgeCommunicationException(response.status, s"Unable to create container with name: ${name}")))
      }
    }
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



  /**
  /**
 * Gets the IP address of a given container.
 *
 * A container may have more than one network. The container has an
 * IP address in each of these networks such that the network name
 * is needed.
 *
 * @param id the id of the container to get the IP address from
 * @param network name of the network to get the IP address from
 * @return ip of the container
 */
  def inspectIPAddress(id: ContainerId, network: String)(implicit transid: TransactionId): Future[ContainerAddress] =
    ???

  /**
 * Pauses the container with the given id.
 *
 * @param id the id of the container to pause
 * @return a Future completing according to the command's exit-code
 */
  def pause(id: ContainerId)(implicit transid: TransactionId): Future[Unit] = ???

  /**
 * Unpauses the container with the given id.
 *
 * @param id the id of the container to unpause
 * @return a Future completing according to the command's exit-code
 */
  def unpause(id: ContainerId)(implicit transid: TransactionId): Future[Unit] = ???

  /**
 * Removes the container with the given id.
 *
 * @param id the id of the container to remove
 * @return a Future completing according to the command's exit-code
 */
  def rm(id: ContainerId)(implicit transid: TransactionId): Future[Unit] = ???

  /**
 * Returns a list of ContainerIds in the system.
 *
 * @param filters Filters to apply to the 'ps' command
 * @param all Whether or not to return stopped containers as well
 * @return A list of ContainerIds
 */
  def ps(filters: Seq[(String, String)], all: Boolean)(implicit transid: TransactionId): Future[Seq[ContainerId]] = ???

  /**
 * Pulls the given image.
 *
 * @param image the image to pull
 * @return a Future completing once the pull is complete
 */
  def pull(image: String)(implicit transid: TransactionId): Future[Unit] = ???

  /**
 * Determines whether the given container was killed due to
 * memory constraints.
 *
 * @param id the id of the container to check
 * @return a Future containing whether the container was killed or not
 */
  def isOomKilled(id: ContainerId)(implicit transid: TransactionId): Future[Boolean] = ???
**/
}

trait ContainerdClientAPI {

  /**
   * The version number of the docker client cli
   *
   * @return The version of the docker client cli being used by the invoker
   */
  def clientVersion(): Future[Version]

  /**
   * Spawns a container in detached mode.
   *
   * @param image the image to start the container with
   * @param name the name for the to created container
   * @return id of the started container
   */
  def createAndRun(image: String, name: String)(implicit transid: TransactionId): Future[Container]

  /**
   * Deletes the container
   * @param id the ContainerId of the container to delete
   * @return The deleted container
   */
  def delete(id: ContainerId)(implicit transid: TransactionId): Future[Container]
  /**
  /**
 * Gets the IP address of a given container.
 *
 * A container may have more than one network. The container has an
 * IP address in each of these networks such that the network name
 * is needed.
 *
 * @param id the id of the container to get the IP address from
 * @param network name of the network to get the IP address from
 * @return ip of the container
 */
  def inspectIPAddress(id: ContainerId, network: String)(implicit transid: TransactionId): Future[ContainerAddress]

  /**
 * Pauses the container with the given id.
 *
 * @param id the id of the container to pause
 * @return a Future completing according to the command's exit-code
 */
  def pause(id: ContainerId)(implicit transid: TransactionId): Future[Unit]

  /**
 * Unpauses the container with the given id.
 *
 * @param id the id of the container to unpause
 * @return a Future completing according to the command's exit-code
 */
  def unpause(id: ContainerId)(implicit transid: TransactionId): Future[Unit]

  /**
 * Removes the container with the given id.
 *
 * @param id the id of the container to remove
 * @return a Future completing according to the command's exit-code
 */
  def rm(id: ContainerId)(implicit transid: TransactionId): Future[Unit]

  /**
 * Returns a list of ContainerIds in the system.
 *
 * @param filters Filters to apply to the 'ps' command
 * @param all Whether or not to return stopped containers as well
 * @return A list of ContainerIds
 */
  def ps(filters: Seq[(String, String)] = Seq.empty, all: Boolean = false)(
    implicit transid: TransactionId): Future[Seq[ContainerId]]

  /**
 * Pulls the given image.
 *
 * @param image the image to pull
 * @return a Future completing once the pull is complete
 */
  def pull(image: String)(implicit transid: TransactionId): Future[Unit]

  /**
 * Determines whether the given container was killed due to
 * memory constraints.
 *
 * @param id the id of the container to check
 * @return a Future containing whether the container was killed or not
 */
  def isOomKilled(id: ContainerId)(implicit transid: TransactionId): Future[Boolean]
**/
}

case class BridgeCommunicationException(statusCode: StatusCode, message: String) extends Exception(message)
