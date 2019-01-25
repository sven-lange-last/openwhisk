package org.apache.openwhisk.core.containerpool.containerd.model

import spray.json._
// import DefaultJsonProtocol._
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport

case class Version(id: String, description: String, fullVersion: String, major: Int, minor: Int, patch: Int)

object Version {
  val requestPath = "version"

  def fromVersionResponse(vr: VersionResponse): Version = {
    Version(vr.id, vr.description, vr.fullVersion, vr.major, vr.minor, vr.patch)
  }
}

case class VersionResponse(id: String, description: String, fullVersion: String, major: Int, minor: Int, patch: Int)

object VersionJsonProtocol extends SprayJsonSupport with DefaultJsonProtocol {
  implicit val VersionResponseFormat = jsonFormat6(VersionResponse)
}
