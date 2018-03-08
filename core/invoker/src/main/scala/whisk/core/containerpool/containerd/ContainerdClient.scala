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
import java.util.concurrent.Semaphore

import akka.actor.ActorSystem

import scala.collection.concurrent.TrieMap
import scala.concurrent.blocking
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.Failure
import scala.util.Success
import scala.util.Try
import akka.event.Logging.{ErrorLevel, InfoLevel}
import pureconfig.loadConfigOrThrow
import whisk.common.Logging
import whisk.common.LoggingMarkers
import whisk.common.TransactionId
import whisk.core.ConfigKeys
import whisk.core.containerpool.ContainerId
import whisk.core.containerpool.docker.ProcessRunner

import scala.concurrent.duration.Duration

/**
 * Configuration for docker client command timeouts.
 */
case class ContainerdClientTimeoutConfig(run: Duration,
                                         rm: Duration,
                                         pull: Duration,
                                         ps: Duration,
                                         pause: Duration,
                                         unpause: Duration,
                                         inspect: Duration)

/**
 * Serves as interface to the docker CLI tool.
 *
 * Be cautious with the ExecutionContext passed to this, as the
 * calls to the CLI are blocking.
 *
 * You only need one instance (and you shouldn't get more).
 */
class ContainerdClient(dockerHost: Option[String] = None,
                       timeouts: ContainerdClientTimeoutConfig =
                         loadConfigOrThrow[ContainerdClientTimeoutConfig](ConfigKeys.dockerTimeouts))(
  executionContext: ExecutionContext)(implicit log: Logging, as: ActorSystem)
    extends ContainerdApi
    with ProcessRunner {
  implicit private val ec = executionContext

  protected val containerdCmd: Seq[String] = {
    val alternatives = List("/usr/local/bin/ctr")

    val containerdBin = Try {
      alternatives.find(a => Files.isExecutable(Paths.get(a))).get
    } getOrElse {
      throw new FileNotFoundException(s"Couldn't locate ctr binary (tried: ${alternatives.mkString(", ")}).")
    }

    Seq(containerdBin, "--namespace", "wsk-action-container")
  }

  protected val maxParallelRuns = 100
  protected val runSemaphore = new Semaphore( /* permits= */ maxParallelRuns, /* fair= */ true)

  // Docker < 1.13.1 has a known problem: if more than 10 containers are created (docker run)
  // concurrently, there is a good chance that some of them will fail.
  // See https://github.com/moby/moby/issues/29369
  // Use a semaphore to make sure that at most 10 `docker run` commands are active
  // the same time.
  def run(image: String, name: String, args: Seq[String] = Seq.empty[String])(
    implicit transid: TransactionId): Future[ContainerId] = {
    Future {
      blocking {
        // Acquires a permit from this semaphore, blocking until one is available, or the thread is interrupted.
        // Throws InterruptedException if the current thread is interrupted
        runSemaphore.acquire()
      }
    }.flatMap { _ =>
      // Iff the semaphore was acquired successfully
      // ctr --namespace wsk-action-container run --detach --env __OW_API_HOST=todo --with-ns network:/var/run/netns/wsk_1_org_space_action --mount "type=bind,src=/var/run/whisk/action/resolv.conf,dst=/etc/resolv.conf,options=rbind:ro" --mount "type=bind,src=/var/run/whisk/action/hostname,dst=/etc/hostname,options=rbind:ro" --mount "type=bind,src=/var/run/whisk/action/wsk_1_org_space_action.hosts,dst=/etc/hosts,options=rbind:ro" openwhisk-docker-local.artifactory.swg-devops.com/whisk/nodejs6action:whisk-build-7908 wsk_1_org_space_action
      runCmd(
        Seq(
          "run",
          "--detach",
          "--with-ns",
          s"network:/var/run/netns/${name}",
          "--mount",
          "type=bind,src=/var/run/whisk/action/resolv.conf,dst=/etc/resolv.conf,options=rbind:ro",
          "--mount",
          "type=bind,src=/var/run/whisk/action/hostname,dst=/etc/hostname,options=rbind:ro",
          "--mount",
          s"type=bind,src=/var/run/whisk/action/${name}.hosts,dst=/etc/hosts,options=rbind:ro") ++ args ++ Seq(
          image,
          name),
        timeouts.run)
        .andThen {
          // Release the semaphore as quick as possible regardless of the runCmd() result
          case _ => runSemaphore.release()
        }
        .map(_ => ContainerId(name))
    }
  }

//  def inspectIPAddress(id: ContainerId, network: String)(implicit transid: TransactionId): Future[ContainerAddress] =
//    runCmd(
//      Seq("inspect", "--format", s"{{.NetworkSettings.Networks.${network}.IPAddress}}", id.asString),
//      timeouts.inspect).flatMap {
//      case "<no value>" => Future.failed(new NoSuchElementException)
//      case stdout       => Future.successful(ContainerAddress(stdout))
//    }

  // ctr --namespace wsk-action-container tasks pause wsk_1_org_space_action
  def pause(id: ContainerId)(implicit transid: TransactionId): Future[Unit] =
    runCmd(Seq("tasks", "pause", id.asString), timeouts.pause).map(_ => ())

  // ctr --namespace wsk-action-container tasks resume wsk_1_org_space_action
  def resume(id: ContainerId)(implicit transid: TransactionId): Future[Unit] =
    runCmd(Seq("tasks", "resume", id.asString), timeouts.unpause).map(_ => ())

  // ctr --namespace wsk-action-container tasks kill --signal SIGKILL --all wsk_1_org_space_action
  def kill(id: ContainerId)(implicit transid: TransactionId): Future[Unit] =
    runCmd(Seq("tasks", "kill", "--signal", "SIGKILL", "--all", id.asString), timeouts.rm).map(_ => ())

  // ctr --namespace wsk-action-container containers delete wsk_1_org_space_action
  def rm(id: ContainerId)(implicit transid: TransactionId): Future[Unit] =
    runCmd(Seq("containers", "delete", id.asString), timeouts.rm).map(_ => ())

  def stoppableTasks()(implicit transid: TransactionId): Future[Seq[ContainerId]] = {
    // Sample output from 'ctr task list':
    // TASK       PID      STATUS
    // dreamer    17422    RUNNING
    runCmd(Seq("tasks", "list"), timeouts.ps).map { output =>
      // Sequence of output lines - drop the header line
      val statusColIndex = 2
      val candidateTasks = output.lines.toSeq.tail
      // Select all tasks which have status "CREATED", "RUNNING", "PAUSED" or "PAUSING" (3rd column)
      candidateTasks
        .map(_.split(' '))
        .filter { c =>
          c(statusColIndex) == "STOPPED" ||
          c(statusColIndex) == "RUNNING" ||
          c(statusColIndex) == "PAUSED" ||
          c(statusColIndex) == "PAUSING"
        }
        .map { t =>
          ContainerId(t(0))
        }
    }
  }

  def containers()(implicit transid: TransactionId): Future[Seq[ContainerId]] = {
    // 'ctr containers list --quiet' only prints container names
    // without header - one container per line.
    runCmd(Seq("containers", "list", "--quiet"), timeouts.ps).map(_.lines.toSeq.map(ContainerId.apply))
  }

  /**
   * Stores pulls that are currently being executed and collapses multiple
   * pulls into just one. After a pull is finished, the cached future is removed
   * to enable constant updates of an image without changing its tag.
   */
  // ctr --namespace wsk-action-container images pull docker.io/openwhisk/nodejs6action:latest
  private val pullsInFlight = TrieMap[String, Future[Unit]]()
  def pull(image: String)(implicit transid: TransactionId): Future[Unit] =
    pullsInFlight.getOrElseUpdate(image, {
      runCmd(Seq("images", "pull", "docker.io/" + image), timeouts.pull).map(_ => ()).andThen {
        case _ => pullsInFlight.remove(image)
      }
    })

//  def isOomKilled(id: ContainerId)(implicit transid: TransactionId): Future[Boolean] = Future.successful(false)

  private def runCmd(args: Seq[String], timeout: Duration)(implicit transid: TransactionId): Future[String] = {
    val cmd = containerdCmd ++ args
    val start = transid.started(
      this,
      LoggingMarkers.INVOKER_CONTAINERD_CMD(args.head + args.tail.head),
      s"running ${cmd.mkString(" ")} (timeout: $timeout)",
      logLevel = InfoLevel)
    executeProcess(cmd, timeout).andThen {
      case Success(_) => transid.finished(this, start)
      case Failure(t) => transid.failed(this, start, t.getMessage, ErrorLevel)
    }
  }
}

