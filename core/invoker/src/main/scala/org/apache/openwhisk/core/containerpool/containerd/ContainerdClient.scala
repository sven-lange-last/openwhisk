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
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpMethods, HttpRequest, StatusCode}
import org.apache.openwhisk.common.{Logging, TransactionId}
import org.apache.openwhisk.core.containerpool.{ContainerAddress, ContainerId}
import org.apache.openwhisk.core.containerpool.containerd.model.{Container, Version, VersionResponse}
import org.apache.openwhisk.core.containerpool.containerd.model.VersionJsonProtocol.VersionResponseFormat
import org.apache.openwhisk.core.containerpool.containerd.model.ContainerJsonProtocol.ContainerFormat

import scala.concurrent.{ExecutionContext, Future}
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
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
  def clientVersion(): Future[Either[Version, StatusCode]] = {
    Http()
      .singleRequest(
        HttpRequest(
          method = HttpMethods.GET,
          uri = s"${config.scheme}://${config.host}:${config.port}/${Version.requestPath}"))
      .flatMap { response =>
        if (response.status.isSuccess) {
          Unmarshal(response.entity.withoutSizeLimit)
            .to[VersionResponse]
            .map(vr => Left.apply(Version.fromVersionResponse(vr)))
        } else {
          // This is important, as it drains the entity stream.
          // Otherwise the connection stays open and the pool dries up.
          response.discardEntityBytes().future.map(_ => Right(response.status))
        }
      }
  }

  /**
   * Deletes the container
   * @param name the name of the container to delete
   * @return The version of the docker client cli being used by the invoker
   */
  def delete(id: ContainerId)(implicit transid: TransactionId): Future[Either[Container, StatusCode]] = {
    Http()
      .singleRequest(
        HttpRequest(
          method = HttpMethods.DELETE,
          uri = s"${config.scheme}://${config.host}:${config.port}/container/${id.asString}"))
      .flatMap { response =>
        if (response.status.isSuccess) {
          Unmarshal(response.entity.withoutSizeLimit).to[Container].map(Left.apply(_))
        } else {
          // This is important, as it drains the entity stream.
          // Otherwise the connection stays open and the pool dries up.
          response.discardEntityBytes().future.map(_ => Right(response.status))
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
  def createAndRun(image: String, name: String)(
    implicit transid: TransactionId): Future[(ContainerId, ContainerAddress)] = {
    Http()
      .singleRequest(
        HttpRequest(
          uri = s"${config.scheme}://${config.host}:${config.port}/container/${name}",
          method = HttpMethods.POST))
      .map { _ =>
        (ContainerId(name), ContainerAddress("127.0.0.1"))
      }
  }

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
}

trait ContainerdClientAPI {

  /**
   * The version number of the docker client cli
   *
   * @return The version of the docker client cli being used by the invoker
   */
  def clientVersion(): Future[Either[Version, StatusCode]]

  /**
   * Spawns a container in detached mode.
   *
   * @param image the image to start the container with
   * @param args arguments for the docker run command
   * @return id of the started container
   */
  def createAndRun(image: String, name: String)(
    implicit transid: TransactionId): Future[(ContainerId, ContainerAddress)]

  /**
   * Deletes the container
   * @param name the name of the container to delete
   * @return The version of the docker client cli being used by the invoker
   */
  def delete(id: ContainerId)(implicit transid: TransactionId): Future[Either[Container, StatusCode]]

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
}
