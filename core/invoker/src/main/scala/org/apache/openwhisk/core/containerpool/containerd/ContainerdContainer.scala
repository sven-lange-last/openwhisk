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
import akka.stream.scaladsl.Source
import akka.util.ByteString
import org.apache.openwhisk.common.{Logging, TransactionId}
import org.apache.openwhisk.core.entity.size._
import org.apache.openwhisk.core.containerpool._
import org.apache.openwhisk.core.entity.ByteSize
import org.apache.openwhisk.core.entity.ExecManifest.ImageName

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
      new ContainerdContainer(c._1, c._2)
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
  override def logs(limit: ByteSize, waitForSentinel: Boolean)(
    implicit transid: TransactionId): Source[ByteString, Any] = ???
}
