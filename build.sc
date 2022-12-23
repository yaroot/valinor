import mill._
import mill.scalalib._
import mill.define._

val AkkaHttpVersion    = "10.2.10"
val AkkaStreamVersion  = "2.6.20"
val BorerVersion       = "1.8.0"
val Log4sVersion       = "1.10.0"
val LogbackVersion     = "1.4.5"
val MUnitVersion       = "0.7.29"
val WartremoverVersion = "3.0.6"

val ScalaVersion = "2.13.8"

object Shared {
  object Deps {
    val akka = Seq(
      ivy"com.typesafe.akka::akka-stream:$AkkaStreamVersion",
      ivy"com.typesafe.akka::akka-http:$AkkaHttpVersion"
    )

    val borer = Seq(
      ivy"io.bullet::borer-core:$BorerVersion",
      ivy"io.bullet::borer-derivation:$BorerVersion",
      ivy"io.bullet::borer-compat-akka:$BorerVersion"
    )

    val common = Seq(
      ivy"ch.qos.logback:logback-classic:$LogbackVersion",
      ivy"org.log4s::log4s:$Log4sVersion"
    )

    val wartremover = Seq(
      ivy"org.wartremover::wartremover:$WartremoverVersion"
    )
  }

  val scalacOptions = Seq(
    "-encoding",
    "utf8",
    "-feature",
    "-unchecked",
    "-language:existentials",
    "-language:experimental.macros",
    "-language:higherKinds",
    "-language:implicitConversions",
    "-Xlint:adapted-args",
    "-Xlint:constant",
    "-Xlint:delayedinit-select",
    "-Xlint:deprecation",
    "-Xlint:doc-detached",
    "-Xlint:implicit-recursion",
    "-Xlint:implicit-not-found",
    "-Xlint:inaccessible",
    "-Xlint:infer-any",
    "-Xlint:missing-interpolator",
    "-Xlint:nullary-unit",
    "-Xlint:option-implicit",
    "-Xlint:package-object-classes",
    "-Xlint:poly-implicit-overload",
    "-Xlint:private-shadow",
    "-Xlint:stars-align",
    "-Xlint:strict-unsealed-patmat",
    "-Xlint:type-parameter-shadow",
    "-Xlint:-byname-implicit",
    "-Wdead-code",
    "-Wextra-implicit",
    "-Wnumeric-widen",
    "-Wvalue-discard",
    "-Wunused:nowarn",
    "-Wunused:implicits",
    "-Wunused:explicits",
    "-Wunused:imports",
    "-Wunused:locals",
    "-Wunused:params",
    "-Wunused:patvars",
    "-Wunused:privates",
    "-Xfatal-warnings",
    "-Ymacro-annotations",
    "-Xsource:3",
    "-P:wartremover:traverser:org.wartremover.warts.AsInstanceOf",
    "-P:wartremover:traverser:org.wartremover.warts.EitherProjectionPartial",
    "-P:wartremover:traverser:org.wartremover.warts.Null",
    "-P:wartremover:traverser:org.wartremover.warts.OptionPartial",
    "-P:wartremover:traverser:org.wartremover.warts.Product",
    "-P:wartremover:traverser:org.wartremover.warts.Return",
    "-P:wartremover:traverser:org.wartremover.warts.TryPartial",
    "-P:wartremover:traverser:org.wartremover.warts.Var"
  )
}

object valinor extends ScalaModule {
  override def scalaVersion: T[String] = T(ScalaVersion)

  override def scalacOptions: Target[Seq[String]] = T(Shared.scalacOptions)
  override def compileIvyDeps                     = T(Shared.Deps.wartremover)
  override def scalacPluginIvyDeps                = T(Shared.Deps.wartremover)

  override def mainClass: T[Option[String]] = T(Some("valinor.Main"))

  override def ivyDeps: T[Agg[Dep]] = T(
    Agg.from(
      Shared.Deps.common ++
        Shared.Deps.akka ++
        Shared.Deps.borer
    )
  )
}
