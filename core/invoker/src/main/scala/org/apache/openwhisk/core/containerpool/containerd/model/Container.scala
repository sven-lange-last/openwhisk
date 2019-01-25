package org.apache.openwhisk.core.containerpool.containerd.model
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
//import org.apache.openwhisk.core.containerpool.containerd.model.VersionJsonProtocol.jsonFormat2
import spray.json.DefaultJsonProtocol

case class Container(message: String, name: String)

object ContainerJsonProtocol extends SprayJsonSupport with DefaultJsonProtocol {
  implicit val ContainerFormat = jsonFormat2(Container)
}