trait ContainerdApi {

  /**
   * Spawns a container in detached mode.
   *
   * @param image the image to start the container with
   * @param args arguments for the ctr run command
   * @return id of the started container
   */
  def run(image: String, name: String, args: Seq[String] = Seq.empty[String])(
    implicit transid: TransactionId): Future[ContainerId]

  /**
   * Gets the IP address of a given container.
   *
   * @param id the id of the container to get the IP address from
   * @param network name of the network to get the IP address from
   * @return ip of the container
   */
//  def inspectIPAddress(id: ContainerId, network: String)(implicit transid: TransactionId): Future[ContainerAddress]

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
  def resume(id: ContainerId)(implicit transid: TransactionId): Future[Unit]

  def kill(id: ContainerId)(implicit transid: TransactionId): Future[Unit]

  /**
   * Removes the container with the given id.
   *
   * @param id the id of the container to remove
   * @return a Future completing according to the command's exit-code
   */
  def rm(id: ContainerId)(implicit transid: TransactionId): Future[Unit]

  def stoppableTasks()(implicit transid: TransactionId): Future[Seq[ContainerId]]
  def containers()(implicit transid: TransactionId): Future[Seq[ContainerId]]

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
//  def isOomKilled(id: ContainerId)(implicit transid: TransactionId): Future[Boolean]
}

/** Indicates any error while starting a container that leaves a broken container behind that needs to be removed */
// case class BrokenDockerContainer(id: ContainerId, msg: String) extends Exception(msg)
