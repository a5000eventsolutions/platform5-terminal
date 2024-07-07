logLevel := sbt.Level.Warn

addSbtPlugin("com.typesafe.sbt" % "sbt-native-packager" % "1.6.0")

addSbtPlugin("ch.epfl.scala" % "sbt-scalafix" % "0.11.1")

libraryDependencies += "com.typesafe" % "config" % "1.4.3"
