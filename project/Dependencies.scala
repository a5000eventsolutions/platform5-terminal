import sbt._

object Dependencies {

  object Versions {
    val scala = "2.12.1"
    val platform5 = "1.0"
    val slf4j = "1.7.12"
    val akka = "2.4.17"
    val logback = "1.1.3"
    val barcode4j = "2.1"
    val scalaLogging = "3.5.0"
    val pdfbox = "2.0.0-RC1"
    val jna = "4.2.1"
    val jSerialComm = "1.3.11"
    val json4s = "3.5.0"
    val jsch = "0.1.54"
    val jnaerator = "0.12"
    val scalatest = "3.0.1"
  }


  lazy val terminal = Def.setting(Seq(
    //Platform5 dependencies
//    "sevts.platform5" %% "protocol" % Versions.platform5,
//    "sevts.platform5" %% "domain" % Versions.platform5,

    // Logging
    "com.typesafe.scala-logging" %% "scala-logging" % Versions.scalaLogging % Compile,
    "org.slf4j" % "slf4j-log4j12" % Versions.slf4j % Compile,
    "com.typesafe.akka" %% "akka-slf4j" % Versions.akka % Compile,

    // Akka
    "com.typesafe.akka" %% "akka-actor" % Versions.akka % Compile,
    "com.typesafe.akka" %% "akka-slf4j" % Versions.akka % Compile,
    "com.typesafe.akka" %% "akka-testkit" % Versions.akka % Test,
    "com.typesafe.akka" %% "akka-remote" % Versions.akka % Compile,

    // Testing
    "org.scalatest" %% "scalatest" % Versions.scalatest % Test,
    "org.mockito" % "mockito-core" % "1.10.19" % Test,

    //Serial port
    "com.fazecast" % "jSerialComm" % Versions.jSerialComm % Compile,

    // Printing
    "org.apache.pdfbox" % "pdfbox" % Versions.pdfbox % Compile,

    //rfid9809 scanner jna api
    "com.nativelibs4java" % "jnaerator-runtime" % Versions.jnaerator

  ))
}

