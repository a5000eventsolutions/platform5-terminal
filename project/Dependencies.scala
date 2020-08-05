import sbt._

object Dependencies {

  object Versions {
    val scala = "2.13.1"
    val platform5 = "1.0"
    val slf4j = "1.7.25"
    val akka = "2.6.1"
    val log4j = "2.8.2"
    val disruptor = "3.3.6"
    val barcode4j = "2.1"
    val scalaLogging = "3.5.0"
    val pdfbox = "2.0.16"
    val jna = "4.2.1"
    val jSerialComm = "1.3.11"
    val jsch = "0.1.54"
    val jnaerator = "0.12"
    val scalatest = "3.0.8"
  }


  lazy val terminal = Def.setting(Seq(
    //Platform5 dependencies
//    "sevts.platform5" %% "protocol" % Versions.platform5,
//    "sevts.platform5" %% "domain" % Versions.platform5,

    "com.twitter" %% "chill-akka" % "0.9.5",

    // Logging
    "com.typesafe.scala-logging" %% "scala-logging" % Versions.scalaLogging % Compile,
    "com.lmax" % "disruptor" % Versions.disruptor,
    "org.apache.logging.log4j" % "log4j-core" % Versions.log4j,
    "org.apache.logging.log4j" % "log4j-api" % Versions.log4j,
    "org.apache.logging.log4j" % "log4j-slf4j-impl" % Versions.log4j,

    // Akka
    "com.typesafe.akka" %% "akka-actor" % Versions.akka % Compile,
    "com.typesafe.akka" %% "akka-slf4j" % Versions.akka % Compile,
    "com.typesafe.akka" %% "akka-testkit" % Versions.akka % Test,

    // Testing
    "org.scalatest" %% "scalatest" % Versions.scalatest % Test,
    "org.mockito" % "mockito-core" % "1.10.19" % Test,

    //Serial port
    "com.fazecast" % "jSerialComm" % Versions.jSerialComm % Compile,

    // Printing
    "org.apache.pdfbox" % "pdfbox" % Versions.pdfbox % Compile,

    //rfid9809 scanner jna api
    "com.nativelibs4java" % "jnaerator-runtime" % Versions.jnaerator,

    "org.rxtx" % "rxtx" % "2.1.7",

    "net.java.dev.jna" % "jna" % "5.5.0",
    "com.nativelibs4java" % "jnaerator" % "0.12"
  ))
}

