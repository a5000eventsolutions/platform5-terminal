import sbt._

object Dependencies {

  object Versions {
    val scala = "2.13.11"
    val platform5 = "1.0"
    val slf4j = "1.7.25"
    val akka = "2.8.0"
    val log4j = "2.20.0"
    val disruptor = "3.4.4"
    val barcode4j = "2.1"
    val scalaLogging = "3.9.5"
    val pdfbox = "2.0.27"
    val jna = "4.2.1"
    val jSerialComm = "2.9.3"
    val jsch = "0.1.54"
    val jnaerator = "0.12"
    val scalatest = "3.2.15"
  }


  lazy val terminal = Def.setting(Seq(
    //Platform5 dependencies
//    "sevts.platform5" %% "protocol" % Versions.platform5,
//    "sevts.platform5" %% "domain" % Versions.platform5,

    "io.altoo" %% "akka-kryo-serialization-typed" % "2.5.2",

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
    "org.scalatest" %% "scalatest-wordspec" %  Versions.scalatest % Test,
    "org.mockito" % "mockito-core" % "5.2.0" % Test,
    "org.scalamock" %% "scalamock" % "5.2.0" % Test,

    //Serial port
    "com.fazecast" % "jSerialComm" % Versions.jSerialComm % Compile,

    // Printing
    "org.apache.pdfbox" % "pdfbox" % Versions.pdfbox % Compile,

    //rfid9809 scanner jna api
    "com.nativelibs4java" % "jnaerator-runtime" % Versions.jnaerator,

    "net.java.dev.jna" % "jna" % "5.12.1",
    "com.nativelibs4java" % "jnaerator" % "0.12",

    "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-core" % "2.23.0",
    "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-macros" % "2.23.0" % "provided"
  ))
}

