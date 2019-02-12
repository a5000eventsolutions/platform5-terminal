logLevel := sbt.Level.Warn

addSbtPlugin("com.typesafe.sbt" % "sbt-native-packager" % "1.3.3")

libraryDependencies += "com.typesafe" % "config" % "1.3.2"