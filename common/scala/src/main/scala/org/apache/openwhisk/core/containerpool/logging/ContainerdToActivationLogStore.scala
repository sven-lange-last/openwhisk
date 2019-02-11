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

package org.apache.openwhisk.core.containerpool.logging

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Flow
import akka.util.ByteString
import org.apache.openwhisk.common.TransactionId
import org.apache.openwhisk.core.containerpool.Container
import org.apache.openwhisk.core.entity.{ActivationLogs, ExecutableWhiskAction, Identity, WhiskActivation}
import org.apache.openwhisk.http.Messages
import org.apache.openwhisk.core.database.UserContext

import scala.concurrent.{ExecutionContext, Future}


object ContainerdToActivationLogStore {

  /** Transforms '|' separated strings */
  val toFormattedString: Flow[ByteString, String, NotUsed] = {
    Flow[ByteString].map { bs =>
      val raw = bs.utf8String
      val idx = raw.indexOf('|')
      val idx2 = raw.indexOf('|', idx + 1)  //TODO robustness - dies IndexOutOfBounds -1 if sentinel expectation doesn't match
      s"date-time: '${raw.substring(0, idx)}', stream: '${raw.substring(idx + 1, idx2)}', content: '${raw.substring(idx2 + 1, raw.length)}'"
    }
  }
}

/**
 * Containerd based implementation of a LogStore.
 *
 * Relies on the containerd-bridge implementation details. The containerd bridge writes stdout/stderr
 * to a plain text file separating metadata and actual log output using the | symbol, which is read and split by this store.
 * Logs are written in the activation record itself.
 */
class ContainerdToActivationLogStore(system: ActorSystem) extends LogStore {
  implicit val ec: ExecutionContext = system.dispatcher
  implicit val mat: ActorMaterializer = ActorMaterializer()(system)


  override val containerParameters: Map[String, Set[String]] = Map.empty

  /* As logs are already part of the activation record, just return that bit of it */
  override def fetchLogs(activation: WhiskActivation, context: UserContext): Future[ActivationLogs] =
    Future.successful(activation.logs)

  override def collectLogs(transid: TransactionId,
                           user: Identity,
                           activation: WhiskActivation,
                           container: Container,
                           action: ExecutableWhiskAction): Future[ActivationLogs] = {

    container
      .logs(action.limits.logs.asMegaBytes, action.exec.sentinelledLogs)(transid)
      .via(ContainerdToActivationLogStore.toFormattedString)
      .runWith(Sink.seq)
      .flatMap { seq =>
        val possibleErrors = Set(Messages.logFailure, Messages.truncateLogs(action.limits.logs.asMegaBytes))
        val errored = seq.lastOption.exists(last => possibleErrors.exists(last.contains))
        val logs = ActivationLogs(seq.toVector)
        if (!errored) {
          Future.successful(logs)
        } else {
          Future.failed(LogCollectingException(logs))
        }
      }
  }
}

object ContainerdToActivationLogStoreProvider extends LogStoreProvider {
  override def instance(actorSystem: ActorSystem): LogStore = new ContainerdToActivationLogStore(actorSystem)
}
