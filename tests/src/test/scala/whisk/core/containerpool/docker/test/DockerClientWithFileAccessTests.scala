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

import scala.concurrent.Future

import org.junit.runner.RunWith
import org.scalatest.FlatSpec
import org.scalatest.fixture.{ FlatSpec => FixtureFlatSpec }
import org.scalatest.junit.JUnitRunner

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.concurrent.Await
import org.scalatest.Matchers
import common.StreamLogging
import whisk.core.containerpool.docker.ContainerId
import whisk.common.TransactionId
import org.scalatest.BeforeAndAfterEach
import whisk.core.containerpool.docker.ContainerIp
import whisk.core.containerpool.docker.DockerClientWithFileAccess
import spray.json._
import java.nio.charset.StandardCharsets
import java.io.File
import java.io.FileWriter
import java.io.IOException
import scala.language.reflectiveCalls // Needed to invoke publicIpAddressFromFile() method of structural dockerClientForIp extension

@RunWith(classOf[JUnitRunner])
class DockerClientWithFileAccessTestsIp extends FlatSpec with Matchers with StreamLogging with BeforeAndAfterEach {

    override def beforeEach = stream.reset()

    implicit val transid = TransactionId.testing
    val id = ContainerId("Id")

    def await[A](f: Future[A], timeout: FiniteDuration = 500.milliseconds) = Await.result(f, timeout)

    val dockerCommand = "docker"
    val networkInConfigFile = "networkConfig"
    val networkInDockerInspect = "networkInspect"
    val ipInConfigFile = ContainerIp("10.0.0.1")
    val ipInDockerInspect = ContainerIp("10.0.0.2")
    val dockerConfig =
        JsObject("NetworkSettings" ->
            JsObject("Networks" ->
                JsObject(s"${networkInConfigFile}" ->
                    JsObject("IPAddress" -> JsString(s"${ipInConfigFile.asString}")))))

    /* Returns a DockerClient with mocked results */
    def dockerClientForIp(execResult: Future[String], readResult: Future[JsObject]) = new DockerClientWithFileAccess()(global) {
        override val dockerCmd = Seq(dockerCommand)
        override def executeProcess(args: String*)(implicit ec: ExecutionContext) = execResult
        override def configFileContents(configFile: File) = readResult
        // Make protected ipAddressFromFile available for testing - requires reflectiveCalls
        def publicIpAddressFromFile(id: ContainerId, network: String): Future[ContainerIp] = ipAddressFromFile(id, network)
    }

    behavior of "DockerClientWithFileAccess - ipAddressFromFile"

    it should "throw NoSuchElementException if specified network is not in configuration file" in {
        val dc = dockerClientForIp(execResult = Future.failed(new RuntimeException()), readResult = Future.successful(dockerConfig))

        a[NoSuchElementException] should be thrownBy await(dc.publicIpAddressFromFile(id, "no_network"))
    }

    behavior of "DockerClientWithFileAccess - inspectIPAddress"

    it should "read from config file" in {
        val dc = dockerClientForIp(execResult = Future.successful(ipInDockerInspect.asString), readResult = Future.successful(dockerConfig))

        await(dc.inspectIPAddress(id, networkInConfigFile)) shouldBe ipInConfigFile
        logLines.foreach { _ should not include (s"${dockerCommand} inspect") }
    }

    it should "fall back to 'docker inspect' if config file cannot be read" in {
        val dc = dockerClientForIp(execResult = Future.successful(ipInDockerInspect.asString), readResult = Future.failed(new RuntimeException()))

        await(dc.inspectIPAddress(id, networkInDockerInspect)) shouldBe ipInDockerInspect
        logLines.head should include(s"${dockerCommand} inspect")
    }
}

@RunWith(classOf[JUnitRunner])
class DockerClientWithFileAccessTestsLogs extends FixtureFlatSpec with Matchers with StreamLogging with BeforeAndAfterEach {

    override def beforeEach = stream.reset()

    implicit val transid = TransactionId.testing

    behavior of "DockerClientWithFileAccess - rawContainerLogs"

    /* Returns a DockerClient with mocked results */
    def dockerClientForLogs(logFile: File) = new DockerClientWithFileAccess()(global) {
        override def containerLogFile(containerId: ContainerId) = logFile
    }

    def await[A](f: Future[A], timeout: FiniteDuration = 500.milliseconds) = Await.result(f, timeout)

    case class FixtureParam(file: File, writer: FileWriter, dc: DockerClientWithFileAccess)

    def withFixture(test: OneArgTest) = {
        val file = File.createTempFile(this.getClass.getName, test.name.replaceAll("[^a-zA-Z0-9.-]", "_"))
        val writer = new FileWriter(file)
        val dc = dockerClientForLogs(file)

        val fixture = FixtureParam(file, writer, dc)

        try {
            withFixture(test.toNoArgTest(fixture))
        } finally {
            writer.close()
            file.delete()
        }
    }

    def writeLogFile(fixture: FixtureParam, content: String) = {
        fixture.writer.write(content)
        fixture.writer.flush()
    }

    val containerId = ContainerId("Id")

    it should "tolerate an empty log file" in { fixture =>
        val logText = ""
        writeLogFile(fixture, logText)

        val buffer = await(fixture.dc.rawContainerLogs(containerId, fromPos = 0))

        val logContent = new String(buffer.array, buffer.arrayOffset, buffer.position, StandardCharsets.UTF_8)

        logContent shouldBe logText
        stream should have size 0
    }

    it should "read a full log file" in { fixture =>
        val logText = "text"
        writeLogFile(fixture, logText)

        val buffer = await(fixture.dc.rawContainerLogs(containerId, fromPos = 0))
        val logContent = new String(buffer.array, buffer.arrayOffset, buffer.position, StandardCharsets.UTF_8)

        logContent shouldBe logText
        stream should have size 0
    }

    it should "read a log file portion" in { fixture =>
        val logText =
            """Hey, dude-it'z true not sad
              |Take a thrash song and make it better
              |Admit it! Beatallica'z under your skin!
              |So now begin to be a shredder""".stripMargin
        val from = 66 // start at third line...
        val expectedText = logText.substring(from)

        writeLogFile(fixture, logText)

        val buffer = await(fixture.dc.rawContainerLogs(containerId, fromPos = from))
        val logContent = new String(buffer.array, buffer.arrayOffset, buffer.position, StandardCharsets.UTF_8)

        logContent shouldBe expectedText
        stream should have size 0
    }

    it should "provide an empty result on failure" in { fixture =>
        fixture.writer.close()
        fixture.file.delete()

        a[IOException] should be thrownBy await(fixture.dc.rawContainerLogs(containerId, fromPos = 0))
        stream should have size 0
    }
}
