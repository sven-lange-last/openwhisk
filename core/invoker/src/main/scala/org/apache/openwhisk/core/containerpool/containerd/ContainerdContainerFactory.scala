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
import scala.concurrent.ExecutionContext
import org.apache.openwhisk.common.{Logging, TransactionId}
import org.apache.openwhisk.core.{ConfigKeys, WhiskConfig}
import org.apache.openwhisk.core.containerpool.{Container, ContainerFactory, ContainerFactoryProvider}
import org.apache.openwhisk.core.entity.{ByteSize, ExecManifest, InvokerInstanceId}
import pureconfig.loadConfigOrThrow

import scala.concurrent.Future

class ContainerdContainerFactory(instanceId: InvokerInstanceId, parameters: Map[String, Set[String]])(
  implicit actorSystem: ActorSystem,
  ec: ExecutionContext,
  logging: Logging,
  client: ContainerdClient)
    extends ContainerFactory {

  /** create a new Container */
  override def createContainer(tid: TransactionId,
                               name: String,
                               actionImage: ExecManifest.ImageName,
                               userProvidedImage: Boolean,
                               memory: ByteSize,
                               cpuShares: Int)(implicit config: WhiskConfig, logging: Logging): Future[Container] = {
    ContainerdContainer.create(
      tid,
      if (userProvidedImage) Left(actionImage) else Right(actionImage.localImageName(config.runtimesRegistry)),
      memory,
      environment = Map("__OW_API_HOST" -> config.wskApiHost),
      name = Some(name))
  }

  def purgeAllActionContainers(): Unit = {
    //TODO Implement
    println("TODO purge all containers")
  }

  /** perform any initialization */
  override def init(): Unit = purgeAllActionContainers()

  /** cleanup any remaining Containers; should block until complete; should ONLY be run at startup/shutdown */
  override def cleanup(): Unit = {
    implicit val transid = TransactionId.invoker
    try {
      purgeAllActionContainers()
    } catch {
      case e: Exception => logging.error(this, s"Failed to remove action containers: ${e.getMessage}")
    }
  }

}

object ContainerdContainerFactoryProvider extends ContainerFactoryProvider {
  override def instance(actorSystem: ActorSystem,
                        logging: Logging,
                        config: WhiskConfig,
                        instanceId: InvokerInstanceId,
                        parameters: Map[String, Set[String]]): ContainerFactory = {
    new ContainerdContainerFactory(instanceId, parameters)(
      actorSystem,
      actorSystem.dispatcher,
      logging,
      new ContainerdClient(loadConfigOrThrow[BridgeConfig](ConfigKeys.containerdContainerFactoryBridge))(
        actorSystem.dispatcher)(logging, actorSystem))
  }
}
