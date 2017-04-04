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

package whisk.core.containerpool.test

import scala.concurrent.duration._
import org.scalatest.Matchers
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import akka.util.Timeout
import akka.actor.ActorRef
import org.scalamock.scalatest.MockFactory
import akka.testkit.TestKit
import akka.actor.ActorSystem
import akka.testkit.ImplicitSender
import org.scalatest.FlatSpecLike
import scala.concurrent.duration._
import org.scalatest.BeforeAndAfterAll
import whisk.core.containerpool.WhiskContainer
import whisk.core.containerpool.Container
import whisk.common.TransactionId
import whisk.core.entity.Exec
import whisk.core.entity.ByteSize
import scala.concurrent.Future
import whisk.core.entity.WhiskActivation
import whisk.core.containerpool.Start
import whisk.core.entity.CodeExecAsString
import whisk.core.entity.ExecManifest.RuntimeManifest
import whisk.core.containerpool.NeedWork
import akka.actor.FSM
import akka.actor.FSM.SubscribeTransitionCallBack
import akka.testkit.TestProbe
import akka.actor.FSM.CurrentState
import whisk.core.containerpool.Uninitialized
import akka.actor.FSM.Transition
import whisk.core.containerpool.Starting
import whisk.core.containerpool.Started
import whisk.core.containerpool.PreWarmedData
import whisk.core.entity.size._

@RunWith(classOf[JUnitRunner])
class WhiskContainerTests extends TestKit(ActorSystem("WhiskContainers"))
    with ImplicitSender
    with FlatSpecLike
    with Matchers
    with BeforeAndAfterAll
    with MockFactory {

    override def afterAll {
        TestKit.shutdownActorSystem(system)
    }

    implicit val timeout = Timeout(5.seconds)

    val actionExec = CodeExecAsString(RuntimeManifest("actionKind"), "testCode", None)
    val preWarmRegex = """wsk_\d+_prewarm_actionKind"""

    /** Imitates a StateTimeout in the FSM */
    def timeout(actor: ActorRef) = actor ! FSM.StateTimeout

    /** Common fixtures for all of the tests */
    val pool = TestProbe()
    val container = stub[Container]
    val ack = stubFunction[TransactionId, WhiskActivation, Future[Any]]
    val store = stubFunction[TransactionId, WhiskActivation, Future[Any]]

    behavior of "WhiskContainer"

    it should "create a container given a Start message" in {
        def factory(tid: TransactionId, name: String, exec: Exec, memoryLimit: ByteSize) = {
            tid shouldBe TransactionId.invokerWarmup
            name should fullyMatch regex preWarmRegex
            memoryLimit shouldBe 256.MB

            Future.successful(container)
        }

        //        val machine = pool.childActorOf(WhiskContainer.props(factory, ack, store))
        //        within(timeout.duration) {
        //            pool.send(machine, SubscribeTransitionCallBack(pool.ref))
        //            pool.expectMsg(CurrentState(machine, Uninitialized))
        //            pool.send(machine, Start(actionExec))
        //            pool.expectMsg(Transition(machine, Uninitialized, Starting))
        //            val needWork = pool.expectMsg(NeedWork(PreWarmedData(`container`, actionExec.kind)))
        //            pool.expectMsg(Transition(machine, Starting, Started))
        //        }
    }
}
