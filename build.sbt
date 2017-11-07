
version := "1.0"

val protoFile = file("../platform5")
val protocolRef = ProjectRef(protoFile, "protocol")

val domainFile = file("../platform5")
val domainRef = ProjectRef(domainFile, "domain")

lazy val commonSettings = Seq(
  organization := "sevts.platform5",
  version := "1.0",
  scalaVersion := Dependencies.Versions.scala,
  scalacOptions ++= Seq(
    "-language:experimental.macros"
  ),
  resolvers ++= Seq(
    Resolver.bintrayRepo("naftoligug", "maven"),
    Resolver.sonatypeRepo("snapshots"))
)


lazy val terminal = (project in file("./"))
  .settings( name := "platform5-terminal",
    organization := "sevts.platform5",
    version := "1.0"
  )
  .settings(
    scriptClasspath := Seq("*"),
    bashScriptConfigLocation := Some("${app_home}/../PLATFORM5_TERMINAL_config.txt"),
    mainClass in Compile := Some("sevts.terminal.Platform5Terminal"),
    topLevelDirectory := Some("platform5-terminal")
  )
  .enablePlugins(JavaAppPackaging)
  .settings(
    mappings in Universal += {
      ((resourceDirectory in Compile).value / "application.conf") -> "conf/application.conf"
    }
  )
  .settings( commonSettings: _* )
  .settings(
    libraryDependencies := Dependencies.terminal.value,
    dependencyOverrides +=  "scala-lang.modules" % "scala-xml_2.12" % "1.0.6",
    dependencyOverrides +=  "javax.activation" % "activation" % "1.1.1"
  )
  .dependsOn(protocolRef)
  .dependsOn(domainRef)