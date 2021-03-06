/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor

import language.postfixOps

import akka.testkit.AkkaSpec
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigParseOptions
import akka.routing._
import scala.concurrent.duration._

object DeployerSpec {
  val deployerConf = ConfigFactory.parseString("""
      akka.actor.deployment {
        /service1 {
        }
        /service-direct {
          router = from-code
        }
        /service-direct2 {
          router = from-code
          # nr-of-instances ignored when router = from-code
          nr-of-instances = 2
        }
        /service3 {
          dispatcher = my-dispatcher
        }
        /service4 {
          mailbox = my-mailbox
        }
        /service-round-robin {
          router = round-robin
        }
        /service-random {
          router = random
        }
        /service-scatter-gather {
          router = scatter-gather
          within = 2 seconds
        }
        /service-consistent-hashing {
          router = consistent-hashing
        }
        /service-resizer {
          router = round-robin
          resizer {
            lower-bound = 1
            upper-bound = 10
          }
        }
        /some/random-service {
          router = round-robin
        }
        "/some/*" {
          router = random
        }
        "/*/some" {
          router = scatter-gather
        }
      }
      """, ConfigParseOptions.defaults)

  class RecipeActor extends Actor {
    def receive = { case _ ⇒ }
  }

}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class DeployerSpec extends AkkaSpec(DeployerSpec.deployerConf) {
  "A Deployer" must {

    "be able to parse 'akka.actor.deployment._' with all default values" in {
      val service = "/service1"
      val deployment = system.asInstanceOf[ActorSystemImpl].provider.deployer.lookup(service.split("/").drop(1))

      deployment must be(Some(
        Deploy(
          service,
          deployment.get.config,
          NoRouter,
          NoScopeGiven,
          Deploy.NoDispatcherGiven,
          Deploy.NoMailboxGiven)))
    }

    "use None deployment for undefined service" in {
      val service = "/undefined"
      val deployment = system.asInstanceOf[ActorSystemImpl].provider.deployer.lookup(service.split("/").drop(1))
      deployment must be(None)
    }

    "be able to parse 'akka.actor.deployment._' with dispatcher config" in {
      val service = "/service3"
      val deployment = system.asInstanceOf[ActorSystemImpl].provider.deployer.lookup(service.split("/").drop(1))

      deployment must be(Some(
        Deploy(
          service,
          deployment.get.config,
          NoRouter,
          NoScopeGiven,
          dispatcher = "my-dispatcher",
          Deploy.NoMailboxGiven)))
    }

    "be able to parse 'akka.actor.deployment._' with mailbox config" in {
      val service = "/service4"
      val deployment = system.asInstanceOf[ActorSystemImpl].provider.deployer.lookup(service.split("/").drop(1))

      deployment must be(Some(
        Deploy(
          service,
          deployment.get.config,
          NoRouter,
          NoScopeGiven,
          Deploy.NoDispatcherGiven,
          mailbox = "my-mailbox")))
    }

    "detect invalid number-of-instances" in {
      intercept[com.typesafe.config.ConfigException.WrongType] {
        val invalidDeployerConf = ConfigFactory.parseString("""
            akka.actor.deployment {
              /service-invalid-number-of-instances {
                router = round-robin
                nr-of-instances = boom
              }
            }
            """, ConfigParseOptions.defaults).withFallback(AkkaSpec.testConf)

        shutdown(ActorSystem("invalid-number-of-instances", invalidDeployerConf))
      }
    }

    "detect invalid deployment path" in {
      val e = intercept[InvalidActorNameException] {
        val invalidDeployerConf = ConfigFactory.parseString("""
            akka.actor.deployment {
              /gul/ubåt {
                router = round-robin
                nr-of-instances = 2
              }
            }
            """, ConfigParseOptions.defaults).withFallback(AkkaSpec.testConf)

        shutdown(ActorSystem("invalid-path", invalidDeployerConf))
      }
      e.getMessage must include("[ubåt]")
      e.getMessage must include("[/gul/ubåt]")
    }

    "be able to parse 'akka.actor.deployment._' with from-code router" in {
      assertRouting("/service-direct", NoRouter, "/service-direct")
    }

    "ignore nr-of-instances with from-code router" in {
      assertRouting("/service-direct2", NoRouter, "/service-direct2")
    }

    "be able to parse 'akka.actor.deployment._' with round-robin router" in {
      assertRouting("/service-round-robin", RoundRobinRouter(1), "/service-round-robin")
    }

    "be able to parse 'akka.actor.deployment._' with random router" in {
      assertRouting("/service-random", RandomRouter(1), "/service-random")
    }

    "be able to parse 'akka.actor.deployment._' with scatter-gather router" in {
      assertRouting("/service-scatter-gather", ScatterGatherFirstCompletedRouter(nrOfInstances = 1, within = 2 seconds), "/service-scatter-gather")
    }

    "be able to parse 'akka.actor.deployment._' with consistent-hashing router" in {
      assertRouting("/service-consistent-hashing", ConsistentHashingRouter(1), "/service-consistent-hashing")
    }

    "be able to parse 'akka.actor.deployment._' with router resizer" in {
      val resizer = DefaultResizer()
      assertRouting("/service-resizer", RoundRobinRouter(resizer = Some(resizer)), "/service-resizer")
    }

    "be able to use wildcards" in {
      assertRouting("/some/wildcardmatch", RandomRouter(1), "/some/*")
      assertRouting("/somewildcardmatch/some", ScatterGatherFirstCompletedRouter(nrOfInstances = 1, within = 2 seconds), "/*/some")
    }

    def assertRouting(service: String, expected: RouterConfig, expectPath: String): Unit = {
      val deployment = system.asInstanceOf[ActorSystemImpl].provider.deployer.lookup(service.split("/").drop(1))
      deployment.map(_.path).getOrElse("NOT FOUND") must be(expectPath)
      deployment.get.routerConfig.getClass must be(expected.getClass)
      deployment.get.routerConfig.resizer must be(expected.resizer)
      deployment.get.scope must be(NoScopeGiven)
    }
  }
}
