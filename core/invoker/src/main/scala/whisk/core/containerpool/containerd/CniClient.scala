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

import java.io.FileNotFoundException
import java.nio.file.Files
import java.nio.file.Paths

import akka.actor.ActorSystem

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.Failure
import scala.util.Success
import scala.util.Try
import akka.event.Logging.{ErrorLevel, InfoLevel}
import whisk.common.Logging
import whisk.common.LoggingMarkers
import whisk.common.TransactionId
import whisk.core.containerpool.ContainerAddress
import whisk.core.containerpool.docker.ProcessRunner

import scala.concurrent.duration._

class CniClient()(executionContext: ExecutionContext)(implicit log: Logging, as: ActorSystem)
    extends CniApi
    with ProcessRunner {
  implicit private val ec = executionContext

  protected val cniCmd: Seq[String] = {
    val cniAlternatives = List("/usr/local/bin/cni-full.sh")

    val cniBin = Try {
      cniAlternatives.find(a => Files.isExecutable(Paths.get(a))).get
    } getOrElse {
      throw new FileNotFoundException(s"Couldn't locate cni binary (tried: ${cniAlternatives.mkString(", ")}).")
    }

    val nsEnterAlternatives = List("/usr/bin/nsenter")

    val nsEnterBin = Try {
      nsEnterAlternatives.find(a => Files.isExecutable(Paths.get(a))).get
    } getOrElse {
      throw new FileNotFoundException(s"Couldn't locate nsenter binary (tried: ${nsEnterAlternatives.mkString(", ")}).")
    }

    // nsenter --target 1 --net /usr/local/bin/cni-full.sh <ADD|DEL> <Container name>
    Seq(nsEnterBin, "--target", "1", "--net", cniBin)
  }

  // nsenter --target 1 --net /usr/local/bin/cni-full.sh ADD wsk_1_org_space_action
  def addNetwork(name: String): Future[ContainerAddress] =
    runCmd(Seq("ADD", name), 30.seconds).map(ContainerAddress(_))

  // nsenter --target 1 --net /usr/local/bin/cni-full.sh DEL wsk_1_org_space_action
  def deleteNetwork(name: String): Future[Unit] =
    runCmd(Seq("DEL", name), 30.seconds).map(_ => ())

  private def runCmd(args: Seq[String], timeout: Duration)(implicit transid: TransactionId): Future[String] = {
    val cmd = cniCmd ++ args
    val start = transid.started(
      this,
      LoggingMarkers.INVOKER_CNI_CMD(args.head),
      s"running ${cmd.mkString(" ")} (timeout: $timeout)",
      logLevel = InfoLevel)
    executeProcess(cmd, timeout).andThen {
      case Success(_) => transid.finished(this, start)
      case Failure(t) => transid.failed(this, start, t.getMessage, ErrorLevel)
    }
  }
}

trait CniApi {
  def addNetwork(name: String): Future[ContainerAddress]
  def deleteNetwork(name: String): Future[Unit]
}
