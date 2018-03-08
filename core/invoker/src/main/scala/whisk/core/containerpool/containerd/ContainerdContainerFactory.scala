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

import akka.actor.ActorSystem
import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import whisk.common.Logging
import whisk.common.TransactionId
import whisk.core.WhiskConfig
import whisk.core.containerpool.Container
import whisk.core.containerpool.ContainerFactory
import whisk.core.containerpool.ContainerFactoryProvider
import whisk.core.entity.ByteSize
import whisk.core.entity.ExecManifest
import whisk.core.entity.InstanceId
import scala.concurrent.duration._
import java.util.concurrent.TimeoutException
import pureconfig._
import whisk.core.ConfigKeys
import whisk.core.containerpool.ContainerArgsConfig

class ContainerdContainerFactory(config: WhiskConfig,
                                 instance: InstanceId,
                                 parameters: Map[String, Set[String]],
                                 containerArgs: ContainerArgsConfig =
                                   loadConfigOrThrow[ContainerArgsConfig](ConfigKeys.containerArgs))(
  implicit actorSystem: ActorSystem,
  ec: ExecutionContext,
  logging: Logging)
    extends ContainerFactory {

  /** Initialize container clients */
  implicit val containerd = new ContainerdClient()(ec)
  implicit val cni = new CniClient()(ec)

  /** Create a container using docker cli */
  override def createContainer(tid: TransactionId,
                               name: String,
                               actionImage: ExecManifest.ImageName,
                               userProvidedImage: Boolean,
                               memory: ByteSize)(implicit config: WhiskConfig, logging: Logging): Future[Container] = {
    val image = if (userProvidedImage) {
      actionImage.publicImageName
    } else {
      actionImage.localImageName(config.dockerRegistry, config.dockerImagePrefix, Some(config.dockerImageTag))
    }

    ContainerdContainer.create(
      tid,
      image = image,
      userProvidedImage = userProvidedImage,
      memory = memory,
      cpuShares = config.invokerCoreShare.toInt,
      environment = Map("__OW_API_HOST" -> config.wskApiHost),
      network = containerArgs.network,
      dnsServers = containerArgs.dnsServers,
      name = Some(name),
      useRunc = config.invokerUseRunc,
      parameters ++ containerArgs.extraArgs)
  }

  /** Perform cleanup on init */
  override def init(): Unit = removeAllActionContainers()

  /** Perform cleanup on exit - to be registered as shutdown hook */
  override def cleanup(): Unit = {
    implicit val transid = TransactionId.invoker
    try {
      removeAllActionContainers()
    } catch {
      case e: Exception => logging.error(this, s"Failed to remove action containers: ${e.getMessage}")
    }
  }

  /**
   * Removes all wsk_ containers - regardless of their state
   *
   * If the system in general or Docker in particular has a very
   * high load, commands may take longer than the specified time
   * resulting in an exception.
   *
   * There is no checking whether container removal was successful
   * or not.
   *
   * @throws InterruptedException     if the current thread is interrupted while waiting
   * @throws TimeoutException         if after waiting for the specified time this `Awaitable` is still not ready
   */
  @throws(classOf[TimeoutException])
  @throws(classOf[InterruptedException])
  private def removeAllActionContainers(): Unit = {
    implicit val transid = TransactionId.invoker

    val cleanContainers = containerd.containers().flatMap { containers =>
      logging.info(this, s"Removing ${containers.size} action containers.")
      val removals = containers.map { id =>
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
      Future.sequence(removals)
    }

    Await.ready(cleanContainers, 30.seconds)
  }
}

object ContainerdContainerFactoryProvider extends ContainerFactoryProvider {
  override def getContainerFactory(actorSystem: ActorSystem,
                                   logging: Logging,
                                   config: WhiskConfig,
                                   instanceId: InstanceId,
                                   parameters: Map[String, Set[String]]): ContainerFactory =
    new ContainerdContainerFactory(config, instanceId, parameters)(actorSystem, actorSystem.dispatcher, logging)
}
