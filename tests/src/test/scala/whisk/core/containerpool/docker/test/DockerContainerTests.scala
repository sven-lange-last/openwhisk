/*
 * Copyright 2015-2016 IBM Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package whisk.core.containerpool.docker.test

import java.time.Instant

import scala.collection.mutable
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

import common.StreamLogging
import org.junit.runner.RunWith
import org.scalamock.scalatest.MockFactory
import org.scalatest.BeforeAndAfterEach
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.FlatSpec
import org.scalatest.junit.JUnitRunner
import org.scalatest.Matchers
import spray.json.JsObject
import whisk.common.LoggingMarkers._
import whisk.common.LogMarker
import whisk.common.TransactionId
import whisk.core.container.Interval
import whisk.core.container.RunResult
import whisk.core.containerpool._
import whisk.core.containerpool.docker._
import whisk.core.entity.ActivationResponse
import whisk.core.entity.ActivationResponse.ContainerResponse
import whisk.core.entity.ActivationResponse.Timeout
import whisk.core.entity.size._
import whisk.http.Messages
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/**
 * Unit tests for ContainerPool schedule
 */
@RunWith(classOf[JUnitRunner])
class DockerContainerTests extends FlatSpec
    with Matchers
    with MockFactory
    with StreamLogging
    with ScalaFutures
    with BeforeAndAfterEach {

    override def beforeEach() = {
        stream.reset()
    }

    /** Awaits the given future, throws the exception enclosed in Failure. */
    def await[A](f: Future[A], timeout: FiniteDuration = 100.millisecond) = Await.result[A](f, timeout)

    /**
     * Constructs a testcontainer with overridden IO methods. Results of the override can be provided
     * as parameters.
     */
    def dockerContainer(id: ContainerId = ContainerId("id"), ip: ContainerIp = ContainerIp("ip"))(ccRes: Future[RunResult])(
        implicit docker: DockerApiWithFileAccess, runc: RuncApi): DockerContainer = {

        new DockerContainer(id, ip) {
            override protected def callContainer(path: String, body: JsObject, timeout: FiniteDuration, retry: Boolean = false): Future[RunResult] = {
                ccRes
            }
        }
    }

    /** Creates an interval starting at EPOCH with the given duration. */
    def intervalOf(duration: FiniteDuration) = Interval(Instant.EPOCH, Instant.ofEpochMilli(duration.toMillis))

    behavior of "DockerContainer"

    implicit val transid = TransactionId.testing

    /*
     * CONTAINER CREATION
     */
    it should "create a new instance" in {
        implicit val docker = new TestDockerClient
        implicit val runc = stub[RuncApi]

        val image = "image"
        val memory = 128.MB
        val cpuShares = 1
        val environment = Map("test" -> "hi")
        val network = "testwork"
        val name = "myContainer"

        val container = DockerContainer.create(
            transid = transid,
            image = image,
            memory = memory,
            cpuShares = cpuShares,
            environment = environment,
            network = network,
            name = Some(name))

        await(container)

        docker.runs should have size 1
        docker.pulls should have size 0
        docker.rms should have size 0

        val (testImage, args) = docker.runs.head
        testImage shouldBe "image"

        // Assert fixed values are passed as well
        args should contain allOf ("--cap-drop", "NET_RAW", "NET_ADMIN")
        args should contain inOrder ("--ulimit", "nofile=1024:1024")
        args should contain inOrder ("--pids-limit", "1024") // OW PR 2119

        // Assert proper parameter translation
        args should contain inOrder ("--memory", s"${memory.toMB}m")
        args should contain inOrder ("--memory-swap", s"${memory.toMB}m")
        args should contain inOrder ("--cpu-shares", cpuShares.toString)
        args should contain inOrder ("--network", network)
        args should contain inOrder ("--name", name)

        // Assert proper environment passing
        args should contain allOf ("-e", "test=hi", "SERVICE_IGNORE=true")
    }

    it should "pull a user provided image before creating the container" in {
        implicit val docker = new TestDockerClient
        implicit val runc = stub[RuncApi]

        val container = DockerContainer.create(transid = transid, image = "image", userProvidedImage = true)
        await(container)

        docker.runs should have size 1
        docker.pulls should have size 1
        docker.rms should have size 0
    }

    it should "remove the container if inspect fails" in {
        implicit val docker = new TestDockerClient {
            override def inspectIPAddress(id: ContainerId, network: String)(implicit transid: TransactionId): Future[ContainerIp] = {
                inspects += ((id, network))
                Future.failed(new RuntimeException())
            }
        }
        implicit val runc = stub[RuncApi]

        val container = DockerContainer.create(transid = transid, image = "image")
        a[WhiskContainerStartupError] should be thrownBy await(container)

        docker.runs should have size 1
        docker.inspects should have size 1
        docker.rms should have size 1
    }

    it should "disambiguate errors if user images are provided" in {
        implicit val docker = new TestDockerClient {
            override def inspectIPAddress(id: ContainerId, network: String)(implicit transid: TransactionId): Future[ContainerIp] = {
                inspects += ((id, network))
                Future.failed(new RuntimeException())
            }
        }
        implicit val runc = stub[RuncApi]

        val container = DockerContainer.create(transid = transid, image = "image", userProvidedImage = true)
        a[BlackboxStartupError] should be thrownBy await(container)

        docker.pulls should have size 1
        docker.runs should have size 1
        docker.inspects should have size 1
        docker.rms should have size 1
    }

    it should "return a specific error if pulling a user provided image failed" in {
        implicit val docker = new TestDockerClient {
            override def pull(image: String)(implicit transid: TransactionId): Future[Unit] = {
                pulls += image
                Future.failed(new RuntimeException())
            }
        }
        implicit val runc = stub[RuncApi]

        val container = DockerContainer.create(transid = transid, image = "image", userProvidedImage = true)
        a[BlackboxStartupError] should be thrownBy await(container)

        docker.pulls should have size 1
        docker.runs should have size 0
        docker.inspects should have size 0
        docker.rms should have size 0
    }

    /*
     * DOCKER COMMANDS
     */
    it should "halt and resume container via runc" in {
        implicit val docker = stub[DockerApiWithFileAccess]
        implicit val runc = stub[RuncApi]

        val id = ContainerId("id")
        val container = new DockerContainer(id, ContainerIp("ip"))

        container.halt()
        container.resume()

        (runc.pause(_: ContainerId)(_: TransactionId)).verify(id, transid)
        (runc.resume(_: ContainerId)(_: TransactionId)).verify(id, transid)
    }

    it should "destroy a container via Docker" in {
        implicit val docker = stub[DockerApiWithFileAccess]
        implicit val runc = stub[RuncApi]

        val id = ContainerId("id")
        val container = new DockerContainer(id, ContainerIp("ip"))

        container.destroy()

        (docker.rm(_: ContainerId)(_: TransactionId)).verify(id, transid)
    }

    /*
     * INITIALIZE
     *
     * Only tests for quite simple cases. Disambiguation of errors is delegated to ActivationResponse
     * and so are the tests for those.
     */
    it should "initialize a container" in {
        implicit val docker = stub[DockerApiWithFileAccess]
        implicit val runc = stub[RuncApi]

        val interval = intervalOf(1.millisecond)
        val container = dockerContainer() {
            Future.successful(RunResult(interval, Right(ContainerResponse(true, "", None))))
        }

        val initInterval = container.initialize(JsObject(), 1.second)
        initInterval.futureValue shouldBe interval

        // assert the starting log is there
        val start = LogMarker.parse(logLines.head)
        start.token shouldBe INVOKER_ACTIVATION_INIT

        // assert the end log is there
        val end = LogMarker.parse(logLines.last)
        end.token shouldBe INVOKER_ACTIVATION_INIT.asFinish
        end.deltaToMarkerStart shouldBe Some(interval.duration.toMillis)
    }

    it should "properly deal with a timeout during initialization" in {
        implicit val docker = stub[DockerApiWithFileAccess]
        implicit val runc = stub[RuncApi]

        val initTimeout = 1.second
        val interval = intervalOf(initTimeout + 1.nanoseconds)

        val container = dockerContainer() {
            Future.successful(RunResult(interval, Left(Timeout())))
        }

        val init = container.initialize(JsObject(), initTimeout)

        val error = the[InitializationError] thrownBy await(init)
        error.interval shouldBe interval
        error.response.statusCode shouldBe ActivationResponse.ApplicationError

        // assert the finish log is there
        val end = LogMarker.parse(logLines.last)
        end.token shouldBe INVOKER_ACTIVATION_INIT.asFinish
    }

    /*
     * RUN
     *
     * Only tests for quite simple cases. Disambiguation of errors is delegated to ActivationResponse
     * and so are the tests for those.
     */
    it should "run a container" in {
        implicit val docker = stub[DockerApiWithFileAccess]
        implicit val runc = stub[RuncApi]

        val interval = intervalOf(1.millisecond)
        val result = JsObject()
        val container = dockerContainer() {
            Future.successful(RunResult(interval, Right(ContainerResponse(true, result.compactPrint, None))))
        }

        val runResult = container.run(JsObject(), JsObject(), 1.second)
        runResult.futureValue shouldBe (interval, ActivationResponse.success(Some(result)))

        // assert the starting log is there
        val start = LogMarker.parse(logLines.head)
        start.token shouldBe INVOKER_ACTIVATION_RUN

        // assert the end log is there
        val end = LogMarker.parse(logLines.last)
        end.token shouldBe INVOKER_ACTIVATION_RUN.asFinish
        end.deltaToMarkerStart shouldBe Some(interval.duration.toMillis)
    }

    it should "properly deal with a timeout during run" in {
        implicit val docker = stub[DockerApiWithFileAccess]
        implicit val runc = stub[RuncApi]

        val runTimeout = 1.second
        val interval = intervalOf(runTimeout + 1.nanoseconds)

        val container = dockerContainer() {
            Future.successful(RunResult(interval, Left(Timeout())))
        }

        val runResult = container.run(JsObject(), JsObject(), runTimeout)

        runResult.futureValue shouldBe (interval, ActivationResponse.applicationError(Messages.timedoutActivation(runTimeout, false)))

        // assert the finish log is there
        val end = LogMarker.parse(logLines.last)
        end.token shouldBe INVOKER_ACTIVATION_RUN.asFinish
    }

    /*
     * LOGS
     */

    class TestDockerClient extends DockerApiWithFileAccess {
        var runs = mutable.Buffer.empty[(String, Seq[String])]
        var inspects = mutable.Buffer.empty[(ContainerId, String)]
        var pauses = mutable.Buffer.empty[ContainerId]
        var unpauses = mutable.Buffer.empty[ContainerId]
        var rms = mutable.Buffer.empty[ContainerId]
        var pulls = mutable.Buffer.empty[String]
        var logs = mutable.Buffer.empty[(ContainerId, Long)]

        def run(image: String, args: Seq[String] = Seq.empty[String])(implicit transid: TransactionId): Future[ContainerId] = {
            runs += ((image, args))
            Future.successful(ContainerId("testId"))
        }

        def inspectIPAddress(id: ContainerId, network: String)(implicit transid: TransactionId): Future[ContainerIp] = {
            inspects += ((id, network))
            Future.successful(ContainerIp("testIp"))
        }

        def pause(id: ContainerId)(implicit transid: TransactionId): Future[Unit] = {
            pauses += id
            Future.successful(())
        }

        def unpause(id: ContainerId)(implicit transid: TransactionId): Future[Unit] = {
            unpauses += id
            Future.successful(())
        }

        def rm(id: ContainerId)(implicit transid: TransactionId): Future[Unit] = {
            rms += id
            Future.successful(())
        }

        def ps(filters: Seq[(String, String)] = Seq(), all: Boolean = false)(implicit transid: TransactionId): Future[Seq[ContainerId]] = ???

        def pull(image: String)(implicit transid: TransactionId): Future[Unit] = {
            pulls += image
            Future.successful(())
        }

        def rawContainerLogs(containerId: ContainerId, fromPos: Long): Future[ByteBuffer] = {
            logs += ((containerId, fromPos))
            Future.successful(ByteBuffer.wrap("".getBytes(StandardCharsets.UTF_8)))
        }
    }
}

