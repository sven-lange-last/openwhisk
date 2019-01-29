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


package org.apache.openwhisk.core.containerpool.containerd.model

import spray.json._
// import DefaultJsonProtocol._
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import org.apache.openwhisk.core.containerpool.containerd.model.BridgeJsonProtocol.BridgeFormat
import org.apache.openwhisk.core.containerpool.containerd.model.ContainerdJsonProtocol.ContainerdFormat


case class Containerd(id: String, description: String, fullVersion: String, major: Int, minor: Int, patch: Int)
case class Bridge(id: String, description: String, fullVersion: String, major: Int, minor: Int, patch: Int)
case class Version(bridge: Bridge, containerd: Containerd)

object BridgeJsonProtocol extends SprayJsonSupport with DefaultJsonProtocol {
  implicit val BridgeFormat = jsonFormat6(Bridge)
}

object ContainerdJsonProtocol extends SprayJsonSupport with DefaultJsonProtocol {
  implicit val ContainerdFormat = jsonFormat6(Containerd)
}

object VersionJsonProtocol extends SprayJsonSupport with DefaultJsonProtocol {
  implicit val VersionFormat = jsonFormat2(Version)
  val requestPath = "version"
}

/**
{
  "bridge": {
    "component": "containerd-bridge",
    "description": "A bridge between OpenWhisk invoker using the ContainerdContainerFactory and containerd",
    "fullVersion": "0.0.1",
    "major": 0,
    "minor": 0,
    "patch": 1
  },
  "containerd": {
    "component": "containerd",
    "description": "A high performance container runtime",
    "fullVersion": "v1.2.0 (c4446665cb9c30056f4998ed953e6d4ff22c7c39)",
    "major": 1,
    "minor": 2,
    "patch": 0
  }
}**/